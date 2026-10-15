package com.marcmayol.rodet.extract

import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.Source
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Etiquetas completas. Son transcripciones reconstruidas con la forma de las
 * etiquetas de cada marca (orden, idioma, abreviaturas), con los errores que
 * suele meter el OCR. Cuando haya fotos reales, sus textos van aqui tambien.
 */
class LabelExtractorTest {

    @Test
    fun `etiqueta tipo eSUN`() {
        val spec = LabelExtractor.extract(
            """
            eSUN
            PLA+
            Diameter: 1.75mm   N.W.: 1KG
            Color: Fire Engine Red
            Printing Temp: 205-225°C  Bed Temp: 60-80°C
            Batch No.: 23061502
            """.trimIndent(),
        )
        assertEquals("eSUN", spec.brand)
        assertEquals("PLA+", spec.material)
        assertEquals(1.75, spec.diameterMm)
        assertEquals(1000, spec.netWeightG)
        assertEquals("Fire Engine Red", spec.colorName)
        assertEquals("CE2029", spec.colorHex)
        assertEquals(205, spec.nozzleTempMin)
        assertEquals(225, spec.nozzleTempMax)
        assertEquals(60, spec.bedTempMin)
        assertEquals(80, spec.bedTempMax)
        assertEquals("23061502", spec.lot)
        assertEquals(Source.CATALOG, spec.provenance[Field.COLOR_HEX]?.source)
    }

    @Test
    fun `etiqueta tipo Smartfil en espanol`() {
        val spec = LabelExtractor.extract(
            """
            SMARTFIL PLA
            Smart Materials 3D
            Color: Azul Marino
            Ø 1,75 mm  Peso neto: 750 g
            Temperatura de impresión: 200-220 ºC
            Temperatura de cama: 50 ºC
            Lote: SF2304B
            """.trimIndent(),
        )
        assertEquals("Smartfil", spec.brand)
        assertEquals("PLA", spec.material)
        assertEquals("Azul Marino", spec.colorName)
        assertEquals("042F56", spec.colorHex)
        assertEquals(1.75, spec.diameterMm)
        assertEquals(750, spec.netWeightG)
        assertEquals(200, spec.nozzleTempMin)
        assertEquals(50, spec.bedTempMin)
        assertEquals("SF2304B", spec.lot)
    }

    @Test
    fun `etiqueta tipo Bambu con fibra de carbono y hex`() {
        val spec = LabelExtractor.extract(
            """
            Bambu Lab
            PLA-CF  Matte Charcoal
            1.75 mm  1 kg
            Nozzle temperature 210-240 °C
            Heatbed 45-65 °C
            #333333
            """.trimIndent(),
        )
        assertEquals("Bambu Lab", spec.brand)
        assertEquals("PLA", spec.material)
        assertTrue("CF" in spec.subtype.split(" "))
        assertTrue("Matte" in spec.subtype.split(" "))
        assertEquals("333333", spec.colorHex)
        assertEquals(Source.OCR, spec.provenance[Field.COLOR_HEX]?.source)
        assertEquals(1000, spec.netWeightG)
        assertEquals(45, spec.bedTempMin)
        assertEquals(65, spec.bedTempMax)
    }

    @Test
    fun `etiqueta tipo Prusament con densidad y peso bruto`() {
        val spec = LabelExtractor.extract(
            """
            PRUSAMENT PETG
            Galaxy Black
            Diameter 1.75 ± 0.02 mm
            Net weight 1 kg   Gross weight 1.2 kg
            Density 1.27 g/cm³
            Nozzle 240 ± 10 °C   Bed 80 °C
            """.trimIndent(),
        )
        assertEquals("Prusament", spec.brand)
        assertEquals("PETG", spec.material)
        assertEquals("Galaxy Black", spec.colorName)
        assertEquals(1000, spec.netWeightG)
        assertEquals(200, spec.spoolWeightG)
        assertEquals(Source.DERIVED, spec.provenance[Field.SPOOL_WEIGHT]?.source)
        assertEquals(1.27, spec.densityGCm3)
        assertEquals(Source.OCR, spec.provenance[Field.DENSITY]?.source)
        assertEquals(240, spec.nozzleTempMin)
        assertEquals(80, spec.bedTempMin)
    }

    @Test
    fun `etiqueta tipo SUNLU con errores de OCR`() {
        val spec = LabelExtractor.extract(
            """
            5UNLU  SILK PIA
            Gold
            Nozz1e: 205-230 C
            Bed: 50-65 C
            NW:1,0KG
            1.75mm
            Drying: 50 °C 4-6h
            """.trimIndent(),
        )
        assertEquals("SUNLU", spec.brand)
        assertTrue(spec.provenance[Field.BRAND]!!.needsReview)
        assertEquals("PLA", spec.material)
        assertEquals("Silk", spec.subtype)
        assertEquals("Gold", spec.colorName)
        assertEquals(205, spec.nozzleTempMin)
        assertEquals(230, spec.nozzleTempMax)
        assertEquals(50, spec.bedTempMin)
        assertEquals(1000, spec.netWeightG)
        assertEquals(50, spec.dryTempC)
        assertEquals(6, spec.dryTimeH)
    }

    @Test
    fun `los huecos se rellenan con valores del material como DEFAULT`() {
        val spec = LabelExtractor.extract("PETG 1.75mm")
        assertEquals(1.27, spec.densityGCm3)
        assertEquals(Source.DEFAULT, spec.provenance[Field.DENSITY]?.source)
        assertEquals(220, spec.nozzleTempMin)
        assertEquals(Source.DEFAULT, spec.provenance[Field.NOZZLE_MIN]?.source)
    }

    @Test
    fun `la palabra carbon de carbon fiber no es un color`() {
        val spec = LabelExtractor.extract("PETG Carbon Fiber\nColor: Black")
        assertEquals("Black", spec.colorName)
        assertTrue("CF" in spec.subtype)
    }

    @Test
    fun `sin pista de color se usa el nombre mas largo, con dudas`() {
        val spec = LabelExtractor.extract("PLA Fire Engine Red 1kg")
        assertEquals("Fire Engine Red", spec.colorName)
        assertTrue(spec.provenance[Field.COLOR_NAME]!!.needsReview)
    }

    @Test
    fun `mil gramos con separador de miles`() {
        assertEquals(1000, LabelExtractor.extract("Net Weight: 1,000g").netWeightG)
        assertEquals(500, LabelExtractor.extract("0.5 kg PLA").netWeightG)
    }

    @Test
    fun `texto vacio no revienta`() {
        val spec = LabelExtractor.extract("")
        assertEquals("", spec.material)
        assertTrue(spec.provenance.isEmpty())
    }

    @Test
    fun `distancia de edicion`() {
        assertEquals(0, LabelExtractor.levenshtein("sunlu", "sunlu"))
        assertEquals(1, LabelExtractor.levenshtein("sunlv", "sunlu"))
        assertEquals(3, LabelExtractor.levenshtein("kitten", "sitting"))
    }
}
