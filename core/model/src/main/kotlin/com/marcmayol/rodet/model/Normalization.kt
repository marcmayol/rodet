package com.marcmayol.rodet.model

import kotlin.math.abs

/** Ajuste a los valores nominales del mercado y limpieza de rangos. */
object Normalization {

    val NOMINAL_DIAMETERS_MM = listOf(1.75, 2.85, 3.00)
    val NOMINAL_WEIGHTS_G = listOf(250, 500, 750, 1000, 2000)

    private const val DIAMETER_TOLERANCE_MM = 0.10
    private const val WEIGHT_TOLERANCE = 0.05

    /** 1.74 -> 1.75; 2.9 -> 2.85; 1.60 se queda como esta. */
    fun snapDiameter(mm: Double): Double =
        NOMINAL_DIAMETERS_MM.minBy { abs(it - mm) }.takeIf { abs(it - mm) <= DIAMETER_TOLERANCE_MM } ?: mm

    fun snapWeight(g: Int): Int =
        NOMINAL_WEIGHTS_G.minBy { abs(it - g) }.takeIf { abs(it - g) <= it * WEIGHT_TOLERANCE } ?: g

    fun isNominalDiameter(mm: Double): Boolean = NOMINAL_DIAMETERS_MM.any { abs(it - mm) < 1e-9 }

    /** `#ff0000`, `ff0000`, `F00` -> `FF0000`. Devuelve null si no es un color valido. */
    fun normalizeHex(input: String): String? {
        val h = input.trim().removePrefix("#").uppercase()
        return when {
            h.length == 6 && h.all { it in HEX } -> h
            h.length == 3 && h.all { it in HEX } -> h.map { "$it$it" }.joinToString("")
            else -> null
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * Normalizacion completa: nominales, hex y rangos invertidos (un guion mal
     * leido convierte 190-230 en 230-190). No toca la procedencia salvo para
     * dejar constancia de lo que ha corregido.
     */
    fun normalize(spec: FilamentSpec): FilamentSpec {
        var s = spec
        s.diameterMm?.let { s = s.copy(diameterMm = snapDiameter(it)) }
        s.netWeightG?.let { s = s.copy(netWeightG = snapWeight(it)) }
        if (s.colorHex.isNotEmpty()) s = s.copy(colorHex = normalizeHex(s.colorHex) ?: s.colorHex)
        s = s.copy(material = s.material.trim(), brand = s.brand.trim(), colorName = s.colorName.trim())
        s = fixRange(s, Field.NOZZLE_MIN, Field.NOZZLE_MAX)
        s = fixRange(s, Field.BED_MIN, Field.BED_MAX)
        return s
    }

    private fun fixRange(spec: FilamentSpec, minF: Field, maxF: Field): FilamentSpec {
        val min = minF.get(spec) as Int? ?: return spec
        val max = maxF.get(spec) as Int? ?: return spec
        if (min <= max) return spec
        val swapped = maxF.set(minF.set(spec, max), min)
        val p = spec.provenance.toMutableMap()
        val pMin = spec.provenance[minF]
        val pMax = spec.provenance[maxF]
        if (pMax != null) p[minF] = pMax else p.remove(minF)
        if (pMin != null) p[maxF] = pMin else p.remove(maxF)
        return swapped.copy(provenance = p)
    }
}
