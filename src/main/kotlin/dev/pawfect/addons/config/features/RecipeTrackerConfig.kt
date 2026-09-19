package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class RecipeTrackerConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var showIcons: Boolean = true

    @Expose
    var showCost: Boolean = true

    @Expose
    var showPercentage: Boolean = true

    @Expose
    var hideCompleted: Boolean = false

    @Expose
    var lineSpacing: Int = 1

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.RECIPE_TRACKER)
}
