package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class SkyboxConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var effect: SkyEffect = SkyEffect.NEBULA

    @Expose
    var quality: SkyQuality = SkyQuality.MEDIUM

    @Expose
    var renderScale: Float = 1f

    @Expose
    var featureScale: Float = 1f

    @Expose
    var intensity: Float = 0.7f

    @Expose
    var brightness: Float = 1f

    @Expose
    var speed: Float = 1f

    @Expose
    var seed: Int = 1

    @Expose
    var onlyAtNight: Boolean = false

    @Expose
    var customColors: Boolean = false

    @Expose
    var colorPrimary: Int = SkyEffect.NEBULA.primary

    @Expose
    var colorSecondary: Int = SkyEffect.NEBULA.secondary

    @Expose
    var colorAccent: Int = SkyEffect.NEBULA.accent

    fun primary(): Int = if (customColors) colorPrimary else effect.primary

    fun secondary(): Int = if (customColors) colorSecondary else effect.secondary

    fun accent(): Int = if (customColors) colorAccent else effect.accent

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (effect == null) effect = SkyEffect.NEBULA
        if (quality == null) quality = SkyQuality.MEDIUM
        if (seed < 1 || seed > 999) seed = 1
        if (featureScale < 0.3f || featureScale > 3f) featureScale = featureScale.coerceIn(0.3f, 3f)
    }

    enum class SkyEffect(
        private val label: String,
        val id: Int,
        val randomised: Boolean,
        val primary: Int,
        val secondary: Int,
        val accent: Int,
    ) {
        NEBULA("Nebula", 1, true, 0xFF3FA8, 0x2B47D6, 0xFFD27A),
        LIQUID("Liquid", 2, true, 0x3FC7FF, 0x7A3FFF, 0xFFF2D0),
        STARS("Stars", 3, false, 0x9D73FF, 0x1E2A5E, 0xFFF6E8),
        CAUSTICS("Caustics", 4, false, 0x53E0FF, 0x0B3A6B, 0xE8FFFF),
        AURORA("Aurora", 5, false, 0x3FFF9E, 0x8A5CFF, 0xD8FFF0),
        GALAXY("Galaxy", 6, false, 0x8C6BFF, 0x4A3A8C, 0xFFB25E),
        ;

        override fun toString(): String = label
    }

    enum class SkyQuality(
        private val label: String,
        val lutWidth: Int,
        val lutHeight: Int,
        val marchSteps: Int,
        val starLayers: Int,
    ) {
        LOW("Low", 192, 96, 18, 1),
        MEDIUM("Medium", 320, 160, 28, 2),
        HIGH("High", 448, 224, 40, 2),
        ;

        override fun toString(): String = label
    }
}
