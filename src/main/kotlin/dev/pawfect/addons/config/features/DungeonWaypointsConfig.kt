package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class DungeonWaypointsConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var showNames: Boolean = true

    @Expose
    var throughWalls: Boolean = true

    @Expose
    var lineWidth: Float = 1f

    @Expose
    var opacity: Float = 1f

    @Expose
    var inflate: Float = 0.002f

    @Expose
    var defaultColor: Int = 0x55FFFF
}
