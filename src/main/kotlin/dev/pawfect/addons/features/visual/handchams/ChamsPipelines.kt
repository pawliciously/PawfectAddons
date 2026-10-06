package dev.pawfect.addons.features.visual.handchams

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.utils.withBindings
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
            .withBindings(samplers = listOf("DepthSampler"), uniforms = listOf("MaskData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val KAWASE_DOWN: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_kawase_down"))
            .withVertexShader(id("core/kawase"))
            .withFragmentShader(id("core/kawase_down"))
            .withBindings(samplers = listOf("Sampler0"), uniforms = listOf("KawaseData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val KAWASE_UP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_kawase_up"))
            .withVertexShader(id("core/kawase"))
            .withFragmentShader(id("core/kawase_up"))
            .withBindings(samplers = listOf("Sampler0"), uniforms = listOf("KawaseData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val TRAIL: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_trail"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/trail"))
            .withBindings(samplers = listOf("MaskSampler", "HistorySampler"), uniforms = listOf("TrailData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val OUTLINE_ROW: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_outline_row"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/outline_row"))
            .withBindings(samplers = listOf("MaskSampler"), uniforms = listOf("RowData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build(),
    )

    val COMPOSITE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/handchams_composite"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/composite"))
            .withBindings(samplers = listOf("BlurSampler", "MaskSampler", "GlowSampler", "PortalSkySampler", "PortalSampler", "TrailSampler", "RowSampler"), uniforms = listOf("CompositeData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build(),
    )

    fun bootstrap() = Unit
}
