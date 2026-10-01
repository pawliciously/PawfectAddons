package dev.pawfect.addons.features.profile

import dev.pawfect.addons.data.NeuRepo
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ItemLore
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Hypixel stores containers as base64, gzipped NBT in the 1.8 item format. Items are
 * rebuilt from the NEU repo by their SkyBlock id (which gives the right model or head
 * texture), then given the real name, lore, count, glint and dye from the profile.
 */
object ProfileItems {

    private val logger = LoggerFactory.getLogger("PawfectAddons/ProfileItems")

    private val TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    /** One entry per slot; empty slots are [ItemStack.EMPTY]. */
    fun decode(base64: String?): List<ItemStack> {
        if (base64.isNullOrEmpty()) return emptyList()
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            val root = NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap())
            val list = root.getListOrEmpty("i")
            (0 until list.size).map { build(list.getCompoundOrEmpty(it)) }
        } catch (error: Exception) {
            logger.warn("Could not read a profile container.", error)
            emptyList()
        }
    }

    private fun build(item: CompoundTag): ItemStack {
        if (item.isEmpty) return ItemStack.EMPTY
        val tag = item.getCompoundOrEmpty("tag")
        val extra = tag.getCompoundOrEmpty("ExtraAttributes")
        val display = tag.getCompoundOrEmpty("display")
        val internal = extra.getStringOr("id", "")

        val texture = tag.getCompoundOrEmpty("SkullOwner").getCompoundOrEmpty("Properties")
            .getListOrEmpty("textures").getCompoundOrEmpty(0).getStringOr("Value", "")

        val base = when {
            texture.isNotEmpty() -> runCatching { NeuRepo.headStack(texture) }.getOrNull()
            internal.isNotEmpty() -> NeuRepo.itemStack(internal).takeUnless { it.isEmpty }
            else -> null
        } ?: ItemStack(Items.PAPER)

        val stack = base.copy()
        stack.count = item.getByteOr("Count", 1).toInt().coerceIn(1, 99)

        val name = display.getStringOr("Name", "")
        if (name.isNotEmpty()) stack.set(DataComponents.CUSTOM_NAME, plain(name))
        val lore = display.getListOrEmpty("Lore")
        if (lore.size > 0) {
            stack.set(DataComponents.LORE, ItemLore((0 until lore.size).map { plain(lore.getStringOr(it, "")) }))
        }

        val color = display.getIntOr("color", -1)
        if (color >= 0) stack.set(DataComponents.DYED_COLOR, DyedItemColor(color))

        if (tag.getListOrEmpty("ench").size > 0 || !extra.getCompoundOrEmpty("enchantments").isEmpty) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        }
        return stack
    }

    /** A pet as an item: the NEU head for its type and rarity, with a level line on top. */
    fun pet(pet: ProfileData.Pet): ItemStack {
        val tierIndex = TIERS.indexOf(pet.tier).coerceAtLeast(0)
        val base = NeuRepo.itemStack("${pet.type};$tierIndex").takeUnless { it.isEmpty }
            ?: NeuRepo.itemStack("${pet.type};${tierIndex.coerceAtMost(4)}").takeUnless { it.isEmpty }
            ?: ItemStack(Items.BONE)
        val stack = base.copy()
        val color = tierColor(pet.tier)
        val title = NeuRepo.item("${pet.type};$tierIndex")?.displayName
            ?.replace(Regex("""\[Lvl \{LVL}]\s*"""), "")
            ?: pet.type.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        stack.set(DataComponents.CUSTOM_NAME, plain("§7[Lvl ${pet.level}] $color${title.replace(Regex("§."), "")}"))

        val lines = mutableListOf(
            plain("$color§l${pet.tier}"),
            plain(""),
            plain(if (pet.level >= pet.maxLevel) "§bMax level" else "§7Progress to Lvl ${pet.level + 1}: §e${(pet.progress * 100).toInt()}%"),
            plain("§7Total XP: §e${"%,d".format(pet.exp.toLong())}"),
        )
        pet.heldItem?.let { held ->
            lines += plain("§7Held item: §f${NeuRepo.item(held)?.displayName ?: held}")
        }
        if (pet.active) lines += plain("§aActive pet")
        stack.set(DataComponents.LORE, ItemLore(lines))
        return stack
    }

    fun tierColor(tier: String): String = when (tier) {
        "UNCOMMON" -> "§a"
        "RARE" -> "§9"
        "EPIC" -> "§5"
        "LEGENDARY" -> "§6"
        "MYTHIC" -> "§d"
        "DIVINE" -> "§b"
        "SPECIAL" -> "§c"
        else -> "§f"
    }

    /** SkyBlock text keeps its § colour codes; only the vanilla italics on custom names go. */
    private fun plain(legacy: String): Component =
        Component.literal(legacy).withStyle { it.withItalic(false) }
}
