package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class DevConfig {

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.PACKET_LOG)

    @Expose
    var overlayEnabled: Boolean = false

    @Expose
    var overlayClickable: Boolean = true

    @Expose
    var overlayDetail: Boolean = true

    @Expose
    var overlayRows: Int = 10

    @Expose
    var overlayWidth: Int = 230

    @Expose
    var captureInbound: Boolean = true

    @Expose
    var captureOutbound: Boolean = true

    @Expose
    var hideSpam: Boolean = true

    @Expose
    var alwaysCapture: Boolean = false
}
