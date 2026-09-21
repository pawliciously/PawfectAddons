package dev.pawfect.addons.features.debug

import dev.pawfect.addons.utils.McCompat
import net.minecraft.world.entity.decoration.ArmorStand

object StandDebug {

    private const val RADIUS = 24.0
    private const val LIMIT = 30

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
}
