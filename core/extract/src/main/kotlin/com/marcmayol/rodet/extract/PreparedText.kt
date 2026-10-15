package com.marcmayol.rodet.extract

/**
 * Tres vistas del mismo texto OCR, todas con la MISMA longitud, de modo que
 * un indice en cualquiera de ellas apunta al mismo caracter del original
 * (trampa 9). Por eso aqui no se usa `Normalizer`: NFD y NFKC cambian la
 * longitud y la evidencia citada acabaria apuntando a otro sitio.
 *
 * - [folded]: minusculas, sin acentos, grados y guiones unificados, y numeros
 *   reparados. Es la vista para leer numeros.
 * - [skeleton]: [folded] con las clases de caracteres que el OCR confunde
 *   colapsadas (`i l 1 |` -> `l`, `0` -> `o`, `5` -> `s`, `8` -> `b`). Es la
 *   vista para buscar palabras (trampa 8): `PIA` y `PLA` dan lo mismo.
 */
internal class PreparedText(val original: String) {

    val folded: String = repairNumbers(foldChars(original))
    val skeleton: String = skeletonize(folded)

    init {
        check(folded.length == original.length && skeleton.length == original.length)
    }

    fun evidence(start: Int, end: Int): String =
        original.substring(start.coerceAtLeast(0), end.coerceAtMost(original.length))
            .replace(Regex("\\s+"), " ").trim()

    companion object {
        const val DEGREE = '°'
        const val CELSIUS_SIGN = '℃'

        private val CHAR_MAP: Map<Char, Char> = buildMap {
            // Trampa 1: los simbolos de grado se resuelven ANTES de cualquier otra
            // cosa. Con NFKC `º` pasaria a `o`, y el reparador de numeros leeria
            // esa `o` como `0`: `75ºC` acabaria valiendo 750.
            "º°˚◦ᵒ".forEach { put(it, DEGREE) }
            "–—−‐‑‒～~".forEach { put(it, '-') }
            "⌀Øø∅".forEach { put(it, 'ø') }
            put('³', '3'); put('²', '2')
            put('：', ':'); put('／', '/')
            "      \t".forEach { put(it, ' ') }
            for (i in 0..9) put('０' + i, '0' + i)
            val accents = "áàäâãåéèëêíìïîóòöôõúùüûñçý"
            val plain = "aaaaaaeeeeiiiiooooouuuuncy"
            accents.forEachIndexed { i, c -> put(c, plain[i]) }
        }

        fun foldChars(s: String): String {
            val out = CharArray(s.length)
            for (i in s.indices) {
                val c = s[i]
                if (c == CELSIUS_SIGN) { out[i] = c; continue }
                val lower = c.lowercaseChar()
                out[i] = CHAR_MAP[c] ?: CHAR_MAP[lower] ?: lower
            }
            return String(out)
        }

        private val NUMBER_CONFUSABLES = mapOf('o' to '0', 'l' to '1', 'i' to '1', '|' to '1', 's' to '5', 'b' to '8')
        private val RUN = Regex("[0-9olis|b]+")

        // Trampa 2: solo la LETRA `o` tras un digito y antes de una `c` es un
        // grado mal leido. Un `0` es siempre un digito: `60 C` son 60 grados,
        // no 6.
        private val O_AS_DEGREE = Regex("(?<=\\d)o(?=\\s?c(?![a-z]))")

        /**
         * `2O5` -> `205`, `2l0` -> `210`. Una letra solo se convierte en digito
         * si tiene un digito real a cada lado dentro de la misma tirada, asi
         * que `75o` no se toca y palabras como `pool` tampoco.
         */
        fun repairNumbers(s: String): String {
            val chars = s.toCharArray()
            for (run in RUN.findAll(s)) {
                val r = run.range
                val digitIdx = r.filter { chars[it].isDigit() }
                if (digitIdx.isEmpty()) continue
                val first = digitIdx.first()
                val last = digitIdx.last()
                for (i in first + 1 until last) {
                    NUMBER_CONFUSABLES[chars[i]]?.let { chars[i] = it }
                }
            }
            val repaired = String(chars)
            return O_AS_DEGREE.replace(repaired) { DEGREE.toString() }
        }

        private val SKELETON_MAP = mapOf('1' to 'l', 'i' to 'l', '|' to 'l', '!' to 'l', '0' to 'o', '5' to 's', '8' to 'b')

        fun skeletonize(s: String): String {
            val out = CharArray(s.length)
            for (i in s.indices) out[i] = SKELETON_MAP[s[i]] ?: s[i]
            return String(out)
        }
    }
}

/** Busca una frase del vocabulario en el esqueleto, tolerando las confusiones del OCR. */
internal class PhraseMatcher(phrase: String) {
    private val folded = PreparedText.foldChars(phrase)
    private val exact = Regex(build(folded))
    private val loose = Regex("(?<![a-z0-9])(?:${build(PreparedText.skeletonize(folded))})(?![a-z0-9])")
    val length = phrase.length

    data class Hit(val range: IntRange, val exact: Boolean)

    fun findAll(text: PreparedText): List<Hit> = loose.findAll(text.skeleton).map { m ->
        val slice = text.folded.substring(m.range.first, m.range.last + 1)
        Hit(m.range, exact.matches(slice))
    }.toList()

    private fun build(p: String): String =
        p.split(' ').filter { it.isNotEmpty() }.joinToString("[\\s._:-]*") { Regex.escape(it) }
}
