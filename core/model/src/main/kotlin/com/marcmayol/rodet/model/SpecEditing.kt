package com.marcmayol.rodet.model

/** Reglas del editor que no dependen de la UI. */
object SpecEditing {

    const val DEFAULT_CONFIDENCE = 0.5

    /**
     * La persona ha tocado un campo: su valor manda. Procedencia MANUAL con
     * confianza 1.0, que la UI pinta sin marca.
     */
    fun editManually(spec: FilamentSpec, field: Field, value: Any?): FilamentSpec {
        val updated = field.set(spec, value)
        return updated.copy(provenance = updated.provenance + (field to Provenance.MANUAL))
    }

    /**
     * Al elegir material se rellenan los campos vacios o marcados como DEFAULT
     * con los valores tipicos de ese material. Lo demas (manual, OCR, catalogo)
     * no se pisa nunca.
     */
    fun applyMaterialDefaults(spec: FilamentSpec): FilamentSpec =
        Catalog.material(spec.material)?.let { applyMaterialDefaults(spec, it) } ?: spec

    fun applyMaterialDefaults(spec: FilamentSpec, material: MaterialInfo): FilamentSpec {
        val defaults: Map<Field, Any> = mapOf(
            Field.DENSITY to material.densityGCm3,
            Field.NOZZLE_MIN to material.nozzle.first,
            Field.NOZZLE_MAX to material.nozzle.last,
            Field.BED_MIN to material.bed.first,
            Field.BED_MAX to material.bed.last,
            Field.DRY_TEMP to material.dryTempC,
            Field.DRY_TIME to material.dryTimeH,
        )
        var s = spec
        val p = spec.provenance.toMutableMap()
        for ((field, value) in defaults) {
            if (!isReplaceable(spec, field)) continue
            s = field.set(s, value)
            p[field] = Provenance(Source.DEFAULT, DEFAULT_CONFIDENCE, material.code)
        }
        return s.copy(provenance = p)
    }

    /** Elegir material desde el desplegable: se fija el material y se rellenan huecos. */
    fun chooseMaterial(spec: FilamentSpec, code: String): FilamentSpec {
        val edited = editManually(spec, Field.MATERIAL, code)
        val info = Catalog.material(code) ?: return edited
        return applyMaterialDefaults(edited.copy(material = info.code), info)
    }

    /** Elegir un color con nombre: el nombre es manual, el hex sale del catalogo. */
    fun chooseNamedColor(spec: FilamentSpec, color: NamedColor, spanish: Boolean): FilamentSpec {
        val name = if (spanish) color.es else color.en
        val withName = editManually(spec, Field.COLOR_NAME, name)
        return withName.copy(
            colorHex = color.hex,
            provenance = withName.provenance + (Field.COLOR_HEX to Provenance(Source.CATALOG, 1.0, name)),
        )
    }

    /** Plantilla vacia con los valores por defecto de un material. */
    fun blank(materialCode: String? = null): FilamentSpec {
        val base = FilamentSpec()
        return if (materialCode == null) base else chooseMaterial(base, materialCode)
    }

    private fun isReplaceable(spec: FilamentSpec, field: Field): Boolean =
        spec.isEmpty(field) || spec.provenance[field]?.source == Source.DEFAULT
}
