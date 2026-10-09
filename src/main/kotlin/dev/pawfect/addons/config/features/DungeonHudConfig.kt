package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class DungeonHudConfig {

    @Expose
    var roomName: Boolean = false

    @Expose
    var roomNameShowName: Boolean = true

    @Expose
    var roomNameSimple: Boolean = false

    @Expose
    val roomNamePosition: Position = HudDefaults.position(HudDefaults.ROOM_NAME)
}
