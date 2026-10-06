package dev.pawfect.addons.features.visual.motionblur

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.utils.withBindings
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object MotionBlurPipelines {

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    val BLUR: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/motion_blur"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/motion_blur"))
            .withBindings(samplers = listOf("ColorSampler", "DepthSampler"), uniforms = listOf("MotionData"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build(),
    )

    val BLIT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/motion_blit"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/blit"))
            .withBindings(samplers = listOf("ColorSampler"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build(),
    )

    fun bootstrap() = Unit
}
