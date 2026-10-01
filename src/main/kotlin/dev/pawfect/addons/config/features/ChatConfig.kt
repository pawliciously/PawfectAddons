package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ChatConfig {

    @Expose
    var styled: Boolean = true

    @Expose
    var background: PanelBackground = PanelBackground.GLASS

    @Expose
    var strength: Float = 0.55f

    @Expose
    var opacity: Float = 0.6f

    @Expose
    var radius: Float = 4f

    @Expose
    var outline: Boolean = true

    @Expose
    var customColor: Boolean = false

    @Expose
    var color: Int = 0x10121A

    @Expose
    var rightClickCopy: Boolean = true

    @Expose
    var levelPrestige: Boolean = true

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (background == null) background = PanelBackground.GLASS
    }
}
