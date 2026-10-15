package com.marcmayol.rodet.model

/**
 * Valores tipicos de un material. Son orientativos: rellenan huecos con
 * procedencia DEFAULT y nunca pisan lo que ha escrito la persona.
 */
data class MaterialInfo(
    val code: String,                 // codigo canonico: "PLA", "PETG"...
    val aliases: List<String>,        // como aparece en las etiquetas, en minusculas
    val densityGCm3: Double,
    val nozzle: IntRange,
    val bed: IntRange,
    val dryTempC: Int,
    val dryTimeH: Int,
)

data class BrandInfo(
    val name: String,                 // nombre canonico, tal cual se muestra
    val aliases: List<String> = emptyList(),
    val spanish: Boolean = false,
)

data class NamedColor(
    val en: String,
    val es: String,
    val hex: String,                  // RRGGBB
    val aliases: List<String> = emptyList(),
)

data class SubtypeInfo(val label: String, val aliases: List<String>)

object Catalog {

    val materials: List<MaterialInfo> = listOf(
        MaterialInfo("PLA", listOf("pla", "polylactic acid", "acido polilactico"), 1.24, 190..230, 45..65, 50, 6),
        MaterialInfo("PLA+", listOf("pla+", "pla plus", "pla pro", "pla-pro", "pla tough", "tough pla"), 1.24, 200..230, 50..65, 50, 6),
        MaterialInfo("PETG", listOf("petg", "pet-g"), 1.27, 220..250, 70..85, 65, 6),
        MaterialInfo("PCTG", listOf("pctg"), 1.23, 230..260, 70..90, 65, 6),
        MaterialInfo("PET", listOf("pet"), 1.38, 230..260, 70..85, 65, 6),
        MaterialInfo("ABS", listOf("abs", "abs+"), 1.04, 230..260, 90..110, 80, 4),
        MaterialInfo("ASA", listOf("asa"), 1.07, 240..260, 90..110, 80, 4),
        MaterialInfo("HIPS", listOf("hips"), 1.04, 220..250, 90..110, 70, 4),
        MaterialInfo("TPU", listOf("tpu", "tpu 95a", "tpe", "flex", "flexible"), 1.21, 210..240, 30..60, 55, 8),
        MaterialInfo("PA", listOf("pa", "nylon", "pa6", "pa12", "pa66", "paht", "pa-ht", "nailon"), 1.14, 250..280, 70..90, 80, 12),
        MaterialInfo("PC", listOf("pc", "polycarbonate", "policarbonato"), 1.20, 260..300, 100..120, 80, 8),
        MaterialInfo("PP", listOf("pp", "polypropylene", "polipropileno"), 0.90, 220..250, 80..100, 55, 6),
        MaterialInfo("PVA", listOf("pva"), 1.23, 190..220, 45..60, 55, 6),
        MaterialInfo("BVOH", listOf("bvoh"), 1.14, 190..220, 50..60, 55, 6),
        MaterialInfo("PVB", listOf("pvb", "polysmooth"), 1.10, 200..230, 60..75, 45, 4),
        MaterialInfo("PEEK", listOf("peek"), 1.30, 370..420, 120..150, 120, 5),
    )

    val subtypes: List<SubtypeInfo> = listOf(
        SubtypeInfo("Silk", listOf("silk", "seda")),
        SubtypeInfo("Matte", listOf("matte", "mate", "matt")),
        SubtypeInfo("Glow", listOf("glow", "glow in the dark", "luminiscente", "fosforescente")),
        SubtypeInfo("Wood", listOf("wood", "madera")),
        SubtypeInfo("CF", listOf("cf", "carbon fiber", "carbon fibre", "fibra de carbono")),
        SubtypeInfo("GF", listOf("gf", "glass fiber", "glass fibre", "fibra de vidrio")),
        SubtypeInfo("HF", listOf("hf", "high flow")),
        SubtypeInfo("HS", listOf("high speed", "hyper", "rapid")),
        SubtypeInfo("Transparent", listOf("transparent", "translucent", "clear", "transparente", "translucido")),
        SubtypeInfo("Marble", listOf("marble", "marmol")),
        SubtypeInfo("Metal", listOf("metal", "metallic", "metalizado")),
        SubtypeInfo("Sparkle", listOf("sparkle", "galaxy", "glitter", "purpurina")),
        SubtypeInfo("Rainbow", listOf("rainbow", "multicolor", "arcoiris", "dual color", "tri color")),
    )

    val brands: List<BrandInfo> = listOf(
        // Espanolas
        BrandInfo("Smartfil", listOf("smart materials 3d", "smart materials"), spanish = true),
        BrandInfo("Winkle", spanish = true),
        BrandInfo("Sakata 3D", listOf("sakata"), spanish = true),
        BrandInfo("Recreus", listOf("filaflex"), spanish = true),
        BrandInfo("BCN3D", spanish = true),
        BrandInfo("FFFWorld", listOf("fff world"), spanish = true),
        BrandInfo("Filamentos Kaizen", listOf("kaizen"), spanish = true),
        // Internacionales
        BrandInfo("Bambu Lab", listOf("bambu", "bambulab")),
        BrandInfo("Prusament", listOf("prusa", "prusa research")),
        BrandInfo("Polymaker", listOf("polyterra", "polylite", "polymax", "panchroma")),
        BrandInfo("eSUN", listOf("esun")),
        BrandInfo("SUNLU", listOf("sunlu")),
        BrandInfo("Elegoo"),
        BrandInfo("Anycubic"),
        BrandInfo("Creality", listOf("hyper series", "ender")),
        BrandInfo("JAYO"),
        BrandInfo("Eryone"),
        BrandInfo("Overture"),
        BrandInfo("Hatchbox"),
        BrandInfo("Inland"),
        BrandInfo("Geeetech"),
        BrandInfo("Kingroon"),
        BrandInfo("Sovol"),
        BrandInfo("QIDI", listOf("qidi tech")),
        BrandInfo("Flashforge"),
        BrandInfo("Amolen"),
        BrandInfo("Tinmorry"),
        BrandInfo("Kexcelled"),
        BrandInfo("Fiberlogy"),
        BrandInfo("Spectrum", listOf("spectrum filaments")),
        BrandInfo("Devil Design"),
        BrandInfo("Extrudr"),
        BrandInfo("Fillamentum"),
        BrandInfo("colorFabb", listOf("colorfabb")),
        BrandInfo("Formfutura"),
        BrandInfo("DasFilament", listOf("das filament")),
        BrandInfo("3DJake", listOf("3d jake")),
        BrandInfo("Azurefilm"),
        BrandInfo("Rosa3D", listOf("rosa 3d")),
        BrandInfo("Proto-pasta", listOf("protopasta")),
        BrandInfo("MatterHackers", listOf("matterhackers")),
        BrandInfo("Atomic Filament"),
        BrandInfo("Push Plastic"),
        BrandInfo("3D-Fuel", listOf("3d fuel", "3dfuel")),
        BrandInfo("Paramount 3D"),
        BrandInfo("Printed Solid", listOf("jessie")),
        BrandInfo("UltiMaker", listOf("ultimaker")),
        BrandInfo("Raise3D", listOf("raise 3d")),
        BrandInfo("Snapmaker"),
        BrandInfo("Voxelab"),
        BrandInfo("Ziro"),
        BrandInfo("Duramic", listOf("duramic 3d")),
        BrandInfo("Filamentive"),
        BrandInfo("Verbatim"),
        BrandInfo("Basf", listOf("forward am", "ultrafuse")),
        BrandInfo("Nobufil"),
        BrandInfo("R3D"),
        BrandInfo("Giantarm"),
        BrandInfo("IIID Max", listOf("iiidmax")),
        BrandInfo("ZYYX"),
    )

    val colors: List<NamedColor> = listOf(
        NamedColor("Black", "Negro", "000000", listOf("jet black", "negro azabache")),
        NamedColor("White", "Blanco", "FFFFFF", listOf("jade white", "pure white", "blanco puro")),
        NamedColor("Ivory", "Marfil", "FFFFF0", listOf("ivory white")),
        NamedColor("Cream", "Crema", "F3E5AB"),
        NamedColor("Beige", "Beis", "D8C3A5", listOf("beige")),
        NamedColor("Gray", "Gris", "8E9089", listOf("grey")),
        NamedColor("Light Gray", "Gris claro", "C8C8C8", listOf("light grey", "silver gray")),
        NamedColor("Dark Gray", "Gris oscuro", "4A4A4A", listOf("dark grey", "charcoal", "carbon", "carbon black")),
        NamedColor("Space Gray", "Gris espacial", "5B5E62", listOf("space grey")),
        NamedColor("Silver", "Plata", "C0C0C0", listOf("plateado")),
        NamedColor("Gold", "Oro", "D4AF37", listOf("dorado")),
        NamedColor("Bronze", "Bronce", "8C6A3E"),
        NamedColor("Copper", "Cobre", "B87333"),
        NamedColor("Red", "Rojo", "C12E1F"),
        NamedColor("Dark Red", "Rojo oscuro", "8B0000", listOf("burgundy", "burdeos", "wine red", "granate")),
        NamedColor("Scarlet", "Escarlata", "FF2400", listOf("scarlet red")),
        NamedColor("Fire Engine Red", "Rojo bombero", "CE2029"),
        NamedColor("Crimson", "Carmesi", "DC143C"),
        NamedColor("Cherry", "Cereza", "9B111E"),
        NamedColor("Pink", "Rosa", "F55A74"),
        NamedColor("Hot Pink", "Rosa fucsia", "FF69B4", listOf("fuchsia", "fucsia", "magenta pink")),
        NamedColor("Magenta", "Magenta", "EC008C"),
        NamedColor("Light Pink", "Rosa claro", "FFB6C1", listOf("sakura pink", "rosa palo", "pastel pink")),
        NamedColor("Salmon", "Salmon", "FA8072"),
        NamedColor("Coral", "Coral", "FF7F50"),
        NamedColor("Peach", "Melocoton", "FFCBA4"),
        NamedColor("Orange", "Naranja", "FF6A13"),
        NamedColor("Dark Orange", "Naranja oscuro", "E05A00", listOf("pumpkin orange", "calabaza")),
        NamedColor("Yellow", "Amarillo", "F4EE2A"),
        NamedColor("Lemon Yellow", "Amarillo limon", "FFF44F", listOf("lemon", "limon")),
        NamedColor("Sunflower Yellow", "Amarillo girasol", "FEC600", listOf("sunflower", "girasol")),
        NamedColor("Mustard", "Mostaza", "C9A227"),
        NamedColor("Ochre", "Ocre", "CC7722"),
        NamedColor("Green", "Verde", "00AE42"),
        NamedColor("Light Green", "Verde claro", "90EE90", listOf("mint green", "verde menta", "mint", "menta")),
        NamedColor("Dark Green", "Verde oscuro", "1C4D2B", listOf("forest green", "verde bosque", "bambu green")),
        NamedColor("Grass Green", "Verde hierba", "61C680", listOf("verde cesped")),
        NamedColor("Lime", "Lima", "BFFF00", listOf("lime green", "verde lima")),
        NamedColor("Olive", "Oliva", "708238", listOf("olive green", "verde oliva", "army green", "verde militar")),
        NamedColor("Jade", "Jade", "00A86B"),
        NamedColor("Emerald", "Esmeralda", "50C878"),
        NamedColor("Teal", "Verde azulado", "008080"),
        NamedColor("Turquoise", "Turquesa", "40E0D0"),
        NamedColor("Cyan", "Cian", "0086D6"),
        NamedColor("Blue", "Azul", "0A2CA5"),
        NamedColor("Light Blue", "Azul claro", "8AC6E8", listOf("sky blue", "azul cielo", "baby blue", "celeste")),
        NamedColor("Dark Blue", "Azul oscuro", "042F56", listOf("navy", "navy blue", "azul marino")),
        NamedColor("Royal Blue", "Azul real", "4169E1"),
        NamedColor("Cobalt Blue", "Azul cobalto", "0047AB", listOf("cobalt")),
        NamedColor("Ice Blue", "Azul hielo", "A5D8F3"),
        NamedColor("Ocean Blue", "Azul oceano", "1F5F8B"),
        NamedColor("Purple", "Morado", "5E43B7", listOf("purpura")),
        NamedColor("Violet", "Violeta", "8F00FF"),
        NamedColor("Lavender", "Lavanda", "B57EDC", listOf("lilac", "lila")),
        NamedColor("Indigo", "Indigo", "4B0082"),
        NamedColor("Plum", "Ciruela", "8E4585"),
        NamedColor("Brown", "Marron", "9D432C", listOf("chocolate", "cafe")),
        NamedColor("Dark Brown", "Marron oscuro", "4E2A1E", listOf("coffee", "espresso")),
        NamedColor("Light Brown", "Marron claro", "B5835A", listOf("tan", "caramel", "caramelo")),
        NamedColor("Wood", "Madera", "A0784F", listOf("natural wood")),
        NamedColor("Terracotta", "Terracota", "C8553D", listOf("terra cotta")),
        NamedColor("Sand", "Arena", "C2B280", listOf("desert tan")),
        NamedColor("Khaki", "Caqui", "C3B091"),
        NamedColor("Natural", "Natural", "EEE8D5", listOf("nature", "sin color")),
        NamedColor("Transparent", "Transparente", "F2F2F2", listOf("clear", "translucent", "translucido")),
        NamedColor("Skin", "Piel", "F1C27D", listOf("skin tone", "flesh", "carne")),
        NamedColor("Glow Green", "Verde fosforescente", "C8FF8C", listOf("glow in the dark green")),
        NamedColor("Glow Blue", "Azul fosforescente", "8CD8FF"),
        NamedColor("Neon Green", "Verde neon", "39FF14", listOf("fluorescent green", "verde fluor")),
        NamedColor("Neon Yellow", "Amarillo neon", "DFFF00", listOf("fluorescent yellow", "amarillo fluor")),
        NamedColor("Neon Orange", "Naranja neon", "FF5F1F", listOf("fluorescent orange", "naranja fluor")),
        NamedColor("Neon Pink", "Rosa neon", "FF10F0", listOf("fluorescent pink", "rosa fluor")),
        NamedColor("Titanium", "Titanio", "878681", listOf("titan gray")),
        NamedColor("Steel", "Acero", "71797E", listOf("steel gray", "gris acero")),
        NamedColor("Pearl White", "Blanco perla", "EAE0C8", listOf("pearl", "perla")),
        NamedColor("Rose Gold", "Oro rosa", "B76E79"),
        NamedColor("Champagne", "Champan", "F7E7CE"),
        NamedColor("Marble", "Marmol", "D6D3CE"),
        NamedColor("Stone", "Piedra", "928E85", listOf("granite", "granito")),
        NamedColor("Slate", "Pizarra", "708090", listOf("slate gray")),
        NamedColor("Mint", "Menta", "98FF98"),
        NamedColor("Aqua", "Aguamarina", "7FFFD4", listOf("aquamarine")),
        NamedColor("Sapphire", "Zafiro", "0F52BA"),
        NamedColor("Ruby", "Rubi", "9B111F"),
        NamedColor("Amber", "Ambar", "FFBF00"),
        NamedColor("Honey", "Miel", "EBA937"),
        NamedColor("Maroon", "Granate oscuro", "800000"),
        NamedColor("Rainbow", "Arcoiris", "FF7F00", listOf("multicolor")),
        NamedColor("Galaxy Black", "Negro galaxia", "1A1A2E", listOf("sparkle black", "negro purpurina")),
    )

    fun material(code: String): MaterialInfo? {
        val key = code.trim().lowercase()
        return materials.firstOrNull { it.code.lowercase() == key }
            ?: materials.firstOrNull { key in it.aliases }
    }

    fun brand(name: String): BrandInfo? {
        val key = name.trim().lowercase()
        return brands.firstOrNull { it.name.lowercase() == key || key in it.aliases }
    }

    /** Busca un color por su nombre en cualquier idioma, sin distinguir mayusculas. */
    fun color(name: String): NamedColor? {
        val key = TextKeys.fold(name.trim())
        return colors.firstOrNull { c ->
            TextKeys.fold(c.en) == key || TextKeys.fold(c.es) == key ||
                c.aliases.any { TextKeys.fold(it) == key }
        }
    }

    /** Los "5 ultimos materiales" de la UI parten de aqui cuando aun no hay historial. */
    val commonMaterials: List<String> = listOf("PLA", "PETG", "ABS", "TPU", "ASA")
}

/** Utilidades de texto compartidas por el catalogo y el extractor. */
object TextKeys {
    private val ACCENTS = mapOf(
        'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'ã' to 'a',
        'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
        'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
        'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o', 'õ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
        'ñ' to 'n', 'ç' to 'c', 'ß' to 's',
    )

    /** Minusculas y sin acentos, caracter a caracter: la longitud no cambia. */
    fun fold(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) {
            val c = s[i].lowercaseChar()
            out[i] = ACCENTS[c] ?: c
        }
        return String(out)
    }
}
