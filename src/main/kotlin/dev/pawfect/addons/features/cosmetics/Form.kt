package dev.pawfect.addons.features.cosmetics

/**
 * The themes the new motes, trails and ripples come in, each loosely matching a celestial set
 * and carrying the colours it was made with. Ids match the website's.
 */
enum class Form(val id: String, vararg val colors: Int) {
    /** Cut crystal, with Prism wings and the Facet halo. */
    PRISM("prism", 0x9BE8FF, 0xC9A8FF, 0xFFFFFF, 0x7FD6FF),

    /** Fire and embers, with Phoenix wings and the Ember halo. */
    EMBER("ember", 0xFF3D14, 0xFF8A26, 0xFFD36B),

    /** Gold light and feathers, with Seraph wings and the Gilded halo. */
    SERAPH("seraph", 0xFFCF5A, 0xFFF2C4, 0xD8962A),

    /** Dark light, with Abyssal wings, the Eclipse halo and Void horns. */
    ABYSS("abyss", 0x8A4CFF, 0x3A1680, 0xD07BFF),

    /** Stars and constellations, with Celestial wings and the Astral halo. */
    ASTRAL("astral", 0x9FC7FF, 0xFFFFFF, 0x6A7DFF),

    /** Petals and fairy light, with Sylph wings. */
    SYLPH("sylph", 0xFFB8E6, 0x9DFFB0, 0xB6F0FF),
    ;

    /** The theme's own palette, drifting slowly through its colours. */
    val palette = ResolvedName(NameStyle.WAVE, colors, 0.5f, 1f, false, false)

    companion object {
        fun of(id: String?): Form = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: PRISM

        /** Saved configs from before the toggle have no look, and get the new one. */
        fun legacy(look: String?) = look?.trim()?.lowercase() == "legacy"
    }
}
