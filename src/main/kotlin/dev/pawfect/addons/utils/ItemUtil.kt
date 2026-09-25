package dev.pawfect.addons.utils

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import java.util.Optional

object ItemUtil {

    fun ItemStack.loreLines(): List<String> =
        getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().map { it.string }

    fun ItemStack.skyblockId(): String? =
        get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }

    fun ItemStack.plainName(): String = hoverName.string

    // Skyblock puts the rarity on the last lore line ("LEGENDARY SWORD"), coloured to match.
    // Falls back to the name colour, and null for plain vanilla items.
    fun ItemStack.rarityColor(): Int? {
        if (isEmpty) return null
        get(DataComponents.LORE)?.lines()?.asReversed()?.forEach { line ->
            if (line.string.isBlank()) return@forEach
            return firstColor(line) ?: firstColor(hoverName)
        }
        return firstColor(hoverName)
    }

    private fun firstColor(component: Component): Int? =
        component.visit<Int>(
            { style, _ -> style.color?.let { Optional.of(it.value) } ?: Optional.empty() },
            Style.EMPTY,
        ).orElse(null)
}
