package dev.pawfect.addons.ui.gpu

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.PawfectAddons
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.Optional

object UiPipelines {

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    val SHAPE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/ui_shape"))
            .withVertexShader(id("core/ui_shape"))
            .withFragmentShader(id("core/ui_shape"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(UiFormats.SHAPE_FORMAT, VertexFormat.Mode.QUADS)
            .withColorTargetState(RenderPipelines.GUI.colorTargetState)
            .withDepthStencilState(Optional.ofNullable(RenderPipelines.GUI.depthStencilState))
            .withCull(RenderPipelines.GUI.isCull)
            .build(),
    )

    val MENU_BACKGROUND: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/menu_bg"))
            .withVertexShader(id("core/ui_shape"))
            .withFragmentShader(id("core/menu_bg"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(UiFormats.SHAPE_FORMAT, VertexFormat.Mode.QUADS)
            .withColorTargetState(RenderPipelines.GUI.colorTargetState)
            .withDepthStencilState(Optional.ofNullable(RenderPipelines.GUI.depthStencilState))
            .withCull(RenderPipelines.GUI.isCull)
            .build(),
    )

    val INVENTORY_BACKGROUND: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/inv_bg"))
            .withVertexShader(id("core/ui_shape"))
            .withFragmentShader(id("core/inv_bg"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(UiFormats.SHAPE_FORMAT, VertexFormat.Mode.QUADS)
            .withColorTargetState(RenderPipelines.GUI.colorTargetState)
            .withDepthStencilState(Optional.ofNullable(RenderPipelines.GUI.depthStencilState))
            .withCull(RenderPipelines.GUI.isCull)
            .build(),
    )

    fun bootstrap() = Unit
}
