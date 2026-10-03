package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.utils.McCompat
import net.minecraft.core.component.DataComponents
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import java.util.Base64

/**
 * Glows the Nukekubi Fixations a Voidgloom Seraph throws out: the floating heads that keep
 * damaging you until you look at them and hit them. They are marker armor stands wearing the
 * EnderDragon skull, and only turn up in The End.
 */
object NukekubiGlow {

    private val config get() = ConfigManager.features.slayers

    private const val END = "combat_3"
    private const val SKIN_HASH = "eb07594e2df273921a77c101d0bfdfa1115abed5b9b2029eb496ceba9bdbb4b3"
    private const val MAX_RANGE_SQ = 64.0 * 64.0

    // The head item can arrive after the stand spawns, so the answer is kept per stack.
    private var lastStack: ItemStack? = null
    private var lastResult = false

    @JvmStatic
    fun shouldGlow(entity: Entity): Boolean {
        if (!config.nukekubiGlow || entity !is ArmorStand || !entity.isMarker) return false
        if (!entity.level().isClientSide || !entity.isAlive) return false
        if (HypixelLocation.mode != END) return false
        val player = McCompat.player ?: return false
        if (player.distanceToSqr(entity) > MAX_RANGE_SQ) return false
        return isNukekubi(entity.getItemBySlot(EquipmentSlot.HEAD))
    }

    @JvmStatic
    fun outlineColor(): Int {
        val alpha = (config.nukekubiOpacity.coerceIn(0.15f, 0.9f) * 255f).toInt()
        return ARGB.color(alpha, config.nukekubiColor and 0xFFFFFF)
    }

    private fun isNukekubi(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack === lastStack) return lastResult
        lastStack = stack
        lastResult = skinOf(stack)?.contains(SKIN_HASH) == true
        return lastResult
    }

    private fun skinOf(stack: ItemStack): String? {
        val profile = stack.get(DataComponents.PROFILE) ?: return null
        val texture = profile.partialProfile().properties()["textures"].firstOrNull() ?: return null
        return runCatching { String(Base64.getDecoder().decode(texture.value())) }.getOrNull()
    }
}
