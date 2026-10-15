package com.marcmayol.rodet.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcmayol.rodet.R
import com.marcmayol.rodet.tags.Availability
import com.marcmayol.rodet.tags.DeviceCapabilities
import com.marcmayol.rodet.tags.DisabledReason
import com.marcmayol.rodet.tags.Machine

@Composable
fun MachineScreen(state: FlowState, vm: FlowViewModel, caps: DeviceCapabilities, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.machine_intro), style = MaterialTheme.typography.bodyLarge)

        Machine.entries.forEach { m ->
            val availability = vm.isAvailable(m, caps)
            // Se puede elegir aunque este movil no sepa grabarla (se avisa al grabar);
            // solo se bloquea lo que nadie puede grabar.
            val enabled = (availability as? Availability.Disabled)?.reason != DisabledReason.FORMAT_NOT_WRITABLE
            val selected = state.machine == m
            Card(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.chooseMachine(m) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { vm.chooseMachine(m) }, enabled = enabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.label, style = MaterialTheme.typography.titleMedium)
                        Text(m.examples, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.machine_sticker, m.profile.tagType),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        if (availability is Availability.Disabled) {
                            Text(
                                disabledText(availability.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!m.profile.verified) {
                            Text(
                                stringResource(R.string.machine_unverified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }

        if (state.machine == Machine.CREALITY_CFS && state.keys != KeysState.OK) {
            CrealityKeysCard(state.keys, vm)
        }

        Button(
            onClick = vm::continueFromMachine,
            enabled = state.machine != null && state.keys != KeysState.MISSING && state.keys != KeysState.WRONG,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_continue)) }
    }
}

@Composable
private fun CrealityKeysCard(keys: KeysState, vm: FlowViewModel) {
    var derivation by rememberSaveable { mutableStateOf("") }
    var payload by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keys_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keys_explain), style = MaterialTheme.typography.bodySmall)
            if (keys == KeysState.WRONG) {
                Text(stringResource(R.string.keys_wrong_long), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(derivation, { derivation = it }, label = { Text(stringResource(R.string.keys_derivation)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(payload, { payload = it }, label = { Text(stringResource(R.string.keys_payload)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.saveCrealityKeys(derivation, payload) }, enabled = derivation.isNotBlank() && payload.isNotBlank()) {
                Text(stringResource(R.string.keys_check))
            }
        }
    }
}
