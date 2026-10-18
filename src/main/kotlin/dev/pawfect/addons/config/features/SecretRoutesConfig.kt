package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

class SecretRoutesConfig {

    enum class Shown(private val label: String) {
        CURRENT("Current Step"),
        NEXT_TWO("Next Two Steps"),
        WHOLE("Whole Route");

        override fun toString(): String = label
    }

    enum class Style(private val label: String) {
        BOTH("Lines & Boxes"),
        LINES("Lines"),
        BOXES("Boxes");

        override fun toString(): String = label
    }

    enum class Source(private val label: String) {
        @SerializedName(value = "DEFAULT", alternate = ["SECRET_ROUTES"])
        DEFAULT("Default"),

        @SerializedName(value = "FLAMEOFWAR", alternate = ["FOW"])
        FLAMEOFWAR("FlameOfWar SRM"),

        STELLA("Stella"),

        RECORDED("Recorded");

        override fun toString(): String = label
    }

    @Expose
    var enabled: Boolean = false

    @Expose
    var source: Source = Source.DEFAULT

    @Expose
    var style: Style = Style.BOTH

    @Expose
    var shown: Shown = Shown.CURRENT

    @Expose
    var labels: Boolean = true

    @Expose
    var throughWalls: Boolean = true

    @Expose
    var fillMissing: Boolean = true

    @Expose
    var useLevers: Boolean = true

    @Expose
    var showFinished: Boolean = false

    @Expose
    var lineWidth: Float = 2f

    @Expose
    var opacity: Float = 1f

    @Expose
    var pathColor: Int = 0x55FFFF

    @Expose
    var etherwarpColor: Int = 0xB57CFF

    @Expose
    var stonkColor: Int = 0xFFAA00

    @Expose
    var superboomColor: Int = 0xFF5555

    @Expose
    var interactColor: Int = 0x55FF55

    @Expose
    var pearlColor: Int = 0x3DDCA0

    @Expose
    var secretColor: Int = 0xFFFF55
}
