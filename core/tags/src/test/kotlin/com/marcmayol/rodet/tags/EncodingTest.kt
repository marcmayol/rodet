package com.marcmayol.rodet.tags

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncodingTest {

    @Nested
    inner class CborDeterminista {
        private fun hex(c: Cbor) = CborEncoder.encode(c).toHex()

        @Test
        fun `enteros en su forma mas corta`() {
            assertEquals("00", hex(Cbor.int(0)))
            assertEquals("17", hex(Cbor.int(23)))
            assertEquals("1818", hex(Cbor.int(24)))
            assertEquals("1903E8", hex(Cbor.int(1000)))
            assertEquals("1A000F4240", hex(Cbor.int(1_000_000)))
            assertEquals("20", hex(Cbor.int(-1)))
            assertEquals("3863", hex(Cbor.int(-100)))
        }

        @Test
        fun `trampa 10 - el orden de insercion no cambia los bytes`() {
            val a = Cbor.intMap(35 to Cbor.int(220), 8 to Cbor.int(0), 11 to Cbor.text("eSUN"))
            val b = Cbor.intMap(11 to Cbor.text("eSUN"), 35 to Cbor.int(220), 8 to Cbor.int(0))
            assertContentEquals(CborEncoder.encode(a), CborEncoder.encode(b))
            // Orden por bytes de la clave: 08, 0B, 18 23.
            assertEquals("A30800" + "0B" + "646553554E" + "1823" + "18DC", hex(a))
        }

        @Test
        fun `trampa 10 - floats en la forma mas corta que no pierde precision`() {
            assertEquals("F93E00", hex(Cbor.Float(1.5))) // RFC 8949, apendice A
            assertEquals("FA47C35000", hex(Cbor.Float(100000.0)))
            assertEquals("FB3FF3D70A3D70A3D7", hex(Cbor.Float(1.24)))
            assertEquals("F90000", hex(Cbor.Float(0.0)))
            assertEquals("F97C00", hex(Cbor.Float(Double.POSITIVE_INFINITY)))
        }

        @Test
        fun `NaN y claves repetidas se rechazan`() {
            assertThrows<IllegalArgumentException> { CborEncoder.encode(Cbor.Float(Double.NaN)) }
            assertThrows<IllegalArgumentException> {
                CborEncoder.encode(Cbor.Map(listOf(Cbor.int(1) to Cbor.Null, Cbor.int(1) to Cbor.Null)))
            }
        }

        @Test
        fun `ida y vuelta`() {
            val v = Cbor.Map(
                listOf(
                    Cbor.int(1) to Cbor.Array(listOf(Cbor.int(-5), Cbor.text("ñ"), Cbor.Bool(true), Cbor.Null)),
                    Cbor.int(2) to Cbor.bytes(byteArrayOf(1, 2, 3)),
                    Cbor.int(3) to Cbor.Float(1.24),
                    Cbor.int(4) to Cbor.Float(1.5),
                ),
            )
            assertEquals(v, CborDecoder.decode(CborEncoder.encode(v)))
        }

        @Test
        fun `lee colecciones indefinidas y half floats`() {
            // {_ 0: 1, 1: [_ 2]} y 0.5 en half
            val m = CborDecoder.decode("BF0001019F02FFFF".hexToBytes()) as Cbor.Map
            assertEquals(1L, m[0].asLong())
            assertEquals(Cbor.Array(listOf(Cbor.int(2))), m[1])
            assertEquals(0.5, CborDecoder.decode("F93800".hexToBytes()).asDouble())
        }

        @Test
        fun `datos truncados dan error de formato, no un crash`() {
            assertThrows<TagFormatException> { CborDecoder.decode("1903".hexToBytes()) }
            assertThrows<TagFormatException> { CborDecoder.decode("BF00".hexToBytes()) }
            assertThrows<TagFormatException> { CborDecoder.decode("9A7FFFFFFF".hexToBytes()) }
        }
    }

    @Nested
    inner class NdefBytes {
        @Test
        fun `registro MIME corto`() {
            val msg = NdefMessage(NdefRecord.mime("text/x", byteArrayOf(0x41)))
            // MB|ME|SR|MIME, tipo de 6 B, payload de 1 B, "text/x", 'A'
            assertEquals("D20601" + "746578742F78" + "41", msg.toByteArray().toHex())
        }

        @Test
        fun `registro largo usa 4 bytes de longitud`() {
            val bytes = NdefMessage(NdefRecord.mime("a/b", ByteArray(300))).toByteArray()
            assertEquals(0xC2, bytes[0].toInt() and 0xFF)
            assertEquals("0000012C", bytes.copyOfRange(2, 6).toHex())
        }

        @Test
        fun `ida y vuelta con varios registros`() {
            val msg = NdefMessage(
                NdefRecord.uri("https://www.marcmayol.com"),
                NdefRecord.external("Rodet.App", "Spool", "x".toByteArray()),
                NdefRecord.mime("application/json", ByteArray(400) { 7 }),
            )
            val parsed = NdefMessage.parse(msg.toByteArray())
            assertEquals(msg, parsed)
            assertEquals("rodet.app:spool", parsed.records[1].typeString)
            assertEquals(2, parsed.records[0].payload[0].toInt(), "prefijo https://www.")
        }

        @Test
        fun `TLV corto y largo`() {
            assertEquals("0302AABBFE", NdefTlv.wrap("AABB".hexToBytes()).toHex())
            val long = NdefTlv.wrap(ByteArray(300))
            assertEquals("03FF012C", long.copyOfRange(0, 4).toHex())
            assertEquals(0xFE, long.last().toInt() and 0xFF)
            assertContentEquals(ByteArray(300), NdefTlv.unwrap(byteArrayOf(0, 0) + long))
        }

        @Test
        fun `capacidad de cada chip`() {
            val small = NdefMessage(NdefRecord.mime("application/json", ByteArray(130)))
            val big = NdefMessage(NdefRecord.mime("application/json", ByteArray(300)))
            assertFalse(TagCapacity.NTAG213.fits(small))
            assertTrue(TagCapacity.NTAG215.fits(small))
            assertTrue(TagCapacity.NTAG215.fits(big))
            assertFalse(TagCapacity.NTAG213.fits(big))
        }

        @Test
        fun `NDEF corrupto da error de formato`() {
            assertThrows<TagFormatException> { NdefMessage.parse("D106".hexToBytes()) }
            assertThrows<TagFormatException> { NdefMessage.parse("B10100".hexToBytes()) }
        }
    }

    @Nested
    inner class Layouts {
        @Test
        fun `rechaza campos solapados o fuera del tamano`() {
            assertThrows<IllegalArgumentException> {
                BinaryLayout(8, listOf(LayoutField("a", 0, 4, FieldEncoding.RAW), LayoutField("b", 3, 2, FieldEncoding.RAW)))
            }
            assertThrows<IllegalArgumentException> { BinaryLayout(4, listOf(LayoutField("a", 2, 4, FieldEncoding.RAW))) }
        }

        @Test
        fun `endianness y escala`() {
            val l = BinaryLayout(
                6,
                listOf(
                    LayoutField("le", 0, 2, FieldEncoding.UINT_LE),
                    LayoutField("be", 2, 2, FieldEncoding.UINT_BE),
                    LayoutField("d", 4, 2, FieldEncoding.UINT_BE, scale = 100),
                ),
            )
            val bytes = l.encode(mapOf("le" to 0x1234, "be" to 0x1234, "d" to 1.75))
            assertEquals("3412123400AF", bytes.toHex())
            val back = l.decode(bytes)
            assertEquals(0x1234L, back["le"])
            assertEquals(1.75, back["d"])
        }

        @Test
        fun `un valor que no cabe falla antes de escribir`() {
            val l = BinaryLayout(4, listOf(LayoutField("s", 0, 3, FieldEncoding.ASCII), LayoutField("n", 3, 1, FieldEncoding.UINT_LE)))
            assertThrows<IllegalArgumentException> { l.encode(mapOf("s" to "PETG")) }
            assertThrows<IllegalArgumentException> { l.encode(mapOf("n" to 256)) }
            assertThrows<IllegalStateException> { l.encode(mapOf("otro" to 1)) }
        }

        @Test
        fun `codigo decimal escrito como hex, alineado a la derecha`() {
            val l = BinaryLayout(4, listOf(LayoutField("t", 0, 4, FieldEncoding.ASCII_DECIMAL_AS_HEX)))
            assertEquals("00807665", l.encode(mapOf("t" to "PLA")).toHex())
            assertEquals("80698471", l.encode(mapOf("t" to "PETG")).toHex())
            assertEquals("PLA", l.decode("00807665".hexToBytes())["t"])
        }
    }

    @Nested
    inner class Mifare {
        private val defaultTrailer = "FFFFFFFFFFFFFF078069FFFFFFFFFFFF".hexToBytes()

        @Test
        fun `bits de acceso de fabrica son validos y uno cambiado no`() {
            assertTrue(MifareTrailer.accessBitsValid(defaultTrailer))
            val broken = defaultTrailer.copyOf().also { it[7] = 0x08 }
            assertFalse(MifareTrailer.accessBitsValid(broken))
        }

        @Test
        fun `cambiar claves conserva bits de acceso y byte de usuario`() {
            val keyA = "A1A2A3A4A5A6".hexToBytes()
            val keyB = "B1B2B3B4B5B6".hexToBytes()
            val out = MifareTrailer.withKeys(defaultTrailer, keyA, keyB)
            assertEquals("A1A2A3A4A5A6FF078069B1B2B3B4B5B6", out.toHex())
        }

        @Test
        fun `nunca se escribe a ciegas un trailer con bits invalidos`() {
            val broken = defaultTrailer.copyOf().also { it[6] = 0 }
            assertThrows<IllegalArgumentException> { MifareTrailer.withKeys(broken, ByteArray(6), ByteArray(6)) }
        }

        @Test
        fun `ni el bloque 0 ni los trailers son escribibles`() {
            listOf(0, 3, 7, 11, 63, 64).forEach { b ->
                assertThrows<IllegalArgumentException>("bloque $b") { BlockWrite(b, ByteArray(16)) }
            }
            assertThrows<IllegalArgumentException> { BlockWrite(4, ByteArray(15)) }
            BlockWrite(4, ByteArray(16))
            assertThrows<IllegalArgumentException> { SectorKeyChange(0, ByteArray(6), ByteArray(6)) }
        }

        @Test
        fun `clave de sector del CFS - UID repetido cifrado y primeros 6 bytes`() {
            val key = ByteArray(16) { it.toByte() } // clave de prueba, no la de Creality
            val uid = "DEADBEEF".hexToBytes()
            val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            val expected = cipher.doFinal("DEADBEEFDEADBEEFDEADBEEFDEADBEEF".hexToBytes()).copyOf(6)
            assertContentEquals(expected, CrealityCrypto.sectorKey(uid, key))
            assertThrows<IllegalArgumentException> { CrealityCrypto.sectorKey(ByteArray(7), key) }
        }

        @Test
        fun `AES-128 coincide con el vector FIPS-197`() {
            val key = "000102030405060708090A0B0C0D0E0F".hexToBytes()
            val block = "00112233445566778899AABBCCDDEEFF".hexToBytes()
            val payload = block + block + block
            val enc = CrealityCrypto.encryptPayload(payload, key)
            assertEquals("69C4E0D86A7B0430D8CDB78070B4C55A", enc.copyOf(16).toHex())
            assertContentEquals(payload, CrealityCrypto.decryptPayload(enc, key))
        }
    }

    @Test
    fun `ningun modulo core importa android`() {
        val core = File("..").canonicalFile
        val offenders = core.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import android.") } }
            .toList()
        assertTrue(core.resolve("model").isDirectory && core.resolve("extract").isDirectory, "no encuentro core/ desde ${File(".").canonicalPath}")
        assertEquals(emptyList(), offenders)
    }
}
