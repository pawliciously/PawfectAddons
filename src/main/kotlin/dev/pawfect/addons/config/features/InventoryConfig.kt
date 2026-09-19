package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class InventoryConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var style: PlateStyle = PlateStyle.AURORA

    @Expose
    var intensity: Float = 0.85f

    @Expose
    var slotPlates: Boolean = true

    @Expose
    var slotOpacity: Float = 0.55f

    @Expose
    var slotRadius: Float = 2f

    @Expose
    var hideSlotHighlight: Boolean = true

    @Expose
    var hideSlotIcons: Boolean = true

    @Expose
    var hideRecipeBook: Boolean = true

    @Expose
    var customColors: Boolean = false

    @Expose
    var colorTop: Int = 0x161A24

    @Expose
    var colorBottom: Int = 0x0A0C12

    @Expose
    var colorAccent: Int = 0x6C5CE7

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (style == null) style = PlateStyle.AURORA
    }

    enum class PlateStyle(private val label: String, val id: Int) {
        AURORA("Aurora", 0),
        CAUSTICS("Caustics", 1),
        SILK("Silk", 2),
        CARBON("Carbon", 3),
        EMBER("Ember", 4),
        ;

        override fun toString(): String = label
    }
}
