package com.marcmayol.rodet.reader

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcV
import com.marcmayol.rodet.tags.MifareClassic1K
import com.marcmayol.rodet.tags.MifareTrailer
import com.marcmayol.rodet.tags.TagWrite
import com.marcmayol.rodet.tags.toHex
import java.io.IOException

/** Por que fallo una escritura. La UI lo traduce y dice que hacer. */
enum class WriteProblem {
    /** El chip no es del tipo que necesita la maquina. */
    WRONG_TECH,

    /** El tag esta bloqueado contra escritura. */
    READ_ONLY,

    /** El mensaje no cabe en el tag. */
    TOO_SMALL,

    /** Ninguna clave conocida abre el sector: el tag no es virgen o es de otro sistema. */
    NO_KEY_OPENS,

    /** Se aparto el tag a mitad. */
    LOST_TAG,

    /** Se escribio, pero al releer no coincide. */
    VERIFY_FAILED,
    UNKNOWN,
}

/** Resultado de una escritura, con el registro paso a paso para el logcat. */
data class WriteReport(val ok: Boolean, val log: String, val problem: WriteProblem? = null)

/**
 * Ejecuta un [TagWrite] ya validado por `core:tags` y relee para comprobarlo.
 * En MIFARE Classic el orden importa: primero los bloques de datos y el
 * trailer al final, para que si algo falla a medias el sector siga abriendo
 * con la clave de antes.
 */
class AndroidTagWriter {

    private class Failure(val problem: WriteProblem, message: String) : Exception(message)

    fun write(tag: Tag, w: TagWrite): WriteReport {
        val out = StringBuilder()
        return try {
            when (w) {
                is TagWrite.MifareClassic -> writeMifareClassic(tag, w, out)
                is TagWrite.Ndef -> writeNdef(tag, w, out)
                is TagWrite.Pages -> writePages(tag, w, out)
                is TagWrite.NfcV -> writeNfcV(tag, w, out)
            }
            out.appendLine("ESCRITURA OK")
            WriteReport(true, out.toString())
        } catch (e: Failure) {
            out.appendLine("FALLO ${e.problem}: ${e.message}")
            WriteReport(false, out.toString(), e.problem)
        } catch (e: TagLostException) {
            out.appendLine("FALLO tag perdido: ${e.message}")
            WriteReport(false, out.toString(), WriteProblem.LOST_TAG)
        } catch (e: IOException) {
            // En la practica casi siempre es que el tag se ha movido.
            out.appendLine("FALLO ${e::class.simpleName}: ${e.message}")
            WriteReport(false, out.toString(), WriteProblem.LOST_TAG)
        } catch (e: Exception) {
            out.appendLine("FALLO ${e::class.simpleName}: ${e.message}")
            WriteReport(false, out.toString(), WriteProblem.UNKNOWN)
        }
    }

    // ------------------------------------------------------------------ NDEF (OpenSpool)

    private fun writeNdef(tag: Tag, w: TagWrite.Ndef, out: StringBuilder) {
        val bytes = w.message.toByteArray()
        val message = android.nfc.NdefMessage(bytes)
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            // Tag virgen sin formatear: se formatea escribiendo ya el mensaje.
            val f = NdefFormatable.get(tag) ?: throw Failure(WriteProblem.WRONG_TECH, "no admite NDEF")
            f.use { it.connect(); it.format(message) }
            out.appendLine("Formateado como NDEF con ${bytes.size} B")
            return
        }
        ndef.use {
            it.connect()
            if (!it.isWritable) throw Failure(WriteProblem.READ_ONLY, "bloqueado")
            if (bytes.size > it.maxSize) throw Failure(WriteProblem.TOO_SMALL, "${bytes.size} B en ${it.maxSize}; hace falta ${w.minimum.label}")
            it.writeNdefMessage(message)
            val back = it.ndefMessage?.toByteArray()
            if (back == null || !back.contentEquals(bytes)) throw Failure(WriteProblem.VERIFY_FAILED, "NDEF releido distinto")
            out.appendLine("NDEF escrito y verificado (${bytes.size} B de ${it.maxSize})")
        }
    }

    // ------------------------------------------------------------------ Tipo 2 (Anycubic, Elegoo)

    private fun writePages(tag: Tag, w: TagWrite.Pages, out: StringBuilder) {
        val ul = MifareUltralight.get(tag) ?: throw Failure(WriteProblem.WRONG_TECH, "no es NFC-A tipo 2")
        ul.use {
            it.connect()
            val pages = w.pages.toSortedMap()
            pages.forEach { (page, data) -> it.writePage(page, data) }
            out.appendLine("Paginas ${pages.firstKey()}-${w.lastPage} escritas (${pages.size})")
            pages.forEach { (page, data) ->
                val back = it.readPages(page).copyOf(4)
                if (!back.contentEquals(data)) throw Failure(WriteProblem.VERIFY_FAILED, "P$page releida ${back.toHex()}")
            }
            out.appendLine("Paginas verificadas")
        }
    }

    // ------------------------------------------------------------------ NFC-V (OpenPrintTag)

    private fun writeNfcV(tag: Tag, w: TagWrite.NfcV, out: StringBuilder) {
        val v = NfcV.get(tag) ?: throw Failure(WriteProblem.WRONG_TECH, "no es NFC-V")
        val uid = tag.id
        v.use {
            it.connect()
            val blocks = w.memory.size / w.blockSize
            fun block(b: Int) = w.memory.copyOfRange(b * w.blockSize, (b + 1) * w.blockSize)
            for (b in 0 until blocks) {
                // WRITE SINGLE BLOCK direccionado: flags 0x22, comando 0x21, UID, bloque, datos.
                val resp = it.transceive(byteArrayOf(0x22, 0x21) + uid + byteArrayOf(b.toByte()) + block(b))
                if (resp.isEmpty() || resp[0].toInt() != 0) throw Failure(WriteProblem.READ_ONLY, "bloque $b rechazado ${resp.toHex()}")
            }
            out.appendLine("$blocks bloques escritos")
            for (b in 0 until blocks) {
                val resp = it.transceive(byteArrayOf(0x22, 0x20) + uid + byteArrayOf(b.toByte()))
                val back = resp.copyOfRange(1, resp.size)
                if (!back.contentEquals(block(b))) throw Failure(WriteProblem.VERIFY_FAILED, "bloque $b releido ${back.toHex()}")
            }
            out.appendLine("Bloques verificados")
        }
    }

    // ------------------------------------------------------------------ MIFARE Classic (Creality, QIDI)

    private fun writeMifareClassic(tag: Tag, w: TagWrite.MifareClassic, out: StringBuilder) {
        val mfc = MifareClassic.get(tag) ?: throw Failure(WriteProblem.WRONG_TECH, "no es MIFARE Classic")
        mfc.use {
            it.connect()
            val sectors = (w.blocks.map { b -> MifareClassic1K.sectorOf(b.block) } + w.keyChanges.map { c -> c.sector }).toSortedSet()
            for (sector in sectors) {
                val used = auth(it, sector, w.authKeys.getValue(sector))
                    ?: throw Failure(WriteProblem.NO_KEY_OPENS, "ninguna clave abre S$sector")
                out.appendLine("S$sector abierto con ${used.toHex()}")

                w.blocks.filter { b -> MifareClassic1K.sectorOf(b.block) == sector }.forEach { b ->
                    it.writeBlock(b.block, b.data)
                    out.appendLine("  B${b.block} escrito")
                }

                val change = w.keyChanges.firstOrNull { c -> c.sector == sector } ?: continue
                if (used.contentEquals(change.newKeyA)) {
                    out.appendLine("  trailer: ya tiene la clave nueva, no se toca")
                    continue
                }
                val trailerBlock = MifareClassic1K.trailerOf(sector)
                val current = it.readBlock(trailerBlock)
                // withKeys se niega si los bits de acceso leidos no son validos.
                val trailer = MifareTrailer.withKeys(current, change.newKeyA, change.newKeyB)
                it.writeBlock(trailerBlock, trailer)
                out.appendLine("  trailer B$trailerBlock: ${current.toHex()} -> ${trailer.toHex()}")
            }

            // Verificacion: reabrir con la clave final y comparar byte a byte.
            for (sector in sectors) {
                val finalKey = w.keyChanges.firstOrNull { c -> c.sector == sector }?.newKeyA
                val keys = listOfNotNull(finalKey) + w.authKeys.getValue(sector)
                val used = auth(it, sector, keys)
                if (used == null || (finalKey != null && !used.contentEquals(finalKey))) {
                    throw Failure(WriteProblem.VERIFY_FAILED, "S$sector no abre con la clave nueva")
                }
                w.blocks.filter { b -> MifareClassic1K.sectorOf(b.block) == sector }.forEach { b ->
                    val back = it.readBlock(b.block)
                    if (!back.contentEquals(b.data)) throw Failure(WriteProblem.VERIFY_FAILED, "B${b.block} releido ${back.toHex()}")
                }
            }
            out.appendLine("Sectores verificados")
        }
    }

    /** Prueba las claves en orden; tras un fallo el chip se queda parado y hay que reconectar. */
    private fun auth(mfc: MifareClassic, sector: Int, keys: List<ByteArray>): ByteArray? {
        for (key in keys) {
            if (runCatching { mfc.authenticateSectorWithKeyA(sector, key) }.getOrDefault(false)) return key
            runCatching { mfc.close() }
            runCatching { mfc.connect() }
        }
        return null
    }
}
