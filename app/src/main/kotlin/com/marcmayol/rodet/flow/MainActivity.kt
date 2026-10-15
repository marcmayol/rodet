package com.marcmayol.rodet.flow

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcmayol.rodet.BuildConfig
import com.marcmayol.rodet.R
import com.marcmayol.rodet.RodetApp
import com.marcmayol.rodet.inventory.InventoryViewModel
import com.marcmayol.rodet.inventory.RootApp
import com.marcmayol.rodet.inventory.Tab
import com.marcmayol.rodet.reader.ReaderActivity
import com.marcmayol.rodet.tags.DeviceCapabilities

/**
 * El flujo de la app: maquina -> foto de la etiqueta -> revision -> grabar la
 * pegatina. El lector NFC esta activo mientras la pantalla esta delante, y el
 * ViewModel decide si el tag que llega toca escribirlo.
 */
class MainActivity : ComponentActivity() {

    private val vm: FlowViewModel by viewModels()
    private val inventoryVm: InventoryViewModel by viewModels()
    private val actualizador get() = (application as RodetApp).actualizador
    private val adapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val caps = DeviceCapabilities(
            hasNfc = adapter != null,
            supportsMifareClassic = packageManager.hasSystemFeature("com.nxp.mifare"),
        )
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                RootApp(
                    inventoryVm,
                    onExit = ::finish,
                    onFromPhoto = vm::startFromPhoto,
                    onWriteTag = vm::loadSpool,
                    updates = if (BuildConfig.DEBUG) null else actualizador,
                ) {
                    FlowApp(vm, caps, nfcEnabled = { adapter?.isEnabled == true }, onExit = { inventoryVm.selectTab(Tab.SPOOLS) })
                }
            }
        }
        if (savedInstanceState == null) handleOpen(intent)
        // Auto-actualizacion (DracApps) solo en la version publicada: la de pruebas es otro paquete.
        // Aqui y no en la Application, como en DracApps: WorkManager aun no existe en los tests.
        if (!BuildConfig.DEBUG) actualizador.programarPeriodica()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Un .gcode o .3mf abierto o compartido desde otra app va a la calculadora. */
    private fun handleOpen(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let(inventoryVm::openGcode)
    }

    /** Llega en el hilo del lector: en la pestaña de grabar escribe; en las demas abre la ficha de la bobina. */
    private fun onTag(tag: Tag) {
        if (inventoryVm.tab.value == Tab.TAGS) {
            vm.onTag(tag)
        } else {
            val uid = tag.id.joinToString("") { "%02X".format(it) }
            runOnUiThread { inventoryVm.onTagUid(uid) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Vuelta de la pantalla de "instalar apps desconocidas": reanuda la actualizacion.
        if (!BuildConfig.DEBUG) actualizador.onPermisoQuizaConcedido()
        adapter?.enableReaderMode(
            this,
            { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_V,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
        )
    }

    override fun onPause() {
        super.onPause()
        adapter?.disableReaderMode(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowApp(vm: FlowViewModel, caps: DeviceCapabilities, nfcEnabled: () -> Boolean, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val photo by vm.photo.collectAsStateWithLifecycle()
    var nfcOn by remember { mutableStateOf(nfcEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { nfcOn = nfcEnabled() }
    val context = LocalContext.current

    BackHandler { if (!vm.back()) onExit() }

    val stepIndex = state.step.ordinal
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when (state.step) {
                                    Step.MACHINE -> R.string.step_machine
                                    Step.CAPTURE -> R.string.step_capture
                                    Step.REVIEW -> R.string.step_review
                                    Step.WRITE -> R.string.step_write
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        if (state.step != Step.MACHINE) {
                            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                        }
                    },
                    actions = {
                        if (state.step != Step.MACHINE) {
                            state.machine?.let { m -> AssistChip(onClick = { vm.go(Step.MACHINE) }, label = { Text(m.label) }) }
                        } else {
                            TextButton(onClick = { context.startActivity(Intent(context, ReaderActivity::class.java)) }) {
                                Icon(Icons.Default.Contactless, null)
                                Text(" " + stringResource(R.string.action_read_tag))
                            }
                        }
                    },
                )
                LinearProgressIndicator(
                    progress = { (stepIndex + 1) / Step.entries.size.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) { padding ->
        val m = Modifier.fillMaxSize().padding(padding)
        if (!nfcOn && caps.hasNfc && state.step == Step.MACHINE) {
            Column(m) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.nfc_turn_on))
                }
                MachineScreen(state, vm, caps)
            }
            return@Scaffold
        }
        when (state.step) {
            Step.MACHINE -> MachineScreen(state, vm, caps, m)
            Step.CAPTURE -> CaptureScreen(vm, m)
            Step.REVIEW -> ReviewScreen(state, photo, vm, caps, m)
            Step.WRITE -> WriteScreen(state, vm, nfcOn, m)
        }
    }
}
