package com.marcmayol.rodet.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marcmayol.rodet.R
import com.marcmayol.rodet.model.FilamentSpec

@Composable
fun WriteScreen(state: FlowState, vm: FlowViewModel, nfcOn: Boolean, modifier: Modifier = Modifier) {
    val machine = state.machine ?: return
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpecCard(state.spec, machine.label)

        if (!nfcOn) {
            Text(stringResource(R.string.write_nfc_off), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            return@Column
        }

        when (val w = state.write) {
            WriteStatus.Waiting -> {
                Icon(Icons.Default.Nfc, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.write_bring, machine.profile.tagType), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.write_bring_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (state.allowOverwrite) {
                    Text(stringResource(R.string.write_overwrite_armed), color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center)
                }
            }
            WriteStatus.Working -> {
                CircularProgressIndicator(Modifier.size(72.dp))
                Text(stringResource(R.string.write_working), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            is WriteStatus.Done -> {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.write_done), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    w.readBack?.let { stringResource(R.string.write_read_back, summary(it)) } ?: stringResource(R.string.write_read_back_none),
                    textAlign = TextAlign.Center,
                )
                Text(stringResource(R.string.write_stick_it, machine.label), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (state.savedSpoolId != null) {
                    Text(stringResource(R.string.write_tag_linked), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                } else {
                    Button(onClick = { vm.saveToInventory(w.uid) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.review_save_spool)) }
                }
                OutlinedButton(onClick = vm::writeAnother, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.write_another)) }
                OutlinedButton(onClick = { vm.go(Step.CAPTURE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.write_new_spool)) }
            }
            is WriteStatus.Failed -> {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.error)
                val (what, todo) = failureText(w, machine.profile.tagType)
                Text(what, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(todo, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (w.existing != null) {
                    Button(onClick = vm::allowOverwrite, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.write_overwrite)) }
                }
                OutlinedButton(onClick = vm::writeAnother, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.write_retry)) }
            }
        }
    }
}

@Composable
private fun SpecCard(spec: FilamentSpec, machine: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(summary(spec), style = MaterialTheme.typography.titleMedium)
            Text(machine, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun summary(s: FilamentSpec): String = listOf(
    s.brand, s.material, s.subtype, s.colorName.ifBlank { s.colorHex.takeIf { it.isNotBlank() }?.let { "#$it" }.orEmpty() },
    s.netWeightG?.let { "$it g" }.orEmpty(),
).filter { it.isNotBlank() }.joinToString(" · ")
