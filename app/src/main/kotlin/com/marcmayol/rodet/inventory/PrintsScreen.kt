package com.marcmayol.rodet.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcmayol.rodet.R

@Composable
fun PrintsScreen(
    inv: Inventory,
    settings: CostSettings,
    calc: CalcDraft,
    busy: Boolean,
    vm: InventoryViewModel,
    onOpenGcode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = page) {
            Tab(page == 0, { page = 0 }, text = { Text(stringResource(R.string.prints_calc)) })
            Tab(page == 1, { page = 1 }, text = { Text(stringResource(R.string.prints_history, inv.jobs.size)) })
        }
        when (page) {
            0 -> CalculatorPage(inv, settings, calc, busy, vm, onOpenGcode, onSaved = { page = 1 })
            else -> HistoryPage(inv, settings, vm, onRepeat = { page = 0 })
        }
    }
}

@Composable
private fun CalculatorPage(
    inv: Inventory,
    settings: CostSettings,
    calc: CalcDraft,
    busy: Boolean,
    vm: InventoryViewModel,
    onOpenGcode: () -> Unit,
    onSaved: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val cost = vm.costOf(calc, inv, settings)
    val hasSpoolLines = calc.lines.any { it.spoolId != null && (it.grams ?: 0.0) > 0 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onOpenGcode, modifier = Modifier.weight(1f), enabled = !busy) {
                Icon(Icons.Default.FileOpen, null); Text("  " + stringResource(R.string.calc_open_gcode), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, stringResource(R.string.cost_settings)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            if (calc.source.isNotBlank()) stringResource(R.string.calc_from_gcode, calc.source) else stringResource(R.string.calc_gcode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(calc.name, vm::setJobName, label = { Text(stringResource(R.string.calc_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        SectionTitle(stringResource(R.string.calc_time))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val total = calc.durationMin
            DecimalField(
                stringResource(R.string.unit_hours), total?.let { (it / 60).toDouble() },
                { h -> vm.setDuration(((h?.toInt() ?: 0) * 60 + (total ?: 0) % 60).takeIf { h != null || total != null }) },
                modifier = Modifier.weight(1f), suffix = "h", decimals = false,
            )
            DecimalField(
                stringResource(R.string.unit_minutes), total?.let { (it % 60).toDouble() },
                { m -> vm.setDuration(((total ?: 0) / 60 * 60 + (m?.toInt() ?: 0).coerceAtMost(59)).takeIf { m != null || total != null }) },
                modifier = Modifier.weight(1f), suffix = "min", decimals = false,
            )
        }

        SectionTitle(stringResource(R.string.calc_filament))
        calc.lines.forEachIndexed { i, line -> LineCard(i, line, calc.lines.size, inv, settings, vm) }
        TextButton(onClick = vm::addLine) { Icon(Icons.Default.Add, null); Text(" " + stringResource(R.string.calc_add_filament)) }

        if (settings.labourEnabled) {
            DecimalField(stringResource(R.string.calc_labour), calc.labourMin.toDouble().takeIf { it > 0 }, { vm.setLabour(it?.toInt() ?: 0) }, suffix = "min", decimals = false)
        }

        CostCard(cost, settings, calc.durationMin ?: 0)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(calc.outcome == PrintOutcome.FAILED, { vm.setOutcome(if (it) PrintOutcome.FAILED else PrintOutcome.OK) })
            Text(stringResource(R.string.calc_failed))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(calc.deduct && hasSpoolLines, { vm.setDeduct(it) }, enabled = hasSpoolLines)
            Column {
                Text(stringResource(R.string.calc_deduct))
                if (!hasSpoolLines) Text(stringResource(R.string.calc_deduct_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val canSave = cost.total > 0 || calc.lines.any { (it.grams ?: 0.0) > 0 }
        Button(onClick = { vm.saveJob(); onSaved() }, enabled = canSave, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.calc_save))
        }
        TextButton(onClick = vm::clearCalc, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.calc_clear)) }
    }

    if (showSettings) CostSettingsDialog(settings, vm) { showSettings = false }
}

@Composable
private fun LineCard(i: Int, line: CalcLine, count: Int, inv: Inventory, settings: CostSettings, vm: InventoryViewModel) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val spool = inv.spool(line.spoolId)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        ColorDot(spool?.spec?.colorHex ?: line.colorHex, 20.dp)
                        Text(
                            "  " + (spool?.let { "${spoolTitle(it.spec)} · ${grams(it.effectiveRemainingG ?: 0.0)}" }
                                ?: stringResource(R.string.calc_no_spool, line.material.ifBlank { "?" })),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        inv.spools.filter { it.isActive }.sortedBy { it.status.ordinal }.forEach { s ->
                            DropdownMenuItem(
                                text = { Text("${spoolTitle(s.spec)} · ${s.spec.brand} · ${grams(s.effectiveRemainingG ?: 0.0)}", maxLines = 2) },
                                leadingIcon = { ColorDot(s.spec.colorHex, 20.dp) },
                                onClick = { vm.setLineSpool(i, s.id); menu = false },
                            )
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.calc_without_spool)) }, onClick = { vm.setLineSpool(i, null); menu = false })
                    }
                }
                if (count > 1) IconButton(onClick = { vm.removeLine(i) }) { Icon(Icons.Default.Close, stringResource(R.string.action_delete)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(stringResource(R.string.calc_grams), line.grams, { g -> vm.updateLine(i) { it.copy(grams = g) } }, modifier = Modifier.weight(1f), suffix = "g")
                if (spool?.pricePerKg == null) {
                    DecimalField(
                        stringResource(R.string.f_price_per_kg), line.pricePerKg, { p -> vm.updateLine(i) { it.copy(pricePerKg = p) } },
                        modifier = Modifier.weight(1f), suffix = settings.currency, placeholder = number(settings.defaultPricePerKg, 2),
                    )
                }
            }
            if (spool == null) {
                OutlinedTextField(
                    line.material, { m -> vm.updateLine(i) { it.copy(material = m.uppercase().take(12)) } },
                    label = { Text(stringResource(R.string.f_material)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            val left = spool?.effectiveRemainingG
            if (left != null && (line.grams ?: 0.0) > left) {
                Text(stringResource(R.string.calc_not_enough, grams(left)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CostCard(c: CostBreakdown, s: CostSettings, durationMin: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoRow(stringResource(R.string.cost_filament), money(c.filament, s.currency))
            InfoRow(stringResource(R.string.cost_energy, number(c.kwh, 2), duration(durationMin)), money(c.energy, s.currency))
            if (s.wearEnabled) InfoRow(stringResource(R.string.cost_wear), money(c.wear, s.currency))
            if (s.failureEnabled) InfoRow(stringResource(R.string.cost_failure, number(s.failurePct, 0)), money(c.failure, s.currency))
            if (s.labourEnabled) InfoRow(stringResource(R.string.cost_labour), money(c.labour, s.currency))
            if (s.profitEnabled) InfoRow(stringResource(R.string.cost_profit, number(s.profitPct, 0)), money(c.profit, s.currency))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (s.profitEnabled) R.string.cost_sale_price else R.string.cost_total),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                Text(money(c.total, s.currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CostSettingsDialog(s: CostSettings, vm: InventoryViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cost_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(stringResource(R.string.set_kwh), s.kwhPrice, { v -> v?.let { vm.updateSettings { c -> c.copy(kwhPrice = it) } } }, suffix = "${s.currency}/kWh")
                DecimalField(
                    stringResource(R.string.set_watts), s.printerWatts, { v -> v?.let { vm.updateSettings { c -> c.copy(printerWatts = it) } } },
                    suffix = "W", supporting = stringResource(R.string.set_watts_hint),
                )
                DecimalField(
                    stringResource(R.string.set_default_kg), s.defaultPricePerKg, { v -> v?.let { vm.updateSettings { c -> c.copy(defaultPricePerKg = it) } } },
                    suffix = "${s.currency}/kg", supporting = stringResource(R.string.set_default_kg_hint),
                )
                OutlinedTextField(
                    s.currency, { t -> vm.updateSettings { c -> c.copy(currency = t.take(4)) } },
                    label = { Text(stringResource(R.string.set_currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text(stringResource(R.string.set_extras), style = MaterialTheme.typography.titleSmall)
                Toggle(stringResource(R.string.cost_wear), s.wearEnabled) { on -> vm.updateSettings { it.copy(wearEnabled = on) } }
                if (s.wearEnabled) {
                    DecimalField(stringResource(R.string.set_printer_price), s.printerPrice, { v -> v?.let { vm.updateSettings { c -> c.copy(printerPrice = it) } } }, suffix = s.currency)
                    DecimalField(stringResource(R.string.set_printer_life), s.printerLifeH, { v -> v?.let { vm.updateSettings { c -> c.copy(printerLifeH = it) } } }, suffix = "h", decimals = false)
                }
                Toggle(stringResource(R.string.set_failure), s.failureEnabled) { on -> vm.updateSettings { it.copy(failureEnabled = on) } }
                if (s.failureEnabled) DecimalField(stringResource(R.string.set_failure_pct), s.failurePct, { v -> v?.let { vm.updateSettings { c -> c.copy(failurePct = it) } } }, suffix = "%")
                Toggle(stringResource(R.string.cost_labour), s.labourEnabled) { on -> vm.updateSettings { it.copy(labourEnabled = on) } }
                if (s.labourEnabled) DecimalField(stringResource(R.string.set_labour_rate), s.labourPerHour, { v -> v?.let { vm.updateSettings { c -> c.copy(labourPerHour = it) } } }, suffix = "${s.currency}/h")
                Toggle(stringResource(R.string.set_profit), s.profitEnabled) { on -> vm.updateSettings { it.copy(profitEnabled = on) } }
                if (s.profitEnabled) DecimalField(stringResource(R.string.set_profit_pct), s.profitPct, { v -> v?.let { vm.updateSettings { c -> c.copy(profitPct = it) } } }, suffix = "%")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun HistoryPage(inv: Inventory, settings: CostSettings, vm: InventoryViewModel, onRepeat: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val jobs = inv.jobs.sortedByDescending { it.at }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (jobs.isEmpty()) EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
        jobs.forEach { j ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.name.ifBlank { stringResource(R.string.job_unnamed) }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(date(j.at), duration(j.durationMin), grams(j.grams)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(money(j.cost.total, settings.currency), style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        j.usages.forEach { ColorDot(it.colorHex, 16.dp) }
                        if (j.outcome == PrintOutcome.FAILED) {
                            Text(" " + stringResource(R.string.job_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Box(Modifier.weight(1f))
                        IconButton(onClick = { vm.repeatJob(j.id); onRepeat() }) { Icon(Icons.Default.Replay, stringResource(R.string.job_repeat)) }
                        IconButton(onClick = { deleting = j.id }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        val j = inv.jobs.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.job_delete_q)) },
            text = { Text(stringResource(if (j?.deducted == true) R.string.job_delete_body_restore else R.string.job_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.deleteJob(id); deleting = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
