package com.marcmayol.rodet.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcmayol.rodet.R
import java.time.format.TextStyle
import java.util.Locale

private enum class Period(val days: Int?) { D30(30), D90(90), Y1(365), ALL(null) }

private enum class Metric { GRAMS, COST }

@Composable
fun StatsScreen(inv: Inventory, settings: CostSettings, modifier: Modifier = Modifier) {
    var period by rememberSaveable { mutableStateOf(Period.Y1) }
    var metric by rememberSaveable { mutableStateOf(Metric.GRAMS) }
    val now = System.currentTimeMillis()
    val from = period.days?.let { now - it * Stats.DAY_MS }
    val jobs = inv.jobs.filter { from == null || it.at >= from }
    val sum = Stats.summary(inv, from)
    val cur = settings.currency

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p, onClick = { period = p },
                    label = {
                        Text(
                            stringResource(
                                when (p) {
                                    Period.D30 -> R.string.period_30
                                    Period.D90 -> R.string.period_90
                                    Period.Y1 -> R.string.period_year
                                    Period.ALL -> R.string.period_all
                                },
                            ),
                        )
                    },
                )
            }
        }

        // --- cifras
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            val tile = Modifier.weight(1f)
            StatTile(stringResource(R.string.stat_print_cost), money(sum.totalCost, cur), pluralStringResource(R.plurals.p_prints, sum.prints, sum.prints), tile)
            StatTile(stringResource(R.string.stat_filament_used), grams(sum.grams), money(sum.filamentCost, cur), tile)
            StatTile(stringResource(R.string.stat_energy), "${number(sum.kwh, 1)} kWh", money(sum.energyCost, cur), tile)
            StatTile(
                stringResource(R.string.stat_failed), if (sum.prints > 0) "${number(sum.failed * 100.0 / sum.prints, 0)} %" else "—",
                pluralStringResource(R.plurals.p_failed, sum.failed, sum.failed), tile,
            )
            StatTile(stringResource(R.string.stat_bought), money(sum.spentOnSpools, cur), pluralStringResource(R.plurals.p_spools, sum.spoolsBought, sum.spoolsBought), tile)
            StatTile(stringResource(R.string.stat_stock), grams(sum.stockG), stringResource(R.string.stat_stock_value, money(sum.stockValue, cur)), tile)
        }

        // --- que pedir
        val hints = Stats.reorder(inv, now)
        SectionTitle(stringResource(R.string.stat_reorder))
        if (hints.isEmpty()) {
            Text(stringResource(R.string.stat_reorder_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.stat_reorder_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hints.take(6).forEach { ReorderRow(it) }
        }

        if (jobs.isEmpty()) {
            EmptyState(stringResource(R.string.stat_empty_title), stringResource(R.string.stat_empty_body))
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(metric == Metric.GRAMS, { metric = Metric.GRAMS }, label = { Text(stringResource(R.string.metric_grams)) })
            FilterChip(metric == Metric.COST, { metric = Metric.COST }, label = { Text(stringResource(R.string.metric_cost)) })
        }

        // --- por material
        SectionTitle(stringResource(R.string.stat_by_material))
        Buckets(Stats.byMaterial(jobs), metric, cur, withColor = false)

        // --- por color
        SectionTitle(stringResource(R.string.stat_by_color))
        Buckets(Stats.byColor(jobs), metric, cur, withColor = true)

        // --- por mes
        SectionTitle(stringResource(R.string.stat_by_month))
        Monthly(Stats.monthly(inv, now), cur)
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun ReorderRow(h: ReorderHint) {
    val out = h.stockG < Spools.EMPTY_G
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (h.urgent || out) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorDot(h.colorHex, 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${h.material} · ${ColorNames.label(h.colorHex, h.colorName, spanish)}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (out) stringResource(R.string.reorder_line_out, grams(h.gramsPerMonth))
                    else stringResource(R.string.reorder_line, grams(h.stockG), pluralStringResource(R.plurals.p_spools, h.activeSpools, h.activeSpools), grams(h.gramsPerMonth)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    out -> {
                        Icon(Icons.Default.ShoppingCart, null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.reorder_out), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    h.urgent -> {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.reorder_days, number(h.daysLeft ?: 0.0, 0)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    else -> Text(
                        h.daysLeft?.let { if (it > 365) stringResource(R.string.reorder_plenty) else stringResource(R.string.reorder_days, number(it, 0)) } ?: "—",
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun Buckets(buckets: List<Bucket>, metric: Metric, currency: String, withColor: Boolean) {
    val shown = buckets.take(8)
    val rest = buckets.drop(8)
    fun v(b: Bucket) = if (metric == Metric.GRAMS) b.grams else b.cost
    val totalAll = buckets.sumOf { v(it) }.takeIf { it > 0 } ?: 1.0
    val max = (shown.maxOfOrNull { v(it) } ?: 1.0).takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { b ->
            val label = if (withColor) ColorNames.label(b.colorHex, b.colorName, spanish) else b.material.ifBlank { "?" }
            val value = if (metric == Metric.GRAMS) grams(b.grams) else money(b.cost, currency)
            BarRow(
                label = label,
                value = "$value · ${number(v(b) * 100 / totalAll, 0)} %",
                fraction = (v(b) / max).toFloat(),
                leading = if (withColor) ({ ColorDot(b.colorHex, 18.dp) }) else null,
                detail = stringResource(R.string.bucket_detail, pluralStringResource(R.plurals.p_prints, b.prints, b.prints), if (metric == Metric.GRAMS) money(b.cost, currency) else grams(b.grams)),
            )
        }
        if (rest.isNotEmpty()) {
            val g = rest.sumOf { v(it) }
            Text(
                stringResource(R.string.bucket_other, rest.size, if (metric == Metric.GRAMS) grams(g) else money(g, currency)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Columnas del gasto imprimiendo de cada mes; el valor encima, en texto. */
@Composable
private fun Monthly(months: List<MonthSpend>, currency: String) {
    val max = (months.maxOfOrNull { it.printCost } ?: 0.0).takeIf { it > 0 } ?: 1.0
    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        months.forEach { m ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(if (m.printCost > 0) money0(m.printCost, currency) else "", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(
                    Modifier
                        .width(20.dp)
                        .fillMaxHeight(((m.printCost / max).toFloat() * 0.75f).coerceAtLeast(0.01f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    m.month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
    val bought = months.sumOf { it.purchases }
    if (bought > 0) {
        Text(stringResource(R.string.stat_month_bought, money(bought, currency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
