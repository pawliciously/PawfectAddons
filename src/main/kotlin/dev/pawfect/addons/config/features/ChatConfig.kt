package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ChatConfig {

    /** Swap vanilla's black line strips for one rounded, themed panel. */
    @Expose
    var styled: Boolean = true

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
}
