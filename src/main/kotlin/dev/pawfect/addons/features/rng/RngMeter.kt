package dev.pawfect.addons.features.rng

import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.RngMeterEntry
import dev.pawfect.addons.config.StorageData
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.ItemUtil.loreLines
import dev.pawfect.addons.utils.ItemUtil.plainName
import dev.pawfect.addons.utils.ItemUtil.skyblockId
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.normalizeItemName
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

/**
 * Keeps track of your Catacombs and Slayer RNG Meters.
 *
 * - A dungeon run adds its Team Score to that floor's meter when it ends on S+, 70% of it on S,
 *   nothing below.
 * - A slayer boss's chat says the meter's exact total after each kill.
 * - Opening any RNG Meter menu reads the exact total and the selected drop off its items, which
 *   also corrects anything the run counting gets wrong (the Pity attribute adds up to 10% more).
 *
 * What each drop needs comes from the NEU repo's rngscore.
 */
object RngMeter {

    private val config get() = ModuleSwitch.view("rngmeter", ConfigManager.features.rngMeter)

    private val logger = LoggerFactory.getLogger("PawfectAddons/RngMeter")

    /** Slayer chat names the boss by its mob; the meters go by the boss. */
    private val SLAYER_BY_MOB = mapOf(
        "Zombie" to "Revenant Horror",
        "Spider" to "Tarantula Broodfather",
        "Wolf" to "Sven Packmaster",
        "Enderman" to "Voidgloom Seraph",
        "Blaze" to "Inferno Demonlord",
        "Vampire" to "Riftstalker Bloodfiend",
    )

    val SLAYER_BOSSES: Collection<String> = SLAYER_BY_MOB.values

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII")

    private val scoreRegex = Regex("""^\s*Team Score: (\d+) \((S\+|S|A|B|C|D)\)""")
    private val floorHeaderRegex = Regex("""^\s*(Master Mode )?The Catacombs - Floor ([IVX]+)\s*$""")
    private val setRegex = Regex("""^You set your (.+) RNG Meter to drop (.+)!$""")
    private val resetRegex = Regex("""^You reset your selected drop for your (.+) RNG Meter!$""")
    private val droppedRegex = Regex("""^RNG METER! Reselected the (.+) for (.+?)!\s*CLICK HERE""")
    private val slayerLevelRegex = Regex("""^\s*(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL""")
    private val storedRegex = Regex("""^\s*RNG Meter - ([\d,]+) Stored XP""")

    private val titleRegex = Regex("""^(.+) RNG Meter$""")
    private val progressRegex = Regex("""(?:Dungeon Score|Slayer XP): ([\d,.]+[kKmM]?)/([\d,.]+[kKmM]?)""")

    /** The floor from the run summary's header, read just before its score. */
    private var headerFloor: String? = null

    /** The slayer whose level line came last, for the meter line under it. */
    private var lastSlayer: String? = null
    private var lastSlayerAt = 0L

    private var dirtySince = 0L
    private var ticks = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { tick() } }
    }

    // ------------------------------------------------------------------ chat

    fun onChat(message: Component) {
        if (!config.enabled || !SkyBlockData.onSkyBlock) return
        val text = message.string.removeColor()

        floorHeaderRegex.find(text)?.let { match ->
            val floor = ROMAN.indexOf(match.groupValues[2]) + 1
            headerFloor = if (floor > 0) (if (match.groupValues[1].isNotEmpty()) "M" else "F") + floor else null
            return
        }
        scoreRegex.find(text)?.let { match ->
            val floor = headerFloor ?: SkyBlockData.dungeonFloor
            headerFloor = null
            if (floor == null || floor == "E") return
            val score = match.groupValues[1].toLong()
            val gain = when (match.groupValues[2]) {
                "S+" -> score
                "S" -> score * 7 / 10
                else -> 0L
            }
            if (gain <= 0) return
            update(floor) { entry ->
                entry.stored += gain
                entry.lastGain = gain
                entry.exact = false
            }
            return
        }
        slayerLevelRegex.find(text)?.let { match ->
            lastSlayer = SLAYER_BY_MOB[match.groupValues[1]]
            lastSlayerAt = System.currentTimeMillis()
            return
        }
        storedRegex.find(text)?.let { match ->
            val boss = lastSlayer?.takeIf { System.currentTimeMillis() - lastSlayerAt < SLAYER_LINE_MS } ?: questBoss() ?: return
            val stored = match.groupValues[1].replace(",", "").toLongOrNull() ?: return
            update(boss) { entry ->
                if (entry.exact && stored > entry.stored) entry.lastGain = stored - entry.stored
                entry.stored = stored
                entry.exact = true
            }
            return
        }
        setRegex.find(text)?.let { match ->
            val meter = meterKey(match.groupValues[1]) ?: return
            val id = dropId(meter, match.groupValues[2])
            update(meter) { entry ->
                entry.item = id
                entry.goal = id?.let { NeuRepo.rngDrops(meter)[it] }
            }
            return
        }
        resetRegex.find(text)?.let { match ->
            val meter = meterKey(match.groupValues[1]) ?: return
            update(meter) { entry ->
                entry.item = null
                entry.goal = null
            }
            return
        }
        droppedRegex.find(text)?.let { match ->
            val meter = meterKey(match.groupValues[2]) ?: return
            // The drop empties the meter and picks the same drop again.
            update(meter) { entry ->
                entry.stored = 0
                entry.exact = false
                dropId(meter, match.groupValues[1])?.let { id ->
                    entry.item = id
                    entry.goal = NeuRepo.rngDrops(meter)[id]
                }
            }
        }
    }

    /** A meter's key from how chat names it: "Catacombs (F7)", "Catacombs - Floor 6" or a slayer boss. */
    fun meterKey(name: String): String? {
        val plain = name.removeColor()
        Regex("""\(([FM]\d)\)""").find(plain)?.let { return it.groupValues[1] }
        Regex("""Floor (\d|[IVX]+)\b""").find(plain)?.let { match ->
            val floor = match.groupValues[1].toIntOrNull() ?: (ROMAN.indexOf(match.groupValues[1]) + 1).takeIf { it > 0 } ?: return null
            return (if (plain.contains("Master")) "M" else "F") + floor
        }
        return SLAYER_BOSSES.firstOrNull { plain.contains(it) }
    }

    /** The drop [name] stands for among what [meter] can be set to. */
    fun dropId(meter: String, name: String): String? {
        val plain = name.removeColor().trim().removePrefix("Enchanted Book (").removeSuffix(")")
        val wanted = plain.normalizeItemName()
        val drops = NeuRepo.rngDrops(meter).keys
        drops.firstOrNull { id -> names(id).any { it == wanted } }?.let { return it }
        return NeuRepo.resolve(plain)?.internalName
    }

    /** Names a drop goes by, enchantments with and without "Ultimate" and their level either way. */
    private fun names(id: String): List<String> {
        val list = ArrayList<String>()
        list += NeuRepo.displayName(id).normalizeItemName()
        if (';' in id) {
            val base = id.substringBefore(';').split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
            val level = id.substringAfter(';').toIntOrNull() ?: return list
            val roman = ROMAN.getOrNull(level - 1) ?: level.toString()
            for (name in listOf(base, base.removePrefix("Ultimate "))) {
                list += "$name $roman".normalizeItemName()
                list += "$name $level".normalizeItemName()
            }
        }
        return list
    }

    // ------------------------------------------------------------------ menus

    private fun tick() {
        flushIfDirty()
        if (!config.enabled) return
        if (++ticks < MENU_INTERVAL) return
        ticks = 0
        val screen = McCompat.screen as? AbstractContainerScreen<*> ?: return
        val title = screen.title.string.removeColor().trim()
        val name = titleRegex.find(title)?.groupValues?.get(1) ?: return
        runCatching { readMenu(name, screen) }
    }

    /**
     * Reads a meter's own menu ("Catacombs (F7) RNG Meter", "Voidgloom Seraph RNG Meter"), or the
     * Catacombs one listing every floor.
     */
    private fun readMenu(name: String, screen: AbstractContainerScreen<*>) {
        val inventory = McCompat.player?.inventory
        val stacks = screen.menu.slots.filter { it.container !== inventory }.map { it.item }.filter { !it.isEmpty }
        val meter = meterKey(name)
        if (meter == null) {
            // Every floor at once: each item is a floor, with its progress and drop.
            stacks.forEach { stack -> meterKey(stack.plainName())?.let { readFloorItem(it, stack.loreLines()) } }
            return
        }
        var stored: Long? = null
        var selected: Pair<String, Long>? = null
        var noDrop = false
        for (stack in stacks) {
            val lore = stack.loreLines().map { it.removeColor() }
            if (lore.any { it.contains("don't have an RNG drop", ignoreCase = true) }) noDrop = true
            val progress = lore.firstNotNullOfOrNull { progressRegex.find(it) } ?: continue
            val current = amount(progress.groupValues[1]) ?: continue
            // Cheaper drops can show a capped amount, so the biggest is the meter.
            stored = maxOf(stored ?: 0L, current)
            if (lore.any { it.contains("SELECTED") }) {
                val id = stack.skyblockId()?.takeIf { it in NeuRepo.rngDrops(meter) } ?: dropId(meter, stack.plainName()) ?: continue
                selected = id to (amount(progress.groupValues[2]) ?: 0L)
            }
        }
        val total = stored ?: return
        if (selected == null && !noDrop) logMenuOnce(name, stacks)
        update(meter) { entry ->
            entry.stored = total
            entry.exact = true
            // Not finding the selected drop isn't proof there's none: only the menu saying so,
            // or the reset message, clears it.
            val pick = selected
            if (pick != null) {
                entry.item = pick.first
                entry.goal = pick.second.takeIf { it > 0 } ?: NeuRepo.rngDrops(meter)[pick.first]
            } else if (noDrop) {
                entry.item = null
                entry.goal = null
            }
        }
    }

    /** Menus already written to the log this session. */
    private val logged = HashSet<String>()

    /** Writes a menu whose selected drop couldn't be found to the log once, to see how it shows it. */
    private fun logMenuOnce(name: String, stacks: List<ItemStack>) {
        if (!logged.add(name)) return
        val dump = stacks.filter { stack -> stack.loreLines().any { progressRegex.containsMatchIn(it.removeColor()) } }.take(6)
            .joinToString("\n") { stack -> "  ${stack.hoverName.string} [${stack.skyblockId()}]: " + stack.loreLines().joinToString(" | ") }
        logger.info("RNG Meter menu '{}' had no selected drop I could find:\n{}", name, dump)
    }

    private fun readFloorItem(meter: String, raw: List<String>) {
        val lore = raw.map { it.removeColor().trim() }
        val progress = lore.firstNotNullOfOrNull { progressRegex.find(it) }
        val noDrop = lore.any { it.contains("don't have an RNG drop", ignoreCase = true) }
        val dropName = lore.indexOfFirst { it.equals("Selected Drop", ignoreCase = true) }
            .takeIf { it >= 0 }?.let { lore.getOrNull(it + 1) }?.takeIf { it.isNotBlank() }
        if (progress == null && !noDrop && dropName == null) return
        update(meter) { entry ->
            progress?.let { amount(it.groupValues[1]) }?.let { entry.stored = it; entry.exact = true }
            if (noDrop) {
                entry.item = null
                entry.goal = null
            } else if (dropName != null) {
                entry.item = dropId(meter, dropName)
                entry.goal = progress?.let { amount(it.groupValues[2]) } ?: entry.item?.let { NeuRepo.rngDrops(meter)[it] }
            }
        }
    }

    /** "31,800", "31.8k" or "1.2M" as a number. */
    fun amount(text: String): Long? {
        val clean = text.replace(",", "").trim()
        val multiplier = when (clean.lastOrNull()?.lowercaseChar()) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            else -> 1.0
        }
        val number = (if (multiplier == 1.0) clean else clean.dropLast(1)).toDoubleOrNull() ?: return null
        return Math.round(number * multiplier)
    }

    // ------------------------------------------------------------------ state

    /** The meter that matters where you are: your floor in a dungeon, your slayer on a quest. */
    fun activeMeter(): String? {
        if (!SkyBlockData.onSkyBlock) return null
        if (SkyBlockData.inDungeons) return if (config.dungeons) SkyBlockData.dungeonFloor?.takeIf { it != "E" } else null
        return if (config.slayers) questBoss() else null
    }

    /** The boss of the slayer quest on your sidebar. */
    private fun questBoss(): String? {
        val lines = SkyBlockData.sidebarLines
        if (lines.none { it.contains("Slayer Quest") }) return null
        return lines.firstNotNullOfOrNull { line -> SLAYER_BOSSES.firstOrNull { line.contains(it) } }
    }

    fun entry(meter: String): RngMeterEntry? = profile()?.rngMeters?.get(meter)

    private fun update(meter: String, change: (RngMeterEntry) -> Unit) {
        val profile = profile() ?: return
        change(profile.rngMeters.getOrPut(meter) { RngMeterEntry() })
        if (dirtySince == 0L) dirtySince = System.currentTimeMillis()
    }

    private fun profile(): StorageData.ProfileStorage? {
        if (!SkyBlockData.onSkyBlock) return null
        return ConfigManager.storage.players
            .getOrPut(SkyBlockData.playerUuid) { StorageData.PlayerStorage() }
            .profiles
            .getOrPut(SkyBlockData.currentProfile) { StorageData.ProfileStorage() }
    }

    private fun flushIfDirty() {
        if (dirtySince == 0L || System.currentTimeMillis() - dirtySince < SAVE_DELAY_MS) return
        dirtySince = 0L
        ConfigManager.save(ConfigFileType.STORAGE, "rng meters changed")
    }

    private const val MENU_INTERVAL = 10
    private const val SAVE_DELAY_MS = 2_000L
    private const val SLAYER_LINE_MS = 3_000L
}
