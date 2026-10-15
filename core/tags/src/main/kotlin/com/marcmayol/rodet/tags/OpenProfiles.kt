package com.marcmayol.rodet.tags

import com.marcmayol.rodet.model.Catalog
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Material base y aditivos, que cada formato expresa a su manera. */
internal data class MaterialParts(val base: String, val plus: Boolean, val subtypes: Set<String>) {
    val fibre: String? get() = listOf("CF", "GF").firstOrNull { it in subtypes }

    companion object {
        fun of(spec: FilamentSpec): MaterialParts {
            val code = Catalog.material(spec.material)?.code ?: spec.material.trim().uppercase()
            val subtypes = spec.subtype.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return if (code == "PLA+") MaterialParts("PLA", true, subtypes) else MaterialParts(code, false, subtypes)
        }
    }
}

// ============================================================ OpenSpool

/**
 * OpenSpool 1.0: registro MIME `application/json` en un NTAG215/216.
 * Numeros como numero JSON (asi escribe el firmware oficial; su lector y
 * OpenRFID aceptan tambien texto) y `color_hex` sin '#'.
 */
object OpenSpoolProfile : BaseProfile() {
    const val MIME = "application/json"
    override val id = "openspool"
    override val name = "OpenSpool"
    override val tagType = "NTAG215 / NTAG216"
    override val tech = TagTech.NFC_A_TYPE2
    override val verified = true
    override val unverifiedReason: UnverifiedReason? = null
    override val reference = "https://github.com/spuder/OpenSpool (docs/rfid.md, firmware/bambu.h)"
    override val requiresColor = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite {
        val parts = MaterialParts.of(spec)
        val obj = buildJsonObject {
            put("protocol", "openspool")
            put("version", "1.0")
            put("type", parts.fibre?.let { "${parts.base}-$it" } ?: parts.base)
            put("color_hex", spec.colorHex.uppercase())
            put("brand", spec.brand.ifBlank { "Generic" })
            put("min_temp", need(spec.nozzleTempMin, Field.NOZZLE_MIN))
            put("max_temp", need(spec.nozzleTempMax, Field.NOZZLE_MAX))
            // Extensiones opcionales (firmware Snapmaker U1 Extended); el lector oficial las ignora.
            spec.bedTempMin?.let { put("bed_min_temp", it) }
            spec.bedTempMax?.let { put("bed_max_temp", it) }
            val subtype = (listOfNotNull("Plus".takeIf { parts.plus }) + (parts.subtypes - setOfNotNull(parts.fibre))).joinToString(" ")
            if (subtype.isNotEmpty()) put("subtype", subtype)
            spec.netWeightG?.let { put("weight", it) }
            spec.diameterMm?.let { put("diameter", it) }
        }
        val record = NdefRecord.mime(MIME, obj.toString().toByteArray(Charsets.UTF_8))
        val message = NdefMessage(record)
        if (!TagCapacity.NTAG215.fits(message)) {
            throw ProfileException(ProfileError.TOO_LARGE, listOf(message.byteArrayLength, TagCapacity.NTAG215.userBytes))
        }
        return TagWrite.Ndef(message, TagCapacity.NTAG215)
    }

    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? {
        val record = (read as? TagRead.Ndef)?.message?.firstOfMime(MIME) ?: return null
        val obj = runCatching { json.parseToJsonElement(record.payload.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        if (obj.str("protocol")?.lowercase() != "openspool") return null
        val type = obj.str("type").orEmpty()
        val fibre = listOf("CF", "GF").firstOrNull { type.uppercase().endsWith("-$it") }
        val subtype = listOfNotNull(obj.str("subtype"), fibre).joinToString(" ").trim()
        val spec = FilamentSpec(
            material = fibre?.let { type.dropLast(it.length + 1) } ?: type,
            subtype = subtype,
            brand = obj.str("brand").orEmpty(),
            colorHex = obj.str("color_hex").orEmpty().removePrefix("#").take(6).uppercase(),
            nozzleTempMin = obj.int("min_temp"),
            nozzleTempMax = obj.int("max_temp"),
            bedTempMin = obj.int("bed_min_temp"),
            bedTempMax = obj.int("bed_max_temp"),
            netWeightG = obj.int("weight"),
            diameterMm = obj.str("diameter")?.toDoubleOrNull(),
        )
        return fromTag(spec, name)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.roundToInt() }
}

// ============================================================ OpenPrintTag

/**
 * OpenPrintTag (Prusa): NDEF `application/vnd.openprinttag` con secciones CBOR
 * de claves enteras, en un tag NFC-V (ICODE SLIX2). Un NTAG no lo lee ningun
 * lector de Prusa, por eso el perfil genera la memoria completa de un SLIX2.
 */
object OpenPrintTagProfile : BaseProfile() {
    const val MIME = "application/vnd.openprinttag"
    override val id = "openprinttag"
    override val name = "OpenPrintTag"
    override val tagType = "ICODE SLIX2 (NFC-V)"
    override val tech = TagTech.NFC_V
    override val verified = false
    override val unverifiedReason = UnverifiedReason.SPEC_NOT_TESTED_WITH_READER
    override val reference = "https://github.com/OpenPrintTag/openprinttag-specification (data/main_fields.yaml)"
    override val requiresColor = false

    /** Memoria del SLIX2 que usa la referencia (sample_tag.bin). */
    const val MEMORY_SIZE = 304
    private const val BLOCK = 4
    private const val AUX_SIZE = 32

    // Claves de la seccion main (data/main_fields.yaml).
    object Key {
        const val MATERIAL_CLASS = 8
        const val MATERIAL_TYPE = 9
        const val MATERIAL_NAME = 10
        const val BRAND_NAME = 11
        const val NOMINAL_NETTO_FULL_WEIGHT = 16
        const val EMPTY_CONTAINER_WEIGHT = 18
        const val PRIMARY_COLOR = 19
        const val DENSITY = 29
        const val MIN_PRINT_TEMPERATURE = 34
        const val MAX_PRINT_TEMPERATURE = 35
        const val MIN_BED_TEMPERATURE = 37
        const val MAX_BED_TEMPERATURE = 38
        const val MATERIAL_ABBREVIATION = 52
        const val DRYING_TEMPERATURE = 57
        const val DRYING_TIME_MIN = 58
        const val FILAMENT_DIAMETER_UM = 61
    }

    // data/material_type_enum.yaml. PA no va: el enum distingue PA6/PA11/PA12/PA66.
    private val MATERIAL_TYPES = mapOf(
        "PLA" to 0, "PETG" to 1, "TPU" to 2, "ABS" to 3, "ASA" to 4, "PC" to 5, "PCTG" to 6,
        "PP" to 7, "HIPS" to 14, "PET" to 16, "PVB" to 19, "PVA" to 20, "PEEK" to 22, "BVOH" to 23,
    )

    fun mainSection(spec: FilamentSpec): Cbor.Map {
        val parts = MaterialParts.of(spec)
        val name = listOf(spec.material, spec.subtype, spec.colorName).filter { it.isNotBlank() }.joinToString(" ")
        return Cbor.intMap(
            Key.MATERIAL_CLASS to Cbor.int(0), // FFF
            Key.MATERIAL_TYPE to MATERIAL_TYPES[parts.base]?.let { Cbor.int(it) },
            Key.MATERIAL_NAME to name.takeIf { it.isNotEmpty() }?.let { Cbor.text(it.utf8Take(63)) },
            Key.BRAND_NAME to spec.brand.takeIf { it.isNotBlank() }?.let { Cbor.text(it.utf8Take(31)) },
            Key.MATERIAL_ABBREVIATION to Cbor.text(parts.base.utf8Take(7)),
            Key.NOMINAL_NETTO_FULL_WEIGHT to spec.netWeightG?.let { Cbor.int(it) },
            Key.EMPTY_CONTAINER_WEIGHT to spec.spoolWeightG?.let { Cbor.int(it) },
            Key.PRIMARY_COLOR to spec.colorHex.takeIf { it.length == 6 }?.let { Cbor.bytes(it.hexToBytes()) },
            // La especificacion define la densidad como numero con decimales.
            Key.DENSITY to spec.densityGCm3?.let { Cbor.Float(Math.round(it * 1000) / 1000.0) },
            Key.MIN_PRINT_TEMPERATURE to spec.nozzleTempMin?.let { Cbor.int(it) },
            Key.MAX_PRINT_TEMPERATURE to spec.nozzleTempMax?.let { Cbor.int(it) },
            Key.MIN_BED_TEMPERATURE to spec.bedTempMin?.let { Cbor.int(it) },
            Key.MAX_BED_TEMPERATURE to spec.bedTempMax?.let { Cbor.int(it) },
            Key.DRYING_TEMPERATURE to spec.dryTempC?.let { Cbor.int(it) },
            Key.DRYING_TIME_MIN to spec.dryTimeH?.let { Cbor.int(it * 60) },
            Key.FILAMENT_DIAMETER_UM to spec.diameterMm?.let { Cbor.int((it * 1000).roundToInt()) },
        )
    }

    override fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite =
        TagWrite.NfcV(memoryImage(spec), BLOCK)

    /** Memoria completa: CC + TLV + registro NDEF que ocupa todo + terminador. */
    fun memoryImage(spec: FilamentSpec, memorySize: Int = MEMORY_SIZE): ByteArray {
        require(memorySize % 8 == 0 && memorySize / 8 <= 255)
        val cc = byteArrayOf(0xE1.toByte(), 0x40, (memorySize / 8).toByte(), 0x01)
        val typeBytes = MIME.toByteArray(Charsets.US_ASCII)
        // TLV largo (4 B) + cabecera de registro no corto: flags, tipo, 4 B de longitud.
        val recordHeader = 1 + 1 + 4 + typeBytes.size
        val payloadStart = cc.size + 4 + recordHeader
        val payloadSize = memorySize - payloadStart - 1

        val main = CborEncoder.encode(mainSection(spec))
        // La region aux va al final, alineada a bloque en la memoria absoluta.
        val auxAbs = (payloadStart + payloadSize - AUX_SIZE) / BLOCK * BLOCK
        val auxOffset = auxAbs - payloadStart
        val meta = CborEncoder.encode(Cbor.intMap(2 to Cbor.int(auxOffset)))
        if (meta.size + main.size > auxOffset) {
            throw ProfileException(ProfileError.TOO_LARGE, listOf(meta.size + main.size, auxOffset))
        }
        val payload = ByteArray(payloadSize)
        meta.copyInto(payload, 0)
        main.copyInto(payload, meta.size)
        payload[auxOffset] = 0xA0.toByte() // mapa aux vacio

        val record = NdefMessage(NdefRecord.mime(MIME, payload)).toByteArray()
        check(record.size == recordHeader + payloadSize) { "el registro deberia ser no corto" }
        val image = cc + NdefTlv.wrap(record)
        check(image.size == memorySize) { "imagen de ${image.size} B, se esperaban $memorySize" }
        return image
    }

    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? {
        val payload = when (read) {
            is TagRead.Ndef -> read.message.firstOfMime(MIME)?.payload
            is TagRead.NfcV -> runCatching {
                NdefMessage.parse(NdefTlv.unwrap(read.memory, from = 4)).firstOfMime(MIME)?.payload
            }.getOrNull()
            else -> null
        } ?: return null
        return fromTag(decodePayload(payload) ?: return null, name)
    }

    fun decodePayload(payload: ByteArray): FilamentSpec? {
        val decoder = CborDecoder(payload)
        val meta = decoder.read() as? Cbor.Map ?: return null
        val mainOffset = meta[0].asLong()?.toInt() ?: decoder.position
        val main = CborDecoder(payload, mainOffset).read() as? Cbor.Map ?: return null
        val type = main[Key.MATERIAL_TYPE.toLong()].asLong()?.toInt()
        val material = MATERIAL_TYPES.entries.firstOrNull { it.value == type }?.key
            ?: main[Key.MATERIAL_ABBREVIATION.toLong()].asText().orEmpty()
        return FilamentSpec(
            material = material,
            brand = main[Key.BRAND_NAME.toLong()].asText().orEmpty(),
            colorHex = main[Key.PRIMARY_COLOR.toLong()].asBytes()?.take(3)?.toByteArray()?.toHex().orEmpty(),
            notes = main[Key.MATERIAL_NAME.toLong()].asText().orEmpty(),
            netWeightG = main[Key.NOMINAL_NETTO_FULL_WEIGHT.toLong()].asLong()?.toInt(),
            spoolWeightG = main[Key.EMPTY_CONTAINER_WEIGHT.toLong()].asLong()?.toInt(),
            densityGCm3 = main[Key.DENSITY.toLong()].asDouble(),
            nozzleTempMin = main[Key.MIN_PRINT_TEMPERATURE.toLong()].asLong()?.toInt(),
            nozzleTempMax = main[Key.MAX_PRINT_TEMPERATURE.toLong()].asLong()?.toInt(),
            bedTempMin = main[Key.MIN_BED_TEMPERATURE.toLong()].asLong()?.toInt(),
            bedTempMax = main[Key.MAX_BED_TEMPERATURE.toLong()].asLong()?.toInt(),
            dryTempC = main[Key.DRYING_TEMPERATURE.toLong()].asLong()?.toInt(),
            dryTimeH = main[Key.DRYING_TIME_MIN.toLong()].asLong()?.let { (it / 60.0).roundToInt() },
            diameterMm = main[Key.FILAMENT_DIAMETER_UM.toLong()].asLong()?.let { it / 1000.0 },
        )
    }

    private fun String.utf8Take(maxBytes: Int): String {
        var s = this
        while (s.toByteArray(Charsets.UTF_8).size > maxBytes) s = s.dropLast(1)
        return s
    }
}
