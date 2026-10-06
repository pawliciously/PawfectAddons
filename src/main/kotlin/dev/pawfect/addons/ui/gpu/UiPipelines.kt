package dev.pawfect.addons.ui.gpu

import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.pawfect.addons.PawfectAddons
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object UiPipelines {

    private fun id(path: String): Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    /** A GUI pipeline drawn with our rounded-shape vertices and the given fragment shader. */
    private fun shape(name: String, fragment: String): RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(id("pipeline/$name"))
            .withVertexShader(id("core/ui_shape"))
            .withFragmentShader(id("core/$fragment"))
            .withVertexBinding(0, UiFormats.SHAPE_FORMAT)
            .build(),
    )

    val SHAPE: RenderPipeline = shape("ui_shape", "ui_shape")

    val MENU_BACKGROUND: RenderPipeline = shape("menu_bg", "menu_bg")

    val INVENTORY_BACKGROUND: RenderPipeline = shape("inv_bg", "inv_bg")

    val MEDIA_BACKGROUND: RenderPipeline = shape("media_bg", "media_bg")

    val CHAT_BACKGROUND: RenderPipeline = shape("chat_bg", "chat_bg")

    fun bootstrap() = Unit
}
