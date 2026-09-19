package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ExperimentsConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var chronomatron: Boolean = true

    @Expose
    var ultrasequencer: Boolean = true

    @Expose
    var superpairs: Boolean = true

    @Expose
    var blockIncorrectClicks: Boolean = false

    @Expose
    var showPanel: Boolean = true

    @Expose
    var ghostUpcoming: Boolean = true

    @Expose
    var ghostCount: Int = 3

    @Expose
    var showOrder: Boolean = true

    @Expose
    var radius: Float = 3f

    @Expose
    var chronomatronRounds: Int = 10

    @Expose
    var ultrasequencerRounds: Int = 6
}
