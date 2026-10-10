package dev.pawfect.addons.features.visual.playerchams

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.PlayerChamsConfig.ArmorTarget
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.layers.WingsLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.player.Player

/** Stops armour, helmets, skulls worn as hats and elytra drawing on players. Only you see it. */
object ArmorHider {

    private val config get() = ModuleSwitch.view("chams", ConfigManager.features.playerChams)

    @JvmStatic
    fun skips(layer: RenderLayer<*, *>, state: EntityRenderState): Boolean {
        if (layer !is HumanoidArmorLayer<*, *, *> && layer !is CustomHeadLayer<*, *> && layer !is WingsLayer<*, *>) return false
        if (state !is AvatarRenderState || !ModuleSwitch.enabled("chams")) return false
        config.sanitize()
        val target = config.hideArmor
        if (target == ArmorTarget.OFF) return false
        val self = state.id == McCompat.player?.id
        if (target == ArmorTarget.SELF) return self
        if (self) return true
        val player = McCompat.mc.level?.getEntity(state.id) as? Player ?: return false
        return player.uuid.version() == 4
    }
}
