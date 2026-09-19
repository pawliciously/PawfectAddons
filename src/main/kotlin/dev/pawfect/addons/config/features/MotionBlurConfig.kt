package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class MotionBlurConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var strength: Float = 0.6f

    @Expose
    var samples: Int = 10

    @Expose
    var maxRadius: Float = 0.06f

    @Expose
    var shutter: Float = 1f

    @Expose
    var includeMovement: Boolean = true
}
