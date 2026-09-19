package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class TooltipConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var frame: Frame = Frame.PLATE

    @Expose
    var border: BorderSource = BorderSource.RARITY

    @Expose
    var opacity: Float = 1f

    @Expose
    var scale: Float = 1f

    @Expose
    var radius: Float = 4f

    @Expose
    var inset: Float = 4f

    @Expose
    var shadow: Boolean = true

    @Expose
    var restyleFont: Boolean = true

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (frame == null) frame = Frame.PLATE
        if (border == null) border = BorderSource.RARITY
        if (scale < 0.7f || scale > 1.6f) scale = scale.coerceIn(0.7f, 1.6f)
    }

    enum class Frame(private val label: String) {
        PLATE("Plate"),
        FLAT("Flat"),
        GLASS("Glass"),
        ;

        override fun toString(): String = label
    }

    enum class BorderSource(private val label: String) {
        RARITY("Item Rarity"),
        ACCENT("Theme Accent"),
        BORDER("Theme Border"),
        NONE("None"),
        ;

        override fun toString(): String = label
    }
}
