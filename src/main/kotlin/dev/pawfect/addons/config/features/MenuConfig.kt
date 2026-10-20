package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class MenuConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var pawLoadingScreen: Boolean = true

    @Expose
    var styleButtons: Boolean = true

    @Expose
    var styleEverywhere: Boolean = false

    @Expose
    var intensity: Float = 0.85f

    @Expose
    var buttonRadius: Float = 6f

    val hideSplash: Boolean = true

    val showBranding: Boolean = true

    @Expose
    var customColors: Boolean = false

    @Expose
    var colorTop: Int = 0x0B0D12

    @Expose
    var colorBottom: Int = 0x05060A

    @Expose
    var colorAccent: Int = 0x6C5CE7

    @Expose
    var imageEnabled: Boolean = false

    @Expose
    var imageSource: String = ""

    @Expose
    var imageCutBackground: Boolean = false

    @Expose
    var imageOpacity: Float = 1f

    @Expose
    val imagePosition: Position = HudDefaults.position(HudDefaults.MENU_IMAGE)

    val hubScene: Boolean = true

    val hubCapture: Boolean = true

    val showcase: Boolean = true

    @Expose
    var anchorSet: Boolean = false

    @Expose
    var anchorX: Double = 0.0

    @Expose
    var anchorY: Double = 0.0

    @Expose
    var anchorZ: Double = 0.0

    @Expose
    var anchorYaw: Float = 0f

    @Expose
    var anchorPitch: Float = 0f

    @Expose
    var spots: MutableList<MenuSpot> = mutableListOf()
}

class MenuSpot {

    @Expose
    var area: String = "hub"

    @Expose
    var x: Double = 0.0

    @Expose
    var y: Double = 0.0

    @Expose
    var z: Double = 0.0

    @Expose
    var yaw: Float = 0f

    @Expose
    var pitch: Float = 0f
}
