package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class ScoreboardConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var background: PanelBackground = PanelBackground.GLASS

    @Expose
    var opacity: Float = 0.75f

    @Expose
    var radius: Int = 6

    @Expose
    var outline: Boolean = true

    @Expose
    var textShadow: Boolean = true

    @Expose
    var hideDate: Boolean = false

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
        if (background == null) background = PanelBackground.GLASS
        if (placement == null) placement = Placement.VANILLA
    }

    enum class Placement(private val label: String) {
        VANILLA("Where vanilla puts it"),
        CUSTOM("Anywhere (drag it)"),
        ;

        override fun toString(): String = label
    }
}
