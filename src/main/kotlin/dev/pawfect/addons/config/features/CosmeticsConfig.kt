package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class CosmeticsConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var nameColors: Boolean = true

    @Expose
    var animate: Boolean = true

    @Expose
    var capes: Visibility = Visibility.ALL

    @Expose
    var trails: Visibility = Visibility.ALL

    @Expose
    var motes: Visibility = Visibility.ALL

    @Expose
    var badges: Visibility = Visibility.ALL

    @Expose
    var firstPerson: Boolean = true

    @Expose
    var enrolledAt: Long = 0L

    enum class Visibility(private val label: String) {
        ALL("Everyone"),
        SELF("Only Mine"),
        NONE("Off"),
        ;

        override fun toString(): String = label
    }
}
