package com.marcmayol.rodet.extract

/**
 * Pistas: las palabras que dicen a que campo pertenece el numero que viene
 * detras. Se buscan todas a la vez, de izquierda a derecha y la frase mas
 * larga primero, para que `bed temp` se lea como una pista de cama y no deje
 * suelto un `temp` que parezca de boquilla.
 */
internal object Hints {

    enum class Kind { NOZZLE, BED, DRY, NET, GROSS, SPOOL }

    val TEMPERATURE_KINDS = setOf(Kind.NOZZLE, Kind.BED, Kind.DRY)
    val WEIGHT_KINDS = setOf(Kind.NET, Kind.GROSS, Kind.SPOOL)

    data class Hit(val kind: Kind, val start: Int, val end: Int, val exact: Boolean)

    private val VOCABULARY: Map<Kind, List<String>> = mapOf(
        Kind.NOZZLE to listOf(
            "nozzle temperature", "nozzle temp", "nozzle", "printing temperature", "printing temp",
            "print temperature", "print temp", "extruder temperature", "extruder temp", "extruder",
            "extrusion temperature", "extrusion temp", "hotend", "hot end",
            "temperatura de impresion", "temperatura impresion", "temp. de impresion", "temp. impresion",
            "temperatura de extrusion", "temperatura de boquilla", "temperatura boquilla", "boquilla",
            "extrusor", "impresion", "printing", "print", "duse", "buse", "ugello",
            "temperature", "temp",
        ),
        Kind.BED to listOf(
            "bed temperature", "bed temp", "heated bed", "heatbed", "heat bed", "hot bed", "hotbed",
            "build plate temperature", "build plate", "plate temperature", "plate temp", "platform temperature",
            "platform temp", "temperatura de cama", "temperatura cama", "temp. cama", "cama caliente",
            "bed", "plate", "platform", "cama", "plataforma", "bett", "plateau", "piatto",
        ),
        Kind.DRY to listOf(
            "drying temperature", "drying temp", "drying", "dry temp", "dryer", "dry",
            "temperatura de secado", "secado", "secar", "trocknung", "sechage",
        ),
        Kind.NET to listOf("net weight", "net wt", "n.w.", "peso neto", "neto", "net", "nettogewicht", "poids net"),
        Kind.GROSS to listOf("gross weight", "gross wt", "g.w.", "peso bruto", "bruto", "gross"),
        Kind.SPOOL to listOf("spool weight", "empty spool", "peso bobina", "bobina vacia", "peso de la bobina", "tare"),
    )

    private val matchers: List<Pair<Kind, PhraseMatcher>> =
        VOCABULARY.flatMap { (kind, phrases) -> phrases.map { kind to PhraseMatcher(it) } }
            .sortedByDescending { it.second.length }

    fun find(text: PreparedText): List<Hit> {
        val taken = BooleanArray(text.original.length)
        val out = mutableListOf<Hit>()
        for ((kind, matcher) in matchers) {
            for (hit in matcher.findAll(text)) {
                if (hit.range.any { taken[it] }) continue
                hit.range.forEach { taken[it] = true }
                out += Hit(kind, hit.range.first, hit.range.last + 1, hit.exact)
            }
        }
        return out.sortedBy { it.start }
    }
}
