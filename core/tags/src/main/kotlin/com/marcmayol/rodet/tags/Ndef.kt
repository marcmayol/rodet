package com.marcmayol.rodet.tags

import java.io.ByteArrayOutputStream

/**
 * NDEF en Kotlin puro. `android.nfc.NdefMessage` hace lo mismo, pero aqui se
 * puede probar en JVM y medir el tamano exacto antes de acercar el movil.
 */
class NdefRecord(
    val tnf: Int,
    val type: ByteArray,
    val payload: ByteArray,
    val id: ByteArray = ByteArray(0),
) {
    init {
        require(tnf in 0..6) { "TNF invalido: $tnf" }
        require(type.size <= 255 && id.size <= 255) { "tipo o id demasiado largos" }
    }

    val typeString: String get() = type.toString(Charsets.US_ASCII)

    override fun equals(other: Any?): Boolean = other is NdefRecord && tnf == other.tnf &&
        type.contentEquals(other.type) && payload.contentEquals(other.payload) && id.contentEquals(other.id)

    override fun hashCode(): Int = (tnf * 31 + type.contentHashCode()) * 31 + payload.contentHashCode()

    companion object {
        const val TNF_EMPTY = 0
        const val TNF_WELL_KNOWN = 1
        const val TNF_MIME = 2
        const val TNF_ABSOLUTE_URI = 3
        const val TNF_EXTERNAL = 4
        const val TNF_UNKNOWN = 5

        fun mime(mimeType: String, payload: ByteArray): NdefRecord {
            val t = mimeType.trim().lowercase()
            require(t.isNotEmpty() && t.all { it.code in 0x20..0x7E }) { "tipo MIME invalido: $mimeType" }
            return NdefRecord(TNF_MIME, t.toByteArray(Charsets.US_ASCII), payload)
        }

        /** Tipo externo NFC Forum: `dominio:tipo`, siempre en minusculas. */
        fun external(domain: String, type: String, payload: ByteArray): NdefRecord {
            val t = "${domain.trim()}:${type.trim()}".lowercase()
            require(domain.isNotBlank() && type.isNotBlank()) { "dominio y tipo obligatorios" }
            return NdefRecord(TNF_EXTERNAL, t.toByteArray(Charsets.US_ASCII), payload)
        }

        private val URI_PREFIXES = listOf("", "http://www.", "https://www.", "http://", "https://")

        fun uri(uri: String): NdefRecord {
            val code = (1 until URI_PREFIXES.size)
                .filter { uri.startsWith(URI_PREFIXES[it]) }
                .maxByOrNull { URI_PREFIXES[it].length } ?: 0
            val rest = uri.substring(URI_PREFIXES[code].length).toByteArray(Charsets.UTF_8)
            return NdefRecord(TNF_WELL_KNOWN, byteArrayOf('U'.code.toByte()), byteArrayOf(code.toByte()) + rest)
        }
    }
}

class NdefMessage(val records: List<NdefRecord>) {
    init {
        require(records.isNotEmpty()) { "un mensaje NDEF necesita al menos un registro" }
    }

    constructor(vararg records: NdefRecord) : this(records.toList())

    fun toByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        records.forEachIndexed { i, r ->
            val shortRecord = r.payload.size <= 255
            var flags = r.tnf
            if (i == 0) flags = flags or MB
            if (i == records.lastIndex) flags = flags or ME
            if (shortRecord) flags = flags or SR
            if (r.id.isNotEmpty()) flags = flags or IL
            out.write(flags)
            out.write(r.type.size)
            if (shortRecord) out.write(r.payload.size) else out.write(r.payload.size.toBigEndian(4))
            if (r.id.isNotEmpty()) out.write(r.id.size)
            out.write(r.type)
            out.write(r.id)
            out.write(r.payload)
        }
        return out.toByteArray()
    }

    val byteArrayLength: Int get() = toByteArray().size

    fun firstOfMime(mimeType: String): NdefRecord? =
        records.firstOrNull { it.tnf == NdefRecord.TNF_MIME && it.typeString.equals(mimeType, ignoreCase = true) }

    override fun equals(other: Any?): Boolean = other is NdefMessage && records == other.records
    override fun hashCode(): Int = records.hashCode()

    companion object {
        private const val MB = 0x80
        private const val ME = 0x40
        private const val CF = 0x20
        private const val SR = 0x10
        private const val IL = 0x08

        fun parse(bytes: ByteArray): NdefMessage {
            val records = mutableListOf<NdefRecord>()
            var p = 0
            fun u8(): Int {
                if (p >= bytes.size) throw TagFormatException("NDEF truncado en el byte $p")
                return bytes[p++].toInt() and 0xFF
            }
            while (true) {
                val flags = u8()
                if (flags and CF != 0) throw TagFormatException("registros NDEF troceados: no soportados")
                val typeLen = u8()
                val payloadLen = if (flags and SR != 0) u8() else (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or u8().toLong() }.let {
                    if (it > bytes.size) throw TagFormatException("longitud de payload imposible: $it")
                    it.toInt()
                }
                val idLen = if (flags and IL != 0) u8() else 0
                fun take(n: Int): ByteArray {
                    if (p + n > bytes.size) throw TagFormatException("NDEF truncado")
                    return bytes.copyOfRange(p, p + n).also { p += n }
                }
                val type = take(typeLen)
                val id = take(idLen)
                val payload = take(payloadLen)
                records += NdefRecord(flags and 0x07, type, payload, id)
                if (flags and ME != 0) break
            }
            return NdefMessage(records)
        }
    }
}

/** Empaquetado TLV de un mensaje NDEF, tal como se guarda en la memoria de un NTAG o NFC-V. */
object NdefTlv {
    const val NDEF_TYPE = 0x03
    const val TERMINATOR = 0xFE

    fun header(length: Int): ByteArray = if (length < 0xFF) {
        byteArrayOf(NDEF_TYPE.toByte(), length.toByte())
    } else {
        require(length <= 0xFFFE) { "mensaje NDEF demasiado grande: $length B" }
        byteArrayOf(NDEF_TYPE.toByte(), 0xFF.toByte(), (length shr 8).toByte(), length.toByte())
    }

    fun wrap(message: ByteArray): ByteArray = header(message.size) + message + byteArrayOf(TERMINATOR.toByte())

    /** Busca el primer TLV NDEF saltando los de relleno (0x00) y los propietarios. */
    fun unwrap(memory: ByteArray, from: Int = 0): ByteArray {
        var p = from
        while (p < memory.size) {
            val t = memory[p].toInt() and 0xFF
            when (t) {
                0x00 -> { p++; continue }
                TERMINATOR -> break
            }
            if (p + 1 >= memory.size) break
            var len = memory[p + 1].toInt() and 0xFF
            var start = p + 2
            if (len == 0xFF) {
                if (p + 3 >= memory.size) break
                len = ((memory[p + 2].toInt() and 0xFF) shl 8) or (memory[p + 3].toInt() and 0xFF)
                start = p + 4
            }
            if (start + len > memory.size) throw TagFormatException("TLV sale de la memoria")
            if (t == NDEF_TYPE) return memory.copyOfRange(start, start + len)
            p = start + len
        }
        throw TagFormatException("no hay ningun TLV NDEF")
    }
}

/** Memoria util para NDEF de cada chip (sin contar el capability container). */
enum class TagCapacity(val label: String, val userBytes: Int) {
    NTAG213("NTAG213", 144),
    NTAG215("NTAG215", 504),
    NTAG216("NTAG216", 872),
    ICODE_SLIX2("ICODE SLIX2", 316),
    ;

    fun fits(message: NdefMessage): Boolean = NdefTlv.wrap(message.toByteArray()).size <= userBytes
}

class TagFormatException(message: String) : Exception(message)

internal fun Int.toBigEndian(bytes: Int): ByteArray = ByteArray(bytes) { i -> (this shr (8 * (bytes - 1 - i))).toByte() }

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "").replace(":", "")
    require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) { "hex invalido" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
