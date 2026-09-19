package dev.pawfect.addons.features.visual.motionblur

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.PawfectAddons
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
            .withSampler("ColorSampler")
            .withSampler("DepthSampler")
            .withUniform("MotionData", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build(),
    )

    val BLIT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/motion_blit"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/blit"))
            .withSampler("ColorSampler")
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build(),
    )

    fun bootstrap() = Unit
}
