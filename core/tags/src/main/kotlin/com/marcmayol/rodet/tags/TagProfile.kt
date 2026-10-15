package com.marcmayol.rodet.tags

import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Provenance
import com.marcmayol.rodet.model.Severity
import com.marcmayol.rodet.model.Source
import com.marcmayol.rodet.model.Validation

/** Tecnologia del chip. Decide que API de Android hace falta. */
enum class TagTech {
    /** NFC-A tipo 2: NTAG21x, Ultralight. Cualquier movil con NFC. */
    NFC_A_TYPE2,

    /** Solo moviles con controlador NFC de NXP. */
    MIFARE_CLASSIC_1K,

    /** ISO 15693 (ICODE SLIX2). */
    NFC_V,
}

/** Por que un perfil no esta verificado. La UI lo traduce y lo ensena ANTES de escribir. */
enum class UnverifiedReason {
    /** Formato de ingenieria inversa comunitaria, sin probar con un volcado propio. */
    COMMUNITY_REVERSE_ENGINEERED,

    /** Especificacion publica, pero sin probar contra el lector del fabricante. */
    SPEC_NOT_TESTED_WITH_READER,

    /** Lectura contrastada con un tag de fabrica; la escritura aun no se ha probado en la impresora. */
    WRITE_NOT_TESTED_ON_PRINTER,
}

data class ProfileSettings(
    /** Clave AES para derivar la clave de sector del CFS. La pone la persona. */
    val crealityDerivationKey: ByteArray? = null,
    /** Clave AES del payload del CFS. La pone la persona. */
    val crealityPayloadKey: ByteArray? = null,
    /** Codigo de 5 digitos de `material_database.json`; manda sobre la tabla interna. */
    val crealityMaterialCode: String? = null,
) {
    override fun equals(other: Any?) = other is ProfileSettings &&
        crealityDerivationKey.contentEqualsNullable(other.crealityDerivationKey) &&
        crealityPayloadKey.contentEqualsNullable(other.crealityPayloadKey) &&
        crealityMaterialCode == other.crealityMaterialCode

    override fun hashCode() = crealityMaterialCode.hashCode()

    override fun toString() = "ProfileSettings(claves=${crealityDerivationKey != null}/${crealityPayloadKey != null}, " +
        "codigo=$crealityMaterialCode)"
}

private fun ByteArray?.contentEqualsNullable(o: ByteArray?) = if (this == null) o == null else o != null && contentEquals(o)

/** Lo que se sabe del tag al acercarlo, antes de codificar. */
data class WriteContext(
    val uid: ByteArray? = null,
    val settings: ProfileSettings = ProfileSettings(),
) {
    override fun equals(other: Any?) = other is WriteContext && uid.contentEqualsNullable(other.uid) && settings == other.settings
    override fun hashCode() = settings.hashCode()
}

/** Operacion que el escritor de Android ejecuta tal cual. */
sealed interface TagWrite {
    data class Ndef(val message: NdefMessage, val minimum: TagCapacity) : TagWrite

    /**
     * Paginas sueltas de un tipo 2 (4 B cada una). Solo las que usa el
     * formato: el resto del tag no se toca.
     */
    class Pages(val pages: Map<Int, ByteArray>) : TagWrite {
        init {
            require(pages.isNotEmpty())
            pages.forEach { (page, data) ->
                require(page >= 4) { "las paginas 0-3 (UID, lock, CC) no se escriben" }
                require(data.size == 4) { "la pagina $page no mide 4 B" }
            }
        }

        val lastPage: Int get() = pages.keys.max()

        companion object {
            /** Trocea [data] (que empieza en [firstPage]) y se queda con las paginas de [layout] que tienen campos. */
            fun fromLayout(layout: BinaryLayout, data: ByteArray, firstPage: Int): Pages {
                val used = layout.fields.flatMap { f -> (f.offset / 4)..((f.end - 1) / 4) }.toSortedSet()
                return Pages(used.associate { i -> (firstPage + i) to data.copyOfRange(i * 4, i * 4 + 4) })
            }
        }
    }

    class MifareClassic(
        /** Claves a probar por sector, en orden. */
        val authKeys: Map<Int, List<ByteArray>>,
        val blocks: List<BlockWrite>,
        val keyChanges: List<SectorKeyChange> = emptyList(),
    ) : TagWrite {
        init {
            val sectors = (blocks.map { MifareClassic1K.sectorOf(it.block) } + keyChanges.map { it.sector }).toSet()
            require(sectors.all { it in authKeys }) { "falta la clave de algun sector" }
        }
    }

    /** Imagen completa de la memoria de un NFC-V, CC incluido. */
    class NfcV(val memory: ByteArray, val blockSize: Int = 4) : TagWrite {
        init {
            require(memory.size % blockSize == 0) { "la memoria no esta alineada a bloque" }
        }
    }
}

/** Lo leido de un tag, para reconocer su formato. */
sealed interface TagRead {
    data class Ndef(val message: NdefMessage) : TagRead
    /** Memoria leida de un tipo 2 desde [startPage] (normalmente la 4). */
    class Pages(val startPage: Int, val data: ByteArray) : TagRead
    class MifareClassic(val uid: ByteArray, val blocks: Map<Int, ByteArray>) : TagRead
    class NfcV(val memory: ByteArray) : TagRead
}

enum class ProfileError {
    /** El formato no admite tags nuevos (Bambu). */
    NOT_WRITABLE,
    MISSING_KEY,

    /** La clave introducida no es la del formato. */
    WRONG_KEY,
    MISSING_FIELD,
    UNSUPPORTED_MATERIAL,
    TOO_LARGE,
    INVALID_UID,
    INVALID_SPEC,
}

/** Error con codigo; la UI lo traduce y [args] rellena los huecos. */
class ProfileException(
    val error: ProfileError,
    val args: List<Any> = emptyList(),
    /** Perfil que se propone en su lugar, si lo hay. */
    val alternativeProfileId: String? = null,
    message: String = "$error $args",
) : Exception(message)

interface TagProfile {
    val id: String
    val name: String
    val tagType: String
    val tech: TagTech
    val verified: Boolean
    val unverifiedReason: UnverifiedReason?
    val reference: String
    val requiresColor: Boolean
    val canWrite: Boolean get() = true

    /** Traduce la bobina al binario del ecosistema. Lanza [ProfileException] si no se puede. */
    fun encode(spec: FilamentSpec, context: WriteContext = WriteContext()): TagWrite

    /** Devuelve la bobina si reconoce el formato; null si no es suyo. */
    fun decode(read: TagRead, settings: ProfileSettings = ProfileSettings()): FilamentSpec?
}

/**
 * Base con la regla del plan: nunca se codifica un tag que no pase la
 * validacion bloqueante del editor.
 */
abstract class BaseProfile : TagProfile {
    final override fun encode(spec: FilamentSpec, context: WriteContext): TagWrite {
        val issues = Validation.validate(spec, requireColor = requiresColor)
        if (Validation.hasBlocking(issues)) {
            throw ProfileException(ProfileError.INVALID_SPEC, issues.filter { it.severity == Severity.BLOCKING })
        }
        return encodeValid(spec, context)
    }

    protected abstract fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite

    protected fun <T : Any> need(value: T?, field: Field): T =
        value?.takeIf { it !is String || it.isNotBlank() }
            ?: throw ProfileException(ProfileError.MISSING_FIELD, listOf(field))

    /** Bobina leida de un tag: todo lo que trae lleva procedencia TAG. */
    protected fun fromTag(spec: FilamentSpec, evidence: String): FilamentSpec {
        val p = Field.entries
            .filter { !spec.isEmpty(it) }
            .associateWith { Provenance(Source.TAG, 1.0, evidence) }
        return spec.copy(provenance = p)
    }
}

/** Lo que el movil sabe hacer. Se detecta al arrancar, no al escribir. */
data class DeviceCapabilities(
    val hasNfc: Boolean,
    /** `packageManager.hasSystemFeature("com.nxp.mifare")` */
    val supportsMifareClassic: Boolean,
    val supportsNfcV: Boolean = hasNfc,
)

sealed interface Availability {
    data object Available : Availability
    data class Disabled(val reason: DisabledReason) : Availability
}

enum class DisabledReason { NO_NFC, NO_MIFARE_CLASSIC, NO_NFC_V, FORMAT_NOT_WRITABLE }

object Profiles {
    val all: List<TagProfile> = listOf(
        OpenSpoolProfile,
        OpenPrintTagProfile,
        AnycubicAceProfile,
        ElegooProfile,
        QidiBoxProfile,
        CrealityCfsProfile,
        BambuProfile,
    )

    fun byId(id: String): TagProfile? = all.firstOrNull { it.id == id }

    /**
     * Seccion 4.1: los perfiles que el movil no puede escribir aparecen
     * deshabilitados con su motivo, no fallan a mitad de la escritura.
     */
    fun availability(profile: TagProfile, device: DeviceCapabilities): Availability = when {
        !profile.canWrite -> Availability.Disabled(DisabledReason.FORMAT_NOT_WRITABLE)
        !device.hasNfc -> Availability.Disabled(DisabledReason.NO_NFC)
        profile.tech == TagTech.MIFARE_CLASSIC_1K && !device.supportsMifareClassic ->
            Availability.Disabled(DisabledReason.NO_MIFARE_CLASSIC)
        profile.tech == TagTech.NFC_V && !device.supportsNfcV -> Availability.Disabled(DisabledReason.NO_NFC_V)
        else -> Availability.Available
    }

    /** Prueba todos los perfiles; el primero que reconoce el contenido gana. */
    fun identify(read: TagRead, settings: ProfileSettings = ProfileSettings()): Pair<TagProfile, FilamentSpec>? =
        all.firstNotNullOfOrNull { p -> runCatching { p.decode(read, settings) }.getOrNull()?.let { p to it } }
}
