package dev.pawfect.addons.features.cosmetics

enum class Form(val id: String, vararg val colors: Int) {
    PRISM("prism", 0x9BE8FF, 0xC9A8FF, 0xFFFFFF, 0x7FD6FF),

    EMBER("ember", 0xFF3D14, 0xFF8A26, 0xFFD36B),

    SERAPH("seraph", 0xFFCF5A, 0xFFF2C4, 0xD8962A),

    ABYSS("abyss", 0x8A4CFF, 0x3A1680, 0xD07BFF),

    ASTRAL("astral", 0x9FC7FF, 0xFFFFFF, 0x6A7DFF),

    SYLPH("sylph", 0xFFB8E6, 0x9DFFB0, 0xB6F0FF),
    ;

    val palette = ResolvedName(NameStyle.WAVE, colors, 0.5f, 1f, false, false)

    companion object {
        fun of(id: String?): Form = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: PRISM

        fun legacy(look: String?) = look?.trim()?.lowercase() == "legacy"
    }
}
