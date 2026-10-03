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
import net.minecraft.world.item.Items
import java.util.Base64
import java.util.WeakHashMap

/**
 * Glows the Nukekubi Fixations a Voidgloom Seraph throws out: the floating heads that keep
 * damaging you until you look at them and hit them. They are armor stands carrying the
 * EnderDragon skull, and only turn up in The End.
 */
object NukekubiGlow {

    private val config get() = ConfigManager.features.slayers

    private const val END = "combat_3"
    private const val SKIN_HASH = "eb07594e2df273921a77c101d0bfdfa1115abed5b9b2029eb496ceba9bdbb4b3"
    private const val MAX_RANGE_SQ = 64.0 * 64.0

    // ItemStack keeps identity equality, so each stack is decoded once for as long as it lives.
    private val checked = WeakHashMap<ItemStack, Boolean>()

    @JvmStatic
    fun shouldGlow(entity: Entity): Boolean {
        if (!config.nukekubiGlow || entity !is ArmorStand) return false
        if (!entity.level().isClientSide || !entity.isAlive) return false
        if (HypixelLocation.mode != END) return false
        val player = McCompat.player ?: return false
        if (player.distanceToSqr(entity) > MAX_RANGE_SQ) return false
        return isNukekubi(entity)
    }

    @JvmStatic
    fun outlineColor(): Int {
        val alpha = (config.nukekubiOpacity.coerceIn(0.15f, 0.9f) * 255f).toInt()
        return ARGB.color(alpha, config.nukekubiColor and 0xFFFFFF)
    }

    /** The skull can sit in any slot, so every one is checked, as SkyHanni does. */
    fun isNukekubi(stand: ArmorStand): Boolean = EquipmentSlot.entries.any { isNukekubi(stand.getItemBySlot(it)) }

    private fun isNukekubi(stack: ItemStack): Boolean {
        if (stack.isEmpty || !stack.`is`(Items.PLAYER_HEAD)) return false
        return checked.getOrPut(stack) { skinOf(stack)?.contains(SKIN_HASH) == true }
    }

    /** The decoded texture JSON of a player head, or null when it has none. */
    fun skinOf(stack: ItemStack): String? {
        val profile = stack.get(DataComponents.PROFILE) ?: return null
        val texture = profile.partialProfile().properties()["textures"].firstOrNull() ?: return null
        return runCatching { String(Base64.getDecoder().decode(texture.value())) }.getOrNull()
    }
}
