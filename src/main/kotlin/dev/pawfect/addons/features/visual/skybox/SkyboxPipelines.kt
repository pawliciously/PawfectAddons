package dev.pawfect.addons.features.visual.skybox

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.PawfectAddons
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
            .withUniform("SkyData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build(),
    )

    val COMPOSITE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/sky_composite"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/sky_composite"))
            .withSampler("SkySampler")
            .withUniform("SkyData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build(),
    )

    val UPSCALE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/sky_upscale"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/sky_upscale"))
            .withSampler("SkySampler")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    fun bootstrap() = Unit
}
