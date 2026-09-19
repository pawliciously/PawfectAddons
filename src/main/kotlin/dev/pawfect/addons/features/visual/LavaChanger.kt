package dev.pawfect.addons.features.visual

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

object LavaChanger {

    private val config get() = ConfigManager.features.visuals

    @JvmStatic
    val isEnabled: Boolean get() = config.lavaChanger

    @JvmStatic
    val hideFog: Boolean get() = config.lavaChanger && config.hideLavaFog

    @JvmStatic
    fun modelFor(set: FluidStateModelSet, state: FluidState): FluidModel? {
        if (!config.lavaChanger) return null
        if (state.type !== Fluids.LAVA && state.type !== Fluids.FLOWING_LAVA) return null
        return set.get(Fluids.WATER.defaultFluidState())
    }

    fun invalidate() {
        McCompat.mc.levelRenderer.allChanged()
    }
}
