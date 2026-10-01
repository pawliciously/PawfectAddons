package dev.pawfect.addons.features.visual.playerchams

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.PlayerChamsConfig.ScaleTarget
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.player.Player

object PlayerScale {

    private val config get() = ConfigManager.features.playerChams

    @JvmStatic
    var inPreview = false

    @JvmStatic
    fun apply(entity: Avatar, state: AvatarRenderState) {
        if (inPreview) return
        val scale = config.scale.coerceIn(MIN, MAX)
        if (scale == 1f) return
        config.sanitize()
        if (entity !is Player) return

        val self = entity.id == McCompat.player?.id
        val wanted = when (config.scaleTarget) {
            ScaleTarget.SELF -> self
            ScaleTarget.OTHERS -> !self && isRealPlayer(entity)
            ScaleTarget.EVERYONE -> self || isRealPlayer(entity)
        }
        if (!wanted) return

        state.scale *= scale
        state.shadowRadius *= scale
        state.nameTagAttachment = state.nameTagAttachment?.scale(scale.toDouble())
    }

    private fun isRealPlayer(player: Player): Boolean = player.uuid.version() == 4

    const val MIN = 0.25f
    const val MAX = 3f
}
