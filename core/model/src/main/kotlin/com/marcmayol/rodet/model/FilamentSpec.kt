package com.marcmayol.rodet.model

/**
 * Formato canonico por el que pasa todo: OCR, catalogo y entrada manual.
 * De aqui salen tanto los tags como las etiquetas impresas.
 */
data class FilamentSpec(
    val material: String = "",            // PLA, PETG, ABS, ASA, TPU, PA, PC...
    val subtype: String = "",             // Silk, Matte, CF, GF, Glow... (separados por espacio)
    val brand: String = "",
    val colorName: String = "",
    val colorHex: String = "",            // RRGGBB en mayusculas, sin '#'
    val diameterMm: Double? = null,
    val netWeightG: Int? = null,
    val spoolWeightG: Int? = null,
    val densityGCm3: Double? = null,
    val nozzleTempMin: Int? = null, val nozzleTempMax: Int? = null,
    val bedTempMin: Int? = null, val bedTempMax: Int? = null,
    val dryTempC: Int? = null, val dryTimeH: Int? = null,
    val serial: String = "", val lot: String = "", val notes: String = "",
    val provenance: Map<Field, Provenance> = emptyMap(),
) {
    fun provenanceOf(field: Field): Provenance? = provenance[field]

    fun isEmpty(field: Field): Boolean = when (val v = field.get(this)) {
        null -> true
        is String -> v.isBlank()
        else -> false
    }

    /** Longitud estimada en metros; sirve de comprobacion (1 kg de PLA a 1.75 mm son unos 335 m). */
    fun estimatedLengthM(): Double? {
        val mass = netWeightG ?: return null
        val density = densityGCm3 ?: return null
        val diameter = diameterMm ?: return null
        if (density <= 0.0 || diameter <= 0.0) return null
        val radiusCm = diameter / 20.0
        val sectionCm2 = Math.PI * radiusCm * radiusCm
        return mass / (density * sectionCm2) / 100.0
    }
}

/** De donde sale un valor. TAG: leido de un tag NFC ya escrito. */
enum class Source { OCR, DEFAULT, MANUAL, CATALOG, DERIVED, TAG }

data class Provenance(
    val source: Source,
    val confidence: Double,     // 0.0 - 1.0
    val evidence: String = "",  // fragmento de texto que lo justifica
) {
    init {
        require(confidence in 0.0..1.0) { "confianza fuera de rango: $confidence" }
    }

    /** Lo que la UI pinta en ambar: leido, pero con dudas. */
    val needsReview: Boolean get() = source == Source.OCR && confidence < REVIEW_THRESHOLD

    companion object {
        const val REVIEW_THRESHOLD = 0.8
        val MANUAL = Provenance(Source.MANUAL, 1.0)
    }
}

/** Campos editables de [FilamentSpec]. La clave es estable: se persiste en JSON. */
enum class Field(val key: String) {
    MATERIAL("material"),
    SUBTYPE("subtype"),
    BRAND("brand"),
    COLOR_NAME("colorName"),
    COLOR_HEX("colorHex"),
    DIAMETER("diameterMm"),
    NET_WEIGHT("netWeightG"),
    SPOOL_WEIGHT("spoolWeightG"),
    DENSITY("densityGCm3"),
    NOZZLE_MIN("nozzleTempMin"),
    NOZZLE_MAX("nozzleTempMax"),
    BED_MIN("bedTempMin"),
    BED_MAX("bedTempMax"),
    DRY_TEMP("dryTempC"),
    DRY_TIME("dryTimeH"),
    SERIAL("serial"),
    LOT("lot"),
    NOTES("notes");

    fun get(spec: FilamentSpec): Any? = when (this) {
        MATERIAL -> spec.material
        SUBTYPE -> spec.subtype
        BRAND -> spec.brand
        COLOR_NAME -> spec.colorName
        COLOR_HEX -> spec.colorHex
        DIAMETER -> spec.diameterMm
        NET_WEIGHT -> spec.netWeightG
        SPOOL_WEIGHT -> spec.spoolWeightG
        DENSITY -> spec.densityGCm3
        NOZZLE_MIN -> spec.nozzleTempMin
        NOZZLE_MAX -> spec.nozzleTempMax
        BED_MIN -> spec.bedTempMin
        BED_MAX -> spec.bedTempMax
        DRY_TEMP -> spec.dryTempC
        DRY_TIME -> spec.dryTimeH
        SERIAL -> spec.serial
        LOT -> spec.lot
        NOTES -> spec.notes
    }

    /**
     * Devuelve una copia con el valor cambiado. Acepta `null` para vaciar.
     * Un tipo equivocado es un error de programacion, no de datos.
     */
    fun set(spec: FilamentSpec, value: Any?): FilamentSpec = when (this) {
        MATERIAL -> spec.copy(material = str(value))
        SUBTYPE -> spec.copy(subtype = str(value))
        BRAND -> spec.copy(brand = str(value))
        COLOR_NAME -> spec.copy(colorName = str(value))
        COLOR_HEX -> spec.copy(colorHex = str(value))
        DIAMETER -> spec.copy(diameterMm = dbl(value))
        NET_WEIGHT -> spec.copy(netWeightG = int(value))
        SPOOL_WEIGHT -> spec.copy(spoolWeightG = int(value))
        DENSITY -> spec.copy(densityGCm3 = dbl(value))
        NOZZLE_MIN -> spec.copy(nozzleTempMin = int(value))
        NOZZLE_MAX -> spec.copy(nozzleTempMax = int(value))
        BED_MIN -> spec.copy(bedTempMin = int(value))
        BED_MAX -> spec.copy(bedTempMax = int(value))
        DRY_TEMP -> spec.copy(dryTempC = int(value))
        DRY_TIME -> spec.copy(dryTimeH = int(value))
        SERIAL -> spec.copy(serial = str(value))
        LOT -> spec.copy(lot = str(value))
        NOTES -> spec.copy(notes = str(value))
    }

    private fun str(v: Any?): String = (v ?: "") as String
    private fun dbl(v: Any?): Double? = (v as Number?)?.toDouble()
    private fun int(v: Any?): Int? = (v as Number?)?.toInt()

    companion object {
        fun fromKey(key: String): Field? = entries.firstOrNull { it.key == key }
    }
}
