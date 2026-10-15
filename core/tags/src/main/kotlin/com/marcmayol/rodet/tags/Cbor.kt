package com.marcmayol.rodet.tags

import java.io.ByteArrayOutputStream

/**
 * CBOR (RFC 8949) con codificacion determinista: enteros en su forma mas
 * corta, mapas ordenados por los bytes de la clave, sin longitudes
 * indefinidas y sin claves repetidas.
 *
 * Trampa 10 del plan: dos escrituras del mismo filamento tienen que dar bytes
 * identicos. Los perfiles usan enteros escalados siempre que la especificacion
 * lo permite (micras, minutos). Cuando exige un numero con decimales (la
 * densidad de OpenPrintTag), el float va en la forma mas corta que conserva el
 * valor exacto (half, single o double), que tambien es determinista.
 */
sealed interface Cbor {
    data class UInt(val value: Long) : Cbor {
        init { require(value >= 0) }
    }

    /** Entero negativo: representa `-1 - n`. */
    data class NInt(val value: Long) : Cbor {
        init { require(value < 0) }
    }

    class Bytes(val value: ByteArray) : Cbor {
        override fun equals(other: Any?) = other is Bytes && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
        override fun toString() = "h'${value.toHex()}'"
    }

    data class Text(val value: String) : Cbor
    data class Array(val items: List<Cbor>) : Cbor
    data class Map(val entries: List<Pair<Cbor, Cbor>>) : Cbor {
        operator fun get(key: Long): Cbor? = entries.firstOrNull { (k, _) -> k == UInt(key) }?.second
    }
    data class Float(val value: Double) : Cbor
    data class Tagged(val tag: Long, val value: Cbor) : Cbor
    data class Bool(val value: Boolean) : Cbor
    data object Null : Cbor
    data object Undefined : Cbor

    companion object {
        fun int(v: Long): Cbor = if (v >= 0) UInt(v) else NInt(v)
        fun int(v: Int): Cbor = int(v.toLong())
        fun text(v: String): Cbor = Text(v)
        fun bytes(v: ByteArray): Cbor = Bytes(v)

        /** Mapa con claves enteras, el caso de OpenPrintTag. Los nulos se omiten. */
        fun intMap(vararg entries: Pair<Int, Cbor?>): Map =
            Map(entries.mapNotNull { (k, v) -> v?.let { int(k) to it } })
    }
}

object CborEncoder {

    fun encode(value: Cbor): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, v: Cbor) {
        when (v) {
            is Cbor.UInt -> head(out, 0, v.value)
            is Cbor.NInt -> head(out, 1, -1 - v.value)
            is Cbor.Bytes -> { head(out, 2, v.value.size.toLong()); out.write(v.value) }
            is Cbor.Text -> v.value.toByteArray(Charsets.UTF_8).let { head(out, 3, it.size.toLong()); out.write(it) }
            is Cbor.Array -> { head(out, 4, v.items.size.toLong()); v.items.forEach { write(out, it) } }
            is Cbor.Map -> {
                val encoded = v.entries.map { (k, value) -> encode(k) to encode(value) }
                val sorted = encoded.sortedWith { a, b -> compareBytes(a.first, b.first) }
                for (i in 1 until sorted.size) {
                    require(!sorted[i].first.contentEquals(sorted[i - 1].first)) { "clave repetida en un mapa CBOR" }
                }
                head(out, 5, sorted.size.toLong())
                sorted.forEach { out.write(it.first); out.write(it.second) }
            }
            is Cbor.Float -> writeFloat(out, v.value)
            is Cbor.Tagged -> { head(out, 6, v.tag); write(out, v.value) }
            is Cbor.Bool -> out.write(if (v.value) 0xF5 else 0xF4)
            Cbor.Null -> out.write(0xF6)
            Cbor.Undefined -> out.write(0xF7)
        }
    }

    private fun head(out: ByteArrayOutputStream, major: Int, arg: Long) {
        val m = major shl 5
        when {
            arg < 24 -> out.write(m or arg.toInt())
            arg <= 0xFF -> { out.write(m or 24); out.write(arg.toInt()) }
            arg <= 0xFFFF -> { out.write(m or 25); out.write((arg shr 8).toInt()); out.write(arg.toInt() and 0xFF) }
            arg <= 0xFFFFFFFFL -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write(((arg shr s) and 0xFF).toInt()) }
            else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write(((arg shr s) and 0xFF).toInt()) }
        }
    }

    private fun writeFloat(out: ByteArrayOutputStream, d: Double) {
        require(!d.isNaN()) { "NaN no tiene una codificacion determinista util" }
        val f = d.toFloat()
        val half = if (f.toDouble() == d) halfBits(f) else null
        when {
            half != null -> { out.write(0xF9); out.write(half shr 8); out.write(half and 0xFF) }
            f.toDouble() == d -> {
                out.write(0xFA)
                val bits = f.toRawBits()
                for (s in 24 downTo 0 step 8) out.write((bits shr s) and 0xFF)
            }
            else -> {
                out.write(0xFB)
                val bits = d.toRawBits()
                for (s in 56 downTo 0 step 8) out.write(((bits shr s) and 0xFF).toInt())
            }
        }
    }

    /**
     * Bits de un half-float si [f] cabe en el sin perder nada; si no, null.
     * Los subnormales de half se omiten a proposito: van en single, que sigue
     * siendo determinista y ningun dato de filamento vive ahi.
     */
    internal fun halfBits(f: kotlin.Float): Int? {
        val bits = f.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        return when {
            exp == 0 && mant == 0 -> sign
            exp == 0xFF && mant == 0 -> sign or 0x7C00
            exp - 127 in -14..15 && mant and 0x1FFF == 0 -> sign or ((exp - 127 + 15) shl 10) or (mant ushr 13)
            else -> null
        }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}

/**
 * Lector tolerante: acepta tambien longitudes indefinidas y floats de
 * cualquier tamano, porque la implementacion de referencia de OpenPrintTag
 * escribe el mapa principal como indefinido.
 */
class CborDecoder(private val bytes: ByteArray, private var pos: Int = 0) {

    val position: Int get() = pos

    fun read(): Cbor {
        val ib = u8()
        val major = ib shr 5
        val info = ib and 0x1F
        if (major == 7) return simple(info)
        if (info == 31) return indefinite(major)
        val arg = argument(info)
        return when (major) {
            0 -> Cbor.UInt(arg)
            1 -> Cbor.NInt(-1 - arg)
            2 -> Cbor.Bytes(take(arg))
            3 -> Cbor.Text(take(arg).toString(Charsets.UTF_8))
            4 -> Cbor.Array(List(checkedCount(arg)) { read() })
            5 -> Cbor.Map(List(checkedCount(arg)) { read() to read() })
            6 -> Cbor.Tagged(arg, read())
            else -> throw TagFormatException("tipo CBOR imposible")
        }
    }

    private fun indefinite(major: Int): Cbor = when (major) {
        2 -> Cbor.Bytes(chunks(2).fold(ByteArray(0)) { acc, c -> acc + (c as Cbor.Bytes).value })
        3 -> Cbor.Text(chunks(3).joinToString("") { (it as Cbor.Text).value })
        4 -> Cbor.Array(buildList { while (!atBreak()) add(read()); pos++ })
        5 -> Cbor.Map(buildList { while (!atBreak()) add(read() to read()); pos++ })
        else -> throw TagFormatException("longitud indefinida no valida para el tipo $major")
    }

    private fun chunks(major: Int): List<Cbor> = buildList {
        while (!atBreak()) {
            val c = read()
            val ok = (major == 2 && c is Cbor.Bytes) || (major == 3 && c is Cbor.Text)
            if (!ok) throw TagFormatException("trozo de tipo equivocado en cadena indefinida")
            add(c)
        }
        pos++
    }

    private fun atBreak(): Boolean {
        if (pos >= bytes.size) throw TagFormatException("CBOR truncado: falta el fin de coleccion")
        return bytes[pos].toInt() and 0xFF == 0xFF
    }

    private fun simple(info: Int): Cbor = when (info) {
        20 -> Cbor.Bool(false)
        21 -> Cbor.Bool(true)
        22 -> Cbor.Null
        23 -> Cbor.Undefined
        25 -> Cbor.Float(halfToDouble((u8() shl 8) or u8()))
        26 -> Cbor.Float(java.lang.Float.intBitsToFloat(argument(26).toInt()).toDouble())
        27 -> Cbor.Float(java.lang.Double.longBitsToDouble(argument(27)))
        else -> throw TagFormatException("valor simple CBOR no soportado: $info")
    }

    private fun argument(info: Int): Long = when {
        info < 24 -> info.toLong()
        info == 24 -> u8().toLong()
        info == 25 -> ((u8() shl 8) or u8()).toLong()
        info == 26 -> (0 until 4).fold(0L) { a, _ -> (a shl 8) or u8().toLong() }
        info == 27 -> (0 until 8).fold(0L) { a, _ -> (a shl 8) or u8().toLong() }
        else -> throw TagFormatException("argumento CBOR reservado: $info")
    }

    private fun checkedCount(n: Long): Int {
        if (n > bytes.size - pos) throw TagFormatException("coleccion CBOR mas grande que los datos")
        return n.toInt()
    }

    private fun take(n: Long): ByteArray {
        if (n < 0 || pos + n > bytes.size) throw TagFormatException("CBOR truncado")
        return bytes.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() }
    }

    private fun u8(): Int {
        if (pos >= bytes.size) throw TagFormatException("CBOR truncado")
        return bytes[pos++].toInt() and 0xFF
    }

    private fun halfToDouble(h: Int): Double {
        val sign = if (h and 0x8000 != 0) -1.0 else 1.0
        val exp = (h shr 10) and 0x1F
        val mant = h and 0x3FF
        return sign * when (exp) {
            0 -> Math.scalb(mant.toDouble(), -24)
            31 -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> Math.scalb((mant + 1024).toDouble(), exp - 25)
        }
    }

    companion object {
        fun decode(bytes: ByteArray): Cbor = CborDecoder(bytes).read()
    }
}

// Accesos comodos para leer mapas decodificados.
fun Cbor?.asLong(): Long? = when (this) {
    is Cbor.UInt -> value
    is Cbor.NInt -> value
    is Cbor.Float -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else null
    else -> null
}

fun Cbor?.asDouble(): Double? = when (this) {
    is Cbor.Float -> value
    is Cbor.UInt -> value.toDouble()
    is Cbor.NInt -> value.toDouble()
    else -> null
}

fun Cbor?.asText(): String? = (this as? Cbor.Text)?.value
fun Cbor?.asBytes(): ByteArray? = (this as? Cbor.Bytes)?.value
