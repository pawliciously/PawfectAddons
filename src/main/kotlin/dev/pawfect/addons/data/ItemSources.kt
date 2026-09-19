package dev.pawfect.addons.data

import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.StorageData
import dev.pawfect.addons.utils.ItemUtil.plainName
import dev.pawfect.addons.utils.ItemUtil.skyblockId
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

class OwnedAmount(
    val sacks: Int?,
    val inventory: Int,
    val storage: Int,
) {
    val total: Int get() = (sacks ?: 0) + inventory + storage

    val isKnown: Boolean get() = sacks != null || total > 0

    val amountOrNull: Int? get() = if (isKnown) total else null
}

object ItemSources {

    private val logger = LoggerFactory.getLogger("PawfectAddons/ItemSources")

    private const val PARSE_INTERVAL_TICKS = 10
    private const val SAVE_DEBOUNCE_MS = 3_000L

    private val enderChestTitle = Regex("Ender Chest.*\\((\\d+)/\\d+\\)", RegexOption.IGNORE_CASE)
    private val backpackTitle = Regex("Backpack.*\\(Slot #(\\d+)\\)", RegexOption.IGNORE_CASE)

    private var tickCounter = 0
    private var dirtySince = 0L

    fun owned(itemId: String): OwnedAmount {
        val general = ConfigManager.features.general
        return OwnedAmount(
            sacks = if (general.trackSacks) SackApi.amountInSacks(itemId) else null,
            inventory = if (general.countInventory) inventoryCount(itemId) else 0,
            storage = if (general.countStorage) storageCount(itemId) else 0,
        )
    }

    private fun inventoryCount(itemId: String): Int {
        val player = McCompat.player ?: return 0
        var total = 0

        for (stack in player.inventory.nonEquipmentItems) {
            if (matches(stack, itemId)) total += stack.count
        }
        for (slot in EQUIPMENT_SLOTS) {
            val stack = player.getItemBySlot(slot)
            if (matches(stack, itemId)) total += stack.count
        }
        return total
    }

    private fun storageCount(itemId: String): Int =
        profileStorage()?.pages?.values?.sumOf { it[itemId] ?: 0 } ?: 0

    private fun matches(stack: ItemStack, itemId: String): Boolean {
        if (stack.isEmpty) return false
        return resolveId(stack) == itemId
    }

    private fun resolveId(stack: ItemStack): String? =
        stack.skyblockId() ?: NeuRepo.resolve(stack.plainName())?.internalName

    fun onTick() {
        flushIfDirty()
        if (!ConfigManager.features.general.countStorage) return
        if (++tickCounter < PARSE_INTERVAL_TICKS) return
        tickCounter = 0

        val screen = McCompat.screen as? AbstractContainerScreen<*> ?: return
        val title = screen.title.string.removeColor().trim()
        val pageKey = pageKeyFor(title) ?: return

        runCatching { capturePage(pageKey, screen) }
            .onFailure { logger.warn("Failed to read storage page '{}'", title, it) }
    }

    private fun pageKeyFor(title: String): String? {
        enderChestTitle.find(title)?.let { return "ender_" + it.groupValues[1] }
        backpackTitle.find(title)?.let { return "backpack_" + it.groupValues[1] }
        return null
    }

    private fun capturePage(pageKey: String, screen: AbstractContainerScreen<*>) {
        val playerInventory = McCompat.player?.inventory ?: return
        val counts = HashMap<String, Int>()

        for (slot in screen.menu.slots) {
            if (slot.container === playerInventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val id = resolveId(stack) ?: continue
            counts[id] = (counts[id] ?: 0) + stack.count
        }

        val storage = profileStorage() ?: return
        if (storage.pages[pageKey] == counts) return
        storage.pages[pageKey] = counts
        markDirty()
    }

    private fun profileStorage(): StorageData.ProfileStorage? {
        if (!SkyBlockData.onSkyBlock) return null
        return ConfigManager.storage.players
            .getOrPut(SkyBlockData.playerUuid) { StorageData.PlayerStorage() }
            .profiles
            .getOrPut(SkyBlockData.currentProfile) { StorageData.ProfileStorage() }
    }

    private fun markDirty() {
        if (dirtySince == 0L) dirtySince = System.currentTimeMillis()
    }

    private fun flushIfDirty() {
        if (dirtySince == 0L) return
        if (System.currentTimeMillis() - dirtySince < SAVE_DEBOUNCE_MS) return
        dirtySince = 0L
        ConfigManager.save(ConfigFileType.STORAGE, "storage contents changed")
    }

    private val EQUIPMENT_SLOTS = listOf(
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
        EquipmentSlot.OFFHAND,
    )
}
