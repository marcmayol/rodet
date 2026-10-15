package com.marcmayol.rodet.tags

/**
 * Lo que la persona elige: su maquina, no el formato del tag. Cada familia de
 * impresoras con lector de filamento se traduce al perfil que entiende.
 */
enum class Machine(
    val label: String,
    /** Modelos de ejemplo, para que se reconozca en la lista. */
    val examples: String,
    val profileId: String,
) {
    CREALITY_CFS("Creality CFS", "K2, K2 Plus, K1C, Hi Combo", "creality_cfs"),
    ANYCUBIC_ACE("Anycubic ACE Pro", "Kobra 3, Kobra S1", "anycubic_ace"),
    ELEGOO_CANVAS("Elegoo Canvas", "Centauri Carbon", "elegoo"),
    QIDI_BOX("QIDI Box", "Plus4, Q2", "qidi_box"),
    PRUSA("Prusa (OpenPrintTag)", "Core One, MK4 + MMU", "openprinttag"),
    OPENSPOOL("OpenSpool", "Klipper, OpenSpool reader (Bambu)", "openspool"),
    BAMBU_AMS("Bambu Lab AMS", "X1, P1S, A1", "bambu"),
    ;

    val profile: TagProfile get() = Profiles.byId(profileId)!!

    companion object {
        fun byName(name: String?): Machine? = entries.firstOrNull { it.name == name }
    }
}
