package com.marcmayol.rodet.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcmayol.rodet.R
import com.marcmayol.rodet.flow.SpecFields
import com.marcmayol.rodet.model.Severity
import com.marcmayol.rodet.model.TextKeys
import com.marcmayol.rodet.model.Validation

private enum class SpoolFilter { ACTIVE, IN_USE, SEALED, FINISHED, ALL }

@Composable
fun SpoolListScreen(
    inv: Inventory,
    settings: CostSettings,
    vm: InventoryViewModel,
    onFromPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(SpoolFilter.ACTIVE) }
    var query by rememberSaveable { mutableStateOf("") }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    val q = TextKeys.fold(query.trim())
    val shown = inv.spools
        .filter {
            when (filter) {
                SpoolFilter.ACTIVE -> it.isActive
                SpoolFilter.IN_USE -> it.status == SpoolStatus.IN_USE
                SpoolFilter.SEALED -> it.status == SpoolStatus.SEALED
                SpoolFilter.FINISHED -> it.status == SpoolStatus.FINISHED
                SpoolFilter.ALL -> true
            }
        }
        .filter { s -> q.isEmpty() || TextKeys.fold("${s.spec.brand} ${spoolTitle(s.spec)} ${s.spec.colorName} ${s.shop}").contains(q) }
        // En uso primero (es lo que esta en la impresora) y, dentro, lo que menos le queda.
        .sortedWith(compareBy<Spool> { listOf(SpoolStatus.IN_USE, SpoolStatus.SEALED, SpoolStatus.FINISHED).indexOf(it.status) }.thenBy { it.remainingFraction ?: 1.0 })

    Box(modifier) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                val active = inv.spools.filter { it.isActive }
                val stock = active.sumOf { it.effectiveRemainingG ?: 0.0 }
                Text(
                    stringResource(R.string.spools_summary, pluralStringResource(R.plurals.p_spools, active.size, active.size), grams(stock)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    query, { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(stringResource(R.string.spools_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpoolFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = {
                                Text(
                                    stringResource(
                                        when (f) {
                                            SpoolFilter.ACTIVE -> R.string.filter_active
                                            SpoolFilter.IN_USE -> R.string.status_in_use
                                            SpoolFilter.SEALED -> R.string.status_sealed
                                            SpoolFilter.FINISHED -> R.string.status_finished
                                            SpoolFilter.ALL -> R.string.filter_all
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            if (inv.spools.isEmpty()) {
                item { EmptyState(stringResource(R.string.spools_empty_title), stringResource(R.string.spools_empty_body)) }
            } else if (shown.isEmpty()) {
                item { Text(stringResource(R.string.spools_none_match), modifier = Modifier.padding(vertical = 24.dp)) }
            }
            items(shown, key = { it.id }) { s -> SpoolCard(s, settings) { vm.openSpools(SpoolRoute.Detail(s.id)) } }
        }
        ExtendedFloatingActionButton(
            onClick = { addMenu = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text(stringResource(R.string.spools_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (addMenu) {
        AlertDialog(
            onDismissRequest = { addMenu = false },
            title = { Text(stringResource(R.string.spools_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { addMenu = false; vm.newSpool() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null); Text("  " + stringResource(R.string.spools_add_manual))
                    }
                    OutlinedButton(onClick = { addMenu = false; onFromPhoto() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PhotoCamera, null); Text("  " + stringResource(R.string.spools_add_photo))
                    }
                    Text(stringResource(R.string.spools_add_photo_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addMenu = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpoolCard(s: Spool, settings: CostSettings, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorDot(s.spec.colorHex, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spoolTitle(s.spec), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOf(s.spec.brand, statusText(s.status), s.pricePerKg?.let { stringResource(R.string.per_kg, money(it, settings.currency)) }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.status != SpoolStatus.FINISHED) {
                    RemainingBar(s.remainingFraction)
                    val left = s.effectiveRemainingG
                    Text(
                        when {
                            left == null -> stringResource(R.string.remaining_unknown)
                            s.netG != null -> stringResource(R.string.remaining_of, grams(left), grams(s.netG!!))
                            else -> grams(left)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- ficha

@Composable
fun SpoolDetailScreen(
    id: String,
    inv: Inventory,
    settings: CostSettings,
    vm: InventoryViewModel,
    onWriteTag: (Spool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = inv.spool(id)
    if (s == null) {
        Text(stringResource(R.string.spool_gone), modifier.padding(16.dp))
        return
    }
    var weighing by rememberSaveable { mutableStateOf(false) }
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorDot(s.spec.colorHex, 64.dp)
            Column(Modifier.weight(1f)) {
                Text(spoolTitle(s.spec), style = MaterialTheme.typography.titleLarge)
                val sub = listOf(s.spec.brand, s.spec.diameterMm?.let { "${number(it, 2)} mm" }.orEmpty(), s.spec.colorHex.takeIf { it.isNotBlank() }?.let { "#$it" }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // --- lo que queda
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SpoolStatus.entries.forEachIndexed { i, st ->
                        SegmentedButton(
                            selected = s.status == st,
                            onClick = { vm.setStatus(s.id, st) },
                            shape = SegmentedButtonDefaults.itemShape(i, SpoolStatus.entries.size),
                            icon = {},
                        ) { Text(statusText(st), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                val left = s.effectiveRemainingG
                Text(
                    left?.let { l -> s.netG?.let { stringResource(R.string.remaining_of, grams(l), grams(it)) } ?: grams(l) } ?: stringResource(R.string.remaining_unknown),
                    style = MaterialTheme.typography.headlineSmall,
                )
                RemainingBar(s.remainingFraction)
                val meters = left?.let { l -> s.spec.copy(netWeightG = l.toInt()).estimatedLengthM() }
                meters?.let { Text(stringResource(R.string.remaining_meters, number(it, 0)), style = MaterialTheme.typography.bodySmall) }
                if (s.remainingG == null && s.status == SpoolStatus.IN_USE) {
                    Text(stringResource(R.string.remaining_assumed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { weighing = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Scale, null); Text("  " + stringResource(R.string.spool_weigh), maxLines = 1)
                    }
                    OutlinedButton(onClick = { adjusting = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.spool_adjust), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // --- compra
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spool_purchase), style = MaterialTheme.typography.titleSmall)
                InfoRow(stringResource(R.string.f_price), s.price?.let { money(it, settings.currency) } ?: "—")
                s.pricePerKg?.let { InfoRow(stringResource(R.string.f_price_per_kg), money(it, settings.currency)) }
                if (s.shop.isNotBlank()) InfoRow(stringResource(R.string.f_shop), s.shop)
                s.purchasedAt?.let { InfoRow(stringResource(R.string.f_purchased), date(it)) }
                s.spec.spoolWeightG?.let { InfoRow(stringResource(R.string.f_tare), "$it g") }
                InfoRow(stringResource(R.string.spool_tag), s.tagUid.ifBlank { stringResource(R.string.spool_no_tag) })
            }
        }

        Button(onClick = { onWriteTag(s) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Nfc, null); Text("  " + stringResource(if (s.tagUid.isBlank()) R.string.spool_write_tag else R.string.spool_rewrite_tag))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.editSpool(s.id) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, null); Text(" " + stringResource(R.string.action_edit), maxLines = 1)
            }
            OutlinedButton(onClick = { vm.duplicate(s.id) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentCopy, null); Text(" " + stringResource(R.string.action_duplicate), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // --- impresiones con esta bobina
        val uses = inv.jobs.mapNotNull { j -> j.usages.filter { it.spoolId == s.id }.sumOf { it.grams }.takeIf { it > 0 }?.let { j to it } }
            .sortedByDescending { it.first.at }
        if (uses.isNotEmpty()) {
            SectionTitle(stringResource(R.string.spool_used_in, grams(uses.sumOf { it.second })))
            uses.take(20).forEach { (j, g) ->
                InfoRow("${j.name.ifBlank { stringResource(R.string.job_unnamed) }} · ${date(j.at)}", grams(g))
            }
        }

        HorizontalDivider()
        TextButton(onClick = { deleting = true }) {
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            Text(" " + stringResource(R.string.spool_delete), color = MaterialTheme.colorScheme.error)
        }
    }

    if (weighing) WeighDialog(s, onDismiss = { weighing = false }) { gross, tare -> vm.weigh(s.id, gross, tare); weighing = false }
    if (adjusting) {
        var g by rememberSaveable { mutableStateOf(s.effectiveRemainingG) }
        AlertDialog(
            onDismissRequest = { adjusting = false },
            title = { Text(stringResource(R.string.spool_adjust)) },
            text = { DecimalField(stringResource(R.string.spool_remaining_g), g, { g = it }, suffix = "g") },
            confirmButton = { TextButton(onClick = { g?.let { vm.setRemaining(s.id, it) }; adjusting = false }, enabled = g != null) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { adjusting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.spool_delete_q)) },
            text = { Text(stringResource(R.string.spool_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteSpool(s.id) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun WeighDialog(s: Spool, onDismiss: () -> Unit, onWeigh: (Double, Int?) -> Unit) {
    var gross by rememberSaveable { mutableStateOf<Double?>(null) }
    var tare by rememberSaveable { mutableStateOf(s.spec.spoolWeightG?.toDouble()) }
    val result = gross?.let { g -> tare?.let { t -> (g - t).coerceAtLeast(0.0) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spool_weigh)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.weigh_explain), style = MaterialTheme.typography.bodySmall)
                DecimalField(stringResource(R.string.weigh_gross), gross, { gross = it }, suffix = "g", decimals = false)
                DecimalField(
                    stringResource(R.string.f_tare), tare, { tare = it }, suffix = "g", decimals = false,
                    supporting = stringResource(R.string.tare_hint),
                )
                result?.let { Text(stringResource(R.string.weigh_result, grams(it)), style = MaterialTheme.typography.titleMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { onWeigh(gross!!, tare?.toInt()) }, enabled = result != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------- alta / edicion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpoolEditScreen(draft: SpoolDraft, settings: CostSettings, vm: InventoryViewModel, modifier: Modifier = Modifier) {
    val issues = Validation.validate(draft.spec, requireColor = false)
    val blocking = issues.any { it.severity == Severity.BLOCKING }
    var copies by rememberSaveable { mutableIntStateOf(1) }
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SpecFields(draft.spec, issues, vm.editor)

        HorizontalDivider()
        SectionTitle(stringResource(R.string.spool_purchase))
        DecimalField(stringResource(R.string.f_price), draft.price, vm::setPrice, suffix = settings.currency)
        val perKg = draft.price?.let { p -> draft.spec.netWeightG?.takeIf { it > 0 }?.let { p / it * 1000 } }
        perKg?.let { Text(stringResource(R.string.per_kg, money(it, settings.currency)), style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(draft.shop, vm::setShop, label = { Text(stringResource(R.string.f_shop)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.f_purchased) + ": " + (draft.purchasedAt?.let(::date) ?: "—"))
        }
        DecimalField(
            stringResource(R.string.f_tare), draft.spec.spoolWeightG?.toDouble(), { vm.setTare(it?.toInt()) },
            suffix = "g", decimals = false, supporting = stringResource(R.string.tare_hint),
        )

        if (draft.spoolId == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.spool_copies), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { if (copies > 1) copies-- }) { Text("−") }
                Text("$copies", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { if (copies < 20) copies++ }) { Text("+") }
            }
        }

        if (blocking) Text(stringResource(R.string.review_fix_first_save), color = MaterialTheme.colorScheme.error)
        Button(onClick = { vm.saveDraft(copies) }, enabled = !blocking, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(if (draft.spoolId == null) R.string.spool_save_new else R.string.action_save))
        }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.purchasedAt ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { vm.setPurchasedAt(state.selectedDateMillis); picking = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state) }
    }
}
