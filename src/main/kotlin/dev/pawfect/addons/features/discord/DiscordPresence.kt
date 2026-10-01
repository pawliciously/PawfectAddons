package dev.pawfect.addons.features.discord

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Discord Rich Presence: what the player is doing in SkyBlock, their name and skin face.
 *
 * The client thread works out the presence once a second; a worker thread owns the Discord
 * connection, sends the presence when it changes and reconnects if Discord restarts.
 */
object DiscordPresence {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Discord")

    private const val APP_ID = "1550369431237034074"
    private const val LOGO = "https://pawfectaddons.net/favicon-192.png"
    private const val SITE = "https://pawfectaddons.net"

    /** Discord allows five activity updates every 20 seconds. */
    private const val MIN_GAP_MS = 5_000L

    /** Sent again now and then, so a restarted Discord gets it without waiting for a change. */
    private const val RESEND_MS = 60_000L
    private const val RETRY_MS = 15_000L

    private val config get() = ConfigManager.features.discord

    private data class Presence(
        val details: String,
        val state: String?,
        val start: Long?,
        val largeImage: String,
        val largeText: String,
        val smallImage: String?,
        val smallText: String?,
    ) {
        fun toJson() = JsonObject().apply {
            addProperty("type", 0)
            addProperty("details", fit(details))
            state?.let { addProperty("state", fit(it)) }
            start?.let { add("timestamps", JsonObject().apply { addProperty("start", it) }) }
            add("assets", JsonObject().apply {
                addProperty("large_image", largeImage)
                addProperty("large_text", fit(largeText))
                smallImage?.let { addProperty("small_image", it) }
                smallText?.let { addProperty("small_text", fit(it)) }
            })
            add("buttons", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("label", "Get PawfectAddons")
                    addProperty("url", SITE)
                })
            })
        }

        /** Discord wants 2 to 128 characters. */
        private fun fit(text: String) = text.take(128).padEnd(2)
    }

    @Volatile
    private var wanted: Presence? = null

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var ticker = 0

    /** The timer counts from when the activity line last changed. */
    private var place: String? = null
    private var placeSince = 0L

    fun register() {
        HypixelLocation.register()
    }

    fun onTick() {
        if (++ticker < 20) return
        ticker = 0

        if (!config.enabled) {
            if (running) stop()
            return
        }
        wanted = runCatching { build() }
            .onFailure { logger.debug("Could not build the presence.", it) }
            .getOrNull()
        if (!running) start()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        wanted = null
        place = null
    }

    private fun start() {
        running = true
        worker = Thread(::loop, "PawfectAddons Discord").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        val ipc = DiscordIpc(APP_ID)
        var sent: Presence? = null
        var sentAt = 0L
        var retryAt = 0L
        try {
            while (running) {
                val now = System.currentTimeMillis()
                if (!ipc.connected && now >= retryAt) {
                    if (ipc.connect()) {
                        logger.info("Connected to Discord.")
                        sent = null
                        sentAt = 0L
                    } else {
                        retryAt = now + RETRY_MS
                    }
                }
                if (ipc.connected) {
                    val target = wanted
                    val changed = target != sent && now - sentAt >= MIN_GAP_MS
                    if (changed || now - sentAt >= RESEND_MS) {
                        try {
                            ipc.setActivity(target?.toJson())
                            sent = target
                            sentAt = now
                        } catch (error: IOException) {
                            logger.info("Lost the Discord connection; retrying.")
                            ipc.close()
                            retryAt = now + RETRY_MS
                        }
                    }
                }
                Thread.sleep(500)
            }
        } catch (_: InterruptedException) {
        } finally {
            // Discord drops the activity on its own once the pipe closes.
            ipc.close()
        }
    }

    // Working out the presence

    private fun build(): Presence {
        val mc = McCompat.mc
        val (details, area) = when {
            McCompat.player == null -> "In the menus" to null
            mc.hasSingleplayerServer() -> "Playing singleplayer" to null
            !onHypixel() -> "Playing multiplayer" to null
            else -> hypixel()
        }

        if (details != place) {
            place = details
            placeSince = System.currentTimeMillis()
        }

        val name = mc.user.name
        val state = listOfNotNull(name.takeIf { config.showName }, area).joinToString(" · ").ifEmpty { null }
        val version = "PawfectAddons ${PawfectAddons.VERSION}"
        val head = config.showHead
        return Presence(
            details = details,
            state = state,
            start = placeSince.takeIf { config.showTimer },
            largeImage = LOGO,
            largeText = version,
            smallImage = "https://mc-heads.net/avatar/${mc.user.profileId.toString().replace("-", "")}/128".takeIf { head },
            smallText = name.takeIf { head },
        )
    }

    private fun onHypixel(): Boolean =
        HypixelLocation.known || McCompat.mc.currentServer?.ip?.contains("hypixel", ignoreCase = true) == true

    /** The activity line and the extra detail that goes after the player's name. */
    private fun hypixel(): Pair<String, String?> {
        val type = HypixelLocation.serverType
        val onSkyBlock = if (type != null) type == "SKYBLOCK" else SkyBlockData.onSkyBlock
        if (!onSkyBlock) {
            val game = HypixelLocation.gameName
            return when {
                type == "MAIN" -> "In the Main Lobby"
                game == null -> "On Hypixel"
                HypixelLocation.lobby != null -> "In a $game lobby"
                else -> "Playing $game"
            } to null
        }

        if (!config.showLocation) return "Playing SkyBlock" to null
        val sidebar = SkyBlockData.sidebarLines
        val island = Island.find(HypixelLocation.mode, tabArea())
        return when (island) {
            Island.DUNGEON -> dungeon(sidebar)
            Island.KUUDRA -> kuudra(sidebar)
            null -> "Playing SkyBlock" to zone(sidebar, null)
            else -> island.doing to zone(sidebar, island)
        }
    }

    private val floorRegex = Regex("""The Catacombs \((E|F\d|M\d)\)""")
    private val clearedRegex = Regex("""Cleared: (\d+)%""")
    private val kuudraRegex = Regex("""Kuudra's Hollow \(T(\d)\)""")

    private fun dungeon(sidebar: List<String>): Pair<String, String?> {
        val floor = sidebar.firstNotNullOfOrNull { floorRegex.find(it)?.groupValues?.get(1) }
        val doing = when {
            floor == null -> "Running the Catacombs"
            floor == "E" -> "Running the Catacombs Entrance"
            floor.startsWith("M") -> "Running Master Mode Floor ${floor.drop(1)}"
            else -> "Running Catacombs Floor ${floor.drop(1)}"
        }
        val cleared = sidebar.firstNotNullOfOrNull { clearedRegex.find(it)?.groupValues?.get(1) }
        return doing to cleared?.takeIf { config.showArea }?.let { "$it% cleared" }
    }

    private fun kuudra(sidebar: List<String>): Pair<String, String?> {
        val tier = sidebar.firstNotNullOfOrNull { kuudraRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val name = KUUDRA_TIERS.getOrNull((tier ?: 0) - 1)
        return (if (name != null) "Fighting $name Kuudra" else "Fighting Kuudra") to null
    }

    private val KUUDRA_TIERS = listOf("Basic", "Hot", "Burning", "Fiery", "Infernal")

    /** The sidebar's area line, like "⏣ Village", unless it only repeats the island's name. */
    private fun zone(sidebar: List<String>, island: Island?): String? {
        if (!config.showArea) return null
        val line = sidebar.firstOrNull { it.trimStart().startsWith("⏣") || it.trimStart().startsWith("ф") } ?: return null
        val text = line.trim().drop(1).replace(junk, "").trim()
        if (text.length < 2) return null
        if (island != null && text.removePrefix("The ").equals(island.area.removePrefix("The "), ignoreCase = true)) return null
        return text
    }

    /** Hypixel pads sidebar lines with emoji and stray symbols to keep them unique. */
    private val junk = Regex("""[^\p{L}\p{N}\p{P}\p{Zs}]""")

    /** The tab list's "Area: Hub" line, for when the Mod API isn't installed. */
    private fun tabArea(): String? {
        val connection = McCompat.mc.connection ?: return null
        for (info in connection.onlinePlayers) {
            val text = info.tabListDisplayName?.string?.removeColor()?.trim() ?: continue
            if (text.startsWith("Area: ")) return text.removePrefix("Area: ")
            if (text.startsWith("Dungeon: ")) return "Catacombs"
        }
        return null
    }

    private enum class Island(val mode: String, val area: String, val doing: String) {
        PRIVATE_ISLAND("dynamic", "Private Island", "On their Private Island"),
        GARDEN("garden", "Garden", "Farming in the Garden"),
        HUB("hub", "Hub", "In the Hub"),
        FARMING_ISLANDS("farming_1", "The Farming Islands", "Farming on The Farming Islands"),
        PARK("foraging_1", "The Park", "Foraging in The Park"),
        GALATEA("foraging_2", "Galatea", "Foraging on Galatea"),
        GOLD_MINE("mining_1", "Gold Mine", "Mining in the Gold Mine"),
        DEEP_CAVERNS("mining_2", "Deep Caverns", "Mining in the Deep Caverns"),
        DWARVEN_MINES("mining_3", "Dwarven Mines", "Mining in the Dwarven Mines"),
        CRYSTAL_HOLLOWS("crystal_hollows", "Crystal Hollows", "Mining in the Crystal Hollows"),
        MINESHAFT("mineshaft", "Mineshaft", "Exploring a Glacite Mineshaft"),
        SPIDERS_DEN("combat_1", "Spider's Den", "Fighting in the Spider's Den"),
        END("combat_3", "The End", "Fighting in The End"),
        CRIMSON_ISLE("crimson_isle", "Crimson Isle", "Fighting on the Crimson Isle"),
        KUUDRA("kuudra", "Kuudra", "Fighting Kuudra"),
        DUNGEON_HUB("dungeon_hub", "Dungeon Hub", "In the Dungeon Hub"),
        DUNGEON("dungeon", "Catacombs", "Running the Catacombs"),
        WINTER("winter", "Jerry's Workshop", "In Jerry's Workshop"),
        RIFT("rift", "The Rift", "In the Rift"),
        DARK_AUCTION("dark_auction", "Dark Auction", "At the Dark Auction"),
        BAYOU("fishing_1", "Backwater Bayou", "Fishing in the Backwater Bayou"),
        ;

        companion object {
            fun find(mode: String?, area: String?): Island? {
                mode?.let { id -> entries.firstOrNull { it.mode == id }?.let { return it } }
                val name = area ?: return null
                // Longest match wins, so "Dungeon Hub" isn't read as the Hub.
                return entries.filter { name.contains(it.area, ignoreCase = true) }.maxByOrNull { it.area.length }
            }
        }
    }
}
