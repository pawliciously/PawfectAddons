package dev.pawfect.addons.features.visual

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.ChatUtils

object TooltipHider {

    private val config get() = ConfigManager.features.visuals

    @JvmStatic
    val isHidingTooltips: Boolean get() = config.hideTooltips

    fun toggle() {
        config.hideTooltips = !config.hideTooltips
        if (config.hideTooltips) ChatUtils.success("Tooltips hidden.")
        else ChatUtils.chat("Tooltips shown.")
    }
}
