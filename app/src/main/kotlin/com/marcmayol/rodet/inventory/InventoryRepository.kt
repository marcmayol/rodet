package com.marcmayol.rodet.inventory

import android.content.Context
import android.util.AtomicFile
import com.marcmayol.rodet.flow.SpecJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Las bobinas y el historial de impresiones, en un JSON en el almacenamiento
 * privado. Es poco dato (cientos de filas como mucho) y asi el mismo formato
 * sirve para exportar e importar. Una sola instancia por proceso: la pestaña de
 * grabar tags y la del inventario ven lo mismo.
 */
class InventoryRepository private constructor(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "inventory.json"))
    private val prefs = context.getSharedPreferences("rodet_inventory", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _inventory = MutableStateFlow(load())
    val inventory: StateFlow<Inventory> = _inventory.asStateFlow()

    private val _settings = MutableStateFlow(prefs.getString(KEY_SETTINGS, null)?.let { runCatching { InventoryJson.settings(JSONObject(it)) }.getOrNull() } ?: CostSettings())
    val settings: StateFlow<CostSettings> = _settings.asStateFlow()

    fun update(change: (Inventory) -> Inventory) {
        synchronized(this) { _inventory.value = change(_inventory.value) }
        val snapshot = _inventory.value
        scope.launch { writeLock.withLock { save(snapshot) } }
    }

    fun setSettings(s: CostSettings) {
        _settings.value = s
        prefs.edit().putString(KEY_SETTINGS, InventoryJson.settings(s).toString()).apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Borradores de formularios: lo que se escribe no se pierde si Android mata la app. */
    fun draft(key: String): String? = prefs.getString("draft_$key", null)
    fun setDraft(key: String, json: String?) = prefs.edit().putString("draft_$key", json).apply()

    fun exportJson(): String = InventoryJson.encode(_inventory.value, _settings.value).toString(2)

    /** Sustituye todo por lo importado. Devuelve false si el fichero no es de Rodet. */
    fun importJson(text: String): Boolean {
        val (inv, settings) = runCatching { InventoryJson.decode(JSONObject(text)) }.getOrNull() ?: return false
        update { inv }
        settings?.let(::setSettings)
        return true
    }

    private fun load(): Inventory = runCatching {
        InventoryJson.decode(JSONObject(String(file.readFully(), Charsets.UTF_8))).first
    }.getOrDefault(Inventory())

    private fun save(inv: Inventory) {
        val out = file.startWrite()
        try {
            out.write(InventoryJson.encode(inv, null).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }

    companion object {
        private const val KEY_SETTINGS = "cost_settings"

        @Volatile private var instance: InventoryRepository? = null

        fun get(context: Context): InventoryRepository =
            instance ?: synchronized(this) { instance ?: InventoryRepository(context.applicationContext).also { instance = it } }
    }
}

/**
 * Formato en disco y de exportacion. Se guardan los nombres de los enums, nunca
 * textos de pantalla: el dato no puede depender del idioma.
 */
object InventoryJson {

    const val FORMAT = "rodet-inventory"
    const val VERSION = 1

    fun encode(inv: Inventory, settings: CostSettings?): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("spools", JSONArray().apply { inv.spools.forEach { put(spool(it)) } })
        put("jobs", JSONArray().apply { inv.jobs.forEach { put(job(it)) } })
        settings?.let { put("settings", settings(it)) }
    }

    fun decode(o: JSONObject): Pair<Inventory, CostSettings?> {
        require(o.optString("format") == FORMAT) { "no es un inventario de Rodet" }
        val spools = o.optJSONArray("spools").objects().map(::spool)
        val jobs = o.optJSONArray("jobs").objects().map(::job)
        return Inventory(spools, jobs) to o.optJSONObject("settings")?.let(::settings)
    }

    private fun spool(s: Spool) = JSONObject().apply {
        put("id", s.id)
        put("spec", JSONObject(SpecJson.encode(s.spec)))
        putOpt("price", s.price)
        put("shop", s.shop)
        putOpt("purchasedAt", s.purchasedAt)
        put("status", s.status.name)
        putOpt("remainingG", s.remainingG)
        put("tagUid", s.tagUid)
        put("createdAt", s.createdAt)
    }

    private fun spool(o: JSONObject) = Spool(
        id = o.getString("id"),
        spec = SpecJson.decode(o.getJSONObject("spec").toString()),
        price = o.optDoubleOrNull("price"),
        shop = o.optString("shop"),
        purchasedAt = o.optLongOrNull("purchasedAt"),
        status = SpoolStatus.entries.firstOrNull { it.name == o.optString("status") } ?: SpoolStatus.SEALED,
        remainingG = o.optDoubleOrNull("remainingG"),
        tagUid = o.optString("tagUid"),
        createdAt = o.optLong("createdAt"),
    )

    private fun job(j: PrintJob) = JSONObject().apply {
        put("id", j.id)
        put("name", j.name)
        put("at", j.at)
        put("durationMin", j.durationMin)
        put("outcome", j.outcome.name)
        put("deducted", j.deducted)
        put("labourMin", j.labourMin)
        put("usages", JSONArray().apply { j.usages.forEach { put(usage(it)) } })
        put("cost", cost(j.cost))
    }

    private fun job(o: JSONObject) = PrintJob(
        id = o.getString("id"),
        name = o.optString("name"),
        at = o.getLong("at"),
        durationMin = o.optInt("durationMin"),
        usages = o.optJSONArray("usages").objects().map(::usage),
        cost = cost(o.optJSONObject("cost") ?: JSONObject()),
        outcome = PrintOutcome.entries.firstOrNull { it.name == o.optString("outcome") } ?: PrintOutcome.OK,
        deducted = o.optBoolean("deducted"),
        labourMin = o.optInt("labourMin"),
    )

    private fun usage(u: Usage) = JSONObject().apply {
        putOpt("spoolId", u.spoolId)
        put("grams", u.grams)
        put("material", u.material)
        put("colorHex", u.colorHex)
        put("colorName", u.colorName)
        put("brand", u.brand)
        put("pricePerKg", u.pricePerKg)
    }

    private fun usage(o: JSONObject) = Usage(
        spoolId = o.optString("spoolId").takeIf { o.has("spoolId") && !o.isNull("spoolId") && it.isNotBlank() },
        grams = o.optDouble("grams", 0.0),
        material = o.optString("material"),
        colorHex = o.optString("colorHex"),
        colorName = o.optString("colorName"),
        brand = o.optString("brand"),
        pricePerKg = o.optDouble("pricePerKg", 0.0),
    )

    private fun cost(c: CostBreakdown) = JSONObject().apply {
        put("filament", c.filament); put("kwh", c.kwh); put("energy", c.energy); put("wear", c.wear)
        put("failure", c.failure); put("labour", c.labour); put("profit", c.profit)
    }

    private fun cost(o: JSONObject) = CostBreakdown(
        filament = o.optDouble("filament", 0.0), kwh = o.optDouble("kwh", 0.0), energy = o.optDouble("energy", 0.0),
        wear = o.optDouble("wear", 0.0), failure = o.optDouble("failure", 0.0), labour = o.optDouble("labour", 0.0),
        profit = o.optDouble("profit", 0.0),
    )

    fun settings(s: CostSettings) = JSONObject().apply {
        put("kwhPrice", s.kwhPrice); put("printerWatts", s.printerWatts); put("defaultPricePerKg", s.defaultPricePerKg)
        put("currency", s.currency)
        put("failureEnabled", s.failureEnabled); put("failurePct", s.failurePct)
        put("wearEnabled", s.wearEnabled); put("printerPrice", s.printerPrice); put("printerLifeH", s.printerLifeH)
        put("labourEnabled", s.labourEnabled); put("labourPerHour", s.labourPerHour)
        put("profitEnabled", s.profitEnabled); put("profitPct", s.profitPct)
    }

    fun settings(o: JSONObject): CostSettings {
        val d = CostSettings()
        return CostSettings(
            kwhPrice = o.optDouble("kwhPrice", d.kwhPrice),
            printerWatts = o.optDouble("printerWatts", d.printerWatts),
            defaultPricePerKg = o.optDouble("defaultPricePerKg", d.defaultPricePerKg),
            currency = o.optString("currency", d.currency),
            failureEnabled = o.optBoolean("failureEnabled", d.failureEnabled),
            failurePct = o.optDouble("failurePct", d.failurePct),
            wearEnabled = o.optBoolean("wearEnabled", d.wearEnabled),
            printerPrice = o.optDouble("printerPrice", d.printerPrice),
            printerLifeH = o.optDouble("printerLifeH", d.printerLifeH),
            labourEnabled = o.optBoolean("labourEnabled", d.labourEnabled),
            labourPerHour = o.optDouble("labourPerHour", d.labourPerHour),
            profitEnabled = o.optBoolean("profitEnabled", d.profitEnabled),
            profitPct = o.optDouble("profitPct", d.profitPct),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
}
