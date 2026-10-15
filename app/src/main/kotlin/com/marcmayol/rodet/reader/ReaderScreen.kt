package com.marcmayol.rodet.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcmayol.rodet.R
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, device: DeviceInfo) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var nfcOn by remember { mutableStateOf(device.nfcEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { nfcOn = device.nfcEnabled() }
    val context = LocalContext.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.reader_title), style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusLine(
                        stringResource(R.string.status_nfc),
                        when {
                            !device.hasNfc -> stringResource(R.string.nfc_missing)
                            !nfcOn -> stringResource(R.string.nfc_off)
                            else -> stringResource(R.string.yes)
                        },
                        ok = device.hasNfc && nfcOn,
                    )
                    StatusLine(
                        stringResource(R.string.status_mifare),
                        stringResource(if (device.supportsMifareClassic) R.string.yes else R.string.no),
                        ok = device.supportsMifareClassic,
                    )
                    StatusLine(
                        stringResource(R.string.status_creality_keys),
                        stringResource(
                            when (state.crealityKeys) {
                                KeyStatus.OK -> R.string.keys_ok
                                KeyStatus.WRONG -> R.string.keys_wrong
                                KeyStatus.MISSING -> R.string.keys_missing
                            },
                        ),
                        ok = state.crealityKeys == KeyStatus.OK,
                    )
                    if (device.hasNfc && !nfcOn) {
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                            Text(stringResource(R.string.open_nfc_settings))
                        }
                    }
                }
            }

            if (state.reading) LinearProgressIndicator(Modifier.fillMaxWidth())

            state.clone?.let { src ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.clone_armed, src.label, src.uid), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.clone_bring_blank))
                        OutlinedButton(onClick = viewModel::disarmClone) { Text(stringResource(R.string.clone_cancel)) }
                    }
                }
            }
            state.cloneResult?.let { r ->
                Text(
                    r.message,
                    fontWeight = FontWeight.SemiBold,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            val report = state.last
            if (report == null) {
                Text(stringResource(R.string.bring_tag), style = MaterialTheme.typography.bodyLarge)
            } else {
                ReportView(report, state.count, context)
                if (state.clone == null && viewModel.canClone(report)) {
                    Button(onClick = { viewModel.armClone(report) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.clone_this))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ReportView(report: TagReport, count: Int, context: Context) {
    Text(
        stringResource(R.string.read_summary, count, report.uid, report.millis),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(report.techs.joinToString(), style = MaterialTheme.typography.bodySmall)

    if (report.identified.isEmpty()) {
        Text(stringResource(R.string.not_identified), color = MaterialTheme.colorScheme.error)
    }
    report.identified.forEach { id ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    id.profile + if (id.verified) "" else " · " + stringResource(R.string.unverified),
                    style = MaterialTheme.typography.titleMedium,
                )
                SpecFields(id.spec)
            }
        }
    }

    report.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    OutlinedButton(onClick = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Rodet ${report.uid}", report.dump))
    }) { Text(stringResource(R.string.copy_dump)) }

    SelectionContainer {
        Text(
            report.dump,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun SpecFields(spec: FilamentSpec) {
    Field.entries.filter { !spec.isEmpty(it) }.forEach { f ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(f.key, style = MaterialTheme.typography.bodySmall)
            Text(f.get(spec).toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}
