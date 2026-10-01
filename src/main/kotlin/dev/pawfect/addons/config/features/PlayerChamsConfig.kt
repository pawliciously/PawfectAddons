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

    /** Leave capes (vanilla and PA ones) unshaded. */
    @Expose
    var ignoreCape: Boolean = false

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

    /** Visual size of players; 1 is normal. Hitboxes and the server don't change. */
    @Expose
    var scale: Float = 1f

    @Expose
    var scaleTarget: ScaleTarget = ScaleTarget.EVERYONE

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        // Saved configs from before Prism and Ripple were removed come back as null.
        if (style == null) style = Style.GHOST
        if (scaleTarget == null) scaleTarget = ScaleTarget.EVERYONE
    }

    /** The number is the shader's style id (player_chams.fsh). */
    enum class Style(private val label: String, val id: Int) {
        GHOST("Ghost", 0),
        SKETCH("Ink Sketch", 1),
        NEON("Neon Pixels", 4),
        STARS("Stars", 2),
        END_PORTAL("End Portal", 3),
        ;

        override fun toString(): String = label
    }

    enum class ScaleTarget(private val label: String) {
        OTHERS("Other players"),
        SELF("Just you"),
        EVERYONE("Everyone"),
        ;

        override fun toString(): String = label
    }
}
