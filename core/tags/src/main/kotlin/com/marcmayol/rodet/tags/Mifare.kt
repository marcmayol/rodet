package com.marcmayol.rodet.tags

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Reglas de MIFARE Classic 1K. Aqui el riesgo es real: un trailer con basura
 * inutiliza el sector para siempre, asi que todo lo que se puede comprobar
 * sin el movil se comprueba aqui.
 */
object MifareClassic1K {
    const val BLOCK_SIZE = 16
    const val KEY_SIZE = 6
    const val BLOCKS = 64
    val KEY_DEFAULT: ByteArray = ByteArray(KEY_SIZE) { 0xFF.toByte() }

    fun sectorOf(block: Int): Int = block / 4
    fun trailerOf(sector: Int): Int = sector * 4 + 3
    fun isTrailer(block: Int): Boolean = block % 4 == 3
    fun isManufacturer(block: Int): Boolean = block == 0

    /** Solo se pueden escribir bloques de datos: nunca el 0 ni un trailer. */
    fun isWritableDataBlock(block: Int): Boolean = block in 1 until BLOCKS && !isTrailer(block)
}

object MifareTrailer {
    /**
     * Los bits de acceso (bytes 6-8) llevan cada bit dos veces, una negada. Si
     * esa redundancia no cuadra, el chip bloquea el sector para siempre.
     */
    fun accessBitsValid(trailer: ByteArray): Boolean {
        if (trailer.size != MifareClassic1K.BLOCK_SIZE) return false
        val b6 = trailer[6].toInt() and 0xFF
        val b7 = trailer[7].toInt() and 0xFF
        val b8 = trailer[8].toInt() and 0xFF
        val c1 = b7 shr 4
        val c2 = b8 and 0x0F
        val c3 = b8 shr 4
        val notC1 = b6 and 0x0F
        val notC2 = b6 shr 4
        val notC3 = b7 and 0x0F
        return (c1 xor notC1) == 0x0F && (c2 xor notC2) == 0x0F && (c3 xor notC3) == 0x0F
    }

    /**
     * Sustituye las claves conservando los bits de acceso y el byte de usuario
     * del trailer leido. Se niega si ese trailer no tiene bits de acceso
     * validos: al leerlo algo fue mal y no se escribe a ciegas.
     */
    fun withKeys(currentTrailer: ByteArray, keyA: ByteArray, keyB: ByteArray): ByteArray {
        require(keyA.size == MifareClassic1K.KEY_SIZE && keyB.size == MifareClassic1K.KEY_SIZE) { "las claves miden 6 B" }
        require(accessBitsValid(currentTrailer)) { "bits de acceso invalidos en el trailer leido: no se escribe" }
        val out = currentTrailer.copyOf()
        keyA.copyInto(out, 0)
        keyB.copyInto(out, 10)
        check(accessBitsValid(out))
        return out
    }
}

/** Una escritura de bloque de datos, ya validada. */
class BlockWrite(val block: Int, val data: ByteArray) {
    init {
        require(MifareClassic1K.isWritableDataBlock(block)) { "el bloque $block no es de datos: no se escribe" }
        require(data.size == MifareClassic1K.BLOCK_SIZE) { "un bloque mide 16 B, no ${data.size}" }
    }
}

/**
 * Cambio de claves de un sector: el escritor lee el trailer, llama a
 * [MifareTrailer.withKeys] y solo entonces lo escribe. Si se autentico con
 * [newKeyA], el tag ya tiene las claves buenas y el trailer no se toca.
 */
class SectorKeyChange(val sector: Int, val newKeyA: ByteArray, val newKeyB: ByteArray) {
    init {
        require(sector in 1..15) { "el sector 0 no se toca" }
        require(newKeyA.size == 6 && newKeyB.size == 6)
    }
}

/**
 * Cifrado de las etiquetas del CFS de Creality, segun la ingenieria inversa
 * de DnG-Crafts/K2-RFID y OpenRFID. Las dos claves AES NO van en la app: las
 * rellena la persona en ajustes avanzados.
 */
object CrealityCrypto {
    const val PAYLOAD_SIZE = 48

    /** Clave de sector: AES-128-ECB del UID repetido hasta 16 B; se usan los 6 primeros. */
    fun sectorKey(uid: ByteArray, derivationKey: ByteArray): ByteArray {
        require(uid.size == 4) { "el CFS solo acepta UIDs de 4 B" }
        val block = ByteArray(16) { uid[it % 4] }
        return aes(Cipher.ENCRYPT_MODE, derivationKey, block).copyOf(MifareClassic1K.KEY_SIZE)
    }

    fun encryptPayload(plain: ByteArray, payloadKey: ByteArray): ByteArray {
        require(plain.size == PAYLOAD_SIZE) { "el payload del CFS mide $PAYLOAD_SIZE B" }
        return aes(Cipher.ENCRYPT_MODE, payloadKey, plain)
    }

    fun decryptPayload(encrypted: ByteArray, payloadKey: ByteArray): ByteArray {
        require(encrypted.size == PAYLOAD_SIZE)
        return aes(Cipher.DECRYPT_MODE, payloadKey, encrypted)
    }

    private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "clave AES-128 de 16 B" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}
