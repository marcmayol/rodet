package com.marcmayol.rodet.tags

import com.marcmayol.rodet.model.FilamentSpec

/**
 * Un material de la base de datos del CFS (`material_database.json`).
 * [type] es el `filament_type` del perfil: material base y aditivo con guion.
 */
data class CrealityMaterial(val code: String, val name: String, val type: String) {
    val generic: Boolean get() = code.startsWith("000")
}

/**
 * Codigos `filament_id` de los perfiles de filamento de Creality Print 7.2
 * (`resources/profiles/<fabricante>/filament/`). Coinciden con los de los tags de
 * fabrica: el volcado real de un Hyper PLA lleva `01001`.
 *
 * Los `000xx` son los genericos: los que tocan a un filamento que no es de
 * Creality, para que la impresora aplique temperaturas del material y no las
 * de una gama concreta.
 */
object CrealityMaterials {
    val all: List<CrealityMaterial> = listOf(
        CrealityMaterial("00001", "Generic PLA", "PLA"),
        CrealityMaterial("00002", "Generic PLA-Silk", "PLA-Silk"),
        CrealityMaterial("00003", "Generic PETG", "PETG"),
        CrealityMaterial("00004", "Generic ABS", "ABS"),
        CrealityMaterial("00005", "Generic TPU", "TPU"),
        CrealityMaterial("00006", "Generic PLA-CF", "PLA-CF"),
        CrealityMaterial("00007", "Generic ASA", "ASA"),
        CrealityMaterial("00008", "Generic PA", "PA"),
        CrealityMaterial("00009", "Generic PA-CF", "PA-CF"),
        CrealityMaterial("00010", "Generic BVOH", "BVOH"),
        CrealityMaterial("00011", "Generic PVA", "PVA"),
        CrealityMaterial("00012", "Generic HIPS", "HIPS"),
        CrealityMaterial("00013", "Generic PET-CF", "PET-CF"),
        CrealityMaterial("00014", "Generic PETG-CF", "PETG-CF"),
        CrealityMaterial("00015", "Generic PA6-CF", "PA6-CF"),
        CrealityMaterial("00016", "Generic PAHT-CF", "PAHT-CF"),
        CrealityMaterial("00017", "Generic PPS", "PPS"),
        CrealityMaterial("00018", "Generic PPS-CF", "PPS-CF"),
        CrealityMaterial("00019", "Generic PP", "PP"),
        CrealityMaterial("00020", "Generic PET", "PET"),
        CrealityMaterial("00021", "Generic PC", "PC"),
        CrealityMaterial("00022", "Generic PA612-CF", "PA-CF"),
        CrealityMaterial("00023", "Generic Support for PA", "PA"),
        CrealityMaterial("00024", "Generic Support for PLA", "PLA"),
        CrealityMaterial("00025", "Generic PA12-CF", "PA-CF"),
        CrealityMaterial("00026", "Generic TPU 64D", "TPU"),
        CrealityMaterial("00027", "Generic PETG-GF", "PETG-GF"),
        CrealityMaterial("00031", "Generic PP-CF", "PP-CF"),
        CrealityMaterial("00032", "Generic PCTG", "PCTG"),
        CrealityMaterial("00033", "Generic ASA-CF", "ASA-CF"),
        CrealityMaterial("00034", "Generic PA6-GF", "PA-GF"),
        CrealityMaterial("00035", "Generic PLA-LW", "PLA"),
        CrealityMaterial("00036", "Generic TPU 90A", "TPU"),
        CrealityMaterial("00037", "Generic TPU 85A", "TPU"),
        CrealityMaterial("00040", "Generic PLA-HT-GF", "PLA-GF"),
        CrealityMaterial("00041", "Generic ABS-GF", "ABS-GF"),
        CrealityMaterial("00042", "Generic ABS-CF", "ABS-CF"),
        CrealityMaterial("00043", "Generic PET-GF15", "PETG-GF"),
        CrealityMaterial("00044", "Generic PC-CF", "PC-CF"),
        CrealityMaterial("00045", "Generic ABS-FR", "ABS-FR"),
        CrealityMaterial("01001", "Hyper PLA", "PLA"),
        CrealityMaterial("01002", "Hyper L-W PLA", "PLA"),
        CrealityMaterial("01003", "Hyper Luminous", "PLA"),
        CrealityMaterial("01004", "Hyper Stardust", "PLA"),
        CrealityMaterial("01601", "Soleyin Ultra PLA", "PLA"),
        CrealityMaterial("02001", "Hyper PLA-CF", "PLA-CF"),
        CrealityMaterial("03001", "Hyper ABS", "ABS"),
        CrealityMaterial("04001", "CR-PLA", "PLA"),
        CrealityMaterial("05001", "CR-Silk", "PLA"),
        CrealityMaterial("06001", "CR-PETG", "PETG"),
        CrealityMaterial("06002", "Hyper PETG", "PETG"),
        CrealityMaterial("06003", "Hyper PETG-CF", "PETG-CF"),
        CrealityMaterial("06004", "Hyper PETG-GF", "PETG-GF"),
        CrealityMaterial("06005", "Soleyin Basic PETG", "PETG"),
        CrealityMaterial("07001", "CR-ABS", "ABS"),
        CrealityMaterial("07002", "Hyper PC", "PC"),
        CrealityMaterial("08001", "Ender-PLA", "PLA"),
        CrealityMaterial("09001", "EN-PLA+", "PLA"),
        CrealityMaterial("09002", "ENDER FAST PLA", "PLA"),
        CrealityMaterial("10001", "HP-TPU", "TPU"),
        CrealityMaterial("11001", "CR-Nylon", "PA"),
        CrealityMaterial("12002", "Hyper PPA-CF", "PA-CF"),
        CrealityMaterial("12003", "Hyper PAHT-CF", "PAHT-CF"),
        CrealityMaterial("12004", "Hyper PA612-CF", "PA-CF"),
        CrealityMaterial("12005", "Hyper PA6-CF", "PA-CF"),
        CrealityMaterial("13001", "CR-PLA Carbon", "PLA-CF"),
        CrealityMaterial("14001", "CR-PLA Matte", "PLA"),
        CrealityMaterial("15001", "CR-PLA Fluo", "PLA"),
        CrealityMaterial("16001", "CR-TPU", "TPU"),
        CrealityMaterial("17001", "CR-Wood", "PLA"),
        CrealityMaterial("18001", "HP Ultra PLA", "PLA"),
        CrealityMaterial("19001", "HP-ASA", "ASA"),
        CrealityMaterial("29001", "Hyper Marble", "PLA"),
    )

    fun byCode(code: String): CrealityMaterial? = all.firstOrNull { it.code == code }

    /**
     * Elige el codigo para una bobina: si es de Creality, su gama (Hyper si es
     * de alta velocidad, CR- si no); en cualquier otro caso, el generico del
     * material con su aditivo, o el del material base si no hay uno mas fino.
     */
    fun codeFor(spec: FilamentSpec, isCreality: Boolean): String? {
        val parts = MaterialParts.of(spec)
        val additive = parts.fibre ?: "Silk".takeIf { parts.base == "PLA" && "Silk" in parts.subtypes }
        val exact = listOfNotNull(additive?.let { "${parts.base}-$it" }, parts.base)
        if (isCreality) {
            val prefix = if ("HS" in parts.subtypes) "Hyper" else "CR-"
            all.firstOrNull { !it.generic && it.type == exact.first() && it.name.startsWith(prefix) }?.let { return it.code }
        }
        return exact.firstNotNullOfOrNull { t -> all.firstOrNull { it.generic && it.type == t } }?.code
    }

    private val NAME_SUBTYPES = listOf(
        "Hyper" to "HS", "HP " to "HS", "FAST" to "HS", "Silk" to "Silk", "Matte" to "Matte", "Wood" to "Wood",
        "Marble" to "Marble", "Luminous" to "Glow", "Stardust" to "Sparkle", "Carbon" to "CF",
    )

    /** Material y subtipos de Rodet para un codigo leido de un tag. */
    fun describe(material: CrealityMaterial): Pair<String, String> {
        val base = material.type.substringBefore('-').let { if (it.startsWith("PA")) "PA" else it }
        val suffix = material.type.substringAfter('-', "")
        val subtypes = linkedSetOf<String>()
        NAME_SUBTYPES.forEach { (hint, st) -> if (material.name.contains(hint, ignoreCase = true)) subtypes += st }
        when (suffix) {
            "CF", "GF" -> subtypes += suffix
            "Silk" -> subtypes += "Silk"
        }
        val plus = material.name.contains("PLA+")
        return (if (plus) "PLA+" else base) to subtypes.joinToString(" ")
    }
}
