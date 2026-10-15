package com.marcmayol.rodet.inventory

import com.marcmayol.rodet.model.FilamentSpec
import kotlin.math.max
import kotlin.math.min

enum class SpoolStatus { SEALED, IN_USE, FINISHED }

/**
 * Una bobina que la persona tiene (o tuvo). [spec] es la misma ficha que va al
 * tag; lo demas solo importa en casa: cuanto costo y cuanto le queda.
 */
data class Spool(
    val id: String,
    val spec: FilamentSpec,
    /** Lo que costo la bobina entera. */
    val price: Double? = null,
    val shop: String = "",
    /** epoch ms */
    val purchasedAt: Long? = null,
    val status: SpoolStatus = SpoolStatus.SEALED,
    /** Gramos de filamento que quedan; null = no se sabe (se asume el peso neto). */
    val remainingG: Double? = null,
    val tagUid: String = "",
    val createdAt: Long = 0,
) {
    val netG: Double? get() = spec.netWeightG?.toDouble()?.takeIf { it > 0 }

    val effectiveRemainingG: Double?
        get() = when {
            status == SpoolStatus.FINISHED -> 0.0
            remainingG != null -> remainingG
            else -> netG
        }

    val remainingFraction: Double?
        get() {
            val n = netG ?: return null
            val r = effectiveRemainingG ?: return null
            return (r / n).coerceIn(0.0, 1.0)
        }

    val pricePerKg: Double?
        get() {
            val p = price ?: return null
            val n = netG ?: return null
            return p / n * 1000.0
        }

    val isActive: Boolean get() = status != SpoolStatus.FINISHED
}

/** Lo que cambia lo que queda en una bobina. Puras: devuelven la bobina nueva. */
object Spools {

    /** Por debajo de esto la bobina se da por acabada: lo que queda no da para una pieza. */
    const val EMPTY_G = 1.0

    /** Si al pesarla faltan mas de estos gramos, es que ya se ha usado. */
    private const val OPENED_TOLERANCE_G = 5.0

    /**
     * Pesada en la bascula: bruto menos la bobina vacia. [tareG] sustituye a la
     * tara guardada (y se recuerda); sin ninguna de las dos no se puede calcular.
     */
    fun weigh(spool: Spool, grossG: Double, tareG: Int? = null): Spool? {
        val tare = tareG ?: spool.spec.spoolWeightG ?: return null
        val withTare = if (tareG != null) spool.copy(spec = spool.spec.copy(spoolWeightG = tareG)) else spool
        return setRemaining(withTare, grossG - tare)
    }

    /** Lo que queda, puesto a mano (o calculado con la bascula). */
    fun setRemaining(spool: Spool, grams: Double): Spool {
        val g = max(0.0, grams)
        val net = spool.netG
        val status = when {
            g < EMPTY_G -> SpoolStatus.FINISHED
            spool.status == SpoolStatus.SEALED && net != null && g >= net - OPENED_TOLERANCE_G -> SpoolStatus.SEALED
            else -> SpoolStatus.IN_USE
        }
        return spool.copy(remainingG = g, status = status)
    }

    /** Lo que se ha llevado una impresion. */
    fun consume(spool: Spool, grams: Double): Spool {
        val left = spool.effectiveRemainingG ?: return spool.copy(status = SpoolStatus.IN_USE)
        val g = max(0.0, left - grams)
        return spool.copy(remainingG = g, status = if (g < EMPTY_G) SpoolStatus.FINISHED else SpoolStatus.IN_USE)
    }

    /** Deshace [consume]: al borrar una impresion del historial vuelve el filamento. */
    fun restore(spool: Spool, grams: Double): Spool {
        val base = if (spool.status == SpoolStatus.FINISHED) 0.0 else spool.effectiveRemainingG ?: return spool
        val g = (base + grams).let { v -> spool.netG?.let { min(v, it) } ?: v }
        return spool.copy(remainingG = g, status = if (g < EMPTY_G) SpoolStatus.FINISHED else SpoolStatus.IN_USE)
    }

    /** La segunda bobina del mismo lote: misma ficha y compra, entera y sin tag. */
    fun duplicate(spool: Spool, newId: String, now: Long): Spool =
        spool.copy(id = newId, status = SpoolStatus.SEALED, remainingG = null, tagUid = "", createdAt = now)
}

enum class PrintOutcome { OK, FAILED }

/**
 * Filamento gastado en una impresion. Material y color se copian de la bobina
 * para que las estadisticas no cambien si luego se borra o se edita.
 */
data class Usage(
    val spoolId: String?,
    val grams: Double,
    val material: String,
    val colorHex: String,
    val colorName: String = "",
    val brand: String = "",
    val pricePerKg: Double,
)

data class PrintJob(
    val id: String,
    val name: String,
    /** epoch ms */
    val at: Long,
    val durationMin: Int,
    val usages: List<Usage>,
    /** El coste tal y como se calculo: los precios de mañana no reescriben el pasado. */
    val cost: CostBreakdown,
    val outcome: PrintOutcome = PrintOutcome.OK,
    /** Si se desconto de las bobinas al guardarla. */
    val deducted: Boolean = false,
    val labourMin: Int = 0,
) {
    val grams: Double get() = usages.sumOf { it.grams }
}

data class Inventory(
    val spools: List<Spool> = emptyList(),
    val jobs: List<PrintJob> = emptyList(),
) {
    fun spool(id: String?): Spool? = id?.let { i -> spools.firstOrNull { it.id == i } }

    fun bySpoolTag(uid: String): Spool? =
        uid.takeIf { it.isNotBlank() }?.let { u -> spools.firstOrNull { it.tagUid.equals(u, ignoreCase = true) } }

    fun upsert(spool: Spool): Inventory {
        val i = spools.indexOfFirst { it.id == spool.id }
        return copy(spools = if (i < 0) spools + spool else spools.toMutableList().also { it[i] = spool })
    }

    fun removeSpool(id: String): Inventory = copy(spools = spools.filterNot { it.id == id })

    /** Guarda la impresion y, si [PrintJob.deducted], descuenta los gramos de cada bobina. */
    fun addJob(job: PrintJob): Inventory {
        val next = copy(jobs = jobs.filterNot { it.id == job.id } + job)
        return if (job.deducted) next.applyUsages(job.usages, Spools::consume) else next
    }

    /** Borra la impresion y devuelve a las bobinas lo que se les desconto. */
    fun removeJob(id: String): Inventory {
        val job = jobs.firstOrNull { it.id == id } ?: return this
        val next = copy(jobs = jobs.filterNot { it.id == id })
        return if (job.deducted) next.applyUsages(job.usages, Spools::restore) else next
    }

    private fun applyUsages(usages: List<Usage>, op: (Spool, Double) -> Spool): Inventory {
        val grams = usages.filter { it.spoolId != null }.groupBy { it.spoolId!! }.mapValues { (_, u) -> u.sumOf { it.grams } }
        return copy(spools = spools.map { s -> grams[s.id]?.let { op(s, it) } ?: s })
    }
}
