package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ChatConfig {

    /** Swap vanilla's black line strips for one rounded, themed panel. */
    @Expose
    var styled: Boolean = true

    @Expose
    var background: PanelBackground = PanelBackground.GLASS

    /** How strongly an animated background shows; kept low so text stays easy to read. */
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

    /** Right click a message while chat is open to copy it. */
    @Expose
    var rightClickCopy: Boolean = true

    /** Prestige styling for SkyBlock levels past 480, in chat, the tab list and nametags. */
    @Expose
    var levelPrestige: Boolean = true

    @Suppress("SENSELESS_COMPARISON")
    fun sanitize() {
        if (background == null) background = PanelBackground.GLASS
    }
}
