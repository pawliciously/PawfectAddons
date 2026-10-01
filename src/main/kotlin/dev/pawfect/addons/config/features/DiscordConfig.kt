package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class DiscordConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var showLocation: Boolean = true

    @Expose
    var showArea: Boolean = true

    @Expose
    var showName: Boolean = true

    @Expose
    var showHead: Boolean = true

    @Expose
    var showTimer: Boolean = true

    @Expose
    var showButton: Boolean = true
}
