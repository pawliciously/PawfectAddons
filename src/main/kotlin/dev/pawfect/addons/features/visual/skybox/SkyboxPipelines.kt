package dev.pawfect.addons.features.visual.skybox

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.utils.withBindings
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object SkyboxPipelines {

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    val LUT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/sky_lut"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/sky_lut"))
            .withBindings(uniforms = listOf("SkyData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build(),
    )

    val COMPOSITE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/sky_composite"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/sky_composite"))
            .withBindings(samplers = listOf("SkySampler"), uniforms = listOf("SkyData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build(),
    )

    val UPSCALE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/sky_upscale"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/sky_upscale"))
            .withBindings(samplers = listOf("SkySampler"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    fun bootstrap() = Unit
}
