package com.marcmayol.rodet

import android.app.Application
import com.marcm.actualizador.Actualizador
import com.marcm.actualizador.ActualizadorConfig

class RodetApp : Application() {
    /**
     * Rodet se distribuye fuera de Play Store (DracApps): consulta un manifiesto estatico en
     * GitHub Pages y compara por versionCode entero. Mismo modulo que Faltis y Kuse.
     * Es la unica conexion de la app; las bobinas y las impresiones no salen del movil.
     */
    val actualizador: Actualizador by lazy {
        Actualizador(
            app = this,
            config = ActualizadorConfig(
                manifiestoUrl = MANIFIESTO_URL,
                versionCodeActual = BuildConfig.VERSION_CODE,
                checkHorasPorDefecto = 24,
            ),
        )
    }

    companion object {
        const val MANIFIESTO_URL = "https://marcmayol.com/rodet/updates.json"
    }
}
