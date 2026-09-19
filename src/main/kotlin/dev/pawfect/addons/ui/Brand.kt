package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object Brand {

    private const val SOURCE = 512

    const val CONTENT_RATIO = 0.7773f

    val LOGO: Identifier = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "textures/gui/logo.png")

    fun logo(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float, alpha: Float = 1f) {
        val level = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        if (level <= 0 || size <= 0f) return
        val scale = size / SOURCE
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
        graphics.pose().scale(scale, scale)
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            LOGO,
            0,
            0,
            0f,
            0f,
            SOURCE,
            SOURCE,
            SOURCE,
            SOURCE,
            (level shl 24) or 0xFFFFFF,
        )
        graphics.pose().popMatrix()
    }
}
