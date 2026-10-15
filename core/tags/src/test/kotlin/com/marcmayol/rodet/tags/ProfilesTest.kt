package com.marcmayol.rodet.tags

import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.IssueCode
import com.marcmayol.rodet.model.Issue
import com.marcmayol.rodet.model.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {

    private val pla = FilamentSpec(
        material = "PLA", brand = "eSUN", colorName = "Fire Engine Red", colorHex = "CE2029",
        diameterMm = 1.75, netWeightG = 1000, spoolWeightG = 200, densityGCm3 = 1.24,
        nozzleTempMin = 205, nozzleTempMax = 225, bedTempMin = 60, bedTempMax = 80,
        dryTempC = 50, dryTimeH = 6,
    )

    private fun page(w: TagWrite, n: Int) = (w as TagWrite.Pages).pages.getValue(n).toHex()

    /** Reconstruye la memoria desde la pagina 4 como la leeria el movil. */
    private fun asRead(w: TagWrite): TagRead.Pages {
        val pages = (w as TagWrite.Pages).pages
        val mem = ByteArray((pages.keys.max() - 3) * 4)
        pages.forEach { (p, d) -> d.copyInto(mem, (p - 4) * 4) }
        return TagRead.Pages(4, mem)
    }

    private fun assertError(expected: ProfileError, block: () -> Unit): ProfileException {
        val e = assertThrows<ProfileException> { block() }
        assertEquals(expected, e.error)
        return e
    }

    @Nested
    inner class Comunes {
        @Test
        fun `nunca se codifica una bobina con errores bloqueantes`() {
            val e = assertError(ProfileError.INVALID_SPEC) { OpenSpoolProfile.encode(pla.copy(material = "")) }
            @Suppress("UNCHECKED_CAST")
            assertTrue((e.args as List<Issue>).any { it.code == IssueCode.MISSING_MATERIAL })
        }

        @Test
        fun `el color es obligatorio si el perfil lo exige`() {
            val e = assertError(ProfileError.INVALID_SPEC) { AnycubicAceProfile.encode(pla.copy(colorHex = "")) }
            @Suppress("UNCHECKED_CAST")
            assertTrue((e.args as List<Issue>).any { it.code == IssueCode.MISSING_COLOR })
            // OpenPrintTag no lo exige.
            OpenPrintTagProfile.encode(pla.copy(colorHex = ""))
        }

        @Test
        fun `solo OpenSpool y el aviso de Bambu estan verificados`() {
            val verified = Profiles.all.filter { it.verified }.map { it.id }.toSet()
            assertEquals(setOf("openspool", "bambu", "creality_cfs"), verified)
            Profiles.all.filter { !it.verified }.forEach { assertNotNull(it.unverifiedReason, it.id) }
            Profiles.all.forEach { assertTrue(it.reference.isNotBlank(), it.id) }
        }

        @Test
        fun `cada maquina apunta a un perfil y todos los perfiles tienen maquina`() {
            Machine.entries.forEach { assertNotNull(Profiles.byId(it.profileId), it.name) }
            assertEquals(Profiles.all.map { it.id }.toSet(), Machine.entries.map { it.profileId }.toSet())
            assertEquals(Machine.CREALITY_CFS, Machine.byName("CREALITY_CFS"))
            assertNull(Machine.byName("K3"))
        }

        @Test
        fun `ids unicos`() {
            assertEquals(Profiles.all.size, Profiles.all.map { it.id }.toSet().size)
            assertEquals(CrealityCfsProfile, Profiles.byId("creality_cfs"))
        }
    }

    @Nested
    inner class Disponibilidad {
        private val sinNxp = DeviceCapabilities(hasNfc = true, supportsMifareClassic = false)
        private val pixel = DeviceCapabilities(hasNfc = true, supportsMifareClassic = true)

        @Test
        fun `sin chip NXP los perfiles MIFARE salen deshabilitados con motivo`() {
            assertEquals(Availability.Disabled(DisabledReason.NO_MIFARE_CLASSIC), Profiles.availability(CrealityCfsProfile, sinNxp))
            assertEquals(Availability.Disabled(DisabledReason.NO_MIFARE_CLASSIC), Profiles.availability(QidiBoxProfile, sinNxp))
            assertEquals(Availability.Available, Profiles.availability(OpenSpoolProfile, sinNxp))
            assertEquals(Availability.Available, Profiles.availability(CrealityCfsProfile, pixel))
        }

        @Test
        fun `sin NFC nada se puede escribir`() {
            val none = DeviceCapabilities(hasNfc = false, supportsMifareClassic = false)
            Profiles.all.filter { it.canWrite }.forEach {
                assertEquals(Availability.Disabled(DisabledReason.NO_NFC), Profiles.availability(it, none), it.id)
            }
        }

        @Test
        fun `Bambu nunca esta disponible, ni en un movil con NXP`() {
            assertEquals(Availability.Disabled(DisabledReason.FORMAT_NOT_WRITABLE), Profiles.availability(BambuProfile, pixel))
        }
    }

    @Nested
    inner class OpenSpool {
        @Test
        fun `JSON con numeros, hex sin almohadilla y marca`() {
            val w = OpenSpoolProfile.encode(pla) as TagWrite.Ndef
            val record = w.message.records.single()
            assertEquals("application/json", record.typeString)
            val obj = Json.parseToJsonElement(record.payload.decodeToString()).jsonObject
            assertEquals("openspool", obj["protocol"]!!.let { (it as JsonPrimitive).content })
            assertEquals("1.0", (obj["version"] as JsonPrimitive).content)
            assertEquals("CE2029", (obj["color_hex"] as JsonPrimitive).content)
            assertFalse((obj["min_temp"] as JsonPrimitive).isString)
            assertEquals(205, (obj["min_temp"] as JsonPrimitive).content.toInt())
            assertEquals("eSUN", (obj["brand"] as JsonPrimitive).content)
            assertEquals(TagCapacity.NTAG215, w.minimum)
        }

        @Test
        fun `sin marca pone Generic y la fibra va en el tipo`() {
            val w = OpenSpoolProfile.encode(pla.copy(brand = "", material = "PETG", subtype = "CF Matte")) as TagWrite.Ndef
            val obj = Json.parseToJsonElement(w.message.records.single().payload.decodeToString()).jsonObject
            assertEquals("Generic", (obj["brand"] as JsonPrimitive).content)
            assertEquals("PETG-CF", (obj["type"] as JsonPrimitive).content)
            assertEquals("Matte", (obj["subtype"] as JsonPrimitive).content)
        }

        @Test
        fun `sin temperaturas de boquilla no se escribe`() {
            assertError(ProfileError.MISSING_FIELD) { OpenSpoolProfile.encode(pla.copy(nozzleTempMin = null)) }
        }

        @Test
        fun `ida y vuelta con procedencia TAG`() {
            val w = OpenSpoolProfile.encode(pla.copy(subtype = "CF")) as TagWrite.Ndef
            val back = OpenSpoolProfile.decode(TagRead.Ndef(w.message))!!
            assertEquals("PLA", back.material)
            assertEquals("CF", back.subtype)
            assertEquals("CE2029", back.colorHex)
            assertEquals(225, back.nozzleTempMax)
            assertEquals(80, back.bedTempMax)
            assertEquals(1.75, back.diameterMm)
            assertEquals(Source.TAG, back.provenance[Field.COLOR_HEX]?.source)
        }

        @Test
        fun `lee la variante documentada con textos y almohadilla`() {
            val json = """{"protocol":"openspool","version":"1.0","type":"PLA","color_hex":"#FFAABB","brand":"Generic","min_temp":"220","max_temp":"240"}"""
            val msg = NdefMessage(NdefRecord.mime("application/json", json.toByteArray()))
            val spec = OpenSpoolProfile.decode(TagRead.Ndef(msg))!!
            assertEquals("FFAABB", spec.colorHex)
            assertEquals(220, spec.nozzleTempMin)
        }

        @Test
        fun `un JSON de otro protocolo no es OpenSpool`() {
            val msg = NdefMessage(NdefRecord.mime("application/json", """{"protocol":"otro"}""".toByteArray()))
            assertNull(OpenSpoolProfile.decode(TagRead.Ndef(msg)))
        }
    }

    @Nested
    inner class OpenPrintTag {
        private val sample = javaClass.getResourceAsStream("/openprinttag/sample_tag.bin")!!.readBytes()

        @Test
        fun `decodifica la muestra oficial de Prusa`() {
            val spec = OpenPrintTagProfile.decode(TagRead.NfcV(sample))!!
            assertEquals("PLA", spec.material)
            assertEquals("Prusament", spec.brand)
            assertEquals("PLA Galaxy Black", spec.notes)
            assertEquals("3D3E3D", spec.colorHex)
            assertEquals(1000, spec.netWeightG)
            assertEquals(100, spec.spoolWeightG)
            assertEquals(205, spec.nozzleTempMin)
            assertEquals(220, spec.nozzleTempMax)
            assertEquals(40, spec.bedTempMin)
            assertEquals(60, spec.bedTempMax)
        }

        @Test
        fun `la memoria generada tiene la misma estructura que la muestra`() {
            val mem = (OpenPrintTagProfile.encode(pla) as TagWrite.NfcV).memory
            assertEquals(sample.size, mem.size)
            // CC, cabecera TLV larga y cabecera del registro: identicas a la referencia.
            assertEquals(sample.copyOfRange(0, 42).toHex(), mem.copyOfRange(0, 42).toHex())
            // Meta {2: 226} y region aux vacia en el mismo sitio.
            assertEquals("A10218E2", mem.copyOfRange(42, 46).toHex())
            assertEquals(0xA0, mem[268].toInt() and 0xFF)
            assertEquals(0xFE, mem.last().toInt() and 0xFF)
        }

        @Test
        fun `ida y vuelta, con densidad, diametro en micras y secado en minutos`() {
            val w = OpenPrintTagProfile.encode(pla) as TagWrite.NfcV
            val back = OpenPrintTagProfile.decode(TagRead.NfcV(w.memory))!!
            assertEquals(1.24, back.densityGCm3)
            assertEquals(1.75, back.diameterMm)
            assertEquals(6, back.dryTimeH)
            assertEquals(200, back.spoolWeightG)
            val main = OpenPrintTagProfile.mainSection(pla)
            assertEquals(1750L, main[61].asLong())
            assertEquals(360L, main[58].asLong())
            assertEquals(0L, main[8].asLong())
            assertEquals(0L, main[9].asLong())
        }

        @Test
        fun `trampa 10 - dos escrituras del mismo filamento dan los mismos bytes`() {
            val a = (OpenPrintTagProfile.encode(pla) as TagWrite.NfcV).memory
            val b = (OpenPrintTagProfile.encode(pla.copy(provenance = mapOf())) as TagWrite.NfcV).memory
            assertContentEquals(a, b)
        }

        @Test
        fun `PA no tiene tipo en el enum pero conserva la abreviatura`() {
            val main = OpenPrintTagProfile.mainSection(pla.copy(material = "PA"))
            assertNull(main[9])
            assertEquals("PA", main[52].asText())
        }

        @Test
        fun `PLA+ se escribe como PLA con su nombre`() {
            val main = OpenPrintTagProfile.mainSection(pla.copy(material = "PLA+"))
            assertEquals(0L, main[9].asLong())
            assertEquals("PLA+ Fire Engine Red", main[10].asText())
        }

        @Test
        fun `un nombre demasiado largo se recorta por bytes UTF-8`() {
            val main = OpenPrintTagProfile.mainSection(pla.copy(brand = "Marca ñññññññññññññññññññññññññññ"))
            assertTrue(main[11].asText()!!.toByteArray().size <= 31)
        }
    }

    @Nested
    inner class Anycubic {
        private val w = AnycubicAceProfile.encode(pla)

        @Test
        fun `solo escribe las paginas del escritor de referencia`() {
            val expected = (4..18) + listOf(20, 24, 29, 30, 31)
            assertEquals(expected, (w as TagWrite.Pages).pages.keys.sorted())
        }

        @Test
        fun `bytes por pagina`() {
            assertEquals("7B006500", page(w, 4))
            assertEquals("6553554E", page(w, 10)) // "eSUN"
            assertEquals("00000000", page(w, 11))
            assertEquals("504C4100", page(w, 15))
            assertEquals("FF2920CE", page(w, 20)) // A, B, G, R
            assertEquals("CD00E100", page(w, 24)) // 205, 225 LE
            assertEquals("3C005000", page(w, 29)) // 60, 80 LE
            assertEquals("AF004A01", page(w, 30)) // 175, 330 LE
            assertEquals("E8030000", page(w, 31)) // 1000 g
        }

        @Test
        fun `el negro puro se escribe como 010101`() {
            assertEquals("FF010101", page(AnycubicAceProfile.encode(pla.copy(colorHex = "000000")), 20))
        }

        @Test
        fun `PLA+ y alta velocidad van en el tipo`() {
            val t = AnycubicAceProfile.encode(pla.copy(material = "PLA+", subtype = "HS"))
            val back = AnycubicAceProfile.decode(asRead(t))!!
            assertEquals("PLA+", back.material)
            assertEquals("HS", back.subtype)
        }

        @Test
        fun `ida y vuelta`() {
            val back = AnycubicAceProfile.decode(asRead(w))!!
            assertEquals("PLA", back.material)
            assertEquals("eSUN", back.brand)
            assertEquals("CE2029", back.colorHex)
            assertEquals(205, back.nozzleTempMin)
            assertEquals(80, back.bedTempMax)
            assertEquals(1.75, back.diameterMm)
            assertEquals(1000, back.netWeightG)
        }

        @Test
        fun `otro contenido no se reconoce como Anycubic`() {
            assertNull(AnycubicAceProfile.decode(TagRead.Pages(4, ByteArray(112))))
        }
    }

    @Nested
    inner class Elegoo {
        private val w = ElegooProfile.encode(pla.copy(material = "PLA+", nozzleTempMin = 210, nozzleTempMax = 240))

        @Test
        fun `paginas y bytes`() {
            assertEquals((4..24).toList(), (w as TagWrite.Pages).pages.keys.sorted())
            assertEquals("00000000", page(w, 4))
            assertEquals("36EEEEEE", page(w, 16))
            assertEquals("EE000000", page(w, 17))
            assertEquals("00807665", page(w, 18))
            assertEquals("00010000", page(w, 19)) // PLA, subtipo "+"
            assertEquals("CE2029FF", page(w, 20))
            assertEquals("00D200F0", page(w, 21))
            assertEquals("00000000", page(w, 22))
            assertEquals("00AF03E8", page(w, 23))
            assertEquals("0036C800", page(w, 24))
        }

        @Test
        fun `subtipos de PLA`() {
            assertEquals("00030000", page(ElegooProfile.encode(pla.copy(subtype = "Silk")), 19))
            assertEquals("02000000", page(ElegooProfile.encode(pla.copy(material = "ABS", subtype = "Silk")), 19))
        }

        @Test
        fun `material sin id propone OpenSpool`() {
            val e = assertError(ProfileError.UNSUPPORTED_MATERIAL) { ElegooProfile.encode(pla.copy(material = "PCTG")) }
            assertEquals("openspool", e.alternativeProfileId)
        }

        @Test
        fun `lee un tag de fabrica aunque las paginas 4-15 lleven una URI`() {
            val read = asRead(w)
            val withUri = read.data.copyOf().also { "D1010F55".hexToBytes().copyInto(it, 0) }
            val back = ElegooProfile.decode(TagRead.Pages(4, withUri))!!
            assertEquals("PLA+", back.material)
            assertEquals("CE2029", back.colorHex)
            assertEquals(240, back.nozzleTempMax)
            assertEquals(1000, back.netWeightG)
        }
    }

    @Nested
    inner class Qidi {
        @Test
        fun `codigos del cfg oficial`() {
            assertEquals(7, QidiBoxProfile.materialCode(pla))
            assertEquals(4, QidiBoxProfile.materialCode(pla.copy(subtype = "Silk")))
            assertEquals(39, QidiBoxProfile.materialCode(pla.copy(material = "PETG")))
            assertEquals(42, QidiBoxProfile.materialCode(pla.copy(material = "PETG", subtype = "Matte CF")))
            assertEquals(7, QidiBoxProfile.materialCode(pla.copy(material = "PLA+")))
            assertNull(QidiBoxProfile.materialCode(pla.copy(material = "PEEK")))
        }

        @Test
        fun `color mas cercano de la paleta de 24`() {
            assertEquals(1, QidiBoxProfile.nearestColor("FFFFFF"))
            assertEquals(2, QidiBoxProfile.nearestColor("000000"))
            assertEquals(18, QidiBoxProfile.nearestColor("FF3030"))
            QidiBoxProfile.COLORS.forEach { (id, hex) -> assertEquals(id, QidiBoxProfile.nearestColor(hex)) }
        }

        @Test
        fun `escribe los bloques 4 a 6 con la clave de fabrica y nada mas`() {
            val w = QidiBoxProfile.encode(pla.copy(brand = "QIDI")) as TagWrite.MifareClassic
            assertEquals(listOf(4, 5, 6), w.blocks.map { it.block })
            assertEquals("07120100000000000000000000000000", w.blocks[0].data.toHex())
            assertTrue(w.keyChanges.isEmpty())
            assertContentEquals(MifareClassic1K.KEY_DEFAULT, w.authKeys.getValue(1).single())
        }

        @Test
        fun `ida y vuelta, tambien con la marca QIDI de DnG`() {
            val w = QidiBoxProfile.encode(pla.copy(subtype = "Silk")) as TagWrite.MifareClassic
            val back = QidiBoxProfile.decode(TagRead.MifareClassic(ByteArray(4), mapOf(4 to w.blocks[0].data)))!!
            assertEquals("PLA", back.material)
            assertEquals("Silk", back.subtype)
            val dng = w.blocks[0].data.copyOf().also { "QIDI".toByteArray().copyInto(it, 12) }
            assertNotNull(QidiBoxProfile.decode(TagRead.MifareClassic(ByteArray(4), mapOf(4 to dng))))
            assertNull(QidiBoxProfile.decode(TagRead.MifareClassic(ByteArray(4), mapOf(4 to ByteArray(16)))))
        }
    }

    @Nested
    inner class Creality {
        // Claves de PRUEBA. Las reales no estan en el repositorio.
        private val settings = ProfileSettings(
            crealityDerivationKey = ByteArray(16) { (it + 1).toByte() },
            crealityPayloadKey = ByteArray(16) { (0x40 + it).toByte() },
        )
        private val uid = "04A1B2C3".hexToBytes()
        private val ctx = WriteContext(uid, settings)

        private fun write(spec: FilamentSpec, c: WriteContext = ctx) =
            CrealityCfsProfile.write(spec, c, checkKeys = false) as TagWrite.MifareClassic

        private fun plainOf(w: TagWrite.MifareClassic) =
            CrealityCrypto.decryptPayload(w.blocks.fold(ByteArray(0)) { a, b -> a + b.data }, settings.crealityPayloadKey!!).decodeToString()

        @Test
        fun `payload en claro con el formato de K2-RFID`() {
            val text = CrealityCfsProfile.plaintext(pla.copy(serial = "42"), "00001")
            assertEquals(48, text.length)
            assertEquals("AB124" + "0276" + "A2" + "1" + "00001" + "0" + "CE2029" + "0330" + "000042" + "0".repeat(14), text)
        }

        @Test
        fun `longitud por peso nominal`() {
            assertEquals("0247", CrealityCfsProfile.plaintext(pla.copy(netWeightG = 750), "00001").substring(24, 28))
            assertEquals("0082", CrealityCfsProfile.plaintext(pla.copy(netWeightG = 250), "00001").substring(24, 28))
        }

        @Test
        fun `codigo generico para filamento que no es de Creality`() {
            fun code(s: FilamentSpec) = CrealityCfsProfile.materialCode(s, ProfileSettings())
            assertEquals("00001", code(pla))
            assertEquals("00001", code(pla.copy(material = "PLA+")))
            assertEquals("00002", code(pla.copy(subtype = "Silk")))
            assertEquals("00006", code(pla.copy(subtype = "CF Matte")))
            assertEquals("00003", code(pla.copy(material = "PETG")))
            assertEquals("00027", code(pla.copy(material = "PETG", subtype = "GF")))
            assertEquals("00032", code(pla.copy(material = "PCTG")))
            assertEquals("00011", code(pla.copy(material = "PVA")))
            // PET-GF no tiene generico propio: se usa el del PET.
            assertEquals("00020", code(pla.copy(material = "PET", subtype = "GF")))
            assertNull(code(pla.copy(material = "PEEK")))
        }

        @Test
        fun `codigo de la gama de Creality si la marca es Creality`() {
            fun code(s: FilamentSpec) = CrealityCfsProfile.materialCode(s.copy(brand = "Creality"), ProfileSettings())
            assertEquals("01001", code(pla.copy(subtype = "HS")))
            assertEquals("04001", code(pla))
            assertEquals("06002", code(pla.copy(material = "PETG", subtype = "HS")))
            assertEquals("00008", code(pla.copy(material = "PA", subtype = "HS")), "sin Hyper PA: generico")
        }

        @Test
        fun `el codigo de ajustes manda`() {
            assertEquals("99001", CrealityCfsProfile.materialCode(pla, ProfileSettings(crealityMaterialCode = "99001")))
            assertEquals("00001", CrealityCfsProfile.materialCode(pla, ProfileSettings(crealityMaterialCode = "12")))
        }

        @Test
        fun `la tabla de Creality Print no tiene codigos repetidos y todos se describen`() {
            val codes = CrealityMaterials.all.map { it.code }
            assertEquals(codes.size, codes.toSet().size)
            assertTrue(CrealityMaterials.all.size >= 70)
            CrealityMaterials.all.forEach { m ->
                assertTrue(m.code.length == 5 && m.code.all(Char::isDigit), m.code)
                val (base, _) = CrealityMaterials.describe(m)
                assertTrue(base.isNotEmpty(), m.name)
            }
            assertEquals("PLA" to "HS", CrealityMaterials.describe(CrealityMaterials.byCode("01001")!!))
            assertEquals("PA" to "HS CF", CrealityMaterials.describe(CrealityMaterials.byCode("12003")!!))
            assertEquals("PLA" to "Silk", CrealityMaterials.describe(CrealityMaterials.byCode("00002")!!))
        }

        @Test
        fun `sin claves no se escribe`() {
            assertError(ProfileError.MISSING_KEY) { CrealityCfsProfile.encode(pla, WriteContext(uid)) }
        }

        @Test
        fun `con claves equivocadas tampoco`() {
            val e = assertError(ProfileError.WRONG_KEY) { CrealityCfsProfile.encode(pla, ctx) }
            assertEquals(listOf<Any>("derivation"), e.args)
        }

        @Test
        fun `solo UIDs de 4 bytes`() {
            assertError(ProfileError.INVALID_UID) { write(pla, ctx.copy(uid = ByteArray(7))) }
            assertError(ProfileError.INVALID_UID) { write(pla, ctx.copy(uid = null)) }
        }

        @Test
        fun `material sin codigo propone OpenSpool, salvo que se fuerce uno`() {
            val e = assertError(ProfileError.UNSUPPORTED_MATERIAL) { write(pla.copy(material = "PEEK")) }
            assertEquals("openspool", e.alternativeProfileId)
            val forced = ctx.copy(settings = settings.copy(crealityMaterialCode = "99001"))
            assertEquals("99001", plainOf(write(pla.copy(material = "PEEK"), forced)).substring(12, 17))
        }

        @Test
        fun `bloques 4-6 cifrados y claves como en el tag de fabrica`() {
            val w = write(pla)
            assertEquals(listOf(4, 5, 6), w.blocks.map { it.block })
            val sectorKey = CrealityCrypto.sectorKey(uid, settings.crealityDerivationKey!!)
            val change = w.keyChanges.single()
            assertEquals(1, change.sector)
            assertContentEquals(sectorKey, change.newKeyA)
            assertContentEquals(sectorKey, change.newKeyB, "clave B = derivada, como el tag de fabrica")
            // Primero la derivada (tag ya escrito) y despues la de fabrica.
            assertEquals(listOf(sectorKey.toHex(), "FFFFFFFFFFFF"), w.authKeys.getValue(1).map { it.toHex() })
            val cipher = w.blocks.fold(ByteArray(0)) { a, b -> a + b.data }
            assertFalse(cipher.decodeToString().startsWith("AB124"), "el payload tiene que ir cifrado")
            assertTrue(plainOf(w).startsWith("AB124"))
        }

        @Test
        fun `ida y vuelta leyendo con la clave de payload`() {
            val w = write(pla.copy(material = "PETG", serial = "123456"))
            val read = TagRead.MifareClassic(uid, w.blocks.associate { it.block to it.data })
            val back = CrealityCfsProfile.decode(read, settings)!!
            assertEquals("PETG", back.material)
            assertEquals("CE2029", back.colorHex)
            assertEquals(1000, back.netWeightG)
            assertEquals("123456", back.serial)
            assertEquals("", back.brand, "un codigo generico no es de Creality")
            assertEquals("Generic PETG", back.notes)
            // Sin la clave no se puede leer.
            assertNull(CrealityCfsProfile.decode(read))
        }

        @Test
        fun `lee un tag de fabrica con binario en la reserva`() {
            // Mismo reparto que el volcado real: la reserva acaba en 0x14 y ceros.
            val plain = ("9C6250276A2101001" + "06667AB0330000001000000").toByteArray() + byteArrayOf(0x14) + ByteArray(7)
            assertEquals(48, plain.size)
            val enc = CrealityCrypto.encryptPayload(plain, settings.crealityPayloadKey!!)
            val read = TagRead.MifareClassic(uid, (0 until 3).associate { 4 + it to enc.copyOfRange(it * 16, it * 16 + 16) })
            val back = CrealityCfsProfile.decode(read, settings)!!
            assertEquals("PLA", back.material)
            assertEquals("HS", back.subtype)
            assertEquals("Creality", back.brand)
            assertEquals("Hyper PLA", back.notes)
            assertEquals("6667AB", back.colorHex)
            assertEquals(1000, back.netWeightG)
        }

        @Test
        fun `lee un tag en claro sin claves`() {
            val plain = CrealityCfsProfile.plaintext(pla.copy(material = "ASA"), "19001").toByteArray()
            val read = TagRead.MifareClassic(uid, (0 until 3).associate { 4 + it to plain.copyOfRange(it * 16, it * 16 + 16) })
            val back = CrealityCfsProfile.decode(read)!!
            assertEquals("ASA", back.material)
            assertEquals("Creality", back.brand)
        }

        @Test
        fun `las claves se aceptan en hex o como lista de bytes`() {
            val k = ByteArray(16) { (it * 17).toByte() }
            assertContentEquals(k, CrealityKeys.parse(k.toHex()))
            assertContentEquals(k, CrealityKeys.parse(k.toHex().chunked(2).joinToString(" ")))
            assertContentEquals(k, CrealityKeys.parse(k.toHex().chunked(2).joinToString(":")))
            assertContentEquals(k, CrealityKeys.parse("{" + k.joinToString(", ") + "}"))
            assertContentEquals(k, CrealityKeys.parse(k.joinToString(",") { "0x%02x".format(it) }))
            assertNull(CrealityKeys.parse("0102"))
            assertNull(CrealityKeys.parse("hola"))
            assertFalse(CrealityKeys.isDerivationKey(k))
        }

        @Test
        fun `los ajustes no filtran las claves al imprimirse`() {
            assertFalse(settings.toString().contains("[B"))
            assertFalse(settings.toString().contains("0102"))
        }

        /**
         * Vector de un tag de fabrica (Hyper PLA "Very Peri", volcado de
         * OpenRFID test/tags/Creality). Necesita las claves reales, que no
         * estan en el repositorio: se pasan como `RODET_CREALITY_KEYS=derivacion:payload`.
         */
        @Test
        fun `vector real de un tag de fabrica`() {
            val env = System.getenv("RODET_CREALITY_KEYS")
            Assumptions.assumeTrue(env != null, "sin RODET_CREALITY_KEYS: se omite")
            val (derivation, payload) = env!!.split(':').map { CrealityKeys.parse(it)!! }
            assertTrue(CrealityKeys.isDerivationKey(derivation))
            assertTrue(CrealityKeys.isPayloadKey(payload))

            val realUid = "4D09E831".hexToBytes()
            assertEquals("2CC2910FDE9F", CrealityCrypto.sectorKey(realUid, derivation).toHex())

            val blocks = ("CDD4F85BB0C56B6314186DAFC324F839" + "64F40DAD7ACE7254B148173DA1C11D7D" + "AA075EE3941C32EE2C2AA29300C9C115")
                .hexToBytes().toList().chunked(16).mapIndexed { i, b -> 4 + i to b.toByteArray() }.toMap()
            val real = ProfileSettings(derivation, payload)
            val back = CrealityCfsProfile.decode(TagRead.MifareClassic(realUid, blocks), real)!!
            assertEquals("PLA", back.material)
            assertEquals("HS", back.subtype)
            assertEquals("Creality", back.brand)
            assertEquals("6667AB", back.colorHex)
            assertEquals(1000, back.netWeightG)
            assertEquals("000001", back.serial)

            // Y lo que escribiria Rodet para ese mismo tag pasa la comprobacion de claves.
            val w = CrealityCfsProfile.encode(pla.copy(brand = "Creality", subtype = "HS", colorHex = "6667AB"), WriteContext(realUid, real))
            assertEquals("2CC2910FDE9F", (w as TagWrite.MifareClassic).keyChanges.single().newKeyA.toHex())
        }

        /**
         * Tag propio leido con el Pixel el 18-sep-2026: Hyper PLA negro de 1 kg.
         * A diferencia del de OpenRFID, su trailer lleva la clave derivada
         * tambien como clave B y bits de acceso FF0780.
         */
        @Test
        fun `vector real de un tag propio, Hyper PLA negro`() {
            val env = System.getenv("RODET_CREALITY_KEYS")
            Assumptions.assumeTrue(env != null, "sin RODET_CREALITY_KEYS: se omite")
            val (derivation, payload) = env!!.split(':').map { CrealityKeys.parse(it)!! }

            val realUid = "70B4276B".hexToBytes()
            assertEquals("7CEB3433DEAD", CrealityCrypto.sectorKey(realUid, derivation).toHex())

            val blocks = ("48F1FC63BF87544D8F159E062658D35D" + "E07623E57AA4DFC8F23BB22F645DC64B" + "EBD624BA375CF4E1F9C56607A63B5FD7")
                .hexToBytes().toList().chunked(16).mapIndexed { i, b -> 4 + i to b.toByteArray() }.toMap()
            val back = CrealityCfsProfile.decode(TagRead.MifareClassic(realUid, blocks), ProfileSettings(derivation, payload))!!
            assertEquals("PLA", back.material)
            assertEquals("HS", back.subtype)
            assertEquals("Creality", back.brand)
            assertEquals("Hyper PLA", back.notes)
            assertEquals("000000", back.colorHex)
            assertEquals(1000, back.netWeightG)
            assertEquals("000001", back.serial)
        }
    }

    @Nested
    inner class Bambu {
        @Test
        fun `no se puede escribir y propone OpenSpool`() {
            assertFalse(BambuProfile.canWrite)
            val e = assertError(ProfileError.NOT_WRITABLE) { BambuProfile.encode(pla) }
            assertEquals("openspool", e.alternativeProfileId)
        }
    }

    @Test
    fun `identify reconoce cada formato por su contenido`() {
        val ndef = OpenSpoolProfile.encode(pla) as TagWrite.Ndef
        assertEquals(OpenSpoolProfile, Profiles.identify(TagRead.Ndef(ndef.message))!!.first)
        val nfcv = OpenPrintTagProfile.encode(pla) as TagWrite.NfcV
        assertEquals(OpenPrintTagProfile, Profiles.identify(TagRead.NfcV(nfcv.memory))!!.first)
        assertEquals(AnycubicAceProfile, Profiles.identify(asRead(AnycubicAceProfile.encode(pla)))!!.first)
        assertEquals(ElegooProfile, Profiles.identify(asRead(ElegooProfile.encode(pla)))!!.first)
        assertNull(Profiles.identify(TagRead.Pages(4, ByteArray(200))))
        assertIs<TagWrite.MifareClassic>(QidiBoxProfile.encode(pla))
    }
}
