package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class ScoreboardConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var background: Background = Background.GLASS

    @Expose
    var opacity: Float = 0.75f

    @Expose
    var radius: Int = 6

    @Expose
    var outline: Boolean = true

    @Expose
    var textShadow: Boolean = true

    @Expose
    var hideServerId: Boolean = false

    @Expose
    var hideWebsite: Boolean = false

    @Expose
    var compactBlankLines: Boolean = true

    @Expose
    var placement: Placement = Placement.VANILLA

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.SCOREBOARD)

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (background == null) background = Background.GLASS
        if (placement == null) placement = Placement.VANILLA
    }

    /** Glass is a flat themed panel; the rest are the inventory's animated styles. */
    enum class Background(private val label: String, val style: Int) {
        GLASS("Glass", -1),
        AURORA("Aurora", 0),
        CAUSTICS("Caustics", 1),
        SILK("Silk", 2),
        CARBON("Carbon", 3),
        EMBER("Ember", 4),
        ;

        override fun toString(): String = label
    }

    enum class Placement(private val label: String) {
        VANILLA("Where vanilla puts it"),
        CUSTOM("Anywhere (drag it)"),
        ;

        override fun toString(): String = label
    }
}
