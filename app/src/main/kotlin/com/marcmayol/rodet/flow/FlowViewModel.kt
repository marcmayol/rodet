package com.marcmayol.rodet.flow

import android.app.Application
import android.graphics.Bitmap
import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marcmayol.rodet.BuildConfig
import com.marcmayol.rodet.extract.LabelExtractor
import com.marcmayol.rodet.inventory.InventoryRepository
import com.marcmayol.rodet.inventory.Spool
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Issue
import com.marcmayol.rodet.model.NamedColor
import com.marcmayol.rodet.model.Provenance
import com.marcmayol.rodet.model.Source
import com.marcmayol.rodet.model.SpecEditing
import com.marcmayol.rodet.model.Validation
import com.marcmayol.rodet.reader.AndroidTagReader
import com.marcmayol.rodet.reader.AndroidTagWriter
import com.marcmayol.rodet.reader.WriteProblem
import com.marcmayol.rodet.tags.CrealityKeys
import com.marcmayol.rodet.tags.DeviceCapabilities
import com.marcmayol.rodet.tags.Machine
import com.marcmayol.rodet.tags.ProfileException
import com.marcmayol.rodet.tags.ProfileSettings
import com.marcmayol.rodet.tags.Profiles
import com.marcmayol.rodet.tags.TagTech
import com.marcmayol.rodet.tags.WriteContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Step { MACHINE, CAPTURE, REVIEW, WRITE }

enum class KeysState { NOT_NEEDED, MISSING, WRONG, OK }

sealed interface WriteStatus {
    data object Waiting : WriteStatus
    data object Working : WriteStatus
    data class Done(val readBack: FilamentSpec?, val uid: String = "") : WriteStatus

    /** [existing]: el formato que ya tenia el tag, si el fallo es que no estaba vacio. */
    data class Failed(val problem: WriteProblem?, val existing: String? = null, val profileError: ProfileException? = null) : WriteStatus
}

data class FlowState(
    val step: Step = Step.MACHINE,
    val machine: Machine? = null,
    val spec: FilamentSpec = FilamentSpec(),
    val ocrRunning: Boolean = false,
    val ocrText: String? = null,
    val ocrFailed: Boolean = false,
    val keys: KeysState = KeysState.NOT_NEEDED,
    val write: WriteStatus = WriteStatus.Waiting,
    val allowOverwrite: Boolean = false,
    val written: Int = 0,
    /** La bobina del inventario que corresponde a esta ficha, si ya se guardo. */
    val savedSpoolId: String? = null,
) {
    val issues: List<Issue> get() = Validation.validate(spec, requireColor = machine?.profile?.requiresColor == true)
}

class FlowViewModel(app: Application) : AndroidViewModel(app), SpecActions {

    private val store = DraftStore(app)
    private val inventory = InventoryRepository.get(app)
    private val writer = AndroidTagWriter()

    private val _photo = MutableStateFlow<Bitmap?>(null)
    val photo: StateFlow<Bitmap?> = _photo.asStateFlow()

    private val _state = MutableStateFlow(
        FlowState(
            machine = store.machine,
            step = if (store.machine == null) Step.MACHINE else store.step.let { if (it == Step.WRITE) Step.REVIEW else it },
            spec = store.spec ?: FilamentSpec(),
            ocrText = store.ocrText,
            savedSpoolId = store.savedSpoolId,
        ),
    )
    val state: StateFlow<FlowState> = _state.asStateFlow()

    init {
        // En depuracion se precargan las claves de local.properties si no hay otras.
        if (store.crealityDerivationKey.isBlank() && BuildConfig.CREALITY_DERIVATION_KEY.isNotBlank()) {
            store.crealityDerivationKey = BuildConfig.CREALITY_DERIVATION_KEY
            store.crealityPayloadKey = BuildConfig.CREALITY_PAYLOAD_KEY
        }
        refreshKeys()
        viewModelScope.launch(Dispatchers.IO) { _photo.value = store.loadPhoto() }
    }

    // ------------------------------------------------------------------ navegacion

    fun go(step: Step) {
        store.step = step
        _state.update { it.copy(step = step, write = WriteStatus.Waiting, allowOverwrite = false) }
    }

    /** Devuelve false si ya no hay paso anterior (salir de la app). */
    fun back(): Boolean {
        val prev = when (_state.value.step) {
            Step.MACHINE -> return false
            Step.CAPTURE -> Step.MACHINE
            Step.REVIEW -> Step.CAPTURE
            Step.WRITE -> Step.REVIEW
        }
        go(prev)
        return true
    }

    // ------------------------------------------------------------------ maquina y claves

    fun chooseMachine(m: Machine) {
        store.machine = m
        _state.update { it.copy(machine = m) }
        refreshKeys()
    }

    fun saveCrealityKeys(derivation: String, payload: String) {
        store.crealityDerivationKey = derivation.trim()
        store.crealityPayloadKey = payload.trim()
        refreshKeys()
    }

    private fun refreshKeys() {
        val needs = _state.value.machine == Machine.CREALITY_CFS
        val d = CrealityKeys.parse(store.crealityDerivationKey)
        val p = CrealityKeys.parse(store.crealityPayloadKey)
        val keys = when {
            !needs -> KeysState.NOT_NEEDED
            store.crealityDerivationKey.isBlank() && store.crealityPayloadKey.isBlank() -> KeysState.MISSING
            d == null || p == null || !CrealityKeys.isDerivationKey(d) || !CrealityKeys.isPayloadKey(p) -> KeysState.WRONG
            else -> KeysState.OK
        }
        _state.update { it.copy(keys = keys) }
    }

    private fun settings() = ProfileSettings(
        crealityDerivationKey = CrealityKeys.parse(store.crealityDerivationKey),
        crealityPayloadKey = CrealityKeys.parse(store.crealityPayloadKey),
    )

    // ------------------------------------------------------------------ foto y OCR

    fun onPhoto(bitmap: Bitmap) {
        setSavedSpool(null)
        _photo.value = bitmap
        _state.update { it.copy(ocrRunning = true, ocrFailed = false) }
        go(Step.REVIEW)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.savePhoto(bitmap) }
            val text = runCatching { Ocr.read(bitmap) }.getOrNull()
            if (text == null) {
                _state.update { it.copy(ocrRunning = false, ocrFailed = true) }
                return@launch
            }
            Log.i(TAG, "OCR:\n$text")
            val spec = LabelExtractor.extract(text)
            Log.i(TAG, "Extraido: $spec")
            store.ocrText = text
            setSpec(spec)
            _state.update { it.copy(ocrRunning = false, ocrText = text, ocrFailed = text.isBlank()) }
        }
    }

    /** Sin foto: formulario con los valores tipicos del PLA. */
    fun startManual() {
        setSavedSpool(null)
        _photo.value = null
        store.savePhoto(null)
        store.ocrText = null
        setSpec(SpecEditing.blank("PLA"))
        _state.update { it.copy(ocrText = null, ocrFailed = false) }
        go(Step.REVIEW)
    }

    // ------------------------------------------------------------------ editor

    private fun setSpec(spec: FilamentSpec) {
        store.spec = spec
        _state.update { it.copy(spec = spec) }
        // Si la bobina ya esta en el inventario, lo que se corrige aqui se corrige alli.
        _state.value.savedSpoolId?.let { id -> inventory.update { inv -> inv.spool(id)?.let { inv.upsert(it.copy(spec = spec)) } ?: inv } }
    }

    private fun setSavedSpool(id: String?) {
        store.savedSpoolId = id
        _state.update { it.copy(savedSpoolId = id) }
    }

    // ------------------------------------------------------------------ inventario

    /** Guarda la ficha actual como bobina nueva (sin abrir). Si ya se guardo, no la duplica. */
    fun saveToInventory(tagUid: String = ""): String {
        _state.value.savedSpoolId?.let { id -> if (inventory.inventory.value.spool(id) != null) return id }
        val now = System.currentTimeMillis()
        val spool = Spool(id = inventory.newId(), spec = _state.value.spec, purchasedAt = now, createdAt = now, tagUid = tagUid)
        inventory.update { it.upsert(spool) }
        setSavedSpool(spool.id)
        return spool.id
    }

    /** Tras elegir maquina: a revisar si ya hay ficha (de una bobina o de una foto); si no, a la foto. */
    fun continueFromMachine() = go(if (_state.value.spec.material.isNotBlank()) Step.REVIEW else Step.CAPTURE)

    /** Bobina nueva desde la foto: lo que hubiera de antes no se arrastra. */
    fun startFromPhoto() {
        // Para el inventario no hace falta maquina: se pide al ir a grabar el tag.
        setSavedSpool(null)
        go(Step.CAPTURE)
    }

    /** Abre una bobina del inventario para grabarle (otra) pegatina. */
    fun loadSpool(spool: Spool) {
        _photo.value = null
        store.savePhoto(null)
        store.ocrText = null
        store.spec = spool.spec
        setSavedSpool(spool.id)
        _state.update { it.copy(spec = spool.spec, ocrText = null, ocrFailed = false, ocrRunning = false) }
        go(if (_state.value.machine == null) Step.MACHINE else Step.REVIEW)
    }

    override fun edit(field: Field, value: Any?) = setSpec(SpecEditing.editManually(_state.value.spec, field, value))

    override fun chooseMaterial(code: String) = setSpec(SpecEditing.chooseMaterial(_state.value.spec, code))

    override fun chooseNamedColor(color: NamedColor, spanish: Boolean) = setSpec(SpecEditing.chooseNamedColor(_state.value.spec, color, spanish))

    /** Color cogido de la foto con el cuentagotas. */
    fun pickColor(hex: String) {
        val s = _state.value.spec
        setSpec(s.copy(colorHex = hex, provenance = s.provenance + (Field.COLOR_HEX to Provenance(Source.MANUAL, 1.0, "foto"))))
    }

    /**
     * Comprueba ANTES de pedir la pegatina que el perfil puede codificar esta
     * bobina. Con un UID ficticio: el real solo se sabe al acercar el tag.
     */
    fun preflight(): ProfileException? {
        val s = _state.value
        val machine = s.machine ?: return null
        return try {
            machine.profile.encode(s.spec, WriteContext(ByteArray(4) { 0x11 }, settings()))
            null
        } catch (e: ProfileException) {
            e
        }
    }

    // ------------------------------------------------------------------ NFC

    fun allowOverwrite() = _state.update { it.copy(allowOverwrite = true, write = WriteStatus.Waiting) }

    fun writeAnother() = _state.update { it.copy(write = WriteStatus.Waiting, allowOverwrite = false) }

    /** Llega en el hilo del lector NFC: se escribe aqui mismo, con el tag aun en el campo. */
    fun onTag(tag: Tag) {
        val s = _state.value
        if (s.step != Step.WRITE || s.write is WriteStatus.Working || s.write is WriteStatus.Done) return
        val machine = s.machine ?: return
        val profile = machine.profile
        _state.update { it.copy(write = WriteStatus.Working) }

        fun fail(f: WriteStatus.Failed) {
            Log.i(TAG, "Escritura fallida: $f")
            _state.update { it.copy(write = f) }
        }

        val techs = tag.techList.map { it.substringAfterLast('.') }
        val techOk = when (profile.tech) {
            TagTech.MIFARE_CLASSIC_1K -> "MifareClassic" in techs
            TagTech.NFC_A_TYPE2 -> "MifareUltralight" in techs || "Ndef" in techs || "NdefFormatable" in techs
            TagTech.NFC_V -> "NfcV" in techs
        }
        if (!techOk) return fail(WriteStatus.Failed(WriteProblem.WRONG_TECH))

        val settings = settings()
        if (!s.allowOverwrite) {
            // Un tag con datos de un formato conocido no se pisa sin preguntar.
            val existing = AndroidTagReader(settings).read(tag).identified.firstOrNull()
            if (existing != null) return fail(WriteStatus.Failed(WriteProblem.UNKNOWN, existing = existing.profile))
        }

        val plan = try {
            profile.encode(s.spec, WriteContext(tag.id, settings))
        } catch (e: ProfileException) {
            return fail(WriteStatus.Failed(null, profileError = e))
        }
        val report = writer.write(tag, plan)
        report.log.lines().forEach { Log.i(TAG, it) }
        if (!report.ok) return fail(WriteStatus.Failed(report.problem))

        val back = AndroidTagReader(settings).read(tag).identified.firstOrNull { it.profile == profile.name }?.spec
        Log.i(TAG, "Releido: $back")
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        s.savedSpoolId?.let { id -> inventory.update { inv -> inv.spool(id)?.let { inv.upsert(it.copy(tagUid = uid)) } ?: inv } }
        _state.update { it.copy(write = WriteStatus.Done(back, uid), written = it.written + 1) }
    }

    fun capabilities(hasNfc: Boolean, mifare: Boolean) = DeviceCapabilities(hasNfc = hasNfc, supportsMifareClassic = mifare)

    fun isAvailable(m: Machine, caps: DeviceCapabilities) = Profiles.availability(m.profile, caps)

    companion object {
        const val TAG = "Rodet"
    }
}
