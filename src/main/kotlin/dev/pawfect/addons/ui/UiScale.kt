package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.min

object UiScale {

    private const val REFERENCE_WIDTH = 960f
    private const val REFERENCE_HEIGHT = 540f

    var scale: Float = 1f
        private set

    var width: Float = REFERENCE_WIDTH
        private set

    var height: Float = REFERENCE_HEIGHT
        private set

    private var locked = false

    fun lock() {
        locked = true
    }

    fun unlock() {
        locked = false
    }

    var minWidth: Float = 0f

    var minHeight: Float = 0f

    private fun refresh() {
        if (locked) return
        val guiWidth = McCompat.scaledWidth.toFloat()
        val guiHeight = McCompat.scaledHeight.toFloat()
        val userScale = ConfigManager.features.theme.uiScale.coerceIn(0.5f, 1f)

        val pixelsPerUnit = (McCompat.mc.window.width.toFloat() / guiWidth).coerceAtLeast(0.5f)
        val raw = min(guiWidth / REFERENCE_WIDTH, guiHeight / REFERENCE_HEIGHT) * userScale

        var physical = kotlin.math.round(raw * pixelsPerUnit).coerceAtLeast(1f)
        while (physical > 1f &&
            (guiWidth * pixelsPerUnit / physical < minWidth || guiHeight * pixelsPerUnit / physical < minHeight)
        ) {
            physical -= 1f
        }

        scale = physical / pixelsPerUnit
        if (scale <= 0f) scale = 1f

        width = guiWidth / scale
        height = guiHeight / scale
    }

    fun push(graphics: GuiGraphicsExtractor) {
        refresh()
        graphics.pose().pushMatrix()
        graphics.pose().scale(scale, scale)
    }

    fun pop(graphics: GuiGraphicsExtractor) {
        graphics.pose().popMatrix()
    }

    fun mouseX(vanillaX: Double): Float = (vanillaX / scale).toFloat()

    fun mouseY(vanillaY: Double): Float = (vanillaY / scale).toFloat()
}
