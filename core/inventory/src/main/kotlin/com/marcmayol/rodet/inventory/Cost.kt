package com.marcmayol.rodet.inventory

/**
 * Lo que cuesta imprimir en casa. Filamento y luz siempre; lo demas es opcional
 * (desgaste, fallos, mano de obra, beneficio) y por defecto va apagado.
 */
data class CostSettings(
    val kwhPrice: Double = 0.15,
    /** Potencia media de la impresora imprimiendo, no la maxima de la fuente. */
    val printerWatts: Double = 200.0,
    /** Para el filamento que no viene de una bobina con precio. */
    val defaultPricePerKg: Double = 20.0,
    val currency: String = "€",

    val failureEnabled: Boolean = false,
    val failurePct: Double = 10.0,

    val wearEnabled: Boolean = false,
    val printerPrice: Double = 600.0,
    val printerLifeH: Double = 5000.0,

    val labourEnabled: Boolean = false,
    val labourPerHour: Double = 15.0,

    val profitEnabled: Boolean = false,
    val profitPct: Double = 30.0,
)

data class CostBreakdown(
    val filament: Double = 0.0,
    val kwh: Double = 0.0,
    val energy: Double = 0.0,
    val wear: Double = 0.0,
    val failure: Double = 0.0,
    val labour: Double = 0.0,
    val profit: Double = 0.0,
) {
    val total: Double get() = filament + energy + wear + failure + labour + profit
}

object CostCalculator {

    fun filamentCost(grams: Double, pricePerKg: Double): Double = grams / 1000.0 * pricePerKg

    fun compute(usages: List<Usage>, durationMin: Int, settings: CostSettings, labourMin: Int = 0): CostBreakdown {
        val hours = durationMin.coerceAtLeast(0) / 60.0
        val filament = usages.sumOf { filamentCost(it.grams, it.pricePerKg) }
        val kwh = settings.printerWatts * hours / 1000.0
        val energy = kwh * settings.kwhPrice
        val wear = if (settings.wearEnabled && settings.printerLifeH > 0) hours * settings.printerPrice / settings.printerLifeH else 0.0
        // Los fallos repiten la pieza: se cargan sobre lo que cuesta producirla.
        val failure = if (settings.failureEnabled) (filament + energy + wear) * settings.failurePct / 100.0 else 0.0
        val labour = if (settings.labourEnabled) labourMin.coerceAtLeast(0) / 60.0 * settings.labourPerHour else 0.0
        val base = filament + energy + wear + failure + labour
        val profit = if (settings.profitEnabled) base * settings.profitPct / 100.0 else 0.0
        return CostBreakdown(filament, kwh, energy, wear, failure, labour, profit)
    }
}
