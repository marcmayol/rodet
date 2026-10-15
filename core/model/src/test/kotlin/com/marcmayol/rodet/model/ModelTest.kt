package com.marcmayol.rodet.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {

    @Nested
    inner class Normalizacion {
        @Test
        fun `ajusta el diametro al nominal dentro de la tolerancia`() {
            assertEquals(1.75, Normalization.snapDiameter(1.74))
            assertEquals(2.85, Normalization.snapDiameter(2.9))
            assertEquals(3.00, Normalization.snapDiameter(3.05))
            assertEquals(1.60, Normalization.snapDiameter(1.60))
        }

        @Test
        fun `ajusta el peso al nominal dentro del 5 por ciento`() {
            assertEquals(1000, Normalization.snapWeight(980))
            assertEquals(250, Normalization.snapWeight(255))
            assertEquals(800, Normalization.snapWeight(800))
        }

        @Test
        fun `normaliza el hex`() {
            assertEquals("FF0000", Normalization.normalizeHex("#ff0000"))
            assertEquals("AABBCC", Normalization.normalizeHex("abc"))
            assertNull(Normalization.normalizeHex("zz0000"))
            assertNull(Normalization.normalizeHex("#12345"))
        }

        @Test
        fun `corrige un rango invertido y mueve su procedencia`() {
            val pMin = Provenance(Source.OCR, 0.9, "230")
            val pMax = Provenance(Source.OCR, 0.7, "190")
            val spec = FilamentSpec(
                nozzleTempMin = 230, nozzleTempMax = 190,
                provenance = mapOf(Field.NOZZLE_MIN to pMin, Field.NOZZLE_MAX to pMax),
            )
            val n = Normalization.normalize(spec)
            assertEquals(190, n.nozzleTempMin)
            assertEquals(230, n.nozzleTempMax)
            assertEquals(pMax, n.provenance[Field.NOZZLE_MIN])
            assertEquals(pMin, n.provenance[Field.NOZZLE_MAX])
        }
    }

    @Nested
    inner class Longitud {
        @Test
        fun `1 kg de PLA a 175 son unos 335 m`() {
            val spec = FilamentSpec(netWeightG = 1000, densityGCm3 = 1.24, diameterMm = 1.75)
            val m = spec.estimatedLengthM()!!
            assertTrue(m in 330.0..340.0, "salio $m")
        }

        @Test
        fun `sin densidad no hay longitud`() {
            assertNull(FilamentSpec(netWeightG = 1000, diameterMm = 1.75).estimatedLengthM())
        }
    }

    @Nested
    inner class Validacion {
        @Test
        fun `sin material es bloqueante`() {
            val issues = Validation.validate(FilamentSpec())
            assertTrue(Validation.hasBlocking(issues))
            assertTrue(issues.any { it.code == IssueCode.MISSING_MATERIAL })
        }

        @Test
        fun `el color solo es obligatorio si el perfil lo exige`() {
            val spec = FilamentSpec(material = "PLA")
            assertFalse(Validation.hasBlocking(Validation.validate(spec)))
            assertTrue(Validation.validate(spec, requireColor = true).any { it.code == IssueCode.MISSING_COLOR })
        }

        @Test
        fun `un PLA a 260 es advertencia, no bloqueo`() {
            val issues = Validation.validate(FilamentSpec(material = "PLA", nozzleTempMax = 260))
            val issue = issues.single { it.field == Field.NOZZLE_MAX }
            assertEquals(Severity.WARNING, issue.severity)
            assertEquals(IssueCode.TEMP_UNUSUAL_FOR_MATERIAL, issue.code)
        }

        @Test
        fun `una boquilla a 900 grados es bloqueante y no duplica aviso`() {
            val issues = Validation.validate(FilamentSpec(material = "PLA", nozzleTempMax = 900))
            val issue = issues.single { it.field == Field.NOZZLE_MAX }
            assertEquals(IssueCode.OUT_OF_PHYSICAL_RANGE, issue.code)
        }

        @Test
        fun `diametro no nominal es advertencia`() {
            val issues = Validation.validate(FilamentSpec(material = "PLA", diameterMm = 1.60))
            assertEquals(Severity.WARNING, issues.single().severity)
        }

        @Test
        fun `rango invertido es bloqueante`() {
            val issues = Validation.validate(FilamentSpec(material = "PLA", bedTempMin = 70, bedTempMax = 50))
            assertTrue(issues.any { it.code == IssueCode.RANGE_INVERTED && it.severity == Severity.BLOCKING })
        }

        @Test
        fun `hex invalido es bloqueante`() {
            val issues = Validation.validate(FilamentSpec(material = "PLA", colorHex = "red"))
            assertTrue(issues.any { it.code == IssueCode.INVALID_COLOR_HEX })
        }
    }

    @Nested
    inner class Editor {
        @Test
        fun `elegir material rellena los vacios`() {
            val spec = SpecEditing.chooseMaterial(FilamentSpec(), "petg")
            assertEquals("PETG", spec.material)
            assertEquals(220, spec.nozzleTempMin)
            assertEquals(1.27, spec.densityGCm3)
            assertEquals(Source.DEFAULT, spec.provenance[Field.NOZZLE_MIN]?.source)
            assertEquals(Source.MANUAL, spec.provenance[Field.MATERIAL]?.source)
        }

        @Test
        fun `elegir material no pisa lo editado a mano`() {
            var spec = SpecEditing.editManually(FilamentSpec(), Field.NOZZLE_MAX, 215)
            spec = SpecEditing.chooseMaterial(spec, "PLA")
            assertEquals(215, spec.nozzleTempMax)
            assertEquals(Source.MANUAL, spec.provenance[Field.NOZZLE_MAX]?.source)
            assertEquals(190, spec.nozzleTempMin)
        }

        @Test
        fun `elegir material no pisa lo leido por OCR`() {
            val spec = FilamentSpec(
                bedTempMin = 60,
                provenance = mapOf(Field.BED_MIN to Provenance(Source.OCR, 0.9, "60")),
            )
            assertEquals(60, SpecEditing.chooseMaterial(spec, "PETG").bedTempMin)
        }

        @Test
        fun `cambiar de material reemplaza los valores por defecto anteriores`() {
            val pla = SpecEditing.chooseMaterial(FilamentSpec(), "PLA")
            val abs = SpecEditing.chooseMaterial(pla, "ABS")
            assertEquals(90, abs.bedTempMin)
            assertEquals(1.04, abs.densityGCm3)
        }

        @Test
        fun `editar un campo lo pasa a manual con confianza 1`() {
            val spec = FilamentSpec(
                brand = "Esun",
                provenance = mapOf(Field.BRAND to Provenance(Source.OCR, 0.6, "Esun")),
            )
            val p = SpecEditing.editManually(spec, Field.BRAND, "eSUN").provenance[Field.BRAND]!!
            assertEquals(Source.MANUAL, p.source)
            assertEquals(1.0, p.confidence)
            assertFalse(p.needsReview)
        }

        @Test
        fun `ocr con confianza baja pide revision`() {
            assertTrue(Provenance(Source.OCR, 0.79).needsReview)
            assertFalse(Provenance(Source.OCR, 0.8).needsReview)
            assertFalse(Provenance(Source.DEFAULT, 0.5).needsReview)
        }

        @Test
        fun `color con nombre deja el hex con procedencia de catalogo`() {
            val red = Catalog.color("rojo")!!
            val spec = SpecEditing.chooseNamedColor(FilamentSpec(), red, spanish = true)
            assertEquals("Rojo", spec.colorName)
            assertEquals(red.hex, spec.colorHex)
            assertEquals(Source.CATALOG, spec.provenance[Field.COLOR_HEX]?.source)
        }
    }

    @Nested
    inner class Catalogo {
        @Test
        fun `busca materiales por alias`() {
            assertEquals("PA", Catalog.material("Nylon")?.code)
            assertEquals("PLA+", Catalog.material("pla pro")?.code)
        }

        @Test
        fun `busca colores en los dos idiomas y sin acentos`() {
            assertEquals("Brown", Catalog.color("Marrón")?.en)
            assertEquals("Dark Blue", Catalog.color("azul marino")?.en)
        }

        @Test
        fun `las marcas espanolas estan`() {
            listOf("Smartfil", "Winkle", "Sakata 3D", "Recreus", "BCN3D").forEach {
                assertTrue(Catalog.brand(it)?.spanish == true, it)
            }
        }

        @Test
        fun `todos los hex del catalogo son validos`() {
            Catalog.colors.forEach { assertEquals(it.hex, Normalization.normalizeHex(it.hex), it.en) }
        }

        @Test
        fun `el catalogo tiene el tamano del plan`() {
            assertEquals(16, Catalog.materials.size)
            assertTrue(Catalog.brands.size >= 50)
            assertTrue(Catalog.colors.size >= 85)
        }

        @Test
        fun `las claves de campo son unicas`() {
            assertEquals(Field.entries.size, Field.entries.map { it.key }.toSet().size)
            Field.entries.forEach { assertEquals(it, Field.fromKey(it.key)) }
        }
    }
}
