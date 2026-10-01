package dev.pawfect.addons.features.discord

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

object HypixelLocation {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HypixelLocation")

    @Volatile
    var serverType: String? = null
        private set

    @Volatile
    var gameName: String? = null
        private set

    @Volatile
    var lobby: String? = null
        private set

    @Volatile
    var mode: String? = null
        private set

    val known: Boolean get() = serverType != null

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> update(null, null, null, null) }
        if (!FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) return
        runCatching { ModApiBridge.subscribe() }
            .onFailure { logger.warn("Could not subscribe to Hypixel location events.", it) }
    }

    internal fun update(serverType: String?, gameName: String?, lobby: String?, mode: String?) {
        this.serverType = serverType
        this.gameName = gameName
        this.lobby = lobby
        this.mode = mode
    }
}
