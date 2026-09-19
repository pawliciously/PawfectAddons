package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class VisualsConfig {

    @Expose
    var lavaChanger: Boolean = false

    @Expose
    var hideLavaFog: Boolean = true

    @Expose
    var hideTooltips: Boolean = false

    @Expose
    var batHighlighter: Boolean = false

    @Expose
    var batColor: HighlightColor = HighlightColor.PINK

    @Expose
    var batRange: Int = 30

    @Expose
    var starGlow: Boolean = false

    @Expose
    var starColor: Int = 0xFFAA00

    @Expose
    var starOpacity: Float = 0.75f

    @Expose
    var starRadius: Float = 12f

    @Expose
    var starMinibosses: Boolean = true

    @Expose
    var starFels: Boolean = true

    enum class HighlightColor(private val label: String, val rgb: Int) {
        PINK("Pink", 0xFF69B4),
        LIME("Lime", 0x55FF55),
        CYAN("Cyan", 0x55FFFF),
        YELLOW("Yellow", 0xFFFF55),
        ORANGE("Orange", 0xFFAA00),
        PURPLE("Purple", 0x9D4EDD),
        WHITE("White", 0xFFFFFF),
        RED("Red", 0xFF5555),
        ;

        override fun toString(): String = label
    }
}
