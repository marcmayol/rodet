package com.marcmayol.rodet.tags

import com.marcmayol.rodet.model.Catalog
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Normalization
import kotlin.math.roundToInt

/*
 * Formatos propietarios. Ninguno tiene especificacion publica: salen de la
 * ingenieria inversa de DnG-Crafts y OpenRFID, y por eso ninguno esta marcado
 * como verificado hasta contrastarlo con un volcado real.
 */

private fun num(v: Any?): Int? = (v as? Number)?.toDouble()?.roundToInt()

private fun zeros(n: Int) = ByteArray(n)

/** Metros de filamento que las impresoras esperan para cada peso nominal (Anycubic y Creality). */
internal object SpoolLength {
    private val BY_WEIGHT = mapOf(1000 to 330, 750 to 247, 600 to 198, 500 to 165, 250 to 82)

    fun meters(spec: FilamentSpec): Int {
        val w = spec.netWeightG
        BY_WEIGHT[w]?.let { return it }
        return spec.estimatedLengthM()?.roundToInt() ?: 330
    }

    fun weightFor(meters: Int): Int? = BY_WEIGHT.entries.firstOrNull { it.value == meters }?.key
}

// ============================================================ Anycubic ACE Pro

object AnycubicAceProfile : BaseProfile() {
    override val id = "anycubic_ace"
    override val name = "Anycubic ACE Pro"
    override val tagType = "NTAG213 / NTAG215"
    override val tech = TagTech.NFC_A_TYPE2
    override val verified = false
    override val unverifiedReason = UnverifiedReason.COMMUNITY_REVERSE_ENGINEERED
    override val reference = "https://github.com/DnG-Crafts/ACE-RFID (writeTag) · OpenRFID src/tag/anycubic"
    override val requiresColor = true

    const val FIRST_PAGE = 4

    /** Offsets relativos a la pagina 4 (offset = (pagina - 4) * 4). Little endian. */
    val LAYOUT = BinaryLayout(
        size = 112,
        fields = listOf(
            LayoutField("header", 0x00, 4, FieldEncoding.RAW, constant = byteArrayOf(0x7B, 0x00, 0x65, 0x00)),
            LayoutField("sku", 0x04, 20, FieldEncoding.ASCII),
            LayoutField("brand", 0x18, 20, FieldEncoding.ASCII),
            LayoutField("type", 0x2C, 16, FieldEncoding.ASCII),
            LayoutField("color_abgr", 0x40, 4, FieldEncoding.RAW),
            LayoutField("nozzle_min", 0x50, 2, FieldEncoding.UINT_LE),
            LayoutField("nozzle_max", 0x52, 2, FieldEncoding.UINT_LE),
            LayoutField("bed_min", 0x64, 2, FieldEncoding.UINT_LE),
            LayoutField("bed_max", 0x66, 2, FieldEncoding.UINT_LE),
            LayoutField("diameter", 0x68, 2, FieldEncoding.UINT_LE, scale = 100),
            LayoutField("length_m", 0x6A, 2, FieldEncoding.UINT_LE),
            LayoutField("weight_g", 0x6C, 4, FieldEncoding.UINT_LE),
        ),
    )

    override fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite {
        val parts = MaterialParts.of(spec)
        val type = buildString {
            append(parts.base)
            if (parts.plus) append("+")
            if ("HS" in parts.subtypes) append(" High Speed")
        }
        // El ACE lee 000000 como "sin color": el escritor de referencia usa 010101.
        val hex = spec.colorHex.uppercase().let { if (it == "000000") "010101" else it }
        val rgb = hex.hexToBytes()
        val data = LAYOUT.encode(
            mapOf(
                "sku" to "",
                "brand" to spec.brand.take(20),
                "type" to type.take(16),
                "color_abgr" to byteArrayOf(0xFF.toByte(), rgb[2], rgb[1], rgb[0]),
                "nozzle_min" to need(spec.nozzleTempMin, Field.NOZZLE_MIN),
                "nozzle_max" to need(spec.nozzleTempMax, Field.NOZZLE_MAX),
                "bed_min" to (spec.bedTempMin ?: 0),
                "bed_max" to (spec.bedTempMax ?: 0),
                "diameter" to (spec.diameterMm ?: 1.75),
                "length_m" to SpoolLength.meters(spec),
                "weight_g" to (spec.netWeightG ?: 1000),
            ),
        )
        return TagWrite.Pages.fromLayout(LAYOUT, data, FIRST_PAGE)
    }

    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? {
        val pages = read as? TagRead.Pages ?: return null
        val data = pages.data.takeIf { pages.startPage == FIRST_PAGE && LAYOUT.matchesConstants(it) } ?: return null
        val v = LAYOUT.decode(data)
        val abgr = v["color_abgr"] as ByteArray
        val type = (v["type"] as String).trim()
        val spec = FilamentSpec(
            material = if (type.startsWith("PLA+")) "PLA+" else type.substringBefore(' '),
            subtype = if (type.contains("High Speed", ignoreCase = true)) "HS" else "",
            brand = v["brand"] as String,
            colorHex = byteArrayOf(abgr[3], abgr[2], abgr[1]).toHex(),
            nozzleTempMin = num(v["nozzle_min"]), nozzleTempMax = num(v["nozzle_max"]),
            bedTempMin = num(v["bed_min"])?.takeIf { it > 0 }, bedTempMax = num(v["bed_max"])?.takeIf { it > 0 },
            diameterMm = (v["diameter"] as Double).takeIf { it > 0 },
            netWeightG = num(v["weight_g"])?.takeIf { it > 0 } ?: num(v["length_m"])?.let { SpoolLength.weightFor(it) },
        )
        return fromTag(spec, name)
    }
}

// ============================================================ Elegoo

object ElegooProfile : BaseProfile() {
    override val id = "elegoo"
    override val name = "Elegoo Canvas"
    override val tagType = "NTAG213"
    override val tech = TagTech.NFC_A_TYPE2
    override val verified = false
    override val unverifiedReason = UnverifiedReason.COMMUNITY_REVERSE_ENGINEERED
    override val reference = "https://github.com/DnG-Crafts/ELG-RFID (writeTag, Utils.encodeMaterial)"
    override val requiresColor = true

    const val FIRST_PAGE = 4

    /** Big endian. Las paginas 4-15 se borran (llevaban una URI de fabrica). */
    val LAYOUT = BinaryLayout(
        size = 84,
        fields = listOf(
            LayoutField("cleared", 0, 48, FieldEncoding.RAW, constant = zeros(48), identifying = false),
            LayoutField("magic", 48, 4, FieldEncoding.RAW, constant = byteArrayOf(0x36, 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())),
            LayoutField("magic2", 52, 4, FieldEncoding.RAW, constant = byteArrayOf(0xEE.toByte(), 0, 0, 0)),
            LayoutField("type", 56, 4, FieldEncoding.ASCII_DECIMAL_AS_HEX),
            LayoutField("material_id", 60, 1, FieldEncoding.UINT_BE),
            LayoutField("subtype_id", 61, 1, FieldEncoding.UINT_BE),
            LayoutField("pad19", 62, 2, FieldEncoding.RAW, constant = zeros(2), identifying = false),
            LayoutField("color_rgba", 64, 4, FieldEncoding.RAW),
            LayoutField("nozzle_min", 68, 2, FieldEncoding.UINT_BE),
            LayoutField("nozzle_max", 70, 2, FieldEncoding.UINT_BE),
            // Pagina 22: desconocida (¿cama?). El escritor de referencia la deja a cero.
            LayoutField("page22", 72, 4, FieldEncoding.RAW, constant = zeros(4), identifying = false),
            LayoutField("diameter", 76, 2, FieldEncoding.UINT_BE, scale = 100),
            LayoutField("weight_g", 78, 2, FieldEncoding.UINT_BE),
            // Pagina 24: desconocida; el escritor de referencia copia este valor fijo.
            LayoutField("page24", 80, 4, FieldEncoding.RAW, constant = byteArrayOf(0, 0x36, 0xC8.toByte(), 0), identifying = false),
        ),
    )

    val MATERIAL_IDS = mapOf(
        "PLA" to 0, "PETG" to 1, "ABS" to 2, "TPU" to 3, "PA" to 4, "PC" to 6, "PVA" to 7,
        "ASA" to 8, "BVOH" to 9, "HIPS" to 11, "PP" to 12,
    )

    /** Subtipos de PLA. Para el resto de materiales solo se conoce el 0. */
    private val PLA_SUBTYPES = listOf("CF" to 4, "Silk" to 3, "Matte" to 6, "Wood" to 8, "HS" to 10, "Marble" to 11, "Sparkle" to 12)

    override fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite {
        val parts = MaterialParts.of(spec)
        val materialId = MATERIAL_IDS[parts.base]
            ?: throw ProfileException(ProfileError.UNSUPPORTED_MATERIAL, listOf(spec.material), alternativeProfileId = OpenSpoolProfile.id)
        val subtypeId = when {
            parts.base != "PLA" -> 0
            parts.plus -> 1
            else -> PLA_SUBTYPES.firstOrNull { it.first in parts.subtypes }?.second ?: 0
        }
        val data = LAYOUT.encode(
            mapOf(
                "type" to parts.base,
                "material_id" to materialId,
                "subtype_id" to subtypeId,
                "color_rgba" to (spec.colorHex + "FF").hexToBytes(),
                "nozzle_min" to need(spec.nozzleTempMin, Field.NOZZLE_MIN),
                "nozzle_max" to need(spec.nozzleTempMax, Field.NOZZLE_MAX),
                "diameter" to (spec.diameterMm ?: 1.75),
                "weight_g" to (spec.netWeightG ?: 1000),
            ),
        )
        return TagWrite.Pages.fromLayout(LAYOUT, data, FIRST_PAGE)
    }

    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? {
        val pages = read as? TagRead.Pages ?: return null
        val data = pages.data.takeIf { pages.startPage == FIRST_PAGE && LAYOUT.matchesConstants(it) } ?: return null
        val v = LAYOUT.decode(data)
        val base = v["type"] as String
        val subtypeId = num(v["subtype_id"])
        val plus = base == "PLA" && subtypeId == 1
        val rgba = v["color_rgba"] as ByteArray
        val spec = FilamentSpec(
            material = if (plus) "PLA+" else base,
            subtype = if (base == "PLA") PLA_SUBTYPES.firstOrNull { it.second == subtypeId }?.first.orEmpty() else "",
            colorHex = rgba.copyOf(3).toHex(),
            nozzleTempMin = num(v["nozzle_min"]), nozzleTempMax = num(v["nozzle_max"]),
            diameterMm = (v["diameter"] as Double).takeIf { it > 0 },
            netWeightG = num(v["weight_g"])?.takeIf { it > 0 },
        )
        return fromTag(spec, name)
    }
}

// ============================================================ QIDI Box

object QidiBoxProfile : BaseProfile() {
    override val id = "qidi_box"
    override val name = "QIDI Box"
    override val tagType = "MIFARE Classic 1K"
    override val tech = TagTech.MIFARE_CLASSIC_1K
    override val verified = false
    override val unverifiedReason = UnverifiedReason.COMMUNITY_REVERSE_ENGINEERED
    override val reference = "QIDITECH/QIDI_Q2 config/officiall_filas_list.cfg · DnG-Crafts/QIDI-RFID · OpenRFID src/tag/qidi"
    override val requiresColor = true

    const val BLOCK = 4

    /** `[filaN]` del cfg oficial de QIDI: (material base, subtipo o null) -> N. */
    private val MATERIAL_CODES: List<Triple<String, String?, Int>> = listOf(
        Triple("PLA", "HS", 1), Triple("PLA", "Matte", 2), Triple("PLA", "Metal", 3),
        Triple("PLA", "Silk", 4), Triple("PLA", "CF", 5), Triple("PLA", "Wood", 6), Triple("PLA", null, 7),
        Triple("ABS", "GF", 12), Triple("ABS", "Metal", 13), Triple("ABS", null, 11),
        Triple("ASA", "CF", 20), Triple("ASA", null, 18),
        Triple("PC", null, 23),
        Triple("PA", "CF", 25), Triple("PA", null, 24),
        Triple("PET", "CF", 37), Triple("PET", "GF", 38),
        Triple("PETG", "HS", 41), Triple("PETG", "CF", 42), Triple("PETG", "GF", 43),
        Triple("PETG", "Transparent", 45), Triple("PETG", null, 39),
        Triple("PVA", null, 47),
        Triple("TPU", null, 50),
    )

    /** `[colordict]` del cfg oficial. */
    val COLORS: Map<Int, String> = mapOf(
        1 to "FAFAFA", 2 to "060606", 3 to "D9E3ED", 4 to "5CF30F", 5 to "63E492", 6 to "2850FF",
        7 to "FE98FE", 8 to "DFD628", 9 to "228332", 10 to "99DEFF", 11 to "1714B0", 12 to "CEC0FE",
        13 to "CADE4B", 14 to "1353AB", 15 to "5EA9FD", 16 to "A878FF", 17 to "FE717A", 18 to "FF362D",
        19 to "E2DFCD", 20 to "898F9B", 21 to "6E3812", 22 to "CAC59F", 23 to "F28636", 24 to "B87F2B",
    )

    fun materialCode(spec: FilamentSpec): Int? {
        val parts = MaterialParts.of(spec)
        return MATERIAL_CODES.firstOrNull { (base, sub, _) -> base == parts.base && sub != null && sub in parts.subtypes }?.third
            ?: MATERIAL_CODES.firstOrNull { (base, sub, _) -> base == parts.base && sub == null }?.third
    }

    /** La caja solo conoce 24 colores: se elige el mas cercano (distancia RGB ponderada). */
    fun nearestColor(hex: String): Int {
        val (r, g, b) = hex.hexToBytes().map { it.toInt() and 0xFF }
        return COLORS.minBy { (_, c) ->
            val (cr, cg, cb) = c.hexToBytes().map { it.toInt() and 0xFF }
            val rm = (r + cr) / 2
            (2 + rm / 256.0) * (r - cr) * (r - cr) + 4.0 * (g - cg) * (g - cg) + (2 + (255 - rm) / 256.0) * (b - cb) * (b - cb)
        }.key
    }

    override fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite {
        val material = materialCode(spec)
            ?: throw ProfileException(ProfileError.UNSUPPORTED_MATERIAL, listOf(spec.material), alternativeProfileId = OpenSpoolProfile.id)
        val vendor = if (Catalog.brand(spec.brand)?.name == "QIDI") 1 else 0
        // OpenRFID rechaza el tag si hay algo distinto de cero en los bytes 3-47 del sector 1.
        val block4 = ByteArray(16).also {
            it[0] = material.toByte()
            it[1] = nearestColor(spec.colorHex).toByte()
            it[2] = vendor.toByte()
        }
        return TagWrite.MifareClassic(
            authKeys = mapOf(1 to listOf(MifareClassic1K.KEY_DEFAULT)),
            blocks = listOf(BlockWrite(4, block4), BlockWrite(5, zeros(16)), BlockWrite(6, zeros(16))),
        )
    }

    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? {
        val mfc = read as? TagRead.MifareClassic ?: return null
        val b4 = mfc.blocks[4] ?: return null
        val material = b4[0].toInt() and 0xFF
        val color = b4[1].toInt() and 0xFF
        if (material !in 1..50 || color !in 1..24 || (b4[2].toInt() and 0xFF) > 1) return null
        if ((3 until 16).any { b4[it] != 0.toByte() && !(it in 12..15) }) return null
        val entry = MATERIAL_CODES.firstOrNull { it.third == material } ?: return null
        val spec = FilamentSpec(
            material = entry.first,
            subtype = entry.second.orEmpty(),
            brand = if (b4[2].toInt() == 1) "QIDI" else "",
            colorHex = COLORS.getValue(color),
        )
        return fromTag(spec, name)
    }
}

// ============================================================ Creality CFS

/**
 * Tags del CFS de Creality (K2, K1, Hi). Contrastado con dos tags de fabrica
 * (el volcado de OpenRFID y uno propio leido con un Pixel): la clave de sector
 * derivada, el cifrado y las posiciones de los campos coinciden. Escritura
 * probada en una K2 el 30-sep-2026: el CFS leyo un clon y un tag generado por
 * Rodet (PLA generico, FF1E1E) sobre MIFARE Classic 1K en blanco.
 */
object CrealityCfsProfile : BaseProfile() {
    override val id = "creality_cfs"
    override val name = "Creality CFS"
    override val tagType = "MIFARE Classic 1K (UID 4 B)"
    override val tech = TagTech.MIFARE_CLASSIC_1K
    override val verified = true
    override val unverifiedReason: UnverifiedReason? = null
    override val reference = "Volcado de fabrica (OpenRFID) · DnG-Crafts/K2-RFID · codigos de Creality Print 7.2"
    override val requiresColor = true

    private const val VENDOR_CREALITY = "0276"
    private const val BATCH = "A2"
    private const val DATE = "AB124"
    private const val RESERVED = "00000000000000"

    /** Parte de texto que se puede validar: los tags de fabrica llevan binario en la reserva. */
    private const val TEXT_FIELDS = 34

    /**
     * Payload en claro, 48 caracteres, con el reparto que usa DnG y que
     * cuadra con el tag de fabrica: fecha(5) proveedor(4) lote(2) "1"
     * material(5) "0" RRGGBB longitud(4) serie(6) reserva(14).
     */
    fun plaintext(spec: FilamentSpec, materialCode: String): String {
        require(materialCode.length == 5 && materialCode.all { it.isDigit() }) { "codigo de material de 5 digitos" }
        val serial = spec.serial.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 6 }?.padStart(6, '0') ?: "000001"
        val length = SpoolLength.meters(spec).coerceIn(0, 9999).toString().padStart(4, '0')
        val text = DATE + VENDOR_CREALITY + BATCH + "1" + materialCode + "0" + spec.colorHex.uppercase() + length + serial + RESERVED
        check(text.length == CrealityCrypto.PAYLOAD_SIZE) { "payload de ${text.length} caracteres" }
        return text
    }

    fun materialCode(spec: FilamentSpec, settings: ProfileSettings): String? {
        settings.crealityMaterialCode?.takeIf { it.length == 5 && it.all(Char::isDigit) }?.let { return it }
        return CrealityMaterials.codeFor(spec, isCreality = Catalog.brand(spec.brand)?.name == "Creality")
    }

    override fun encodeValid(spec: FilamentSpec, context: WriteContext): TagWrite = write(spec, context, checkKeys = true)

    /**
     * [checkKeys] solo se desactiva en los tests, que no pueden llevar las
     * claves reales en el repositorio.
     */
    internal fun write(spec: FilamentSpec, context: WriteContext, checkKeys: Boolean): TagWrite {
        val settings = context.settings
        val derivation = settings.crealityDerivationKey
        val payloadKey = settings.crealityPayloadKey
        if (derivation == null || payloadKey == null) {
            throw ProfileException(ProfileError.MISSING_KEY, listOf("creality"))
        }
        // Una clave equivocada escribiria un tag que el CFS no lee: se comprueba antes.
        if (checkKeys && !CrealityKeys.isDerivationKey(derivation)) throw ProfileException(ProfileError.WRONG_KEY, listOf("derivation"))
        if (checkKeys && !CrealityKeys.isPayloadKey(payloadKey)) throw ProfileException(ProfileError.WRONG_KEY, listOf("payload"))
        val uid = context.uid
        if (uid == null || uid.size != 4) throw ProfileException(ProfileError.INVALID_UID, listOf(uid?.size ?: 0))
        val code = materialCode(spec, settings)
            ?: throw ProfileException(ProfileError.UNSUPPORTED_MATERIAL, listOf(spec.material), alternativeProfileId = OpenSpoolProfile.id)

        val sectorKey = CrealityCrypto.sectorKey(uid, derivation)
        val encrypted = CrealityCrypto.encryptPayload(plaintext(spec, code).toByteArray(Charsets.US_ASCII), payloadKey)
        return TagWrite.MifareClassic(
            // Primero la clave derivada (tag ya escrito), despues la de fabrica (tag virgen).
            authKeys = mapOf(1 to listOf(sectorKey, MifareClassic1K.KEY_DEFAULT)),
            blocks = (0 until 3).map { i -> BlockWrite(4 + i, encrypted.copyOfRange(i * 16, i * 16 + 16)) },
            // Como el tag de fabrica leido el 18-sep-2026: clave derivada en A y en B.
            // (El volcado de OpenRFID dejaba B por defecto; el CFS autentica con A.)
            keyChanges = listOf(SectorKeyChange(1, sectorKey, sectorKey)),
        )
    }

    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? {
        val mfc = read as? TagRead.MifareClassic ?: return null
        val raw = (4..6).map { mfc.blocks[it] ?: return null }.reduce { a, b -> a + b }
        val text = raw.toString(Charsets.ISO_8859_1).takeIf { looksPlain(it) }
            ?: settings.crealityPayloadKey?.let { key ->
                CrealityCrypto.decryptPayload(raw, key).toString(Charsets.ISO_8859_1).takeIf { looksPlain(it) }
            }
            ?: return null
        val code = text.substring(12, 17)
        val material = CrealityMaterials.byCode(code)
        val (base, subtype) = material?.let { CrealityMaterials.describe(it) } ?: ("" to "")
        val meters = text.substring(24, 28).toIntOrNull()
        val spec = FilamentSpec(
            material = base,
            subtype = subtype,
            // El proveedor 0276 va en todos los tags; la marca la dice el material.
            brand = if (material != null && !material.generic) "Creality" else "",
            colorHex = Normalization.normalizeHex(text.substring(18, 24)).orEmpty(),
            netWeightG = meters?.let { SpoolLength.weightFor(it) },
            serial = text.substring(28, 34),
            notes = material?.name ?: "Creality $code",
        )
        return fromTag(spec, name)
    }

    /**
     * Heuristica de OpenRFID (caracter 3 == '2' y 17 en {'0', '#'}) mas que
     * los campos de texto sean alfanumericos. Solo los 34 primeros: los tags
     * de fabrica llevan bytes binarios en la reserva.
     */
    private fun looksPlain(s: String): Boolean =
        s.length == CrealityCrypto.PAYLOAD_SIZE &&
            s.take(TEXT_FIELDS).all { it in '0'..'9' || it in 'A'..'Z' || it == '#' } &&
            s[3] == '2' && s[17] in "0#"
}

/**
 * Las claves AES del CFS no se distribuyen con la app. Se guarda solo su
 * SHA-256 (los mismos que publica OpenRFID) para confirmar que las que
 * introduce la persona son las buenas antes de gastar un tag.
 */
object CrealityKeys {
    private const val DERIVATION_SHA256 = "E544D94FEB16159BBD7BC227DF1E283ECA1F38F2BB2015DFCC6161B74473B5C2"
    private const val PAYLOAD_SHA256 = "ACEC2106007458579BA522B25610B2CF509AE59D7879CB975F65C45228E5C9A1"

    fun isDerivationKey(key: ByteArray): Boolean = sha256(key) == DERIVATION_SHA256
    fun isPayloadKey(key: ByteArray): Boolean = sha256(key) == PAYLOAD_SHA256

    /**
     * Acepta lo que se suele copiar de otros proyectos: hex (con o sin
     * espacios, `:` o `0x`) o una lista de bytes en decimal o `0x..`
     * separados por comas. Devuelve null si no son 16 bytes.
     */
    fun parse(text: String): ByteArray? {
        val t = text.trim().removePrefix("{").removeSuffix("}").trim()
        val bytes = if (t.contains(',')) {
            t.split(',').map { it.trim().removePrefix("(byte)").trim() }.filter { it.isNotEmpty() }.map { s ->
                val n = if (s.startsWith("0x", ignoreCase = true)) s.drop(2).toIntOrNull(16) else s.toIntOrNull()
                n?.takeIf { it in -128..255 }?.toByte() ?: return null
            }.toByteArray()
        } else {
            runCatching { t.replace(Regex("0x", RegexOption.IGNORE_CASE), "").replace(Regex("[\\s:]"), "").hexToBytes() }.getOrNull()
        }
        return bytes?.takeIf { it.size == 16 }
    }

    private fun sha256(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).toHex()
}

// ============================================================ Bambu Lab

/**
 * No se puede escribir: los tags de Bambu llevan una firma RSA que el AMS
 * valida contra una clave publica del firmware. No es cuestion de esfuerzo.
 * El perfil existe para explicarlo y proponer OpenSpool.
 */
object BambuProfile : TagProfile {
    override val id = "bambu"
    override val name = "Bambu Lab AMS"
    override val tagType = "MIFARE Classic 1K (RSA)"
    override val tech = TagTech.MIFARE_CLASSIC_1K
    override val verified = true
    override val unverifiedReason: UnverifiedReason? = null
    override val reference = "https://github.com/Bambu-Research-Group/RFID-Tag-Guide"
    override val requiresColor = false
    override val canWrite = false

    override fun encode(spec: FilamentSpec, context: WriteContext): TagWrite =
        throw ProfileException(ProfileError.NOT_WRITABLE, listOf(name), alternativeProfileId = OpenSpoolProfile.id)

    /** Leerlos exige derivar claves por UID; queda para mas adelante. */
    override fun decode(read: TagRead, settings: ProfileSettings): FilamentSpec? = null
}
