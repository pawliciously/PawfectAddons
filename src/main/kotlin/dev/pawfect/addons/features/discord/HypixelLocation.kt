package dev.pawfect.addons.features.discord

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/**
 * Where Hypixel says we are, from the Hypixel Mod API's location event. Every field is null
 * until the first event, and when that mod isn't installed; callers fall back to the tab
 * list and sidebar then.
 */
object HypixelLocation {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HypixelLocation")

    /** Hypixel's id for the game, like SKYBLOCK or BEDWARS, or MAIN for the main lobby. */
    @Volatile
    var serverType: String? = null
        private set

    /** The game's display name, like "SkyBlock" or "Bed Wars". */
    @Volatile
    var gameName: String? = null
        private set

    /** Set while in a lobby rather than a game. */
    @Volatile
    var lobby: String? = null
        private set

    /** The SkyBlock island id, like "garden", "dungeon" or "dynamic" for a private island. */
    @Volatile
    var mode: String? = null
        private set

    val known: Boolean get() = serverType != null

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> update(null, null, null, null) }
        if (!FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) return
        // Kept in its own class so nothing touches the Mod API's classes when it's missing.
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
