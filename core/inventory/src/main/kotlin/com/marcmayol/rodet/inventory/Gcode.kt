package com.marcmayol.rodet.inventory

import com.marcmayol.rodet.model.Normalization
import kotlin.math.PI

/** Un filamento de una pieza laminada: cuanto gasta y de que es. */
data class SlicedFilament(
    val grams: Double,
    val type: String = "",
    /** RRGGBB, sin '#'; vacio si el laminador no lo dice. */
    val colorHex: String = "",
    /** Lo calculo Rodet desde la longitud, con densidad supuesta. */
    val estimated: Boolean = false,
)

data class SlicedPrint(
    val slicer: String = "",
    val durationS: Long? = null,
    val filaments: List<SlicedFilament> = emptyList(),
) {
    val grams: Double get() = filaments.sumOf { it.grams }
    val durationMin: Int? get() = durationS?.let { ((it + 30) / 60).toInt() }
}

/**
 * Lee lo que los laminadores dejan en los comentarios del gcode: Creality Print,
 * Orca, Bambu Studio y PrusaSlicer (`; filament used [g] = 1.2, 3.4`), Cura
 * (`;TIME:` y `;Filament used: 1.2m`) y Simplify3D. Solo mira las lineas de
 * comentario, asi que se le puede pasar el fichero entero.
 */
object GcodeInfo {

    private const val PLA_DENSITY = 1.24
    private const val DEFAULT_DIAMETER = 1.75
    private val GENERATED = Regex("^generated (?:by|with) (.+?)(?: on .*)?$", RegexOption.IGNORE_CASE)
    private val TOTAL_TIME = Regex("total estimated time\\s*[:=]\\s*([^;]+)", RegexOption.IGNORE_CASE)

    fun parse(lines: Sequence<String>): SlicedPrint? {
        var slicer = ""
        var grams: List<Double> = emptyList()
        var mm: List<Double> = emptyList()
        var meters: List<Double> = emptyList()
        var total: Double? = null
        var duration: Long? = null
        var fallbackDuration: Long? = null
        var types: List<String> = emptyList()
        var colors: List<String> = emptyList()
        var extruderColors: List<String> = emptyList()
        var densities: List<Double> = emptyList()
        var diameters: List<Double> = emptyList()

        for (raw in lines) {
            if (!raw.startsWith(";")) continue
            val line = raw.trimStart(';', ' ').trimEnd()
            val lower = line.lowercase()

            if (slicer.isEmpty()) {
                val g = GENERATED.find(line)
                if (g != null) {
                    slicer = g.groupValues[1].trim()
                    continue
                }
            }
            // Bambu/Orca: "; model printing time: 1h 2m; total estimated time: 1h 5m"
            if ("total estimated time" in lower) {
                Regex("total estimated time\\s*[:=]\\s*([^;]+)", RegexOption.IGNORE_CASE).find(line)?.let {
                    duration = duration ?: parseDuration(it.groupValues[1])
                }
                continue
            }

            val sep = line.indexOfAny(charArrayOf('=', ':'))
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim()
            when (key) {
                "filament used [g]" -> grams = numbers(value)
                "filament used [mm]" -> mm = numbers(value)
                "total filament used [g]", "total filament weight [g]" -> total = value.toDoubleOrNull()
                "estimated printing time (normal mode)" -> duration = parseDuration(value)
                "estimated printing time" -> fallbackDuration = fallbackDuration ?: parseDuration(value)
                "filament_type" -> types = list(value)
                "filament_colour", "filament_color" -> colors = list(value)
                "extruder_colour" -> extruderColors = list(value)
                "filament_density" -> densities = numbers(value)
                "filament_diameter" -> diameters = numbers(value)
                // Cura
                "time" -> if (value.all(Char::isDigit) && value.isNotEmpty()) fallbackDuration = value.toLong()
                "filament used" -> meters = numbers(value.replace("m", ""))
                "material" -> if (types.isEmpty() && value.isNotBlank() && !value[0].isDigit()) types = list(value)
                // Simplify3D
                "plastic weight" -> total = total ?: numbers(value).firstOrNull()
                "build time" -> fallbackDuration = fallbackDuration ?: parseDuration(value)
            }
        }

        var estimated = false
        if (grams.isEmpty() && (mm.isNotEmpty() || meters.isNotEmpty())) {
            val lengthsMm = mm.ifEmpty { meters.map { it * 1000.0 } }
            estimated = densities.isEmpty()
            grams = lengthsMm.mapIndexed { i, l ->
                val d = densities.getOrNull(i) ?: densities.firstOrNull() ?: PLA_DENSITY
                val dia = diameters.getOrNull(i) ?: diameters.firstOrNull() ?: DEFAULT_DIAMETER
                gramsFromLength(l, dia, d)
            }
        }
        if (grams.isEmpty()) total?.let { grams = listOf(it) }

        val palette = colors.ifEmpty { extruderColors }
        val filaments = grams.mapIndexedNotNull { i, g ->
            if (g < 0.005) return@mapIndexedNotNull null
            SlicedFilament(
                grams = g,
                type = types.getOrNull(i) ?: types.singleOrNull().orEmpty(),
                colorHex = (palette.getOrNull(i) ?: palette.singleOrNull())?.let(::hex6).orEmpty(),
                estimated = estimated,
            )
        }
        val time = duration ?: fallbackDuration
        if (filaments.isEmpty() && time == null) return null
        return SlicedPrint(slicer, time, filaments)
    }

    /**
     * `Metadata/slice_info.config` de un .gcode.3mf de Bambu/Orca/Creality:
     * tiempo y gramos por filamento ya calculados por el laminador.
     */
    fun parseSliceInfo(xml: String): SlicedPrint? {
        val attr = Regex("(\\w+)=\"([^\"]*)\"")
        var seconds = 0L
        var anyTime = false
        val filaments = mutableListOf<SlicedFilament>()
        Regex("<metadata\\s+key=\"prediction\"\\s+value=\"([0-9.]+)\"").findAll(xml).forEach {
            seconds += it.groupValues[1].toDouble().toLong()
            anyTime = true
        }
        Regex("<filament\\s[^>]*>").findAll(xml).forEach { m ->
            val a = attr.findAll(m.value).associate { it.groupValues[1] to it.groupValues[2] }
            val g = a["used_g"]?.toDoubleOrNull() ?: return@forEach
            if (g < 0.005) return@forEach
            filaments += SlicedFilament(g, a["type"].orEmpty(), a["color"]?.let(::hex6).orEmpty())
        }
        if (filaments.isEmpty()) {
            Regex("<metadata\\s+key=\"weight\"\\s+value=\"([0-9.]+)\"").findAll(xml)
                .sumOf { it.groupValues[1].toDouble() }
                .takeIf { it > 0 }
                ?.let { filaments += SlicedFilament(it) }
        }
        if (filaments.isEmpty() && !anyTime) return null
        return SlicedPrint("", seconds.takeIf { anyTime }, filaments)
    }

    /** "1d 2h 3m 4s", "52m 40s", "1 hours 2 minutes" o segundos a secas. */
    fun parseDuration(text: String): Long? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it.toLong() }
        val unit = Regex("(\\d+(?:\\.\\d+)?)\\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
        var total = 0.0
        var found = false
        unit.findAll(t).forEach {
            val n = it.groupValues[1].toDouble()
            total += n * when (it.groupValues[2].first()) {
                'd' -> 86400
                'h' -> 3600
                'm' -> 60
                else -> 1
            }
            found = true
        }
        return if (found) total.toLong() else null
    }

    fun gramsFromLength(lengthMm: Double, diameterMm: Double, density: Double): Double {
        val r = diameterMm / 20.0 // cm
        return lengthMm / 10.0 * PI * r * r * density
    }

    private fun list(value: String): List<String> = value.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun numbers(value: String): List<Double> =
        value.split(';', ',').mapNotNull { it.trim().split(' ').first().toDoubleOrNull() }
}

/** Algunos laminadores escriben el color con alfa (RRGGBBAA): se queda el RGB. */
internal fun hex6(s: String): String? = Normalization.normalizeHex(s.trim().removePrefix("#").let { if (it.length == 8) it.take(6) else it })
