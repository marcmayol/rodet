package com.marcmayol.rodet.inventory

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marcmayol.rodet.flow.SpecActions
import com.marcmayol.rodet.flow.SpecJson
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.FilamentSpec
import com.marcmayol.rodet.model.NamedColor
import com.marcmayol.rodet.model.Provenance
import com.marcmayol.rodet.model.Source
import com.marcmayol.rodet.model.SpecEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Tab { SPOOLS, PRINTS, STATS, TAGS }

/** Donde esta la persona dentro de la pestaña de bobinas. */
sealed interface SpoolRoute {
    data object List : SpoolRoute
    data class Detail(val id: String) : SpoolRoute
    data object Edit : SpoolRoute
}

/** Lo que se esta escribiendo en el alta o la edicion de una bobina. */
data class SpoolDraft(
    /** null = bobina nueva. */
    val spoolId: String? = null,
    val spec: FilamentSpec = newSpoolSpec(),
    val price: Double? = null,
    val shop: String = "",
    val purchasedAt: Long? = null,
)

/**
 * Ficha de partida de una bobina a mano: PLA de 1 kg y 1,75 mm, lo mas comun.
 * Van como valor tipico (DEFAULT), no como dato: el editor lo dice y se cambia con un toque.
 */
fun newSpoolSpec(): FilamentSpec {
    val s = SpecEditing.blank("PLA")
    val typical = Provenance(Source.DEFAULT, SpecEditing.DEFAULT_CONFIDENCE, "PLA")
    return s.copy(
        diameterMm = 1.75,
        netWeightG = 1000,
        provenance = s.provenance + (Field.DIAMETER to typical) + (Field.NET_WEIGHT to typical),
    )
}

/** Un filamento de la impresion que se esta calculando. */
data class CalcLine(
    val spoolId: String? = null,
    val grams: Double? = null,
    val material: String = "PLA",
    val colorHex: String = "",
    val colorName: String = "",
    /** Solo para lo que no viene de una bobina con precio. */
    val pricePerKg: Double? = null,
)

data class CalcDraft(
    val name: String = "",
    val durationMin: Int? = null,
    val labourMin: Int = 0,
    val lines: List<CalcLine> = listOf(CalcLine()),
    val deduct: Boolean = true,
    val outcome: PrintOutcome = PrintOutcome.OK,
    /** Nombre del gcode del que salieron los datos, si salieron de uno. */
    val source: String = "",
)

sealed interface Notice {
    data object GcodeUnreadable : Notice
    data class GcodeRead(val filaments: Int, val matched: Int) : Notice
    data object Exported : Notice
    data object Imported : Notice
    data object ImportFailed : Notice
    data object UnknownTag : Notice
    data object NeedTare : Notice
}

class InventoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InventoryRepository.get(app)

    val inventory: StateFlow<Inventory> = repo.inventory
    val settings: StateFlow<CostSettings> = repo.settings

    private val _tab = MutableStateFlow(repo.draft("tab")?.let { t -> Tab.entries.firstOrNull { it.name == t } } ?: Tab.SPOOLS)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _route = MutableStateFlow(
        decodeRoute(repo.draft("route")).let { r -> if (r is SpoolRoute.Detail && repo.inventory.value.spool(r.id) == null) SpoolRoute.List else r },
    )
    val route: StateFlow<SpoolRoute> = _route.asStateFlow()

    private val _draft = MutableStateFlow(repo.draft("spool")?.let { runCatching { decodeSpoolDraft(JSONObject(it)) }.getOrNull() } ?: SpoolDraft())
    val draft: StateFlow<SpoolDraft> = _draft.asStateFlow()

    private val _calc = MutableStateFlow(repo.draft("calc")?.let { runCatching { decodeCalc(JSONObject(it)) }.getOrNull() } ?: CalcDraft())
    val calc: StateFlow<CalcDraft> = _calc.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ------------------------------------------------------------------ navegacion

    fun selectTab(t: Tab) {
        _tab.value = t
        repo.setDraft("tab", t.name)
    }

    fun openSpools(route: SpoolRoute) {
        _route.value = route
        repo.setDraft("route", encodeRoute(route))
    }

    /** Atras dentro de la pestaña; false si ya esta en la raiz. */
    fun back(): Boolean = when (_route.value) {
        SpoolRoute.List -> false
        is SpoolRoute.Detail -> { openSpools(SpoolRoute.List); true }
        SpoolRoute.Edit -> { openSpools(_draft.value.spoolId?.let { SpoolRoute.Detail(it) } ?: SpoolRoute.List); true }
    }

    fun dismissNotice() { _notice.value = null }

    /** Un tag acercado fuera del flujo de grabar: abre la ficha de su bobina. */
    fun onTagUid(uid: String) {
        val spool = inventory.value.bySpoolTag(uid)
        if (spool == null) {
            _notice.value = Notice.UnknownTag
            return
        }
        selectTab(Tab.SPOOLS)
        openSpools(SpoolRoute.Detail(spool.id))
    }

    // ------------------------------------------------------------------ alta y edicion

    val editor: SpecActions = object : SpecActions {
        override fun edit(field: Field, value: Any?) = setDraftSpec(SpecEditing.editManually(_draft.value.spec, field, value))
        override fun chooseMaterial(code: String) = setDraftSpec(SpecEditing.chooseMaterial(_draft.value.spec, code))
        override fun chooseNamedColor(color: NamedColor, spanish: Boolean) = setDraftSpec(SpecEditing.chooseNamedColor(_draft.value.spec, color, spanish))
    }

    private fun setDraftSpec(spec: FilamentSpec) = setDraft(_draft.value.copy(spec = spec))

    private fun setDraft(d: SpoolDraft) {
        _draft.value = d
        repo.setDraft("spool", encodeSpoolDraft(d).toString())
    }

    fun newSpool() {
        setDraft(SpoolDraft(purchasedAt = System.currentTimeMillis()))
        openSpools(SpoolRoute.Edit)
    }

    fun editSpool(id: String) {
        val s = inventory.value.spool(id) ?: return
        setDraft(SpoolDraft(s.id, s.spec, s.price, s.shop, s.purchasedAt))
        openSpools(SpoolRoute.Edit)
    }

    fun setPrice(p: Double?) = setDraft(_draft.value.copy(price = p))
    fun setShop(s: String) = setDraft(_draft.value.copy(shop = s))
    fun setPurchasedAt(ms: Long?) = setDraft(_draft.value.copy(purchasedAt = ms))
    fun setTare(g: Int?) = editor.edit(Field.SPOOL_WEIGHT, g)

    /** [copies] > 1 da de alta varias iguales de una vez (un pack de 3 bobinas). */
    fun saveDraft(copies: Int = 1) {
        val d = _draft.value
        val now = System.currentTimeMillis()
        val existing = d.spoolId?.let { inventory.value.spool(it) }
        val first = existing?.copy(spec = d.spec, price = d.price, shop = d.shop, purchasedAt = d.purchasedAt)
            ?: Spool(id = repo.newId(), spec = d.spec, price = d.price, shop = d.shop, purchasedAt = d.purchasedAt, createdAt = now)
        val extra = if (existing == null) (1 until copies.coerceIn(1, 20)).map { Spools.duplicate(first, repo.newId(), now) } else emptyList()
        repo.update { inv -> (listOf(first) + extra).fold(inv) { acc, s -> acc.upsert(s) } }
        setDraft(SpoolDraft())
        openSpools(SpoolRoute.Detail(first.id))
    }

    fun deleteSpool(id: String) {
        repo.update { it.removeSpool(id) }
        openSpools(SpoolRoute.List)
    }

    fun duplicate(id: String) {
        val s = inventory.value.spool(id) ?: return
        val copy = Spools.duplicate(s, repo.newId(), System.currentTimeMillis())
        repo.update { it.upsert(copy) }
        openSpools(SpoolRoute.Detail(copy.id))
    }

    fun weigh(id: String, grossG: Double, tareG: Int?) {
        val s = inventory.value.spool(id) ?: return
        val next = Spools.weigh(s, grossG, tareG)
        if (next == null) {
            _notice.value = Notice.NeedTare
            return
        }
        repo.update { it.upsert(next) }
    }

    fun setRemaining(id: String, grams: Double) {
        val s = inventory.value.spool(id) ?: return
        repo.update { it.upsert(Spools.setRemaining(s, grams)) }
    }

    fun setStatus(id: String, status: SpoolStatus) {
        val s = inventory.value.spool(id) ?: return
        val next = when (status) {
            SpoolStatus.FINISHED -> s.copy(status = status, remainingG = 0.0)
            SpoolStatus.SEALED -> s.copy(status = status, remainingG = null)
            SpoolStatus.IN_USE -> s.copy(status = status, remainingG = s.effectiveRemainingG?.takeIf { it > 0 } ?: s.netG)
        }
        repo.update { it.upsert(next) }
    }

    // ------------------------------------------------------------------ calculadora

    private fun setCalc(c: CalcDraft) {
        _calc.value = c
        repo.setDraft("calc", encodeCalc(c).toString())
    }

    fun setJobName(n: String) = setCalc(_calc.value.copy(name = n))
    fun setDuration(min: Int?) = setCalc(_calc.value.copy(durationMin = min))
    fun setLabour(min: Int) = setCalc(_calc.value.copy(labourMin = min))
    fun setDeduct(on: Boolean) = setCalc(_calc.value.copy(deduct = on))
    fun setOutcome(o: PrintOutcome) = setCalc(_calc.value.copy(outcome = o))
    fun addLine() = setCalc(_calc.value.copy(lines = _calc.value.lines + CalcLine()))
    fun removeLine(i: Int) = setCalc(_calc.value.copy(lines = _calc.value.lines.filterIndexed { j, _ -> j != i }.ifEmpty { listOf(CalcLine()) }))
    fun clearCalc() = setCalc(CalcDraft())

    fun updateLine(i: Int, change: (CalcLine) -> CalcLine) =
        setCalc(_calc.value.copy(lines = _calc.value.lines.mapIndexed { j, l -> if (j == i) change(l) else l }))

    fun setLineSpool(i: Int, spoolId: String?) = updateLine(i) { l ->
        val s = inventory.value.spool(spoolId)
        if (s == null) l.copy(spoolId = null) else l.copy(spoolId = s.id, material = s.spec.material, colorHex = s.spec.colorHex, colorName = s.spec.colorName)
    }

    /** Lo que cuesta la impresion del borrador con los ajustes actuales. */
    fun usagesOf(c: CalcDraft, inv: Inventory = inventory.value, s: CostSettings = settings.value): List<Usage> =
        c.lines.filter { (it.grams ?: 0.0) > 0 }.map { l ->
            val spool = inv.spool(l.spoolId)
            Usage(
                spoolId = spool?.id,
                grams = l.grams ?: 0.0,
                material = spool?.spec?.material ?: l.material,
                colorHex = spool?.spec?.colorHex ?: l.colorHex,
                colorName = spool?.spec?.colorName ?: l.colorName,
                brand = spool?.spec?.brand.orEmpty(),
                pricePerKg = spool?.pricePerKg ?: l.pricePerKg ?: s.defaultPricePerKg,
            )
        }

    fun costOf(c: CalcDraft, inv: Inventory, s: CostSettings): CostBreakdown =
        CostCalculator.compute(usagesOf(c, inv, s), c.durationMin ?: 0, s, c.labourMin)

    fun saveJob() {
        val c = _calc.value
        val usages = usagesOf(c)
        if (usages.isEmpty() && (c.durationMin ?: 0) == 0) return
        val job = PrintJob(
            id = repo.newId(),
            name = c.name.ifBlank { c.source },
            at = System.currentTimeMillis(),
            durationMin = c.durationMin ?: 0,
            usages = usages,
            cost = CostCalculator.compute(usages, c.durationMin ?: 0, settings.value, c.labourMin),
            outcome = c.outcome,
            deducted = c.deduct && usages.any { it.spoolId != null },
            labourMin = c.labourMin,
        )
        repo.update { it.addJob(job) }
        clearCalc()
    }

    fun deleteJob(id: String) = repo.update { it.removeJob(id) }

    /** Repite una impresion del historial: mismos gramos y bobinas en la calculadora. */
    fun repeatJob(id: String) {
        val j = inventory.value.jobs.firstOrNull { it.id == id } ?: return
        setCalc(
            CalcDraft(
                name = j.name,
                durationMin = j.durationMin,
                labourMin = j.labourMin,
                lines = j.usages.map { u ->
                    val alive = inventory.value.spool(u.spoolId)?.takeIf { it.isActive }
                    CalcLine(alive?.id, u.grams, u.material, u.colorHex, u.colorName, if (alive == null) u.pricePerKg else null)
                },
            ),
        )
        selectTab(Tab.PRINTS)
    }

    fun openGcode(uri: Uri) {
        selectTab(Tab.PRINTS)
        _busy.value = true
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { GcodeReader.read(getApplication(), uri) }
            _busy.value = false
            val print = opened.print
            if (print == null) {
                _notice.value = Notice.GcodeUnreadable
                return@launch
            }
            val spools = inventory.value.spools
            val taken = mutableSetOf<String>()
            val lines = print.filaments.map { f ->
                // Cada bobina una sola vez: dos negros del gcode son dos bobinas o la misma por error.
                val match = SpoolMatcher.best(spools.filter { it.id !in taken }, f.type, f.colorHex)
                match?.let { taken += it.id }
                CalcLine(match?.id, f.grams, match?.spec?.material ?: f.type.ifBlank { "PLA" }, match?.spec?.colorHex ?: f.colorHex, match?.spec?.colorName.orEmpty())
            }
            setCalc(
                CalcDraft(
                    name = opened.name,
                    durationMin = print.durationMin,
                    lines = lines.ifEmpty { listOf(CalcLine()) },
                    source = opened.name,
                ),
            )
            _notice.value = Notice.GcodeRead(print.filaments.size, lines.count { it.spoolId != null })
        }
    }

    // ------------------------------------------------------------------ ajustes y copia

    fun updateSettings(change: (CostSettings) -> CostSettings) = repo.setSettings(change(settings.value))

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(repo.exportJson().toByteArray()) }
            }.isSuccess
        }
        _notice.value = if (ok) Notice.Exported else Notice.ImportFailed
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
        _notice.value = if (text != null && repo.importJson(text)) Notice.Imported else Notice.ImportFailed
        openSpools(SpoolRoute.List)
    }

    // ------------------------------------------------------------------ borradores en disco

    private fun encodeRoute(r: SpoolRoute) = when (r) {
        SpoolRoute.List -> "list"
        SpoolRoute.Edit -> "edit"
        is SpoolRoute.Detail -> "detail:${r.id}"
    }

    private fun decodeRoute(s: String?): SpoolRoute = when {
        s == "edit" -> SpoolRoute.Edit
        s != null && s.startsWith("detail:") -> SpoolRoute.Detail(s.removePrefix("detail:"))
        else -> SpoolRoute.List
    }

    private fun encodeSpoolDraft(d: SpoolDraft) = JSONObject().apply {
        putOpt("spoolId", d.spoolId)
        put("spec", JSONObject(SpecJson.encode(d.spec)))
        putOpt("price", d.price)
        put("shop", d.shop)
        putOpt("purchasedAt", d.purchasedAt)
    }

    private fun decodeSpoolDraft(o: JSONObject) = SpoolDraft(
        spoolId = o.optString("spoolId").takeIf { o.has("spoolId") && it.isNotBlank() },
        spec = SpecJson.decode(o.getJSONObject("spec").toString()),
        price = if (o.has("price")) o.optDouble("price") else null,
        shop = o.optString("shop"),
        purchasedAt = if (o.has("purchasedAt")) o.optLong("purchasedAt") else null,
    )

    private fun encodeCalc(c: CalcDraft) = JSONObject().apply {
        put("name", c.name)
        putOpt("durationMin", c.durationMin)
        put("labourMin", c.labourMin)
        put("deduct", c.deduct)
        put("outcome", c.outcome.name)
        put("source", c.source)
        put(
            "lines",
            JSONArray().apply {
                c.lines.forEach { l ->
                    put(
                        JSONObject().apply {
                            putOpt("spoolId", l.spoolId); putOpt("grams", l.grams); put("material", l.material)
                            put("colorHex", l.colorHex); put("colorName", l.colorName); putOpt("pricePerKg", l.pricePerKg)
                        },
                    )
                }
            },
        )
    }

    private fun decodeCalc(o: JSONObject): CalcDraft {
        val a = o.optJSONArray("lines") ?: JSONArray()
        return CalcDraft(
            name = o.optString("name"),
            durationMin = if (o.has("durationMin")) o.optInt("durationMin") else null,
            labourMin = o.optInt("labourMin"),
            deduct = o.optBoolean("deduct", true),
            outcome = PrintOutcome.entries.firstOrNull { it.name == o.optString("outcome") } ?: PrintOutcome.OK,
            source = o.optString("source"),
            lines = (0 until a.length()).map { i ->
                val l = a.getJSONObject(i)
                CalcLine(
                    spoolId = l.optString("spoolId").takeIf { l.has("spoolId") && it.isNotBlank() },
                    grams = if (l.has("grams")) l.optDouble("grams") else null,
                    material = l.optString("material", "PLA"),
                    colorHex = l.optString("colorHex"),
                    colorName = l.optString("colorName"),
                    pricePerKg = if (l.has("pricePerKg")) l.optDouble("pricePerKg") else null,
                )
            }.ifEmpty { listOf(CalcLine()) },
        )
    }
}
