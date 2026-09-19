package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class HitsoundConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var melee: Boolean = true

    @Expose
    var arrows: Boolean = true

    @Expose
    var predictArrows: Boolean = true

    @Expose
    var suppressVanilla: Boolean = true

    @Expose
    var abilities: Boolean = true

    @Expose
    var muteExplosions: Boolean = false

    @Expose
    var soundId: String = DEFAULT_SOUND

    @Expose
    var volume: Float = 0.8f

    @Expose
    var pitch: Float = 1f

    @Expose
    var markerEnabled: Boolean = false

    @Expose
    var markerColour: Int = 0xFFFFFF

    @Expose
    var markerSize: Float = 5f

    @Expose
    var markerThickness: Float = 1.5f

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (soundId == null || soundId.isBlank()) soundId = DEFAULT_SOUND
        volume = volume.coerceIn(0f, 1f)
        pitch = pitch.coerceIn(0.5f, 2f)
        markerSize = markerSize.coerceIn(2f, 12f)
        markerThickness = markerThickness.coerceIn(0.5f, 4f)
    }

    companion object {
        const val DEFAULT_SOUND = "pawfectaddons:hitsound/impact"
    }
}
