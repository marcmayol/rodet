package com.marcmayol.rodet.inventory

import com.marcmayol.rodet.model.FilamentSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryTest {

    private fun spool(
        id: String,
        material: String = "PLA",
        hex: String = "000000",
        net: Int = 1000,
        price: Double? = 20.0,
        status: SpoolStatus = SpoolStatus.SEALED,
        remaining: Double? = null,
        tare: Int? = null,
    ) = Spool(
        id = id,
        spec = FilamentSpec(material = material, colorHex = hex, netWeightG = net, spoolWeightG = tare),
        price = price,
        status = status,
        remainingG = remaining,
    )

    private fun usage(spoolId: String?, grams: Double, material: String = "PLA", hex: String = "000000", perKg: Double = 20.0) =
        Usage(spoolId, grams, material, hex, pricePerKg = perKg)

    private fun job(id: String, at: Long, vararg usages: Usage, deducted: Boolean = true) =
        PrintJob(id, id, at, 60, usages.toList(), CostCalculator.compute(usages.toList(), 60, CostSettings()), deducted = deducted)

    @Nested
    inner class Bobinas {
        @Test
        fun `una bobina sin abrir tiene su peso neto`() {
            val s = spool("a")
            assertEquals(1000.0, s.effectiveRemainingG)
            assertEquals(1.0, s.remainingFraction)
            assertEquals(20.0, s.pricePerKg)
        }

        @Test
        fun `el precio por kilo sale del peso neto`() {
            assertEquals(30.0, spool("a", net = 500, price = 15.0).pricePerKg!!, 1e-9)
        }

        @Test
        fun `pesar resta la tara y la recuerda`() {
            val s = Spools.weigh(spool("a"), grossG = 850.0, tareG = 200)!!
            assertEquals(650.0, s.remainingG)
            assertEquals(200, s.spec.spoolWeightG)
            assertEquals(SpoolStatus.IN_USE, s.status)
        }

        @Test
        fun `sin tara no se puede calcular lo que queda`() {
            assertNull(Spools.weigh(spool("a"), 850.0))
            assertEquals(600.0, Spools.weigh(spool("a", tare = 250), 850.0)!!.remainingG)
        }

        @Test
        fun `pesar una bobina precintada no la abre`() {
            val s = Spools.weigh(spool("a", tare = 200), 1198.0)!!
            assertEquals(SpoolStatus.SEALED, s.status)
        }

        @Test
        fun `gastar la abre y vaciarla la acaba`() {
            val s = Spools.consume(spool("a"), 120.0)
            assertEquals(880.0, s.remainingG)
            assertEquals(SpoolStatus.IN_USE, s.status)
            val done = Spools.consume(s, 900.0)
            assertEquals(0.0, done.remainingG)
            assertEquals(SpoolStatus.FINISHED, done.status)
        }

        @Test
        fun `devolver filamento reabre la bobina y no pasa del neto`() {
            val done = Spools.consume(spool("a", remaining = 50.0, status = SpoolStatus.IN_USE), 80.0)
            assertEquals(SpoolStatus.FINISHED, done.status)
            val back = Spools.restore(done, 80.0)
            assertEquals(80.0, back.remainingG)
            assertEquals(SpoolStatus.IN_USE, back.status)
            assertEquals(1000.0, Spools.restore(spool("b", remaining = 990.0), 50.0).remainingG)
        }

        @Test
        fun `duplicar da una bobina entera y sin tag`() {
            val s = spool("a", remaining = 100.0, status = SpoolStatus.IN_USE).copy(tagUid = "70B4276B", shop = "Amazon")
            val d = Spools.duplicate(s, "b", now = 5)
            assertEquals("b", d.id)
            assertEquals(SpoolStatus.SEALED, d.status)
            assertEquals(1000.0, d.effectiveRemainingG)
            assertEquals("", d.tagUid)
            assertEquals("Amazon", d.shop)
        }
    }

    @Nested
    inner class Impresiones {
        @Test
        fun `guardar una impresion descuenta de cada bobina y borrarla lo devuelve`() {
            val inv = Inventory(listOf(spool("negro"), spool("rojo", hex = "FB0207")))
            val j = job("j", 0, usage("negro", 30.0), usage("rojo", 4.0), usage("negro", 2.0))
            val after = inv.addJob(j)
            assertEquals(968.0, after.spool("negro")!!.remainingG)
            assertEquals(996.0, after.spool("rojo")!!.remainingG)
            val undone = after.removeJob("j")
            assertEquals(1000.0, undone.spool("negro")!!.effectiveRemainingG)
            assertTrue(undone.jobs.isEmpty())
        }

        @Test
        fun `una impresion sin descontar no toca las bobinas`() {
            val inv = Inventory(listOf(spool("a"))).addJob(job("j", 0, usage("a", 30.0), deducted = false))
            assertEquals(SpoolStatus.SEALED, inv.spool("a")!!.status)
            assertEquals(1000.0, inv.removeJob("j").spool("a")!!.effectiveRemainingG)
        }

        @Test
        fun `encuentra la bobina por el UID de su tag`() {
            val inv = Inventory(listOf(spool("a").copy(tagUid = "70B4276B")))
            assertEquals("a", inv.bySpoolTag("70b4276b")?.id)
            assertNull(inv.bySpoolTag(""))
        }
    }

    @Nested
    inner class Coste {
        @Test
        fun `filamento mas luz`() {
            val c = CostCalculator.compute(listOf(usage(null, 50.0, perKg = 20.0)), 120, CostSettings(kwhPrice = 0.15, printerWatts = 200.0))
            assertEquals(1.0, c.filament, 1e-9)
            assertEquals(0.4, c.kwh, 1e-9)
            assertEquals(0.06, c.energy, 1e-9)
            assertEquals(1.06, c.total, 1e-9)
        }

        @Test
        fun `los extras solo cuentan si estan encendidos`() {
            val usages = listOf(usage(null, 100.0, perKg = 20.0))
            val off = CostCalculator.compute(usages, 60, CostSettings(), labourMin = 30)
            assertEquals(0.0, off.wear + off.failure + off.labour + off.profit)

            val s = CostSettings(
                kwhPrice = 0.0,
                wearEnabled = true, printerPrice = 500.0, printerLifeH = 5000.0,
                failureEnabled = true, failurePct = 10.0,
                labourEnabled = true, labourPerHour = 12.0,
                profitEnabled = true, profitPct = 50.0,
            )
            val c = CostCalculator.compute(usages, 60, s, labourMin = 30)
            assertEquals(2.0, c.filament, 1e-9)
            assertEquals(0.1, c.wear, 1e-9)
            assertEquals(0.21, c.failure, 1e-9)
            assertEquals(6.0, c.labour, 1e-9)
            assertEquals(4.155, c.profit, 1e-9)
            assertEquals(12.465, c.total, 1e-9)
        }
    }

    @Nested
    inner class Gcode {
        // Lineas reales de un gcode de Creality Print 7.3 para la K2 (llavero Mickey, 3 colores).
        private val creality = """
            ; HEADER_BLOCK_START
            ; generated by Creality_Print V7.3.0.6151 on 2026-09-30 at 23:39:37
            ; filament_density: 1.24,1.24,1.24
            G1 X10 Y10
            ; filament used [mm] = 10280.95, 1403.54, 611.62
            ; filament used [g] = 30.66, 4.19, 1.82
            ; total filament used [g] = 36.67
            ; estimated printing time (normal mode) = 1h 29m 6s
            ; filament_colour = #000000;#FB0207;#FFFFFF
            ; filament_type = PLA;PLA;PLA
        """.trimIndent()

        @Test
        fun `lee un gcode multicolor de Creality Print`() {
            val p = GcodeInfo.parse(creality.lineSequence())!!
            assertEquals("Creality_Print V7.3.0.6151", p.slicer)
            assertEquals(5346L, p.durationS)
            assertEquals(89, p.durationMin)
            assertEquals(listOf(30.66, 4.19, 1.82), p.filaments.map { it.grams })
            assertEquals(listOf("000000", "FB0207", "FFFFFF"), p.filaments.map { it.colorHex })
            assertEquals(listOf("PLA", "PLA", "PLA"), p.filaments.map { it.type })
            assertEquals(36.67, p.grams, 1e-9)
        }

        @Test
        fun `descarta los filamentos que no se usan`() {
            val p = GcodeInfo.parse(
                """
                ; filament used [g] = 7.80, 0.00, 2.28, 2.17
                ; estimated printing time (normal mode) = 52m 40s
                ; filament_colour = #000000;#F4EE2A;#FB0207;#FFFFFF
                ; filament_type = PLA;PLA;PLA;PLA
                """.trimIndent().lineSequence(),
            )!!
            assertEquals(listOf("000000", "FB0207", "FFFFFF"), p.filaments.map { it.colorHex })
            assertEquals(3160L, p.durationS)
        }

        @Test
        fun `Bambu y Orca con el tiempo total en la cabecera`() {
            val p = GcodeInfo.parse(
                """
                ; generated by OrcaSlicer 2.2.0 on 2026-10-01 at 10:00:00
                ; model printing time: 1h 2m 3s; total estimated time: 1h 5m 3s
                ; total filament weight [g] : 12.5
                ; filament_colour = #FFFFFFFF
                """.trimIndent().lineSequence(),
            )!!
            assertEquals("OrcaSlicer 2.2.0", p.slicer)
            assertEquals(3903L, p.durationS)
            assertEquals(12.5, p.grams)
            assertEquals("FFFFFF", p.filaments.single().colorHex)
        }

        @Test
        fun `Cura da metros y segundos`() {
            val p = GcodeInfo.parse(";FLAVOR:Marlin\n;TIME:3600\n;Filament used: 3.35m\n;Generated with Cura_SteamEngine 5.8.1".lineSequence())!!
            assertEquals(3600L, p.durationS)
            assertEquals(1, p.filaments.size)
            assertTrue(p.filaments.single().estimated)
            assertEquals(9.97, p.grams, 0.05)
        }

        @Test
        fun `slice_info de un gcode 3mf`() {
            val xml = """
                <config><plate>
                  <metadata key="index" value="1"/>
                  <metadata key="prediction" value="4210"/>
                  <metadata key="weight" value="15.40"/>
                  <filament id="1" tray_info_idx="GFA00" type="PLA" color="#FFFFFF" used_m="3.2" used_g="10.10" />
                  <filament id="2" type="PETG" color="#00AE42" used_m="1.7" used_g="5.30" />
                </plate></config>
            """.trimIndent()
            val p = GcodeInfo.parseSliceInfo(xml)!!
            assertEquals(4210L, p.durationS)
            assertEquals(listOf("PLA", "PETG"), p.filaments.map { it.type })
            assertEquals(15.4, p.grams, 1e-9)
        }

        @Test
        fun `un fichero sin datos de laminado no es una impresion`() {
            assertNull(GcodeInfo.parse("G28\nG1 X0".lineSequence()))
            assertNull(GcodeInfo.parseSliceInfo("<config><header/></config>"))
        }

        @Test
        fun `formatos de duracion`() {
            assertEquals(93784L, GcodeInfo.parseDuration("1d 2h 3m 4s"))
            assertEquals(3720L, GcodeInfo.parseDuration("1 hours 2 minutes"))
            assertEquals(42L, GcodeInfo.parseDuration("42"))
            assertNull(GcodeInfo.parseDuration("pronto"))
        }
    }

    @Nested
    inner class Estadisticas {
        private val day = Stats.DAY_MS
        private val now = 100 * day

        private val inv = Inventory(
            spools = listOf(
                spool("negro", remaining = 200.0, status = SpoolStatus.IN_USE),
                spool("rojo", hex = "FB0207"),
                spool("petg", material = "PETG", hex = "FFFFFF", remaining = 900.0, status = SpoolStatus.IN_USE),
            ),
            jobs = listOf(
                job("1", now - 80 * day, usage("negro", 300.0), usage("rojo", 10.0, hex = "FB0207")),
                job("2", now - 10 * day, usage("negro", 300.0)),
                job("3", now - 5 * day, usage("petg", 100.0, material = "PETG", hex = "FFFFFF", perKg = 25.0)),
            ),
        )

        @Test
        fun `agrupa por material y por color de mas a menos gastado`() {
            val m = Stats.byMaterial(inv.jobs)
            assertEquals(listOf("PLA", "PETG"), m.map { it.material })
            assertEquals(610.0, m[0].grams)
            assertEquals(12.2, m[0].cost, 1e-9)
            assertEquals(2, m[0].prints)

            val c = Stats.byColor(inv.jobs)
            assertEquals(listOf("000000", "FFFFFF", "FB0207"), c.map { it.colorHex })
        }

        @Test
        fun `avisa de lo que se va a acabar antes`() {
            val r = Stats.reorder(inv, now)
            val first = r.first()
            assertEquals("PLA", first.material)
            assertEquals("000000", first.colorHex)
            // El historial empieza hace 80 dias: 600 g / 80 = 7,5 g/dia; quedan 200 g -> 26,7 dias
            assertEquals(26.67, first.daysLeft!!, 0.01)
            assertEquals(225.0, first.gramsPerMonth, 0.01)
            assertTrue(first.urgent)
            assertTrue(r.any { it.colorHex == "FB0207" && it.stockG == 1000.0 })
        }

        @Test
        fun `con poco historial mide desde la primera impresion`() {
            val short = inv.copy(jobs = listOf(job("x", now - 20 * day, usage("negro", 200.0))))
            val h = Stats.reorder(short, now).single()
            assertEquals(10.0, h.gramsPerMonth / 30 * 1, 0.01)
            assertEquals(20.0, h.daysLeft!!, 0.01)
            assertTrue(h.urgent)
        }

        @Test
        fun `resumen con stock y lo gastado`() {
            val s = Stats.summary(inv)
            assertEquals(3, s.prints)
            assertEquals(710.0, s.grams)
            assertEquals(2100.0, s.stockG)
            assertEquals(60.0, s.spentOnSpools)
        }
    }

    @Nested
    inner class Emparejar {
        private val spools = listOf(
            Spool("negro-lleno", FilamentSpec(material = "PLA", colorHex = "000000", netWeightG = 1000)),
            Spool("negro-usado", FilamentSpec(material = "PLA", colorHex = "111111", netWeightG = 1000), status = SpoolStatus.IN_USE, remainingG = 300.0),
            Spool("negro-exacto-usado", FilamentSpec(material = "PLA", colorHex = "000000", netWeightG = 1000), status = SpoolStatus.IN_USE, remainingG = 500.0),
            Spool("rojo", FilamentSpec(material = "PLA", colorHex = "E53935", netWeightG = 1000)),
            Spool("petg-negro", FilamentSpec(material = "PETG", colorHex = "000000", netWeightG = 1000)),
        )

        @Test
        fun `elige mismo material, color mas cercano y la que esta en uso`() {
            assertEquals("negro-exacto-usado", SpoolMatcher.best(spools, "PLA", "#000000")?.id)
            assertEquals("rojo", SpoolMatcher.best(spools, "PLA", "FB0207")?.id)
            assertEquals("petg-negro", SpoolMatcher.best(spools, "PETG", "000000")?.id)
            assertNull(SpoolMatcher.best(spools, "PLA", "00FF00"))
        }

        @Test
        fun `nombra los colores sin nombre por el mas parecido`() {
            assertEquals("Negro", ColorNames.label("000000", "", spanish = true))
            assertEquals("Fire red", ColorNames.label("FB0207", "Fire red", spanish = true))
            assertNotNull(ColorNames.nearest("FB0207"))
        }
    }
}
