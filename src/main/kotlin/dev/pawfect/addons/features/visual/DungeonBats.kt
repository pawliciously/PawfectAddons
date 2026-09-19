package dev.pawfect.addons.features.visual

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.Bat

object DungeonBats {

    private val config get() = ConfigManager.features.visuals

    @JvmStatic
    fun shouldHighlight(entity: Entity): Boolean {
        if (!config.batHighlighter) return false
        if (entity !is Bat) return false
        if (!entity.level().isClientSide) return false
        if (!entity.isAlive) return false
        if (entity.isInvisible || entity.isPassenger) return false
        if (!SkyBlockData.onSkyBlock || !SkyBlockData.inDungeons) return false

        val player = McCompat.player ?: return false
        val range = config.batRange.toDouble()
        return player.distanceToSqr(entity) <= range * range
    }

    @JvmStatic
    val glowColor: Int get() = config.batColor.rgb
}
