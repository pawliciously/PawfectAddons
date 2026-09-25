package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class HandChamsConfig {

    @Expose
    var halfResolution: Boolean = false

    @Expose
    var hideEnchantGlint: Boolean = false

    @Expose
    var opacity: Float = 0.9f

    @Expose
    var edgeSoftness: Float = 0.5f

    @Expose
    var saturationEnabled: Boolean = false

    @Expose
    var saturation: Float = 1.1f

    @Expose
    var tintEnabled: Boolean = false

    @Expose
    var tintColor: Int = 0x7FD8FF

    @Expose
    var tintStrength: Float = 0.25f

    @Expose
    var outlineEnabled: Boolean = false

    @Expose
    var outlineColor: Int = 0x9D4EDD

    @Expose
    var outlineRarityColor: Boolean = false

    @Expose
    var outlineThickness: Float = 1.5f

    @Expose
    var outlineOpacity: Float = 1f

    @Expose
    var outlineSoftness: Float = 0.15f

    @Expose
    var trailEnabled: Boolean = false

    @Expose
    var trailDecay: Float = 0.82f

    @Expose
    var trailStrength: Float = 0.7f

    @Expose
    var trailColor: Int = 0xFF7FD8FF.toInt()

    @Expose
    var trailTinted: Boolean = true

    @Expose
    var overlay: OverlayEffect = OverlayEffect.NONE

    @Expose
    var overlayStrength: Float = 0.6f

    @Expose
    var overlaySpeed: Float = 1f

    @Expose
    var overlayCustomColors: Boolean = false

    @Expose
    var overlayColorA: Int = 0x6A2CA0

    @Expose
    var overlayColorB: Int = 0x1E3F9E

    @Expose
    var overlayColorC: Int = 0xFFE0F5

    @Expose
    var portalLayers: Int = 15

    @Expose
    var debugView: DebugView = DebugView.OFF

    val bodyActive: Boolean
        get() = tintEnabled || saturationEnabled || overlay != OverlayEffect.NONE

    val anyActive: Boolean
        get() = bodyActive || outlineEnabled || trailEnabled

    fun disableAll() {
        tintEnabled = false
        saturationEnabled = false
        outlineEnabled = false
        trailEnabled = false
        overlay = OverlayEffect.NONE
    }

    enum class DebugView(private val label: String, val id: Int) {
        OFF("Off", 0),
        MASK("Mask", 1),
        GLOW("Blurred Mask", 2),
        BLUR("Scene", 3),
        TRAIL("Trail", 4),
        ;

        override fun toString(): String = label
    }

    enum class OverlayEffect(private val label: String, val id: Int) {
        NONE("None", 0),
        LIQUID("Liquid", 1),
        END_PORTAL("End Portal", 2),
        OIL_SLICK("Oil Slick", 3),
        HOLOGRAM("Hologram", 4),
        SPACE("Space", 5),
        STARS("Stars", 6),
        ;

        override fun toString(): String = label
    }
}
