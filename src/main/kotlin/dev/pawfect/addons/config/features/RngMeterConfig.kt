package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class RngMeterConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var dungeons: Boolean = true

    @Expose
    var slayers: Boolean = true

    @Expose
    var showLeft: Boolean = true

    @Expose
    var background: Boolean = true

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.RNG_METER)
}
