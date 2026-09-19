package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class PlayerChamsConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var self: Boolean = true

    @Expose
    var others: Boolean = true

    @Expose
    var ignoreNpcs: Boolean = true

    @Expose
    var style: Style = Style.GHOST

    @Expose
    var color: Int = 0x9D4EDD

    @Expose
    var accentColor: Int = 0x7FD8FF

    @Expose
    var tint: Float = 0.35f

    @Expose
    var intensity: Float = 1f

    @Expose
    var speed: Float = 1f

    @Expose
    var opacity: Float = 1f

    @Expose
    var rim: Float = 0.5f

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (style == null) style = Style.GHOST
    }

    enum class Style(private val label: String, val id: Int) {
        GHOST("Ghost", 0),
        PRISM("Prism", 1),
        STARS("Stars", 2),
        END_PORTAL("End Portal", 3),
        RIPPLE("Ripple", 5),
        ;

        override fun toString(): String = label
    }
}
