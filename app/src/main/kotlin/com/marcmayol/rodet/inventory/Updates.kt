package com.marcmayol.rodet.inventory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.marcm.actualizador.EstadoActualizacion
import com.marcm.actualizador.TipoError
import com.marcmayol.rodet.R

/** Aviso no bloqueante arriba del todo: solo si hay version nueva o una en marcha. */
@Composable
fun UpdateBanner(estado: EstadoActualizacion, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val visible = estado is EstadoActualizacion.Disponible || estado is EstadoActualizacion.Descargando ||
        estado is EstadoActualizacion.Verificando || estado is EstadoActualizacion.PidiendoPermiso ||
        estado is EstadoActualizacion.Instalando
    AnimatedVisibility(visible, modifier) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (estado) {
                    is EstadoActualizacion.Disponible -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.update_available, estado.info.versionName), style = MaterialTheme.typography.titleSmall)
                            if (estado.info.notas.isNotBlank()) Text(estado.info.notas, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                        Button(onClick = onUpdate) { Text(stringResource(R.string.update_now)) }
                    }
                    is EstadoActualizacion.Descargando -> {
                        Text(stringResource(R.string.update_downloading, estado.porcentaje), style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { estado.porcentaje / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(updateText(estado), style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
fun updateText(estado: EstadoActualizacion): String = when (estado) {
    EstadoActualizacion.Comprobando -> stringResource(R.string.update_checking)
    EstadoActualizacion.AlDia -> stringResource(R.string.update_up_to_date)
    is EstadoActualizacion.Disponible -> stringResource(R.string.update_available, estado.info.versionName)
    is EstadoActualizacion.Descargando -> stringResource(R.string.update_downloading, estado.porcentaje)
    EstadoActualizacion.Verificando -> stringResource(R.string.update_verifying)
    EstadoActualizacion.PidiendoPermiso -> stringResource(R.string.update_permission)
    EstadoActualizacion.Instalando -> stringResource(R.string.update_installing)
    is EstadoActualizacion.Error -> when (estado.tipo) {
        TipoError.SIN_RED -> stringResource(R.string.update_err_offline)
        TipoError.HTTP, TipoError.MANIFIESTO -> stringResource(R.string.update_err_server)
        TipoError.DESCARGA -> stringResource(R.string.update_err_download)
        TipoError.HASH -> stringResource(R.string.update_err_hash)
        TipoError.INSTALACION -> estado.mensaje ?: stringResource(R.string.update_err_install)
    }
    EstadoActualizacion.Inactivo -> ""
}
