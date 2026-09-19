package dev.pawfect.addons.features.dungeon

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.mixin.RenderTypeInvoker
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier

object WaypointRenderTypes {

    private class SeeThroughLines(source: RenderPipeline) : RenderPipeline(
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/waypoint_lines"),
        source.vertexShader,
        source.fragmentShader,
        source.shaderDefines,
        source.samplers,
        source.uniforms,
        source.colorTargetState,
        DepthStencilState(CompareOp.ALWAYS_PASS, false),
        source.polygonMode,
        source.isCull,
        source.vertexFormat,
        source.vertexFormatMode,
        source.sortKey,
    )

    val seeThrough: RenderType by lazy {
        val pipeline = RenderPipelines.register(SeeThroughLines(RenderPipelines.LINES))
        RenderTypeInvoker.`pawfectaddons$create`(
            "pawfect_waypoint_lines",
            RenderSetup.builder(pipeline).createRenderSetup(),
        )
    }

    fun forMode(throughWalls: Boolean): RenderType =
        if (throughWalls) seeThrough else RenderTypes.lines()
}
