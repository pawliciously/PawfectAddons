package dev.pawfect.addons.features.debug

import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.ItemUtil.skyblockId
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.core.component.DataComponents
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Dev dumps of items, for reading how SkyBlock shows something: the held item with /pa dev item,
 * or every slot of the open menu (your inventory with none open) with the dump keybind. Each goes
 * to a file in config/pawfectaddons/dev and to the game log.
 */
object ItemDump {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Dump")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun held() {
        val player = McCompat.player ?: return
        val stack = player.mainHandItem
        if (stack.isEmpty) {
            ChatUtils.error("Hold an item to dump it.")
            return
        }
        val text = describe(stack)
        val file = save("item_${safe(stack.skyblockId() ?: stack.hoverName.string)}", text) ?: return
        logger.info("Item dump of {}:\n{}", stack.hoverName.string, text)
        ChatUtils.chat("§fDumped §r${stack.hoverName.string} §7to §f$file")
    }

    fun gui() {
        val player = McCompat.player ?: return
        val screen = McCompat.screen
        val menu = (screen as? AbstractContainerScreen<*>)?.menu ?: player.inventoryMenu
        val title = (screen as? AbstractContainerScreen<*>)?.title?.string ?: "Inventory"
        val out = StringBuilder()
        out.appendLine("title: $title")
        out.appendLine("screen: ${screen?.javaClass?.name ?: "none (your inventory)"}")
        out.appendLine("menu: ${menu.javaClass.name}, id ${menu.containerId}, type ${runCatching { BuiltInRegistries.MENU.getKey(menu.type) }.getOrNull() ?: "none"}")
        out.appendLine("slots: ${menu.slots.size}")
        val carried = menu.carried
        if (!carried.isEmpty) out.appendLine("carried:\n" + describe(carried).prependIndent("  "))
        out.appendLine()
        menu.slots.forEach { slot ->
            val owner = if (slot.container === player.inventory) "player" else slot.container.javaClass.simpleName
            out.append("slot ${slot.index} ($owner #${slot.containerSlot}, at ${slot.x},${slot.y})")
            if (slot.item.isEmpty) {
                out.appendLine(": empty")
            } else {
                out.appendLine(":")
                out.appendLine(describe(slot.item).prependIndent("  "))
            }
        }
        val text = out.toString()
        val file = save("gui_${safe(title)}", text) ?: return
        logger.info("GUI dump of {}:\n{}", title, text)
        ChatUtils.chat("§fDumped §r$title §7(${menu.slots.size} slots) to §f$file")
    }

    /** Everything about a stack: its names, lore with colour codes kept, and all its data as SNBT. */
    fun describe(stack: ItemStack): String = buildString {
        appendLine("name: ${stack.hoverName.string}")
        appendLine("item: ${BuiltInRegistries.ITEM.getKey(stack.item)} x${stack.count}")
        appendLine("skyblock id: ${stack.skyblockId() ?: "none"}")
        val lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()
        if (lore.isNotEmpty()) {
            appendLine("lore:")
            lore.forEachIndexed { i, line -> appendLine("  $i: ${line.string}") }
        }
        appendLine("data:")
        append(snbt(stack).prependIndent("  "))
    }

    private fun snbt(stack: ItemStack): String {
        val registries = McCompat.mc.level?.registryAccess() ?: return stack.components.toString()
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        return ItemStack.CODEC.encodeStart(ops, stack).result()
            .map { NbtUtils.prettyPrint(it, true) }
            .orElse(stack.components.toString())
    }

    private fun save(name: String, text: String): Path? = runCatching {
        val dir = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev")
        Files.createDirectories(dir)
        val file = dir.resolve("${name}_${LocalDateTime.now().format(stamp)}.txt")
        Files.writeString(file, text)
        file
    }.onFailure {
        logger.error("Could not save the dump", it)
        ChatUtils.error("Could not save the dump: ${it.message}")
    }.getOrNull()

    private fun safe(name: String): String =
        name.replace(Regex("§."), "").replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "item" }
}
