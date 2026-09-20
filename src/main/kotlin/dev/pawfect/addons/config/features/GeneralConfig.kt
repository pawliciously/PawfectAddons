package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class GeneralConfig {

    @Expose
    var trackSacks: Boolean = true

    @Expose
    var hideSackMessages: Boolean = false

    @Expose
    var countInventory: Boolean = true

    @Expose
    var countStorage: Boolean = true

    @Expose
    var debug: Boolean = false

    @Expose
    var notificationSeconds: Float = 5f

    @Expose
    var updateNotice: Boolean = true
}
