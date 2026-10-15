package com.marcmayol.rodet.extract

import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.Source
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Un test por cada trampa de la seccion 5 del plan. El nombre lleva su numero. */
class TrapRegressionTest {

    private fun ocr(s: String) = LabelExtractor.extract(s)

    // --- Trampa 1 -----------------------------------------------------------

    @Test
    fun `trampa 1 - el ordinal º no se convierte en un cero`() {
        val spec = ocr("Drying: 75ºC 6h")
        assertEquals(75, spec.dryTempC)
        assertEquals(6, spec.dryTimeH)
    }

    @Test
    fun `trampa 1 - variantes del simbolo de grado`() {
        assertEquals(205, ocr("Nozzle: 205˚C").nozzleTempMin)
        assertEquals(60, ocr("Bed: 60℃").bedTempMin)
        assertEquals(215, ocr("Nozzle 215 °C").nozzleTempMin)
    }

    @Test
    fun `trampa 1 - una o leida en lugar del grado es un grado`() {
        assertEquals(75, ocr("Drying temp 75oC").dryTempC)
    }

    // --- Trampa 2 -----------------------------------------------------------

    @Test
    fun `trampa 2 - un cero pegado a un numero no es un grado`() {
        val spec = ocr("Bed Temp: 60 C")
        assertEquals(60, spec.bedTempMin)
        assertEquals(60, spec.bedTempMax)
    }

    @Test
    fun `trampa 2 - tampoco sin espacio`() {
        assertEquals(60, ocr("Bed Temp: 60C").bedTempMin)
        assertEquals(200, ocr("Nozzle: 200C").nozzleTempMin)
    }

    // --- Trampa 3 -----------------------------------------------------------

    @Test
    fun `trampa 3 - manda la pista mas cercana, no la mas frecuente`() {
        val spec = ocr("Print Temp: 205-225 C  Bed Temp: 60 C")
        assertEquals(205, spec.nozzleTempMin)
        assertEquals(225, spec.nozzleTempMax)
        assertEquals(60, spec.bedTempMin)
    }

    @Test
    fun `trampa 3 - en una sola linea con muchas temperaturas`() {
        val spec = ocr("Printing temp 190-220°C Printing temp recommended, Bed temp 50-60°C, Dry 55°C 8h")
        assertEquals(190, spec.nozzleTempMin)
        assertEquals(220, spec.nozzleTempMax)
        assertEquals(50, spec.bedTempMin)
        assertEquals(60, spec.bedTempMax)
        assertEquals(55, spec.dryTempC)
        assertEquals(8, spec.dryTimeH)
    }

    // --- Trampa 4 -----------------------------------------------------------

    @Test
    fun `trampa 4 - temperaturas sin unidad tras una pista`() {
        val spec = ocr("Nozzle 190-230\nBed 35-45")
        assertEquals(190, spec.nozzleTempMin)
        assertEquals(230, spec.nozzleTempMax)
        assertEquals(35, spec.bedTempMin)
        assertEquals(45, spec.bedTempMax)
    }

    @Test
    fun `trampa 4 - sin unidad y lejos de la pista no se asigna`() {
        val spec = ocr("Nozzle: see the website for the recommended settings 190-230")
        assertNull(spec.provenance[Field.NOZZLE_MIN]?.takeIf { it.source == Source.OCR })
    }

    @Test
    fun `trampa 4 - sin unidad la confianza es menor que con unidad`() {
        val sin = ocr("Nozzle 190-230").provenance[Field.NOZZLE_MIN]!!.confidence
        val con = ocr("Nozzle 190-230°C").provenance[Field.NOZZLE_MIN]!!.confidence
        assertTrue(sin < con)
    }

    // --- Trampa 5 -----------------------------------------------------------

    @Test
    fun `trampa 5 - el No de Nozzle no es un numero de lote`() {
        val spec = ocr("Nozzle 190-230°C")
        assertEquals("", spec.lot)
    }

    @Test
    fun `trampa 5 - sin exigir el punto, Nozz1e daria el lote zz1e`() {
        // Exigir un digito no basta: el OCR mete uno en `Nozz1e`.
        assertEquals("", ocr("Nozz1e 200-220°C").lot)
        assertEquals("", ocr("NOZZ1E: 210°C").lot)
    }

    @Test
    fun `trampa 5 - lote con No punto`() {
        assertEquals("A2304-17", ocr("Batch No.: A2304-17").lot)
        assertEquals("2023051201", ocr("No. 2023051201").lot)
        assertEquals("LT882", ocr("Lote: LT882").lot)
    }

    @Test
    fun `trampa 5 - un lote sin digitos no es un lote`() {
        assertEquals("", ocr("Lot: ABCDE").lot)
    }

    // --- Trampa 6 -----------------------------------------------------------

    @Test
    fun `trampa 6 - 175mm pegado a la unidad`() {
        assertEquals(1.75, ocr("PLA 1.75mm 1KG").diameterMm)
        assertEquals(2.85, ocr("PETG 2,85mm").diameterMm)
    }

    @Test
    fun `trampa 6 - valores que solo resuelve la regla de la unidad`() {
        // 1.75 lo salva tambien la forma decimal; estos no tienen esa red.
        assertEquals(3.00, ocr("PLA 3mm").diameterMm)
        assertEquals(2.85, ocr("PLA 2.9mm").diameterMm)
    }

    @Test
    fun `trampa 6 - con tolerancia detras`() {
        assertEquals(1.75, ocr("Diameter: 1.75mm±0.02mm").diameterMm)
        assertEquals(1.75, ocr("Diameter 1.75 +/- 0.03 mm").diameterMm)
    }

    // --- Trampa 7 -----------------------------------------------------------

    @Test
    fun `trampa 7 - un entero suelto no es un diametro`() {
        assertNull(ocr("PLA 3 colores").diameterMm)
    }

    @Test
    fun `trampa 7 - con simbolo o unidad si lo es`() {
        assertEquals(3.00, ocr("Ø3 PLA").diameterMm)
        assertEquals(3.00, ocr("PLA 3 mm").diameterMm)
        assertEquals(1.75, ocr("⌀ 1.75").diameterMm)
    }

    @Test
    fun `trampa 7 - el diametro de boquilla no es el del filamento`() {
        assertNull(ocr("Recommended for 0.4mm nozzle").diameterMm)
    }

    // --- Trampa 8 -----------------------------------------------------------

    @Test
    fun `trampa 8 - PIA se lee como PLA, con confianza de revision`() {
        val spec = ocr("eSUN PIA+ 1.75mm")
        assertEquals("PLA+", spec.material)
        assertTrue(spec.provenance[Field.MATERIAL]!!.needsReview)
    }

    @Test
    fun `trampa 8 - Nozz1e sigue siendo una pista de boquilla`() {
        val spec = ocr("Nozz1e Temp: 200-220°C")
        assertEquals(200, spec.nozzleTempMin)
        assertEquals(220, spec.nozzleTempMax)
    }

    @Test
    fun `trampa 8 - 8ed y P1A`() {
        val spec = ocr("P1A\n8ed temp 55°C")
        assertEquals("PLA", spec.material)
        assertEquals(55, spec.bedTempMin)
    }

    @Test
    fun `trampa 8 - digitos confundidos dentro de un numero`() {
        assertEquals(205, ocr("Nozzle 2O5°C").nozzleTempMin)
        assertEquals(210, ocr("Nozzle 2l0°C").nozzleTempMin)
    }

    @Test
    fun `trampa 8 - la lectura limpia tiene confianza alta`() {
        val p = ocr("PLA").provenance[Field.MATERIAL]!!
        assertTrue(p.confidence >= 0.9)
    }

    // --- Trampa 9 -----------------------------------------------------------

    @Test
    fun `trampa 9 - el plegado de acentos conserva la longitud`() {
        val s = "Temperatura de impresión: 200 ºC — Cama: 60 ºC Ø1,75 ＰＬＡ ２１０"
        val p = PreparedText(s)
        assertEquals(s.length, p.folded.length)
        assertEquals(s.length, p.skeleton.length)
    }

    @Test
    fun `trampa 9 - la evidencia apunta al texto original con sus acentos`() {
        val spec = ocr("Marca: Smartfil\nTemperatura de impresión: 200-220 ºC\nCama: 55 ºC")
        val evidence = spec.provenance[Field.NOZZLE_MIN]!!.evidence
        assertEquals("Temperatura de impresión: 200-220 ºC", evidence)
        assertEquals(200, spec.nozzleTempMin)
        assertEquals(55, spec.bedTempMin)
    }

    @Test
    fun `trampa 9 - la evidencia de un lote conserva mayusculas`() {
        val spec = ocr("Número de lote nº: Ab-3C9")
        assertEquals("Ab-3C9", spec.lot)
    }

    // --- Trampa 10 (CBOR) vive en core-tags: CborTest -----------------------

    @Test
    fun `sin unidad y sin pista una temperatura no se inventa`() {
        val spec = ocr("PLA 200")
        assertNotEquals(Source.OCR, spec.provenance[Field.NOZZLE_MIN]?.source)
    }
}
