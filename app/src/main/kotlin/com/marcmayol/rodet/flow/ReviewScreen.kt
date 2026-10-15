package com.marcmayol.rodet.flow

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.rodet.R
import com.marcmayol.rodet.model.Catalog
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Issue
import com.marcmayol.rodet.model.NamedColor
import com.marcmayol.rodet.model.Normalization
import com.marcmayol.rodet.model.Severity
import com.marcmayol.rodet.model.Source
import com.marcmayol.rodet.tags.Availability
import com.marcmayol.rodet.tags.CrealityCfsProfile
import com.marcmayol.rodet.tags.DeviceCapabilities
import com.marcmayol.rodet.tags.CrealityMaterials
import com.marcmayol.rodet.tags.Machine
import com.marcmayol.rodet.tags.ProfileSettings
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(state: FlowState, photo: Bitmap?, vm: FlowViewModel, caps: DeviceCapabilities, modifier: Modifier = Modifier) {
    val spec = state.spec
    val issues = state.issues
    val preflight = vm.preflight()
    val blocking = issues.any { it.severity == Severity.BLOCKING }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (photo != null) PhotoPicker(photo, onColor = vm::pickColor)
        if (state.ocrRunning) {
            Text(stringResource(R.string.review_reading))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (state.ocrFailed) {
            Text(stringResource(R.string.review_ocr_failed), color = MaterialTheme.colorScheme.error)
        } else if (photo != null) {
            Text(stringResource(R.string.review_check), style = MaterialTheme.typography.bodyMedium)
        }

        SpecFields(spec, issues, vm, hasPhoto = photo != null)

        state.ocrText?.let { OcrText(it) }

        HorizontalDivider()
        state.machine?.let { TargetSummary(it, spec) }
        preflight?.let { Text(profileErrorText(it), color = MaterialTheme.colorScheme.error) }
        if (blocking) Text(stringResource(R.string.review_fix_first), color = MaterialTheme.colorScheme.error)
        val unavailable = state.machine?.let { vm.isAvailable(it, caps) } as? Availability.Disabled
        unavailable?.let { Text(disabledText(it.reason), color = MaterialTheme.colorScheme.error) }
        if (state.machine == null) {
            Button(
                onClick = { vm.go(Step.MACHINE) },
                enabled = !state.ocrRunning && !blocking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Nfc, null)
                Text("  " + stringResource(R.string.review_choose_machine))
            }
        } else Button(
            onClick = { vm.go(Step.WRITE) },
            enabled = !state.ocrRunning && !blocking && preflight == null && unavailable == null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Default.Nfc, null)
            Text("  " + stringResource(R.string.review_write))
        }
        OutlinedButton(
            onClick = { vm.saveToInventory() },
            enabled = !state.ocrRunning && spec.material.isNotBlank() && state.savedSpoolId == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(if (state.savedSpoolId == null) Icons.Default.Inventory2 else Icons.Default.Check, null)
            Text("  " + stringResource(if (state.savedSpoolId == null) R.string.review_save_spool else R.string.review_saved_spool))
        }
    }
}

/** Acciones del editor de la ficha: las implementan el flujo de grabar y el alta de bobinas. */
interface SpecActions {
    fun edit(field: Field, value: Any?)
    fun chooseMaterial(code: String)
    fun chooseNamedColor(color: NamedColor, spanish: Boolean)
}

/** Los campos de la ficha de una bobina, de material a lote. */
@Composable
fun SpecFields(spec: FilamentSpec, issues: List<Issue>, vm: SpecActions, hasPhoto: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // --- material
        Section(stringResource(R.string.f_material), spec, Field.MATERIAL, issues) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Catalog.materials.forEach { m ->
                    FilterChip(selected = spec.material == m.code, onClick = { vm.chooseMaterial(m.code) }, label = { Text(m.code) })
                }
            }
        }
        Section(stringResource(R.string.f_subtype), spec, Field.SUBTYPE, issues) {
            val current = spec.subtype.split(' ').filter { it.isNotBlank() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Catalog.subtypes.forEach { st ->
                    val on = current.any { it.equals(st.label, ignoreCase = true) }
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = if (on) current.filterNot { it.equals(st.label, ignoreCase = true) } else current + st.label
                            vm.edit(Field.SUBTYPE, next.joinToString(" "))
                        },
                        label = { Text(st.label) },
                    )
                }
            }
        }
        TextFieldRow(stringResource(R.string.f_brand), spec, Field.BRAND, issues, vm)

        // --- color
        Section(stringResource(R.string.f_color), spec, Field.COLOR_HEX, issues) {
            ColorEditor(spec, vm, hasPhoto = hasPhoto)
        }

        // --- formato
        Section(stringResource(R.string.f_diameter), spec, Field.DIAMETER, issues) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Normalization.NOMINAL_DIAMETERS_MM.forEach { d ->
                    FilterChip(selected = spec.diameterMm == d, onClick = { vm.edit(Field.DIAMETER, d) }, label = { Text("%.2f mm".format(Locale.ROOT, d)) })
                }
            }
        }
        Section(stringResource(R.string.f_weight), spec, Field.NET_WEIGHT, issues) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Normalization.NOMINAL_WEIGHTS_G.forEach { g ->
                    FilterChip(selected = spec.netWeightG == g, onClick = { vm.edit(Field.NET_WEIGHT, g) }, label = { Text(if (g >= 1000) "${g / 1000.0} kg".replace(".0 ", " ") else "$g g") })
                }
            }
            IntField(stringResource(R.string.f_weight_other), spec.netWeightG, "g") { vm.edit(Field.NET_WEIGHT, it) }
        }

        // --- temperaturas
        RangeRow(stringResource(R.string.f_nozzle), spec, Field.NOZZLE_MIN, Field.NOZZLE_MAX, issues, vm)
        RangeRow(stringResource(R.string.f_bed), spec, Field.BED_MIN, Field.BED_MAX, issues, vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { IntFieldFor(stringResource(R.string.f_dry_temp), spec, Field.DRY_TEMP, "°C", issues, vm) }
            Box(Modifier.weight(1f)) { IntFieldFor(stringResource(R.string.f_dry_time), spec, Field.DRY_TIME, "h", issues, vm) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextFieldRow(stringResource(R.string.f_lot), spec, Field.LOT, issues, vm) }
            Box(Modifier.weight(1f)) { TextFieldRow(stringResource(R.string.f_serial), spec, Field.SERIAL, issues, vm) }
        }
    }
}

/** Foto de la etiqueta; un toque coge el color de ese punto. */
@Composable
private fun PhotoPicker(photo: Bitmap, onColor: (String) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    var marker by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val image = remember(photo) { photo.asImageBitmap() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .onSizeChanged { box = it }
                .pointerInput(photo) {
                    detectTapGestures { p ->
                        // ContentScale.Fit: la imagen centrada con bandas.
                        val scale = min(box.width.toFloat() / photo.width, box.height.toFloat() / photo.height)
                        val dx = (box.width - photo.width * scale) / 2f
                        val dy = (box.height - photo.height * scale) / 2f
                        val x = ((p.x - dx) / scale).roundToInt()
                        val y = ((p.y - dy) / scale).roundToInt()
                        if (x in 0 until photo.width && y in 0 until photo.height) {
                            marker = p.x to p.y
                            onColor(Images.sampleHex(photo, x, y))
                        }
                    }
                },
        ) {
            Image(image, null, Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit)
            marker?.let { (x, y) ->
                Box(
                    Modifier
                        .offset { IntOffset((x - 12.dp.toPx()).roundToInt(), (y - 12.dp.toPx()).roundToInt()) }
                        .size(24.dp)
                        .border(3.dp, Color.White, CircleShape),
                )
            }
        }
        Text(stringResource(R.string.review_eyedropper), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ColorEditor(spec: FilamentSpec, vm: SpecActions, hasPhoto: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val spanish = Locale.getDefault().language == "es"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch(spec.colorHex, 44)
            OutlinedTextField(
                value = spec.colorHex,
                onValueChange = { t -> vm.edit(Field.COLOR_HEX, t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)) },
                label = { Text("Hex") },
                prefix = { Text("#") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(stringResource(R.string.review_colors)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Catalog.colors.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(if (spanish) c.es else c.en) },
                            leadingIcon = { Swatch(c.hex, 20) },
                            onClick = { vm.chooseNamedColor(c, spanish); menu = false },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = spec.colorName,
            onValueChange = { vm.edit(Field.COLOR_NAME, it) },
            label = { Text(stringResource(R.string.f_color_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!hasPhoto && spec.colorHex.isBlank()) {
            Text(stringResource(R.string.review_pick_color), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Swatch(hex: String, sizeDp: Int) {
    val color = Normalization.normalizeHex(hex)?.toLong(16)?.let { Color(0xFF000000 or it) }
    Surface(
        Modifier.size(sizeDp.dp),
        shape = RoundedCornerShape(8.dp),
        color = color ?: Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {}
}

/** Titulo, contenido, procedencia y problemas de un campo. */
@Composable
private fun Section(title: String, spec: FilamentSpec, field: Field, issues: List<Issue>, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        content()
        FieldNotes(spec, field, issues)
    }
}

@Composable
private fun FieldNotes(spec: FilamentSpec, field: Field, issues: List<Issue>) {
    val p = spec.provenanceOf(field)
    if (p != null && !spec.isEmpty(field)) {
        when {
            p.needsReview -> Text(
                stringResource(R.string.prov_review, p.evidence),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            p.source == Source.DEFAULT -> Text(stringResource(R.string.prov_default), style = MaterialTheme.typography.bodySmall)
            p.source == Source.OCR -> Text(stringResource(R.string.prov_ocr, p.evidence), style = MaterialTheme.typography.bodySmall)
            else -> {}
        }
    }
    issues.filter { it.field == field }.forEach {
        Text(
            issueText(it),
            color = if (it.severity == Severity.BLOCKING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TextFieldRow(label: String, spec: FilamentSpec, field: Field, issues: List<Issue>, vm: SpecActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = field.get(spec) as String,
            onValueChange = { vm.edit(field, it) },
            label = { Text(label) },
            singleLine = true,
            isError = issues.any { it.field == field && it.severity == Severity.BLOCKING },
            modifier = Modifier.fillMaxWidth().reviewBorder(spec, field),
        )
        FieldNotes(spec, field, issues)
    }
}

@Composable
private fun RangeRow(title: String, spec: FilamentSpec, min: Field, max: Field, issues: List<Issue>, vm: SpecActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { IntFieldFor(stringResource(R.string.f_min), spec, min, "°C", issues, vm) }
            Box(Modifier.weight(1f)) { IntFieldFor(stringResource(R.string.f_max), spec, max, "°C", issues, vm) }
        }
    }
}

@Composable
private fun IntFieldFor(label: String, spec: FilamentSpec, field: Field, unit: String, issues: List<Issue>, vm: SpecActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IntField(
            label,
            field.get(spec) as Int?,
            unit,
            isError = issues.any { it.field == field && it.severity == Severity.BLOCKING },
            modifier = Modifier.reviewBorder(spec, field),
        ) { vm.edit(field, it) }
        FieldNotes(spec, field, issues)
    }
}

@Composable
fun IntField(label: String, value: Int?, unit: String, isError: Boolean = false, modifier: Modifier = Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { t -> onChange(t.filter(Char::isDigit).take(5).toIntOrNull()) },
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Lo leido con dudas se enmarca para que salte a la vista. */
@Composable
private fun Modifier.reviewBorder(spec: FilamentSpec, field: Field): Modifier =
    if (spec.provenanceOf(field)?.needsReview == true && !spec.isEmpty(field)) {
        this.border(2.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(6.dp))
    } else {
        this
    }

@Composable
private fun OcrText(text: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.review_hide_text else R.string.review_show_text)) }
        if (open) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

/** Que se va a grabar exactamente, en el idioma de la maquina. */
@Composable
private fun TargetSummary(machine: Machine, spec: FilamentSpec) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.review_target, machine.label), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.machine_sticker, machine.profile.tagType), style = MaterialTheme.typography.bodySmall)
            if (machine == Machine.CREALITY_CFS) {
                val code = CrealityCfsProfile.materialCode(spec, ProfileSettings())
                val name = code?.let { CrealityMaterials.byCode(it)?.name }
                if (code != null) {
                    Text(stringResource(R.string.review_creality_code, name ?: "?", code), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
