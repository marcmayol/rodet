package com.marcmayol.rodet.reader

import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext

class ReaderActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()
    private val adapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val device = DeviceInfo(
            hasNfc = adapter != null,
            nfcEnabled = { adapter?.isEnabled == true },
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
                ReaderScreen(viewModel, device)
            }
        }
    }

    // Seccion 4.2 del plan: reader mode solo mientras la pantalla esta delante.
    override fun onResume() {
        super.onResume()
        adapter?.enableReaderMode(
            this,
            { tag -> viewModel.onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                // Sin FLAG_READER_SKIP_NDEF_CHECK: el lector quiere tambien el
                // mensaje NDEF (OpenSpool), y MIFARE Classic se lee igual.
                NfcAdapter.FLAG_READER_NFC_V,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
        )
    }

    override fun onPause() {
        super.onPause()
        adapter?.disableReaderMode(this)
    }
}

data class DeviceInfo(
    val hasNfc: Boolean,
    val nfcEnabled: () -> Boolean,
    val supportsMifareClassic: Boolean,
)
