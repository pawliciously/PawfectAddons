package dev.pawfect.addons.utils

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore

object ItemUtil {

    fun ItemStack.loreLines(): List<String> =
        getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().map { it.string }

    fun ItemStack.skyblockId(): String? =
        get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.takeIf { it.isNotEmpty() }

    fun ItemStack.plainName(): String = hoverName.string
}
