package dev.pawfect.addons.data

import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.SackEntry
import dev.pawfect.addons.config.SackStorage
import dev.pawfect.addons.utils.ItemUtil.loreLines
import dev.pawfect.addons.utils.ItemUtil.plainName
import dev.pawfect.addons.utils.ItemUtil.skyblockId
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.NumberUtil.parseHypixelInt
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

enum class SackStatus {
    MISSING,

    CORRECT,

    ALRIGHT,

    OUTDATED,
}

object SackApi {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Sacks")

    private const val PARSE_INTERVAL_TICKS = 10
    private const val SAVE_DEBOUNCE_MS = 3_000L
    private const val REPEAT_WINDOW_MS = 6_000L
    private const val GEMSTONE_FILTER_SLOT = 41

    private val sackTitle = Regex("^(?:.* Sack|Enchanted .* Sack)$")

    private val storedPattern = Regex("Stored: ([\\d,.kKmMbB]+)/([\\d,.kKmMbB]+)")

    private val gemstoneTierPattern = Regex("(Rough|Flawed|Fine): ([\\d,.kKmMbB]+)")

    private val gemstoneAmountPattern = Regex("Amount: ([\\d,.kKmMbB]+)")

    private val gemstoneFilterPattern = Regex("▶ (Rough|Flawed|Fine)")

    private val runeTierPattern = Regex("^(I{1,3}): ([\\d,.kKmMbB]+)/")

    private val sackDeltaPattern = Regex("([+-][\\d,]+) (.+) \\((.+)\\)")

    private val gemTypes = listOf(
        "JADE", "AMBER", "TOPAZ", "SAPPHIRE", "AMETHYST", "JASPER",
        "RUBY", "OPAL", "ONYX", "AQUAMARINE", "CITRINE", "PERIDOT",
    )

    private var tickCounter = 0
    private var dirtySince = 0L
    private var lastDeltas: String? = null
    private var lastDeltasAt = 0L

    fun amountInSacks(internalName: String): Int? {
        val entry = profileSacks()?.contents?.get(internalName) ?: return null
        return when (entry.status) {
            SackStatus.CORRECT, SackStatus.ALRIGHT -> entry.amount
            SackStatus.MISSING, SackStatus.OUTDATED -> null
        }
    }

    fun rawAmount(internalName: String): Int = profileSacks()?.contents?.get(internalName)?.amount ?: 0

    fun statusOf(internalName: String): SackStatus =
        profileSacks()?.contents?.get(internalName)?.status ?: SackStatus.MISSING

    fun onTick() {
        flushIfDirty()
        if (!ConfigManager.features.general.trackSacks) return
        if (++tickCounter < PARSE_INTERVAL_TICKS) return
        tickCounter = 0

        val screen = McCompat.screen as? AbstractContainerScreen<*> ?: return
        val title = screen.title.string.removeColor().trim()
        if (!sackTitle.matches(title)) return

        runCatching { parseSackScreen(title, screen) }
            .onFailure { logger.warn("Failed to parse sack screen '{}'", title, it) }
    }

    private fun parseSackScreen(title: String, screen: AbstractContainerScreen<*>) {
        val slots = screen.menu.slots
        val stacks = slots.map { it.item }

        when {
            title.startsWith("Gemstones") -> parseGemstoneSack(stacks)
            title.startsWith("Runes") -> parseRuneSack(stacks)
            else -> parseNormalSack(stacks)
        }
    }

    private fun parseNormalSack(stacks: List<ItemStack>) {
        for (stack in stacks) {
            if (stack.isEmpty) continue
            val lore = stack.loreLines()
            val match = lore.firstNotNullOfOrNull { storedPattern.find(it) } ?: continue
            val stored = match.groupValues[1].parseHypixelInt() ?: continue
            val internalName = resolveInternalName(stack) ?: continue
            store(internalName, stored, SackStatus.CORRECT)
        }
    }

    private fun parseGemstoneSack(stacks: List<ItemStack>) {
        val activeFilter = stacks.getOrNull(GEMSTONE_FILTER_SLOT)
            ?.loreLines()
            ?.firstNotNullOfOrNull { gemstoneFilterPattern.find(it) }
            ?.groupValues?.get(1)
            ?.uppercase()

        for (stack in stacks) {
            if (stack.isEmpty) continue
            val name = stack.plainName()
            val gem = gemTypes.firstOrNull { name.uppercase().contains(it) } ?: continue
            val lore = stack.loreLines()

            if (activeFilter != null) {
                val amount = lore.firstNotNullOfOrNull { gemstoneAmountPattern.find(it) }
                    ?.groupValues?.get(1)?.parseHypixelInt() ?: continue
                store("${activeFilter}_${gem}_GEM", amount, SackStatus.CORRECT)
            } else {
                for (line in lore) {
                    val match = gemstoneTierPattern.find(line) ?: continue
                    val tier = match.groupValues[1].uppercase()
                    val amount = match.groupValues[2].parseHypixelInt() ?: continue
                    store("${tier}_${gem}_GEM", amount, SackStatus.CORRECT)
                }
            }
        }
    }

    private fun parseRuneSack(stacks: List<ItemStack>) {
        for (stack in stacks) {
            if (stack.isEmpty) continue
            val baseName = stack.plainName().trim().uppercase().replace(' ', '_')
            if (!baseName.endsWith("_RUNE")) continue

            for (line in stack.loreLines()) {
                val match = runeTierPattern.find(line.trim()) ?: continue
                val level = match.groupValues[1].length
                val amount = match.groupValues[2].parseHypixelInt() ?: continue
                store("$baseName;$level", amount, SackStatus.CORRECT)
            }
        }
    }

    private fun resolveInternalName(stack: ItemStack): String? =
        stack.skyblockId() ?: NeuRepo.resolve(stack.plainName())?.internalName

    fun handleChatMessage(message: Component): Boolean {
        if (!ConfigManager.features.general.trackSacks) return true
        if (!message.string.contains("[Sacks]")) return true

        val hoverTexts = collectHoverTexts(message)
        if (hoverTexts.isEmpty()) return true

        val signature = hoverTexts.joinToString("\u0000")
        val now = System.currentTimeMillis()
        if (signature == lastDeltas && now - lastDeltasAt < REPEAT_WINDOW_MS) {
            return !ConfigManager.features.general.hideSackMessages
        }
        lastDeltas = signature
        lastDeltasAt = now

        var changed = false
        var truncated = false
        val touched = HashMap<String, Int>()

        for (text in hoverTexts) {
            if (text.contains("other items")) truncated = true
            for (match in sackDeltaPattern.findAll(text)) {
                val delta = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
                val itemName = match.groupValues[2].trim()
                val internalName = NeuRepo.resolve(itemName)?.internalName ?: continue
                touched[internalName] = (touched[internalName] ?: 0) + delta
            }
        }

        val contents = profileSacks()?.contents ?: return true
        for ((internalName, delta) in touched) {
            val existing = contents[internalName]
            if (existing != null && existing.status != SackStatus.MISSING) {
                val newAmount = (existing.amount + delta).coerceAtLeast(0)
                val status = if (existing.status == SackStatus.OUTDATED) SackStatus.OUTDATED else SackStatus.ALRIGHT
                contents[internalName] = SackEntry(newAmount, status)
            } else {
                contents[internalName] = SackEntry(delta.coerceAtLeast(0), SackStatus.OUTDATED)
            }
            changed = true
        }

        if (truncated) {
            for ((internalName, entry) in contents.entries.toList()) {
                if (internalName in touched) continue
                if (entry.status == SackStatus.CORRECT) {
                    contents[internalName] = SackEntry(entry.amount, SackStatus.ALRIGHT)
                }
            }
            changed = true
        }

        if (changed) markDirty()

        return !ConfigManager.features.general.hideSackMessages
    }

    private fun collectHoverTexts(message: Component): List<String> {
        val results = LinkedHashSet<String>()
        collectHoverTexts(message, results)
        return results.toList()
    }

    private fun collectHoverTexts(component: Component, into: MutableSet<String>) {
        val hover = component.style.hoverEvent
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT) {
            val text = (hover as HoverEvent.ShowText).value.string.removeColor()
            if (text.startsWith("Added") || text.startsWith("Removed")) into.add(text)
        }
        for (sibling in component.siblings) {
            collectHoverTexts(sibling, into)
        }
    }

    private fun store(internalName: String, amount: Int, status: SackStatus) {
        val contents = profileSacks()?.contents ?: return
        val existing = contents[internalName]
        if (existing != null && existing.amount == amount && existing.status == status) return
        contents[internalName] = SackEntry(amount, status)
        markDirty()
    }

    private fun profileSacks(): SackStorage.ProfileSacks? {
        if (!SkyBlockData.onSkyBlock) return null
        val storage = ConfigManager.sacks
        return storage.players
            .getOrPut(SkyBlockData.playerUuid) { SackStorage.PlayerSacks() }
            .profiles
            .getOrPut(SkyBlockData.currentProfile) { SackStorage.ProfileSacks() }
    }

    private fun markDirty() {
        if (dirtySince == 0L) dirtySince = System.currentTimeMillis()
    }

    private fun flushIfDirty() {
        if (dirtySince == 0L) return
        if (System.currentTimeMillis() - dirtySince < SAVE_DEBOUNCE_MS) return
        dirtySince = 0L
        ConfigManager.save(ConfigFileType.SACKS, "sack contents changed")
    }

}
