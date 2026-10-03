package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.PawfectAddons
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object HubPipelines {

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    private fun terrain(name: String, configure: RenderPipeline.Builder.() -> Unit): RenderPipeline =
        RenderPipelines.register(
            RenderPipeline.builder()
                .withLocation(id("pipeline/hub_$name"))
                .withVertexShader(id("core/hub_terrain"))
                .withFragmentShader(id("core/hub_terrain"))
                .withSampler("Sampler0")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("HubScene", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .apply(configure)
                .build(),
        )

    val SOLID: RenderPipeline = terrain("solid") {}

    val CUTOUT: RenderPipeline = terrain("cutout") {
        withShaderDefine("ALPHA_CUTOUT", 0.5f)
    }

    val TRANSLUCENT: RenderPipeline = terrain("translucent") {
        withShaderDefine("ALPHA_CUTOUT", 0.01f)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }

    val SKY: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/hub_sky"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/hub_sky"))
            .withUniform("HubScene", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    fun bootstrap() = Unit
}
