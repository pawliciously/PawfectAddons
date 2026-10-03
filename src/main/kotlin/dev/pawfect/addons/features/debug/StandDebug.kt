package dev.pawfect.addons.features.debug

import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.features.slayer.NukekubiGlow
import dev.pawfect.addons.utils.McCompat
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items

object StandDebug {

    private const val RADIUS = 24.0
    private const val LIMIT = 30
    private val skinUrl = Regex("texture/([0-9a-f]+)")

    fun dump(): List<String> {
        val player = McCompat.player ?: return listOf("No player.")
        val level = McCompat.mc.level ?: return listOf("No level.")

        val stands = level.getEntitiesOfClass(
            ArmorStand::class.java,
            player.boundingBox.inflate(RADIUS),
        ) { it.hasCustomName() }

        if (stands.isEmpty()) return listOf("Nothing named within $RADIUS blocks.")

        return stands
            .sortedBy { player.distanceToSqr(it) }
            .take(LIMIT)
            .map { "§8${"%.1f".format(player.distanceTo(it))}m §f${it.name.string.replace('§', '&')}" }
    }

    /** Armor stands carrying player heads nearby: which slot, the skin, and whether it counts as a Nukekubi. */
    fun heads(): List<String> {
        val player = McCompat.player ?: return listOf("No player.")
        val level = McCompat.mc.level ?: return listOf("No level.")
        val out = mutableListOf("§7Area: §f${HypixelLocation.mode ?: "unknown"}")

        val stands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(RADIUS)) { stand ->
            EquipmentSlot.entries.any { stand.getItemBySlot(it).`is`(Items.PLAYER_HEAD) }
        }
        if (stands.isEmpty()) return out + "No armor stands with heads within $RADIUS blocks."

        for (stand in stands.sortedBy { player.distanceToSqr(it) }.take(LIMIT)) {
            val slots = EquipmentSlot.entries.filter { stand.getItemBySlot(it).`is`(Items.PLAYER_HEAD) }
            val skin = NukekubiGlow.skinOf(stand.getItemBySlot(slots.first()))
            val hash = skin?.let { skinUrl.find(it)?.groupValues?.get(1)?.take(12) } ?: "no texture"
            val verdict = if (NukekubiGlow.isNukekubi(stand)) "§aNukekubi" else "§7other"
            out += "§8${"%.1f".format(player.distanceTo(stand))}m $verdict §7${slots.joinToString { it.name.lowercase() }}" +
                " marker=${stand.isMarker} invisible=${stand.isInvisible} skin=§f$hash"
        }
        return out
    }
}
