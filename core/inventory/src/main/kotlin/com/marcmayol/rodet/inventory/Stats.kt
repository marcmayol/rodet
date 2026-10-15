package com.marcmayol.rodet.inventory

import com.marcmayol.rodet.model.Catalog
import com.marcmayol.rodet.model.TextKeys
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.sqrt

/** Gasto agrupado por material, por color o por las dos cosas. */
data class Bucket(
    val key: String,
    val material: String,
    val colorHex: String,
    val colorName: String,
    val grams: Double,
    val cost: Double,
    val prints: Int,
)

data class Summary(
    val prints: Int = 0,
    val failed: Int = 0,
    val grams: Double = 0.0,
    val filamentCost: Double = 0.0,
    val energyCost: Double = 0.0,
    val kwh: Double = 0.0,
    val totalCost: Double = 0.0,
    val hours: Double = 0.0,
    val spoolsBought: Int = 0,
    val spentOnSpools: Double = 0.0,
    val stockG: Double = 0.0,
    val stockValue: Double = 0.0,
)

data class MonthSpend(val month: YearMonth, val printCost: Double, val purchases: Double, val grams: Double)

/**
 * Un material y color que se esta gastando. [daysLeft] es lo que dura lo que
 * queda al ritmo de [windowDays]; null si no hay stock que medir.
 */
data class ReorderHint(
    val material: String,
    val colorHex: String,
    val colorName: String,
    val stockG: Double,
    val activeSpools: Int,
    val gramsPerMonth: Double,
    val daysLeft: Double?,
    val urgent: Boolean,
)

object Stats {

    const val DAY_MS = 86_400_000L

    fun summary(inv: Inventory, from: Long? = null): Summary {
        val jobs = inv.jobs.filter { from == null || it.at >= from }
        val bought = inv.spools.filter { from == null || (it.purchasedAt ?: it.createdAt) >= from }
        val active = inv.spools.filter { it.isActive }
        return Summary(
            prints = jobs.size,
            failed = jobs.count { it.outcome == PrintOutcome.FAILED },
            grams = jobs.sumOf { it.grams },
            filamentCost = jobs.sumOf { it.cost.filament },
            energyCost = jobs.sumOf { it.cost.energy },
            kwh = jobs.sumOf { it.cost.kwh },
            totalCost = jobs.sumOf { it.cost.total },
            hours = jobs.sumOf { it.durationMin } / 60.0,
            spoolsBought = bought.size,
            spentOnSpools = bought.sumOf { it.price ?: 0.0 },
            stockG = active.sumOf { it.effectiveRemainingG ?: 0.0 },
            stockValue = active.sumOf { s -> val r = s.effectiveRemainingG; val p = s.pricePerKg; if (r != null && p != null) r / 1000 * p else 0.0 },
        )
    }

    fun byMaterial(jobs: List<PrintJob>): List<Bucket> = group(jobs) { materialKey(it.material) }

    fun byColor(jobs: List<PrintJob>): List<Bucket> = group(jobs) { colorKey(it) }

    fun byMaterialAndColor(jobs: List<PrintJob>): List<Bucket> = group(jobs) { "${materialKey(it.material)}|${colorKey(it)}" }

    private fun group(jobs: List<PrintJob>, key: (Usage) -> String): List<Bucket> {
        val rows = jobs.flatMap { j -> j.usages.map { j to it } }
        return rows.groupBy { key(it.second) }.map { (k, list) ->
            val usages = list.map { it.second }
            Bucket(
                key = k,
                material = mostCommon(usages.map { materialKey(it.material) }),
                colorHex = mostCommon(usages.map { it.colorHex }),
                colorName = mostCommon(usages.map { it.colorName }.filter { it.isNotBlank() }),
                grams = usages.sumOf { it.grams },
                cost = usages.sumOf { CostCalculator.filamentCost(it.grams, it.pricePerKg) },
                prints = list.map { it.first.id }.distinct().size,
            )
        }.sortedByDescending { it.grams }
    }

    /** Gasto por mes, de los [months] ultimos (el actual incluido), en orden. */
    fun monthly(inv: Inventory, now: Long, months: Int = 6, zone: ZoneId = ZoneId.systemDefault()): List<MonthSpend> {
        fun ym(ms: Long) = YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone))
        val current = ym(now)
        return (months - 1 downTo 0).map { back ->
            val m = current.minusMonths(back.toLong())
            val jobs = inv.jobs.filter { ym(it.at) == m }
            MonthSpend(
                month = m,
                printCost = jobs.sumOf { it.cost.total },
                purchases = inv.spools.filter { ym(it.purchasedAt ?: it.createdAt) == m }.sumOf { it.price ?: 0.0 },
                grams = jobs.sumOf { it.grams },
            )
        }
    }

    /**
     * Que conviene volver a pedir: el ritmo de gasto de cada material y color en
     * los ultimos [windowDays] contra lo que queda en las bobinas sin acabar.
     * Si hay menos historial que la ventana, se mide desde la primera impresion
     * (con un minimo de dos semanas, para que una tarde no parezca un año).
     */
    fun reorder(inv: Inventory, now: Long, windowDays: Int = 90, leadDays: Int = 30): List<ReorderHint> {
        val from = now - windowDays * DAY_MS
        val recent = inv.jobs.filter { it.at in from..now }
        if (recent.isEmpty()) return emptyList()
        val first = inv.jobs.minOf { it.at }
        val days = max(14.0, minOf(windowDays.toDouble(), (now - first).toDouble() / DAY_MS))

        val used = recent.flatMap { it.usages }.groupBy { groupKey(it.material, it.colorHex, it.colorName) }
        val stock = inv.spools.filter { it.isActive }.groupBy { groupKey(it.spec.material, it.spec.colorHex, it.spec.colorName) }

        return used.map { (k, usages) ->
            val spools = stock[k].orEmpty()
            val left = spools.sumOf { it.effectiveRemainingG ?: 0.0 }
            val perDay = usages.sumOf { it.grams } / days
            val daysLeft = if (perDay > 0) left / perDay else null
            ReorderHint(
                material = mostCommon(usages.map { materialKey(it.material) }),
                colorHex = mostCommon(usages.map { it.colorHex }),
                colorName = mostCommon(usages.map { it.colorName }.filter { it.isNotBlank() } + spools.map { it.spec.colorName }.filter { it.isNotBlank() }),
                stockG = left,
                activeSpools = spools.size,
                gramsPerMonth = perDay * 30,
                daysLeft = daysLeft,
                urgent = daysLeft != null && daysLeft < leadDays,
            )
        }.sortedWith(compareBy<ReorderHint> { it.daysLeft ?: Double.MAX_VALUE }.thenByDescending { it.gramsPerMonth })
    }

    fun materialKey(m: String): String = m.trim().uppercase()

    private fun colorKey(u: Usage): String = colorKey(u.colorHex, u.colorName)

    private fun colorKey(hex: String, name: String): String =
        hex.takeIf { it.isNotBlank() }?.uppercase() ?: "name:${TextKeys.fold(name.trim())}"

    private fun groupKey(material: String, hex: String, name: String) = "${materialKey(material)}|${colorKey(hex, name)}"

    private fun mostCommon(values: List<String>): String =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
}

object ColorNames {

    /**
     * Como llamar a un color: el nombre que puso la persona; si no, el del
     * catalogo que mas se le parece; si no hay ninguno cerca, el hex.
     */
    fun label(hex: String, name: String, spanish: Boolean): String {
        if (name.isNotBlank()) return name
        val h = hex6(hex) ?: return ""
        val c = nearest(h) ?: return "#$h"
        return if (spanish) c.es else c.en
    }

    fun nearest(hex: String, maxDistance: Double = 60.0): com.marcmayol.rodet.model.NamedColor? {
        val h = hex6(hex) ?: return null
        return Catalog.colors.map { it to distance(h, it.hex) }.filter { it.second <= maxDistance }.minByOrNull { it.second }?.first
    }

    fun distance(a: String, b: String): Double {
        val x = a.toLong(16)
        val y = b.toLong(16)
        val dr = ((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)
        val dg = ((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)
        val db = (x and 0xFF) - (y and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}

/** Que bobina del inventario es la que lleva puesta la impresora para cada filamento del gcode. */
object SpoolMatcher {

    private const val MAX_COLOR_DISTANCE = 80.0

    /**
     * Mismo material y el color mas parecido; a igualdad, la que ya esta en uso
     * y despues la que menos tiene (la que se esta acabando es la que va puesta).
     */
    fun best(spools: List<Spool>, type: String, hex: String): Spool? {
        val material = Stats.materialKey(type)
        val h = hex6(hex)
        val candidates = spools.filter { it.isActive && (material.isEmpty() || Stats.materialKey(it.spec.material) == material) }
        if (h == null) return candidates.singleOrNull()
        return candidates
            .mapNotNull { s -> hex6(s.spec.colorHex)?.let { s to ColorNames.distance(h, it) } }
            .filter { it.second <= MAX_COLOR_DISTANCE }
            .sortedWith(
                compareBy<Pair<Spool, Double>> { it.second }
                    .thenBy { if (it.first.status == SpoolStatus.IN_USE) 0 else 1 }
                    .thenBy { it.first.effectiveRemainingG ?: Double.MAX_VALUE },
            )
            .firstOrNull()?.first
    }
}
