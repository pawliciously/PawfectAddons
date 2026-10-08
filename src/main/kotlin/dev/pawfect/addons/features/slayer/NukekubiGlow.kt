package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.minecraft.core.component.DataComponents
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Base64
import java.util.WeakHashMap

/**
 * Glows the Nukekubi Fixations a Voidgloom Seraph throws out: the floating heads that keep
 * damaging you until you look at them and hit them. They are armor stands carrying the
 * EnderDragon skull, a skin nothing else in SkyBlock wears.
 */
object NukekubiGlow {

    private val config get() = ModuleSwitch.view("slayers", ConfigManager.features.slayers)

    private const val SKIN_HASH = "eb07594e2df273921a77c101d0bfdfa1115abed5b9b2029eb496ceba9bdbb4b3"
    private const val MAX_RANGE = 64.0
    private const val MAX_RANGE_SQ = MAX_RANGE * MAX_RANGE

    // ItemStack keeps identity equality, so each stack is decoded once for as long as it lives.
    private val checked = WeakHashMap<ItemStack, Boolean>()

    // Heads spawn on the boss that threw them, so a head that first shows up within this many
    // blocks of your own boss (side to side, ignoring height) is yours.
    private const val SPAWN_RADIUS_SQ = 2.0 * 2.0

    /** Each head's verdict, made the first tick it's seen: whether your own boss threw it. */
    private val ours = HashMap<Int, Boolean>()

    @JvmStatic
    fun shouldGlow(entity: Entity): Boolean = config.nukekubiGlow && isNearbyHead(entity)

    /** A live Nukekubi close enough to matter, thrown by your own boss. */
    fun isNearbyHead(entity: Entity): Boolean {
        if (entity !is ArmorStand) return false
        if (!entity.level().isClientSide || !entity.isAlive) return false
        val player = McCompat.player ?: return false
        if (player.distanceToSqr(entity) > MAX_RANGE_SQ) return false
        return ours[entity.id] == true
    }

    fun onTick() {
        val level = McCompat.mc.level
        val player = McCompat.player
        if (level == null || player == null || (!config.nukekubiGlow && !config.nukekubiTracer)) {
            ours.clear()
            return
        }
        ours.keys.removeIf { level.getEntity(it)?.isAlive != true }

        val boss = SlayerManager.boss?.takeIf { it.owned && it.type == SlayerManager.SlayerType.VOIDGLOOM }
        val stands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(MAX_RANGE)) { it.id !in ours }
        for (stand in stands) {
            if (!isNukekubi(stand)) continue
            ours[stand.id] = boss != null && besideBoss(stand, boss.position)
        }
    }

    private fun besideBoss(stand: ArmorStand, boss: Vec3): Boolean {
        val dx = stand.x - boss.x
        val dz = stand.z - boss.z
        return dx * dx + dz * dz <= SPAWN_RADIUS_SQ
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
