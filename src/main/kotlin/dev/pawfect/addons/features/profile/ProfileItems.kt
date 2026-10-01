package dev.pawfect.addons.features.profile

import com.mojang.serialization.Dynamic
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.utils.McCompat
import net.azureaaron.legacyitemdfu.LegacyItemStackFixer
import net.azureaaron.legacyitemdfu.TypeReferences
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture

object ProfileItems {

    private val logger = LoggerFactory.getLogger("PawfectAddons/ProfileItems")

    private val TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

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

    fun skyblockId(stack: ItemStack): String =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "") ?: ""

    private fun build(item: CompoundTag): ItemStack {
        if (item.isEmpty) return ItemStack.EMPTY
        return runCatching { fixed(item) }.getOrNull()?.takeUnless { it.isEmpty } ?: fallback(item)
    }

    private fun fixed(item: CompoundTag): ItemStack {
        val registries = McCompat.mc.connection?.registryAccess() ?: return ItemStack.EMPTY
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        val updated = LegacyItemStackFixer.getFixer().update(
            TypeReferences.LEGACY_ITEM_STACK,
            Dynamic<Tag>(ops, item.copy()),
            LegacyItemStackFixer.getFirstVersion(),
            LegacyItemStackFixer.getLatestVersion(),
        )
        val stack = ItemStack.CODEC.parse(updated)
            .resultOrPartial { logger.debug("Legacy item fix-up: {}", it) }
            .orElse(ItemStack.EMPTY)
        if (stack.isEmpty || stack.`is`(Items.AIR)) return ItemStack.EMPTY

        stack.get(DataComponents.CUSTOM_NAME)?.let { stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(it.string)) }
        stack.get(DataComponents.LORE)?.let { lore ->
            stack.set(DataComponents.LORE, ItemLore(lore.lines().map { LegacyText.parse(it.string) }))
        }
        stack.get(DataComponents.CUSTOM_DATA)?.let {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(it.copyTag().getCompoundOrEmpty("ExtraAttributes")))
        }
        val display = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT)
            .withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true)
            .withHidden(DataComponents.ENCHANTMENTS, true)
        stack.set(DataComponents.TOOLTIP_DISPLAY, display)
        return stack
    }

    private fun fallback(item: CompoundTag): ItemStack {
        val tag = item.getCompoundOrEmpty("tag")
        val extra = tag.getCompoundOrEmpty("ExtraAttributes")
        val display = tag.getCompoundOrEmpty("display")
        val internal = extra.getStringOr("id", "")
        val texture = tag.getCompoundOrEmpty("SkullOwner").getCompoundOrEmpty("Properties")
            .getListOrEmpty("textures").getCompoundOrEmpty(0).getStringOr("Value", "")

        val base = runCatching {
            CompletableFuture.supplyAsync({
                when {
                    texture.isNotEmpty() -> runCatching { NeuRepo.headStack(texture) }.getOrNull()
                    internal.isNotEmpty() -> NeuRepo.itemStack(internal).takeUnless { it.isEmpty }?.copy()
                    else -> null
                }
            }, McCompat.mc).get()
        }.getOrNull() ?: ItemStack(Items.PAPER)

        val stack = base.copy()
        stack.count = item.getByteOr("Count", 1).toInt().coerceIn(1, 99)
        display.getStringOr("Name", "").takeIf { it.isNotEmpty() }?.let { stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(it)) }
        val lore = display.getListOrEmpty("Lore")
        if (lore.size > 0) stack.set(DataComponents.LORE, ItemLore((0 until lore.size).map { LegacyText.parse(lore.getStringOr(it, "")) }))
        display.getIntOr("color", -1).takeIf { it >= 0 }?.let { stack.set(DataComponents.DYED_COLOR, DyedItemColor(it)) }
        if (tag.getListOrEmpty("ench").size > 0) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        if (!extra.isEmpty) stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
        return stack
    }

    fun pet(pet: ProfileData.Pet): ItemStack {
        val tierIndex = TIERS.indexOf(pet.tier).coerceAtLeast(0)
        val repo = NeuRepo.item("${pet.type};$tierIndex") ?: NeuRepo.item("${pet.type};${tierIndex.coerceAtMost(4)}")
        val base = (repo?.let { NeuRepo.itemStack(it.internalName) }?.takeUnless { it.isEmpty } ?: ItemStack(Items.BONE)).copy()
        val title = repo?.displayName
        val color = tierColor(pet.tier)
        val name = (title ?: pet.type.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) })
            .replace(Regex("""\[Lvl \{LVL}]\s*"""), "")
            .replace(Regex("§."), "")
        base.set(DataComponents.CUSTOM_NAME, LegacyText.parse("§7[Lvl ${pet.level}] $color$name"))

        val lines = mutableListOf<Component>(
            LegacyText.parse(
                if (pet.level >= pet.maxLevel) "§bMax level"
                else "§7Progress to Lvl ${pet.level + 1}: §e${(pet.progress * 100).toInt()}%",
            ),
            LegacyText.parse("§7Total XP: §e${"%,d".format(pet.exp.toLong())}"),
        )
        pet.heldItem?.let { held ->
            lines += LegacyText.parse("§7Held item: §f${NeuRepo.item(held)?.displayName ?: held}")
        }
        if (pet.active) lines += LegacyText.parse("§aActive pet")
        lines += Component.empty()
        lines += LegacyText.parse("$color§l${pet.tier}")
        base.set(DataComponents.LORE, ItemLore(lines))
        base.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("id", "PET") }))
        return base
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
}
