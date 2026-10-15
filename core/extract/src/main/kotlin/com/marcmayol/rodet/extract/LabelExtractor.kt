package com.marcmayol.rodet.extract

import com.marcmayol.rodet.model.Catalog
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Normalization
import com.marcmayol.rodet.model.Provenance
import com.marcmayol.rodet.model.Source
import com.marcmayol.rodet.model.SpecEditing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Texto OCR de una etiqueta -> [FilamentSpec] con procedencia y confianza por
 * campo. Reglas y vocabulario cerrado en lugar de un modelo: la etiqueta de
 * una bobina es un formulario, y asi cada valor sale con su evidencia.
 *
 * Las trampas numeradas de los comentarios son las de la seccion 5 del plan.
 */
object LabelExtractor {

    const val CONF_EXACT = 0.95
    const val CONF_CONFUSED = 0.72   // leido a traves de una confusion del OCR: revisar
    const val CONF_WEAK = 0.6
    private const val CONFUSION_PENALTY = 0.1

    fun extract(ocrText: String): FilamentSpec {
        val text = PreparedText(ocrText)
        val acc = Accumulator()
        val reserved = mutableListOf<IntRange>()

        extractMaterial(text, acc, reserved)
        extractSubtypes(text, acc, reserved)
        extractBrand(text, acc)
        extractColor(text, acc, reserved)
        extractDiameter(text, acc)
        val hints = Hints.find(text)
        extractTemperatures(text, hints, acc)
        extractDryTime(text, hints, acc)
        extractWeights(text, hints, acc)
        extractDensity(text, acc)
        extractLotAndSerial(text, acc)

        val spec = Normalization.normalize(acc.build())
        return SpecEditing.applyMaterialDefaults(spec)
    }

    // ---------------------------------------------------------------- material

    private fun extractMaterial(text: PreparedText, acc: Accumulator, reserved: MutableList<IntRange>) {
        data class Candidate(val code: String, val hit: PhraseMatcher.Hit, val alias: String)

        val all = Catalog.materials.flatMap { m ->
            m.aliases.flatMap { alias ->
                PhraseMatcher(alias).findAll(text).map { Candidate(m.code, it, alias) }
            }
        }
        // `pet` dentro de `pet-g` o `pla` dentro de `pla pro` no cuentan.
        val kept = all.filter { c ->
            all.none { o -> o !== c && o.hit.range.contains(c.hit.range) && o.hit.range.length() > c.hit.range.length() }
        }
        if (kept.isEmpty()) return

        fun weight(c: Candidate) = if (c.alias.length <= 2 || c.alias in GENERIC_MATERIAL_WORDS) 0.5 else 1.0
        val best = kept.groupBy { it.code }.maxWith(
            compareBy<Map.Entry<String, List<Candidate>>> { e -> e.value.sumOf { weight(it) } }
                .thenBy { e -> e.value.maxOf { it.alias.length } },
        )
        val first = best.value.minBy { it.hit.range.first }
        var conf = if (best.value.any { it.hit.exact }) CONF_EXACT else CONF_CONFUSED
        if (best.value.all { weight(it) < 1.0 }) conf = minOf(conf, CONF_WEAK)
        acc.set(Field.MATERIAL, best.key, conf, text.evidence(first.hit.range.first, first.hit.range.last + 1))
        kept.forEach { reserved += it.hit.range }
    }

    private val GENERIC_MATERIAL_WORDS = setOf("flex", "flexible", "tpe")

    // ---------------------------------------------------------------- subtipos

    private fun extractSubtypes(text: PreparedText, acc: Accumulator, reserved: MutableList<IntRange>) {
        val found = mutableListOf<Pair<String, PhraseMatcher.Hit>>()
        for (st in Catalog.subtypes) {
            val hits = st.aliases.flatMap { PhraseMatcher(it).findAll(text) }
            if (hits.isNotEmpty()) {
                found += st.label to hits.minBy { it.range.first }
                hits.forEach { reserved += it.range }
            }
        }
        if (found.isEmpty()) return
        val conf = if (found.all { it.second.exact }) 0.85 else CONF_CONFUSED
        val evidence = found.joinToString(" · ") { text.evidence(it.second.range.first, it.second.range.last + 1) }
        acc.set(Field.SUBTYPE, found.joinToString(" ") { it.first }, conf, evidence)
    }

    // ---------------------------------------------------------------- marca

    private fun extractBrand(text: PreparedText, acc: Accumulator) {
        val hits = Catalog.brands.mapNotNull { b ->
            (listOf(b.name) + b.aliases)
                .flatMap { PhraseMatcher(it).findAll(text) }
                .minByOrNull { it.range.first }
                ?.let { b to it }
        }
        val best = hits.minByOrNull { it.second.range.first }
        if (best != null) {
            val (brand, hit) = best
            acc.set(
                Field.BRAND, brand.name,
                if (hit.exact) 0.9 else CONF_CONFUSED,
                text.evidence(hit.range.first, hit.range.last + 1),
            )
            return
        }
        // Ultimo recurso: una letra de diferencia con una marca larga.
        val tokens = Regex("[a-z0-9]{5,}").findAll(text.skeleton)
        for (tok in tokens) {
            for (b in Catalog.brands) {
                val key = PreparedText.skeletonize(PreparedText.foldChars(b.name)).replace(" ", "")
                if (key.length >= 6 && levenshtein(tok.value, key) == 1) {
                    acc.set(Field.BRAND, b.name, CONF_WEAK, text.evidence(tok.range.first, tok.range.last + 1))
                    return
                }
            }
        }
    }

    // ---------------------------------------------------------------- color

    private val COLOR_HINT = Regex("(?<![a-z])(?:colou?r|farbe|couleur|colore)\\s*[:\\-]\\s*([^\\n:]{2,40})")
    private val HEX_CODE = Regex("(?<![0-9a-z])#([0-9a-f]{6})(?![0-9a-z])")

    private val colorMatchers: List<Pair<com.marcmayol.rodet.model.NamedColor, PhraseMatcher>> by lazy {
        Catalog.colors.flatMap { c -> (listOf(c.en, c.es) + c.aliases).map { c to PhraseMatcher(it) } }
            .sortedByDescending { it.second.length }
    }

    private fun extractColor(text: PreparedText, acc: Accumulator, reserved: List<IntRange>) {
        HEX_CODE.find(text.folded)?.let { m ->
            acc.set(Field.COLOR_HEX, m.groupValues[1].uppercase(), 0.9, text.evidence(m.range.first, m.range.last + 1))
        }

        val hinted = COLOR_HINT.find(text.folded)
        if (hinted != null) {
            val g = hinted.groups[1]!!.range
            // El valor acaba en un doble espacio: en OCR suele separar columnas.
            val raw = text.original.substring(g.first, g.last + 1).split(Regex("\\s{2,}")).first()
            val name = raw.trim().trimEnd('.', ',', ';')
            if (name.isNotEmpty()) {
                acc.set(Field.COLOR_NAME, name, 0.85, text.evidence(hinted.range.first, g.first + raw.length))
                val sub = PreparedText(name)
                colorMatchers.firstOrNull { it.second.findAll(sub).isNotEmpty() }?.let { (color, _) ->
                    acc.setIfAbsent(Field.COLOR_HEX, color.hex, Source.CATALOG, 0.9, name)
                }
                return
            }
        }

        // Sin pista: el nombre de color mas largo que no forme parte de un
        // material o subtipo (`carbon` en `carbon fiber` no es un color).
        val taken = mutableListOf<IntRange>()
        for ((color, matcher) in colorMatchers) {
            for (hit in matcher.findAll(text)) {
                if (reserved.any { it.contains(hit.range) }) continue
                if (taken.any { it.overlaps(hit.range) }) continue
                taken += hit.range
                val name = text.evidence(hit.range.first, hit.range.last + 1)
                acc.set(Field.COLOR_NAME, name, if (hit.exact) 0.75 else CONF_WEAK, name)
                acc.setIfAbsent(Field.COLOR_HEX, color.hex, Source.CATALOG, 0.75, name)
                return
            }
        }
    }

    // ---------------------------------------------------------------- diametro

    // Trampa 6: sin frontera de palabra al final. Entre el `5` y la `m` de
    // `1.75mm` no hay ninguna.
    private val DIAMETER_UNIT = Regex("(?<![\\d.,])(\\d(?:[.,]\\d{1,2})?)\\s*mm")
    private val DIAMETER_SYMBOL = Regex("ø\\s*(\\d(?:[.,]\\d{1,2})?)(?![\\d])")

    // Trampa 7: un entero suelto no es un diametro (`PLA 3 colores`). Sin unidad
    // ni simbolo, solo vale la forma decimal de un nominal.
    private val DIAMETER_DECIMAL = Regex("(?<![\\d.,])(1[.,]75|2[.,]85|3[.,]00?)(?![\\d])")

    private fun extractDiameter(text: PreparedText, acc: Accumulator) {
        val candidates = listOf(DIAMETER_SYMBOL to 0.95, DIAMETER_UNIT to 0.95, DIAMETER_DECIMAL to 0.85)
            .flatMap { (re, conf) -> re.findAll(text.folded).map { Triple(it, conf, parseDecimal(it.groupValues[1])) } }
            .filter { (_, _, v) -> v != null && v in 1.5..3.2 }
            .sortedBy { it.first.range.first }
        val (m, conf, value) = candidates.firstOrNull() ?: return
        val snapped = Normalization.snapDiameter(value!!)
        val finalConf = if (abs(snapped - value) > 1e-9) conf - CONFUSION_PENALTY else conf
        acc.set(Field.DIAMETER, snapped, finalConf, text.evidence(m.range.first, m.range.last + 1))
    }

    // ---------------------------------------------------------------- temperaturas

    private data class NumberToken(val value: Int, val start: Int, val end: Int, val hasUnit: Boolean)

    private data class TempCandidate(val min: Int, val max: Int, val start: Int, val end: Int, val hasUnit: Boolean)

    private val INT_2_3 = Regex("(?<![\\d.,#])\\d{2,3}(?![\\d]|[.,]\\d)")
    private val CELSIUS_AFTER = Regex("^\\s*(?:°\\s*c?|℃|c(?![a-z]))")
    private val OTHER_UNIT_AFTER = Regex("^\\s*(?:mm|cm|m(?![a-z])|g(?![a-z])|kg|gr|h(?![a-z])|hrs?|hours?|horas?|min|%|mm/s|x|pcs)")
    private val RANGE_SEPARATOR = Regex("^\\s*(?:°\\s*c?|℃)?\\s*(?:-|to|a|/|bis|~)\\s*")
    private val UNITLESS_GAP = Regex("^[\\s:=().\\-°c]*$")

    private const val HINT_WINDOW_UNIT = 40
    private const val HINT_WINDOW_UNITLESS = 15

    private fun temperatureCandidates(text: PreparedText): List<TempCandidate> {
        val s = text.folded
        val tokens = INT_2_3.findAll(s).mapNotNull { m ->
            val after = s.substring(m.range.last + 1)
            if (OTHER_UNIT_AFTER.containsMatchIn(after) && !CELSIUS_AFTER.containsMatchIn(after)) return@mapNotNull null
            NumberToken(m.value.toInt(), m.range.first, m.range.last + 1, CELSIUS_AFTER.containsMatchIn(after))
        }.toList()

        val out = mutableListOf<TempCandidate>()
        var i = 0
        while (i < tokens.size) {
            val a = tokens[i]
            val b = tokens.getOrNull(i + 1)
            val between = b?.let { s.substring(a.end, it.start) }
            if (b != null && between != null && RANGE_SEPARATOR.matchEntire(between) != null) {
                out += TempCandidate(a.value, b.value, a.start, b.end + unitLength(s, b.end), a.hasUnit || b.hasUnit)
                i += 2
            } else {
                out += TempCandidate(a.value, a.value, a.start, a.end + unitLength(s, a.end), a.hasUnit)
                i += 1
            }
        }
        return out
    }

    private fun unitLength(s: String, from: Int): Int =
        CELSIUS_AFTER.find(s.substring(from))?.value?.length ?: 0

    private fun extractTemperatures(text: PreparedText, hints: List<Hints.Hit>, acc: Accumulator) {
        val tempHints = hints.filter { it.kind in Hints.TEMPERATURE_KINDS }
        for (c in temperatureCandidates(text)) {
            // Trampa 3: manda la pista MAS CERCANA por la izquierda, no la mas
            // frecuente en la ventana.
            val hint = tempHints.lastOrNull { it.end <= c.start }
            val gap = hint?.let { text.folded.substring(it.end, c.start) }
            val usable = when {
                hint == null || gap == null -> false
                c.hasUnit -> gap.length <= HINT_WINDOW_UNIT
                // Trampa 4: temperaturas sin unidad justo detras de una pista.
                else -> gap.length <= HINT_WINDOW_UNITLESS && UNITLESS_GAP.matches(gap)
            }
            if (usable) {
                val base = if (c.hasUnit) CONF_EXACT else 0.8
                val conf = if (hint!!.exact) base else base - CONFUSION_PENALTY
                assign(text, acc, hint.kind, c, conf, hint.start)
            } else if (c.hasUnit && c.min >= 170) {
                assign(text, acc, Hints.Kind.NOZZLE, c, CONF_WEAK, c.start)
            }
        }
    }

    private fun assign(text: PreparedText, acc: Accumulator, kind: Hints.Kind, c: TempCandidate, conf: Double, evidenceStart: Int) {
        val evidence = text.evidence(evidenceStart, c.end)
        when (kind) {
            Hints.Kind.NOZZLE -> if (c.min in 150..450 && c.max in 150..450 && !acc.has(Field.NOZZLE_MIN)) {
                acc.set(Field.NOZZLE_MIN, c.min, conf, evidence)
                acc.set(Field.NOZZLE_MAX, c.max, conf, evidence)
            }
            Hints.Kind.BED -> if (c.min in 15..160 && c.max in 15..160 && !acc.has(Field.BED_MIN)) {
                acc.set(Field.BED_MIN, c.min, conf, evidence)
                acc.set(Field.BED_MAX, c.max, conf, evidence)
            }
            Hints.Kind.DRY -> if (c.max in 40..120 && !acc.has(Field.DRY_TEMP)) {
                acc.set(Field.DRY_TEMP, c.max, conf, evidence)
            }
            else -> Unit
        }
    }

    private val HOURS = Regex("(?<![\\d.,])(?:(\\d{1,2})\\s*-\\s*)?(\\d{1,2}(?:[.,]\\d)?)\\s*(?:h|hrs?|hours?|horas?|std)(?![a-z])")

    private fun extractDryTime(text: PreparedText, hints: List<Hints.Hit>, acc: Accumulator) {
        val dry = hints.filter { it.kind == Hints.Kind.DRY }
        for (m in HOURS.findAll(text.folded)) {
            val hint = dry.lastOrNull { it.end <= m.range.first && m.range.first - it.end <= HINT_WINDOW_UNIT } ?: continue
            val hours = parseDecimal(m.groupValues[2])?.roundToInt() ?: continue
            if (hours !in 1..96) continue
            acc.set(Field.DRY_TIME, hours, if (hint.exact) 0.9 else 0.8, text.evidence(hint.start, m.range.last + 1))
            return
        }
    }

    // ---------------------------------------------------------------- pesos

    private val WEIGHT = Regex("(?<![\\d.,])(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(kgs?|grs?|g|grams?|gramos?)(?![a-z/])")

    private fun extractWeights(text: PreparedText, hints: List<Hints.Hit>, acc: Accumulator) {
        val weightHints = hints.filter { it.kind in Hints.WEIGHT_KINDS }
        var net: Pair<Int, Double>? = null
        var gross: Int? = null
        var bareBest: Triple<Int, Double, String>? = null

        for (m in WEIGHT.findAll(text.folded)) {
            val grams = parseWeight(m.groupValues[1], m.groupValues[2]) ?: continue
            val hint = weightHints.lastOrNull { it.end <= m.range.first && m.range.first - it.end <= 25 }
            val evidence = text.evidence(hint?.start ?: m.range.first, m.range.last + 1)
            when (hint?.kind) {
                Hints.Kind.NET -> if (net == null && grams in 50..20_000) {
                    val conf = if (hint.exact) CONF_EXACT else CONF_CONFUSED
                    net = grams to conf
                    acc.set(Field.NET_WEIGHT, grams, conf, evidence)
                }
                Hints.Kind.GROSS -> if (gross == null) gross = grams
                Hints.Kind.SPOOL -> if (!acc.has(Field.SPOOL_WEIGHT) && grams in 20..3_000) {
                    acc.set(Field.SPOOL_WEIGHT, grams, if (hint.exact) 0.9 else CONF_CONFUSED, evidence)
                }
                else -> if (grams in 100..5_000) {
                    // Sin pista, mejor el que coincide con un nominal.
                    val nominal = Normalization.snapWeight(grams) in Normalization.NOMINAL_WEIGHTS_G
                    val conf = if (nominal) 0.8 else CONF_WEAK
                    if (bareBest == null || conf > bareBest.second) bareBest = Triple(grams, conf, evidence)
                }
            }
        }
        if (net == null && bareBest != null) {
            net = bareBest.first to bareBest.second
            acc.set(Field.NET_WEIGHT, bareBest.first, bareBest.second, bareBest.third)
        }
        val n = net?.first
        val g = gross
        if (n != null && g != null && !acc.has(Field.SPOOL_WEIGHT) && g - n in 50..600) {
            acc.set(Field.SPOOL_WEIGHT, g - n, 0.6, "${g} g - ${n} g", Source.DERIVED)
        }
    }

    private fun parseWeight(number: String, unit: String): Int? {
        val kg = unit.startsWith("k")
        // `1,000g` es un separador de miles, no un decimal.
        if (!kg && Regex("\\d{1,3}[.,]\\d{3}").matches(number)) return number.replace(Regex("[.,]"), "").toIntOrNull()
        val v = parseDecimal(number) ?: return null
        return if (kg) (v * 1000).roundToInt() else v.roundToInt()
    }

    // ---------------------------------------------------------------- densidad

    private val DENSITY = Regex("(?<![\\d.,])(\\d[.,]\\d{1,3})\\s*g\\s*/\\s*cm\\s*3")

    private fun extractDensity(text: PreparedText, acc: Accumulator) {
        val m = DENSITY.find(text.folded) ?: return
        val v = parseDecimal(m.groupValues[1]) ?: return
        if (v in 0.8..2.5) acc.set(Field.DENSITY, v, 0.9, text.evidence(m.range.first, m.range.last + 1))
    }

    // ---------------------------------------------------------------- lote / serie

    // Trampa 5: `\bno` encuentra el "No" de "Nozzle". Se exige el punto
    // (`no.`), y el valor tiene que contener algun digito.
    private val LOT = Regex(
        "(?<![a-z])(?:(?:lote?|batch)(?:\\s*(?:no\\.|n°|nr\\.|number|#))?|no\\.|n°)[\\s:#.]*([a-z0-9][a-z0-9\\-/]{2,23})",
    )
    private val SERIAL = Regex(
        "(?<![a-z])(?:s/n|serial(?:\\s*(?:no\\.|n°|number|#))?|sn(?=\\s*[:#]))[\\s:#.]*([a-z0-9][a-z0-9\\-/]{2,23})",
    )
    private val NOT_A_LOT_BEFORE = Regex("(?:model|item|art|part|sku|ref|pack)\\.?\\s*$")

    private fun extractLotAndSerial(text: PreparedText, acc: Accumulator) {
        fun find(re: Regex, field: Field) {
            for (m in re.findAll(text.folded)) {
                val value = m.groups[1]!!
                if (value.value.none { it.isDigit() }) continue
                val before = text.folded.substring((m.range.first - 12).coerceAtLeast(0), m.range.first)
                if (NOT_A_LOT_BEFORE.containsMatchIn(before)) continue
                val original = text.original.substring(value.range.first, value.range.last + 1).trimEnd('-', '/')
                acc.set(field, original, 0.85, text.evidence(m.range.first, m.range.last + 1))
                return
            }
        }
        find(SERIAL, Field.SERIAL)
        find(LOT, Field.LOT)
    }

    // ---------------------------------------------------------------- utilidades

    private fun parseDecimal(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    private fun IntRange.length() = last - first + 1
    private fun IntRange.contains(other: IntRange) = other.first >= first && other.last <= last
    private fun IntRange.overlaps(other: IntRange) = other.first <= last && other.last >= first

    internal fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Va reuniendo valores; el primero que llega a un campo se queda. */
    private class Accumulator {
        private var spec = FilamentSpec()
        private val provenance = mutableMapOf<Field, Provenance>()

        fun has(field: Field) = field in provenance

        fun set(field: Field, value: Any, confidence: Double, evidence: String, source: Source = Source.OCR) {
            spec = field.set(spec, value)
            provenance[field] = Provenance(source, confidence.coerceIn(0.0, 1.0), evidence)
        }

        fun setIfAbsent(field: Field, value: Any, source: Source, confidence: Double, evidence: String) {
            if (!has(field)) set(field, value, confidence, evidence, source)
        }

        fun build(): FilamentSpec = spec.copy(provenance = provenance.toMap())
    }
}
