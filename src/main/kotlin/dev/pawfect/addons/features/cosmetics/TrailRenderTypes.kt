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

    private fun blended(name: String, blend: BlendFunction): RenderPipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/$name"))
        .withColorTargetState(ColorTargetState(blend))
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
        .withCull(false)
        .build()

    private fun renderType(name: String, pipeline: RenderPipeline): RenderType =
        RenderTypeInvoker.`pawfectaddons$create`(
            "pawfect_$name",
            RenderSetup.builder(pipeline).createRenderSetup(),
        )

    private val glowing: RenderType by lazy {
        renderType(
            "trail_glow",
            RenderPipelines.register(blended("trail_glow", BlendFunction.LIGHTNING)),
        )
    }

    private val plain: RenderType by lazy {
        renderType(
            "trail",
            RenderPipelines.register(blended("trail", BlendFunction.TRANSLUCENT)),
        )
    }

    fun forGlow(glow: Boolean): RenderType = if (glow) glowing else plain
}
