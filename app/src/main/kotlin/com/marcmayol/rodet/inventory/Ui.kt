package com.marcmayol.rodet.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marcmayol.rodet.R
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Normalization
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

val spanish: Boolean get() = Locale.getDefault().language == "es"

fun money(v: Double, currency: String): String {
    val nf = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    return "${nf.format(v)} $currency"
}

/** Precios por kilo y cosas que no necesitan centimos. */
fun money0(v: Double, currency: String): String {
    val nf = NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (v < 10) 2 else 0 }
    return "${nf.format(v)} $currency"
}

fun grams(g: Double): String {
    val nf = NumberFormat.getNumberInstance()
    return if (g >= 1000) {
        nf.maximumFractionDigits = 2
        "${nf.format(g / 1000)} kg"
    } else {
        nf.maximumFractionDigits = if (g < 10) 1 else 0
        "${nf.format(g)} g"
    }
}

fun number(v: Double, decimals: Int = 1): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = decimals }.format(v)

fun duration(min: Int): String = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

fun colorOf(hex: String): Color? = Normalization.normalizeHex(hex)?.toLong(16)?.let { Color(0xFF000000 or it) }

/** "PLA Silk · Rojo" */
fun spoolTitle(spec: FilamentSpec): String {
    val color = ColorNames.label(spec.colorHex, spec.colorName, spanish)
    return listOf(listOf(spec.material, spec.subtype).filter { it.isNotBlank() }.joinToString(" "), color)
        .filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
fun statusText(s: SpoolStatus): String = stringResource(
    when (s) {
        SpoolStatus.SEALED -> R.string.status_sealed
        SpoolStatus.IN_USE -> R.string.status_in_use
        SpoolStatus.FINISHED -> R.string.status_finished
    },
)

/** La muestra del color: un circulo con borde para que el blanco se vea sobre blanco. */
@Composable
fun ColorDot(hex: String, size: Dp = 36.dp) {
    Surface(
        Modifier.size(size),
        shape = CircleShape,
        color = colorOf(hex) ?: MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {}
}

/**
 * Campo numerico con decimales. Guarda su propio texto para no comerse la coma
 * mientras se escribe ("1," aun no es un numero) y avisa con cada valor valido.
 */
@Composable
fun DecimalField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    decimals: Boolean = true,
) {
    fun show(v: Double?): String = when {
        v == null -> ""
        v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
        else -> v.toString().let { if (spanish) it.replace('.', ',') else it }
    }
    fun parse(t: String): Double? = t.replace(',', '.').toDoubleOrNull()
    var text by rememberSaveable { mutableStateOf(show(value)) }
    LaunchedEffect(value) { if (parse(text) != value) text = show(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() || (decimals && (it == ',' || it == '.')) }.take(9)
            text = clean
            onChange(parse(clean))
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = suffix?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Barra de lo que queda en la bobina. */
@Composable
fun RemainingBar(fraction: Double?, modifier: Modifier = Modifier) {
    val f = (fraction ?: 0.0).toFloat().coerceIn(0f, 1f)
    val low = fraction != null && fraction < 0.15
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Fila de un grafico de barras horizontal: etiqueta arriba, barra fina debajo
 * y el valor en texto (nunca en el color de la barra).
 */
@Composable
fun BarRow(label: String, value: String, fraction: Float, leading: (@Composable () -> Unit)? = null, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leading?.invoke()
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.fillMaxWidth().height(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 4.dp))
}
