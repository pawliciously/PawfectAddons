package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

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

    /** Always on; no longer a setting. */
    val hideSplash: Boolean = true

    /** Always on; no longer a setting. */
    val showBranding: Boolean = true

    @Expose
    var customColors: Boolean = false

    @Expose
    var colorTop: Int = 0x0B0D12

    @Expose
    var colorBottom: Int = 0x05060A

    @Expose
    var colorAccent: Int = 0x6C5CE7

    /** Always on; no longer a setting. */
    val hubScene: Boolean = true

    /** Always on; no longer a setting. */
    val hubCapture: Boolean = true

    /** Always on; no longer a setting. */
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

    /** One camera spot per SkyBlock area; each launch shows one of the captured ones. */
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
