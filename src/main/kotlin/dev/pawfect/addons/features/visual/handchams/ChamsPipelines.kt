package dev.pawfect.addons.features.visual.handchams

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.PawfectAddons
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object ChamsPipelines {

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    val MASK: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_mask"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/hand_mask"))
            .withSampler("DepthSampler")
            .withUniform("MaskData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val KAWASE_DOWN: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_kawase_down"))
            .withVertexShader(id("core/kawase"))
            .withFragmentShader(id("core/kawase_down"))
            .withSampler("Sampler0")
            .withUniform("KawaseData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val KAWASE_UP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_kawase_up"))
            .withVertexShader(id("core/kawase"))
            .withFragmentShader(id("core/kawase_up"))
            .withSampler("Sampler0")
            .withUniform("KawaseData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val TRAIL: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_trail"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/trail"))
            .withSampler("MaskSampler")
            .withSampler("HistorySampler")
            .withUniform("TrailData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val OUTLINE_ROW: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_outline_row"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/outline_row"))
            .withSampler("MaskSampler")
            .withUniform("RowData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val COMPOSITE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_composite"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/composite"))
            .withSampler("BlurSampler")
            .withSampler("MaskSampler")
            .withSampler("GlowSampler")
            .withSampler("PortalSkySampler")
            .withSampler("PortalSampler")
            .withSampler("TrailSampler")
            .withSampler("RowSampler")
            .withUniform("CompositeData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build(),
    )

    fun bootstrap() = Unit
}
