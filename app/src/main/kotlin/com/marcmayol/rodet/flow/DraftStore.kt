package com.marcmayol.rodet.flow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.Provenance
import com.marcmayol.rodet.model.Source
import com.marcmayol.rodet.tags.Machine
import org.json.JSONObject
import java.io.File

/**
 * Lo que la persona lleva hecho vive aqui y no solo en memoria: si Android
 * mata la app mientras va a buscar la pegatina, al volver sigue donde estaba.
 */
class DraftStore(context: Context) {

    private val prefs = context.getSharedPreferences("rodet", Context.MODE_PRIVATE)
    private val photoFile = File(context.filesDir, "label.jpg")

    var machine: Machine?
        get() = Machine.byName(prefs.getString(KEY_MACHINE, null))
        set(v) = prefs.edit().putString(KEY_MACHINE, v?.name).apply()

    var step: Step
        get() = Step.entries.firstOrNull { it.name == prefs.getString(KEY_STEP, null) } ?: Step.MACHINE
        set(v) = prefs.edit().putString(KEY_STEP, v.name).apply()

    var spec: FilamentSpec?
        get() = prefs.getString(KEY_SPEC, null)?.let { runCatching { SpecJson.decode(it) }.getOrNull() }
        set(v) = prefs.edit().putString(KEY_SPEC, v?.let(SpecJson::encode)).apply()

    var ocrText: String?
        get() = prefs.getString(KEY_OCR, null)
        set(v) = prefs.edit().putString(KEY_OCR, v).apply()

    var savedSpoolId: String?
        get() = prefs.getString(KEY_SAVED_SPOOL, null)
        set(v) = prefs.edit().putString(KEY_SAVED_SPOOL, v).apply()

    var crealityDerivationKey: String
        get() = prefs.getString(KEY_DERIVATION, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_DERIVATION, v).apply()

    var crealityPayloadKey: String
        get() = prefs.getString(KEY_PAYLOAD, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_PAYLOAD, v).apply()

    fun savePhoto(bitmap: Bitmap?) {
        if (bitmap == null) {
            photoFile.delete()
            return
        }
        photoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    fun loadPhoto(): Bitmap? = photoFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private companion object {
        const val KEY_MACHINE = "machine"
        const val KEY_STEP = "step"
        const val KEY_SPEC = "spec"
        const val KEY_OCR = "ocr"
        const val KEY_SAVED_SPOOL = "saved_spool"
        const val KEY_DERIVATION = "creality_derivation"
        const val KEY_PAYLOAD = "creality_payload"
    }
}

/** [FilamentSpec] <-> JSON con las claves estables de [Field]. */
object SpecJson {
    fun encode(spec: FilamentSpec): String {
        val o = JSONObject()
        Field.entries.forEach { f -> f.get(spec)?.let { o.put(f.key, it) } }
        val p = JSONObject()
        spec.provenance.forEach { (f, v) ->
            p.put(f.key, JSONObject().put("source", v.source.name).put("confidence", v.confidence).put("evidence", v.evidence))
        }
        o.put("provenance", p)
        return o.toString()
    }

    fun decode(json: String): FilamentSpec {
        val o = JSONObject(json)
        var spec = FilamentSpec()
        Field.entries.forEach { f ->
            if (!o.has(f.key) || o.isNull(f.key)) return@forEach
            val v = o.get(f.key)
            spec = when (f) {
                Field.DIAMETER, Field.DENSITY -> f.set(spec, (v as Number).toDouble())
                else -> if (v is Number) f.set(spec, v.toInt()) else f.set(spec, v.toString())
            }
        }
        val prov = mutableMapOf<Field, Provenance>()
        o.optJSONObject("provenance")?.let { p ->
            p.keys().forEach { k ->
                val f = Field.fromKey(k) ?: return@forEach
                val e = p.getJSONObject(k)
                prov[f] = Provenance(Source.valueOf(e.getString("source")), e.getDouble("confidence"), e.optString("evidence"))
            }
        }
        return spec.copy(provenance = prov)
    }
}
