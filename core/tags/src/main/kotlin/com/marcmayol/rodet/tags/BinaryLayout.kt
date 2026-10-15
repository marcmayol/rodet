package com.marcmayol.rodet.tags

/**
 * Layout binario descrito como TABLA, no como codigo. Cuando alguien contraste
 * un perfil contra un volcado real, corregirlo es mover un numero aqui.
 */
enum class FieldEncoding {
    /** Texto ASCII relleno con ceros por la derecha. */
    ASCII,

    UINT_LE,
    UINT_BE,

    /** Bytes tal cual, longitud exacta. */
    RAW,

    /**
     * Rareza de Elegoo: cada caracter se guarda como su codigo ASCII DECIMAL
     * escrito como si fuera hex ("P" = 80 -> 0x80), alineado a la derecha.
     */
    ASCII_DECIMAL_AS_HEX,
}

data class LayoutField(
    val name: String,
    val offset: Int,
    val length: Int,
    val encoding: FieldEncoding,
    /** Valor almacenado = valor * scale (p. ej. diametro 1.75 mm * 100 = 175). */
    val scale: Int = 1,
    /** Si no es null, el campo siempre vale esto y no se puede cambiar. */
    val constant: ByteArray? = null,
    /** Si la constante sirve para reconocer el formato al leer. */
    val identifying: Boolean = true,
) {
    init {
        require(offset >= 0 && length > 0) { "$name: offset/longitud invalidos" }
        require(constant == null || constant.size == length) { "$name: la constante no mide $length B" }
        require(encoding !in setOf(FieldEncoding.UINT_LE, FieldEncoding.UINT_BE) || length <= 8) { "$name: entero de mas de 8 B" }
    }

    val end: Int get() = offset + length
}

class BinaryLayout(val size: Int, val fields: List<LayoutField>) {

    init {
        val names = fields.map { it.name }
        require(names.size == names.toSet().size) { "nombres de campo repetidos" }
        fields.forEach { require(it.end <= size) { "${it.name} se sale del layout (${it.end} > $size)" } }
        val sorted = fields.sortedBy { it.offset }
        for (i in 1 until sorted.size) {
            require(sorted[i].offset >= sorted[i - 1].end) { "${sorted[i - 1].name} y ${sorted[i].name} se solapan" }
        }
    }

    fun field(name: String): LayoutField = fields.firstOrNull { it.name == name } ?: error("campo desconocido: $name")

    /**
     * Codifica. Los campos que no aparecen en [values] quedan a cero; las
     * constantes se escriben siempre. Un valor que no cabe es un error: mejor
     * fallar aqui que gastar una pegatina.
     */
    fun encode(values: Map<String, Any>): ByteArray {
        values.keys.forEach { field(it) }
        val out = ByteArray(size)
        for (f in fields) {
            val bytes = f.constant ?: values[f.name]?.let { encodeValue(f, it) } ?: continue
            bytes.copyInto(out, f.offset)
        }
        return out
    }

    fun decode(bytes: ByteArray): Map<String, Any> {
        require(bytes.size >= size) { "faltan bytes: ${bytes.size} < $size" }
        return fields.filter { it.constant == null }.associate { f ->
            val slice = bytes.copyOfRange(f.offset, f.end)
            f.name to decodeValue(f, slice)
        }
    }

    /** Comprueba las constantes, que es la forma de reconocer el formato al leer. */
    fun matchesConstants(bytes: ByteArray): Boolean = bytes.size >= size &&
        fields.all { f -> f.constant == null || !f.identifying || bytes.copyOfRange(f.offset, f.end).contentEquals(f.constant) }

    private fun encodeValue(f: LayoutField, v: Any): ByteArray = when (f.encoding) {
        FieldEncoding.ASCII -> {
            val s = (v as String).toByteArray(Charsets.US_ASCII)
            require(s.size <= f.length) { "${f.name}: \"$v\" no cabe en ${f.length} B" }
            s.copyOf(f.length)
        }
        FieldEncoding.UINT_LE, FieldEncoding.UINT_BE -> {
            val n = scaled(f, v)
            val max = if (f.length >= 8) Long.MAX_VALUE else (1L shl (8 * f.length)) - 1
            require(n in 0..max) { "${f.name}: $n no cabe en ${f.length} B" }
            val be = ByteArray(f.length) { i -> (n shr (8 * (f.length - 1 - i))).toByte() }
            if (f.encoding == FieldEncoding.UINT_LE) be.reversedArray() else be
        }
        FieldEncoding.RAW -> (v as ByteArray).also {
            require(it.size == f.length) { "${f.name}: se esperaban ${f.length} B y hay ${it.size}" }
        }
        FieldEncoding.ASCII_DECIMAL_AS_HEX -> {
            val s = v as String
            require(s.length <= f.length) { "${f.name}: \"$s\" no cabe en ${f.length} B" }
            val out = ByteArray(f.length)
            s.forEachIndexed { i, c ->
                require(c.code in 0..99) { "${f.name}: '$c' no se puede escribir en este formato" }
                out[f.length - s.length + i] = ((c.code / 10 shl 4) or (c.code % 10)).toByte()
            }
            out
        }
    }

    private fun decodeValue(f: LayoutField, b: ByteArray): Any = when (f.encoding) {
        FieldEncoding.ASCII -> b.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
        FieldEncoding.UINT_LE, FieldEncoding.UINT_BE -> {
            val be = if (f.encoding == FieldEncoding.UINT_LE) b.reversedArray() else b
            val n = be.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
            if (f.scale == 1) n else n.toDouble() / f.scale
        }
        FieldEncoding.RAW -> b
        FieldEncoding.ASCII_DECIMAL_AS_HEX -> b.dropWhile { it == 0.toByte() }
            .map { x -> val v = x.toInt() and 0xFF; ((v shr 4) * 10 + (v and 0x0F)).toChar() }
            .joinToString("")
    }

    private fun scaled(f: LayoutField, v: Any): Long = when (v) {
        is Int -> v.toLong() * f.scale
        is Long -> v * f.scale
        is Double -> Math.round(v * f.scale)
        else -> error("${f.name}: tipo no numerico ${v::class.simpleName}")
    }
}
