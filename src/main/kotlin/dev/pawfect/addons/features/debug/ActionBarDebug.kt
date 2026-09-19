package dev.pawfect.addons.features.debug

import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object ActionBarDebug {

    private val logger = LoggerFactory.getLogger("PawfectAddons/ActionBar")

    var logging: Boolean = false
        private set

    fun toggle(): Boolean {
        logging = !logging
        return logging
    }

    fun onActionBar(message: Component) {
        if (!logging) return
        val raw = message.string
        logger.info("[ActionBar] {}", raw.removeColor())
        logger.info("[ActionBar raw] {}", raw.replace("§", "&"))
    }
}
