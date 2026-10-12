package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class AmbientMotesConfig {

    enum class Palette(private val label: String) {
        THEME("Theme"),
        PRISM("Prism"),
        EMBER("Ember"),
        SERAPH("Seraph"),
        ABYSS("Abyss"),
        ASTRAL("Astral"),
        SYLPH("Sylph"),
        CUSTOM("Custom");

        override fun toString(): String = label
    }

    enum class Look(private val label: String) {
        GLOW("Glow"),
        SPARKS("Sparks"),
        MIXED("Mixed");

        override fun toString(): String = label
    }

    @Expose
    var enabled: Boolean = false

    @Expose
    var distance: Int = 96

    @Expose
    var count: Int = 220

    @Expose
    var palette: Palette = Palette.SYLPH

    @Expose
    var customColor: Int = 0xFFE9A8

    @Expose
    var look: Look = Look.MIXED

    @Expose
    var size: Float = 1f

    @Expose
    var brightness: Float = 1f

    @Expose
    var speed: Float = 1f

    @Expose
    var inDungeons: Boolean = true
}
