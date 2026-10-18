package dev.pawfect.addons.features.cosmetics.celestial

import com.google.gson.annotations.Expose
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.EntityTypes

class Pick(val piece: Piece, val color: String?, val size: Float = 1f) {
    val paint: Paint? get() = Paint.of(piece, color)
}

object Celestial {
    const val SIZE_MIN = 0.1f
    const val SIZE_MAX = 1.5f

    val CATALOG: Map<String, List<Piece>> = linkedMapOf("wings" to Wings.ALL, "halo" to Halos.ALL, "horns" to Horns.ALL)

    fun register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register { type, renderer, helper, _ ->
            if (type == EntityTypes.PLAYER && renderer is AvatarRenderer<*>) {
                @Suppress("UNCHECKED_CAST")
                helper.register(CelestialLayer(renderer as RenderLayerParent<AvatarRenderState, PlayerModel>))
            }
        }
    }
}

class CelestialPickData {

    @Expose
    var type: String? = null

    @Expose
    var color: String? = null

    @Expose
    var size: Float = 1f

    fun resolve(styles: List<Piece>): Pick? {
        val piece = styles.firstOrNull { it.id == type } ?: return null
        val hex = color?.trim()?.removePrefix("#")?.takeIf { it.length == 6 && it.toIntOrNull(16) != null }
        return Pick(piece, hex?.let { "#$it" }, size.coerceIn(Celestial.SIZE_MIN, Celestial.SIZE_MAX))
    }
}

class CelestialCosmetic {

    @Expose
    var wings: CelestialPickData? = null

    @Expose
    var halo: CelestialPickData? = null

    @Expose
    var horns: CelestialPickData? = null

    fun resolve(): List<Pick>? = listOfNotNull(
        wings?.resolve(Wings.ALL),
        halo?.resolve(Halos.ALL),
        horns?.resolve(Horns.ALL),
    ).takeIf { it.isNotEmpty() }
}
