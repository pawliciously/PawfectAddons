package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.mixin.RenderTypeInvoker
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier

object TrailRenderTypes {

    private class Blended(source: RenderPipeline, name: String, blend: BlendFunction) : RenderPipeline(
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/$name"),
        source.vertexShader,
        source.fragmentShader,
        source.shaderDefines,
        source.samplers,
        source.uniforms,
        ColorTargetState(blend),
        DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true),
        source.polygonMode,
        source.isCull,
        source.vertexFormat,
        source.vertexFormatMode,
        source.sortKey,
    )

    private fun renderType(name: String, pipeline: RenderPipeline): RenderType =
        RenderTypeInvoker.`pawfectaddons$create`(
            "pawfect_$name",
            RenderSetup.builder(pipeline).createRenderSetup(),
        )

    private val glowing: RenderType by lazy {
        renderType(
            "trail_glow",
            RenderPipelines.register(Blended(RenderPipelines.DEBUG_QUADS, "trail_glow", BlendFunction.LIGHTNING)),
        )
    }

    private val plain: RenderType by lazy {
        renderType(
            "trail",
            RenderPipelines.register(Blended(RenderPipelines.DEBUG_QUADS, "trail", BlendFunction.TRANSLUCENT)),
        )
    }

    fun forGlow(glow: Boolean): RenderType = if (glow) glowing else plain
}
