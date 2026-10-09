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

    @Expose
    var map: Boolean = false

    @Expose
    var mapRoomNames: Boolean = true

    @Expose
    var mapSecrets: Boolean = true

    @Expose
    var mapBackground: PanelBackground = PanelBackground.GLASS

    @Expose
    var mapStrength: Float = 0.55f

    @Expose
    var mapOpacity: Float = 0.85f

    @Expose
    var mapRadius: Int = 6

    @Expose
    var mapOutline: Boolean = true

    @Expose
    val mapPosition: Position = HudDefaults.position(HudDefaults.DUNGEON_MAP)

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (mapBackground == null) mapBackground = PanelBackground.GLASS
    }
}
