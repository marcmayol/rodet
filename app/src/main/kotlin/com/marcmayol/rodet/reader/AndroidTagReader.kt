package com.marcmayol.rodet.reader

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcV
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.tags.CrealityCrypto
import com.marcmayol.rodet.tags.MifareClassic1K
import com.marcmayol.rodet.tags.MifareTrailer
import com.marcmayol.rodet.tags.NdefMessage
import com.marcmayol.rodet.tags.ProfileSettings
import com.marcmayol.rodet.tags.Profiles
import com.marcmayol.rodet.tags.TagRead
import com.marcmayol.rodet.tags.toHex
import java.io.ByteArrayOutputStream
import java.io.Closeable

/** Todo lo que se sabe de un tag tras leerlo. */
data class TagReport(
    val uid: String,
    val techs: List<String>,
    val identified: List<Identified>,
    val dump: String,
    val errors: List<String>,
    val millis: Long,
    /** Bloques MIFARE Classic leidos, en crudo; los usa el clonado. */
    val mifareBlocks: Map<Int, ByteArray> = emptyMap(),
) {
    data class Identified(val profile: String, val verified: Boolean, val spec: FilamentSpec)
}

/**
 * Lee un `android.nfc.Tag` con todas las tecnologias que ofrece y pasa lo
 * leido a los perfiles de `core:tags`. Solo lee: no escribe ni un byte.
 */
class AndroidTagReader(private val settings: ProfileSettings) {

    fun read(tag: Tag): TagReport {
        val start = System.currentTimeMillis()
        val uid = tag.id
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val out = StringBuilder()
        val errors = mutableListOf<String>()
        val reads = mutableListOf<TagRead>()

        out.appendLine("UID ${uid.toHex()} (${uid.size} B)")
        out.appendLine("Tecnologias: ${techs.joinToString()}")

        step("NDEF", errors) { readNdef(tag, out)?.let(reads::add) }
        step("MIFARE Classic", errors) { readMifareClassic(tag, out)?.let(reads::add) }
        step("Tipo 2", errors) { readType2(tag, out)?.let(reads::add) }
        step("NFC-V", errors) { readNfcV(tag, out)?.let(reads::add) }

        val identified = reads.mapNotNull { r ->
            Profiles.identify(r, settings)?.let { (p, spec) -> TagReport.Identified(p.name, p.verified, spec) }
        }.distinctBy { it.profile }

        val mifare = reads.filterIsInstance<TagRead.MifareClassic>().firstOrNull()?.blocks.orEmpty()
        return TagReport(uid.toHex(), techs, identified, out.toString(), errors, System.currentTimeMillis() - start, mifare)
    }

    private inline fun step(name: String, errors: MutableList<String>, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            errors += "$name: ${e::class.simpleName}: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ NDEF

    private fun readNdef(tag: Tag, out: StringBuilder): TagRead? {
        val ndef = Ndef.get(tag) ?: return null
        out.appendLine()
        out.appendLine("== NDEF (${ndef.type}, max ${ndef.maxSize} B, escribible=${ndef.isWritable})")
        val msg = ndef.cachedNdefMessage ?: return null.also { out.appendLine("(vacio)") }
        val parsed = NdefMessage.parse(msg.toByteArray())
        parsed.records.forEachIndexed { i, r ->
            out.appendLine("#$i TNF=${r.tnf} tipo=${r.typeString} ${r.payload.size} B")
            val text = r.payload.toString(Charsets.UTF_8)
            if (text.all { it.code in 0x20..0x7E || it == '\n' }) out.appendLine("  $text") else out.appendLine("  ${r.payload.toHex()}")
        }
        return TagRead.Ndef(parsed)
    }

    // ------------------------------------------------------------------ MIFARE Classic

    private fun readMifareClassic(tag: Tag, out: StringBuilder): TagRead? {
        val mfc = MifareClassic.get(tag) ?: return null
        val uid = tag.id
        val derivation = settings.crealityDerivationKey
        val crealityKey = if (derivation != null && uid.size == 4) CrealityCrypto.sectorKey(uid, derivation) else null

        out.appendLine()
        out.appendLine("== MIFARE Classic (${mfc.size} B, ${mfc.sectorCount} sectores)")
        crealityKey?.let { out.appendLine("Clave Creality derivada: ${it.toHex()}") }

        val candidates = buildList {
            crealityKey?.let { add("creality" to it) }
            add("FFFFFFFFFFFF" to MifareClassic1K.KEY_DEFAULT)
            add("MAD" to MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY)
            add("NFC Forum" to MifareClassic.KEY_NFC_FORUM)
        }
        val blocks = mutableMapOf<Int, ByteArray>()
        mfc.closing {
            mfc.connect()
            for (sector in 0 until mfc.sectorCount) {
                // Los sectores que usan los formatos conocidos se prueban con todas
                // las claves; el resto solo con la de fabrica, para no alargar la lectura.
                val keys = if (sector <= 2) candidates else candidates.filter { it.first == "FFFFFFFFFFFF" }
                var used: String? = null
                for ((label, key) in keys) {
                    if (runCatching { mfc.authenticateSectorWithKeyA(sector, key) }.getOrDefault(false)) {
                        used = label
                        break
                    }
                    // Tras un fallo de autenticacion el chip se queda parado: hay que reconectar.
                    reconnect(mfc)
                }
                if (used == null) {
                    out.appendLine("S$sector: sin clave conocida")
                    continue
                }
                out.appendLine("S$sector (clave A $used)")
                val first = mfc.sectorToBlock(sector)
                for (i in 0 until mfc.getBlockCountInSector(sector)) {
                    val b = first + i
                    val data = runCatching { mfc.readBlock(b) }.getOrNull()
                    if (data == null) {
                        out.appendLine("  B${b.toString().padStart(2)} (no legible)")
                        continue
                    }
                    blocks[b] = data
                    val note = when {
                        MifareClassic1K.isTrailer(b) -> "  trailer, bits de acceso ${if (MifareTrailer.accessBitsValid(data)) "OK" else "RAROS"}"
                        else -> "  " + printable(data)
                    }
                    out.appendLine("  B${b.toString().padStart(2)} ${data.toHex()}$note")
                }
            }
        }

        // Si hay clave de payload, se ensena tambien el texto descifrado del CFS.
        val payloadKey = settings.crealityPayloadKey
        val raw = (4..6).mapNotNull { blocks[it] }
        if (payloadKey != null && raw.size == 3) {
            val plain = CrealityCrypto.decryptPayload(raw.reduce { a, b -> a + b }, payloadKey)
            out.appendLine("Payload CFS descifrado: ${printable(plain)}")
        }
        return TagRead.MifareClassic(uid, blocks)
    }

    private fun reconnect(c: MifareClassic) {
        runCatching { c.close() }
        runCatching { c.connect() }
    }

    // ------------------------------------------------------------------ Tipo 2 (NTAG / Ultralight)

    private fun readType2(tag: Tag, out: StringBuilder): TagRead? {
        val ul = MifareUltralight.get(tag) ?: return null
        val bytes = ByteArrayOutputStream()
        var pages = 0
        ul.closing {
            ul.connect()
            // GET_VERSION dice el tamano real; leer a ciegas no sirve porque el
            // NTAG da la vuelta a la memoria en vez de fallar.
            val version = runCatching { ul.transceive(byteArrayOf(0x60)) }.getOrNull()
            pages = when (version?.getOrNull(6)?.toInt()) {
                0x0F -> 45 // NTAG213
                0x11 -> 135 // NTAG215
                0x13 -> 231 // NTAG216
                else -> 16 // Ultralight
            }
            if (version == null) {
                runCatching { ul.close() }
                ul.connect()
            }
            out.appendLine()
            out.appendLine("== Tipo 2: $pages paginas (version ${version?.toHex() ?: "?"})")
            var page = 0
            while (page < pages) {
                val chunk = ul.readPages(page) // 4 paginas
                val take = minOf(4, pages - page)
                bytes.write(chunk, 0, take * 4)
                page += 4
            }
        }
        val mem = bytes.toByteArray()
        for (p in 0 until pages) {
            val d = mem.copyOfRange(p * 4, p * 4 + 4)
            out.appendLine("P${p.toString().padStart(3)} ${d.toHex()}  ${printable(d)}")
        }
        return TagRead.Pages(4, mem.copyOfRange(16, mem.size))
    }

    // ------------------------------------------------------------------ NFC-V (ICODE SLIX2)

    private fun readNfcV(tag: Tag, out: StringBuilder): TagRead? {
        val v = NfcV.get(tag) ?: return null
        val uid = tag.id
        val mem = ByteArrayOutputStream()
        v.closing {
            v.connect()
            var total = 256
            var block = 0
            while (block < total) {
                // READ SINGLE BLOCK direccionado: flags 0x22, comando 0x20, UID, bloque.
                val cmd = byteArrayOf(0x22, 0x20) + uid + byteArrayOf(block.toByte())
                val resp = runCatching { v.transceive(cmd) }.getOrNull() ?: break
                if (resp.isEmpty() || resp[0].toInt() != 0) break
                val data = resp.copyOfRange(1, resp.size)
                mem.write(data)
                // El capability container dice cuantos bytes tiene el tag.
                if (block == 0 && data.size >= 3 && data[0] == 0xE1.toByte()) {
                    total = ((data[2].toInt() and 0xFF) * 8) / data.size
                }
                block++
            }
            out.appendLine()
            out.appendLine("== NFC-V: $block bloques (${v.maxTransceiveLength} max)")
        }
        val bytes = mem.toByteArray()
        bytes.toList().chunked(16).forEachIndexed { i, row ->
            val r = row.toByteArray()
            out.appendLine("${(i * 16).toString(16).padStart(4, '0')} ${r.toHex()}  ${printable(r)}")
        }
        return if (bytes.isEmpty()) null else TagRead.NfcV(bytes)
    }

    private fun printable(b: ByteArray) =
        b.joinToString("") { val c = it.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar().toString() else "." }

    private inline fun <T : Closeable> T.closing(block: () -> Unit) {
        try {
            block()
        } finally {
            runCatching { close() }
        }
    }
}
