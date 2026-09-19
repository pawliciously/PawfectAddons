package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class MenuConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var styleButtons: Boolean = true

    @Expose
    var styleEverywhere: Boolean = false

    @Expose
    var intensity: Float = 0.85f

    @Expose
    var buttonRadius: Float = 6f

    @Expose
    var hideSplash: Boolean = true

    @Expose
    var showBranding: Boolean = true

    @Expose
    var customColors: Boolean = false

    @Expose
    var colorTop: Int = 0x0B0D12

    @Expose
    var colorBottom: Int = 0x05060A

    @Expose
    var colorAccent: Int = 0x6C5CE7
}
