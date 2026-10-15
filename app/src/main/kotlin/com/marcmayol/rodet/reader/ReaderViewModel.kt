package com.marcmayol.rodet.reader

import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.ViewModel
import com.marcmayol.rodet.BuildConfig
import com.marcmayol.rodet.tags.BlockWrite
import com.marcmayol.rodet.tags.CrealityCrypto
import com.marcmayol.rodet.tags.CrealityKeys
import com.marcmayol.rodet.tags.MifareClassic1K
import com.marcmayol.rodet.tags.ProfileSettings
import com.marcmayol.rodet.tags.SectorKeyChange
import com.marcmayol.rodet.tags.TagWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReaderState(
    val reading: Boolean = false,
    val crealityKeys: KeyStatus = KeyStatus.MISSING,
    val last: TagReport? = null,
    val count: Int = 0,
    /** Tag del CFS que se va a copiar en el siguiente tag en blanco que se acerque. */
    val clone: CloneSource? = null,
    val cloneResult: CloneResult? = null,
)

/** Los bloques 4-6 del CFS van cifrados con una clave fija: se copian tal cual. */
class CloneSource(val uid: String, val label: String, val blocks: List<ByteArray>)

data class CloneResult(val ok: Boolean, val message: String)

enum class KeyStatus { MISSING, WRONG, OK }

class ReaderViewModel : ViewModel() {

    private val derivation = CrealityKeys.parse(BuildConfig.CREALITY_DERIVATION_KEY)
    private val payload = CrealityKeys.parse(BuildConfig.CREALITY_PAYLOAD_KEY)
    private val settings = ProfileSettings(crealityDerivationKey = derivation, crealityPayloadKey = payload)
    private val reader = AndroidTagReader(settings)
    private val writer = AndroidTagWriter()

    private val _state = MutableStateFlow(
        ReaderState(
            crealityKeys = when {
                derivation == null || payload == null -> KeyStatus.MISSING
                CrealityKeys.isDerivationKey(derivation) && CrealityKeys.isPayloadKey(payload) -> KeyStatus.OK
                else -> KeyStatus.WRONG
            },
        ),
    )
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** Llega en el hilo del lector NFC: se lee aqui mismo, mientras el tag sigue cerca. */
    fun onTag(tag: Tag) {
        _state.update { it.copy(reading = true) }
        val report = reader.read(tag)
        log(report)
        _state.update { it.copy(reading = false, last = report, count = it.count + 1) }
        val source = _state.value.clone
        if (source != null && report.uid != source.uid) cloneInto(tag, report, source)
    }

    /** Solo se puede clonar un tag del CFS leido entero (bloques 4-6 descifrables). */
    fun canClone(r: TagReport): Boolean =
        r.identified.any { it.profile.startsWith("Creality") } && (4..6).all { it in r.mifareBlocks }

    fun armClone(r: TagReport) {
        if (!canClone(r)) return
        val spec = r.identified.first { it.profile.startsWith("Creality") }.spec
        val label = listOfNotNull(spec.notes.ifBlank { null } ?: spec.material, "#${spec.colorHex}").joinToString(" ")
        val source = CloneSource(r.uid, label, (4..6).map { r.mifareBlocks.getValue(it).copyOf() })
        Log.i(TAG, "CLONE armado desde ${r.uid}: $label")
        _state.update { it.copy(clone = source, cloneResult = null) }
    }

    fun disarmClone() = _state.update { it.copy(clone = null) }

    /**
     * Copia el payload cifrado y pone en el trailer de S1 la clave derivada del
     * UID NUEVO (A y B, como el tag de fabrica). Solo sobre un tag en blanco:
     * S1 legible con la clave de fabrica y los bloques 4-6 a cero.
     */
    private fun cloneInto(tag: android.nfc.Tag, target: TagReport, source: CloneSource) {
        val derivationKey = derivation
        val uid = tag.id
        val result = when {
            derivationKey == null -> CloneResult(false, "Faltan las claves de Creality")
            uid.size != 4 -> CloneResult(false, "UID de ${uid.size} B: el CFS solo acepta UIDs de 4 B")
            "MifareClassic" !in target.techs -> CloneResult(false, "${target.uid} no es MIFARE Classic")
            target.identified.isNotEmpty() -> CloneResult(false, "${target.uid} ya tiene datos (${target.identified.first().profile}): no se pisa")
            (4..6).any { b -> target.mifareBlocks[b]?.all { it == 0.toByte() } != true } ->
                CloneResult(false, "${target.uid} no esta en blanco (S1 no abre con FFFFFFFFFFFF o tiene datos)")
            else -> {
                val key = CrealityCrypto.sectorKey(uid, derivationKey)
                val write = TagWrite.MifareClassic(
                    authKeys = mapOf(1 to listOf(MifareClassic1K.KEY_DEFAULT, key)),
                    blocks = source.blocks.mapIndexed { i, data -> BlockWrite(4 + i, data) },
                    keyChanges = listOf(SectorKeyChange(1, key, key)),
                )
                Log.i(TAG, "CLONE ---- ${source.uid} -> ${target.uid}, clave ${key.joinToString("") { "%02X".format(it) }}")
                val w = writer.write(tag, write)
                w.log.lines().forEach { Log.i(TAG, "CLONE $it") }
                if (w.ok) CloneResult(true, "Clonado en ${target.uid}. Vuelve a acercarlo para comprobar que lo reconoce.")
                else CloneResult(false, "Fallo (${w.problem}): ${w.log.lines().lastOrNull { it.isNotBlank() }}")
            }
        }
        Log.i(TAG, "CLONE resultado: $result")
        // Tras cada intento se desarma, igual que la escritura normal.
        _state.update { it.copy(clone = if (result.ok) null else it.clone, cloneResult = result) }
    }

    /** Todo el volcado a logcat, para sacarlo con `adb logcat -s RodetReader`. */
    private fun log(r: TagReport) {
        val text = buildString {
            appendLine("---- tag ${r.uid} (${r.millis} ms)")
            r.identified.forEach { appendLine("Reconocido: ${it.profile} -> ${it.spec}") }
            r.errors.forEach { appendLine("ERROR $it") }
            append(r.dump)
        }
        text.lines().forEach { Log.i(TAG, it) }
    }

    companion object {
        const val TAG = "RodetReader"
    }
}
