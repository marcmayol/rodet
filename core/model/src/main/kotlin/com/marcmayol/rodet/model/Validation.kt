package com.marcmayol.rodet.model

enum class Severity {
    /** Impide escribir el tag. */
    BLOCKING,

    /** Se muestra en linea bajo el campo y deja continuar. */
    WARNING,
}

/**
 * Problema de validacion. No lleva texto: la UI traduce [code] a sus recursos
 * (es/en) y usa [args] para rellenar los huecos.
 */
data class Issue(
    val field: Field,
    val severity: Severity,
    val code: IssueCode,
    val args: List<Any> = emptyList(),
)

enum class IssueCode {
    MISSING_MATERIAL,
    MISSING_COLOR,
    INVALID_COLOR_HEX,
    OUT_OF_PHYSICAL_RANGE,
    RANGE_INVERTED,
    TEMP_UNUSUAL_FOR_MATERIAL,
    DIAMETER_NOT_NOMINAL,
    WEIGHT_IMPLAUSIBLE,
}

object Validation {

    // Limites fisicos: fuera de aqui el valor es un error seguro, no una rareza.
    val NOZZLE_PHYSICAL = 150..450
    val BED_PHYSICAL = 0..160
    val DRY_TEMP_PHYSICAL = 30..150
    val DRY_TIME_PHYSICAL = 1..96
    val DIAMETER_PHYSICAL = 1.0..3.5
    val NET_WEIGHT_PHYSICAL = 1..20_000
    val SPOOL_WEIGHT_PHYSICAL = 1..3_000
    val DENSITY_PHYSICAL = 0.5..3.0

    /** Margen sobre el rango tipico antes de avisar de una temperatura rara. */
    private const val TEMP_SLACK = 15

    fun validate(spec: FilamentSpec, requireColor: Boolean = false): List<Issue> {
        val out = mutableListOf<Issue>()

        if (spec.material.isBlank()) out += Issue(Field.MATERIAL, Severity.BLOCKING, IssueCode.MISSING_MATERIAL)
        if (spec.colorHex.isNotBlank() && Normalization.normalizeHex(spec.colorHex) == null) {
            out += Issue(Field.COLOR_HEX, Severity.BLOCKING, IssueCode.INVALID_COLOR_HEX, listOf(spec.colorHex))
        } else if (requireColor && spec.colorHex.isBlank()) {
            out += Issue(Field.COLOR_HEX, Severity.BLOCKING, IssueCode.MISSING_COLOR)
        }

        physical(out, Field.NOZZLE_MIN, spec.nozzleTempMin, NOZZLE_PHYSICAL)
        physical(out, Field.NOZZLE_MAX, spec.nozzleTempMax, NOZZLE_PHYSICAL)
        physical(out, Field.BED_MIN, spec.bedTempMin, BED_PHYSICAL)
        physical(out, Field.BED_MAX, spec.bedTempMax, BED_PHYSICAL)
        physical(out, Field.DRY_TEMP, spec.dryTempC, DRY_TEMP_PHYSICAL)
        physical(out, Field.DRY_TIME, spec.dryTimeH, DRY_TIME_PHYSICAL)
        physical(out, Field.NET_WEIGHT, spec.netWeightG, NET_WEIGHT_PHYSICAL)
        physical(out, Field.SPOOL_WEIGHT, spec.spoolWeightG, SPOOL_WEIGHT_PHYSICAL)
        spec.diameterMm?.let {
            if (it !in DIAMETER_PHYSICAL) out += outOfRange(Field.DIAMETER, it, DIAMETER_PHYSICAL)
            else if (!Normalization.isNominalDiameter(it)) {
                out += Issue(Field.DIAMETER, Severity.WARNING, IssueCode.DIAMETER_NOT_NOMINAL, listOf(it))
            }
        }
        spec.densityGCm3?.let {
            if (it !in DENSITY_PHYSICAL) out += outOfRange(Field.DENSITY, it, DENSITY_PHYSICAL)
        }

        rangeOrder(out, Field.NOZZLE_MAX, spec.nozzleTempMin, spec.nozzleTempMax)
        rangeOrder(out, Field.BED_MAX, spec.bedTempMin, spec.bedTempMax)

        Catalog.material(spec.material)?.let { m ->
            unusual(out, Field.NOZZLE_MIN, spec.nozzleTempMin, m.nozzle, m.code)
            unusual(out, Field.NOZZLE_MAX, spec.nozzleTempMax, m.nozzle, m.code)
            unusual(out, Field.BED_MIN, spec.bedTempMin, m.bed, m.code)
            unusual(out, Field.BED_MAX, spec.bedTempMax, m.bed, m.code)
        }

        spec.netWeightG?.let {
            if (it in NET_WEIGHT_PHYSICAL && (it < 50 || it > 10_000)) {
                out += Issue(Field.NET_WEIGHT, Severity.WARNING, IssueCode.WEIGHT_IMPLAUSIBLE, listOf(it))
            }
        }
        return out
    }

    fun hasBlocking(issues: List<Issue>): Boolean = issues.any { it.severity == Severity.BLOCKING }

    private fun physical(out: MutableList<Issue>, field: Field, value: Int?, range: IntRange) {
        if (value != null && value !in range) {
            out += Issue(field, Severity.BLOCKING, IssueCode.OUT_OF_PHYSICAL_RANGE, listOf(value, range.first, range.last))
        }
    }

    private fun outOfRange(field: Field, value: Double, range: ClosedFloatingPointRange<Double>) =
        Issue(field, Severity.BLOCKING, IssueCode.OUT_OF_PHYSICAL_RANGE, listOf(value, range.start, range.endInclusive))

    private fun rangeOrder(out: MutableList<Issue>, field: Field, min: Int?, max: Int?) {
        if (min != null && max != null && min > max) {
            out += Issue(field, Severity.BLOCKING, IssueCode.RANGE_INVERTED, listOf(min, max))
        }
    }

    private fun unusual(out: MutableList<Issue>, field: Field, value: Int?, typical: IntRange, material: String) {
        if (value == null) return
        if (out.any { it.field == field }) return // ya hay un error mas grave
        if (value < typical.first - TEMP_SLACK || value > typical.last + TEMP_SLACK) {
            out += Issue(
                field, Severity.WARNING, IssueCode.TEMP_UNUSUAL_FOR_MATERIAL,
                listOf(value, material, typical.first, typical.last),
            )
        }
    }
}
