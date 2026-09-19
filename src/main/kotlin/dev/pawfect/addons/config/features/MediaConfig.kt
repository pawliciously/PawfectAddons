package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class MediaConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.MEDIA)

    @Expose
    var opacity: Float = 0.9f

    @Expose
    var width: Int = 170

    @Expose
    var showArt: Boolean = true

    @Expose
    var showArtist: Boolean = true

    @Expose
    var showProgress: Boolean = true

    @Expose
    var showControls: Boolean = true

    @Expose
    var showSource: Boolean = false

    @Expose
    var hideWhenStopped: Boolean = true

    @Expose
    var scrollLongTitles: Boolean = true

    @Expose
    var clickableInMenus: Boolean = true
}
