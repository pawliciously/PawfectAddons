package dev.pawfect.addons.ui

import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.ui.gpu.ShapeRenderState
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
import kotlin.math.floor
import kotlin.math.min

object Shapes {

    private const val MODE_SOLID = 0f
    private const val MODE_SV_FIELD = 1f
    private const val MODE_HUE_BAR = 2f

    private var scissor: ScreenRectangle? = null
    private val scissorStack = ArrayDeque<ScreenRectangle?>()

    fun pushScissor(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float) {
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        val right = floor(x + width).toInt()
        val bottom = floor(y + height).toInt()
        scissorStack.addLast(scissor)
        graphics.enableScissor(left, top, right, bottom)
        val rect = ScreenRectangle(left, top, right - left, bottom - top)
            .transformAxisAligned(graphics.pose())
        scissor = scissor?.intersection(rect) ?: rect
    }

    fun popScissor(graphics: GuiGraphicsExtractor) {
        graphics.disableScissor()
        scissor = if (scissorStack.isEmpty()) null else scissorStack.removeLast()
    }

    fun rect(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        fill: Int,
        fillBottom: Int = fill,
        borderWidth: Float = 0f,
        borderColor: Int = 0,
        softness: Float = 0f,
        mode: Float = MODE_SOLID,
        horizontal: Boolean = false,
    ) {
        if (width <= 0f || height <= 0f) return

        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val centerX = x + halfWidth
        val centerY = y + halfHeight
        val grow = if (softness > 0f) softness + 1f else 0f

        val state = ShapeRenderState(
            Matrix3x2f(graphics.pose()),
            x - grow,
            y - grow,
            x + width + grow,
            y + height + grow,
            centerX,
            centerY,
            halfWidth,
            halfHeight,
            min(radius, min(halfWidth, halfHeight)),
            borderWidth,
            softness,
            mode,
            fill,
            fillBottom,
            borderColor,
            scissor,
            horizontal,
        )

        (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addGuiElement(state)
    }

    fun panel(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        fill: Int,
        borderColor: Int,
        borderWidth: Float = 1f,
    ) {
        rect(graphics, x, y, width, height, radius, fill, fill, borderWidth, borderColor)
    }

    fun outline(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        thickness: Float = 1f,
    ) {
        rect(graphics, x, y, width, height, radius, 0, 0, thickness, color)
    }

    fun gradient(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        top: Int,
        bottom: Int,
    ) {
        rect(graphics, x, y, width, height, radius, top, bottom)
    }

    fun gradientHorizontal(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        left: Int,
        right: Int,
    ) {
        rect(graphics, x, y, width, height, radius, left, right, 0f, 0, 0f, MODE_SOLID, true)
    }

    fun shadow(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        spread: Float,
        color: Int,
        offsetY: Float = 0f,
    ) {
        rect(graphics, x, y + offsetY, width, height, radius, color, color, 0f, 0, spread)
    }

    fun circle(graphics: GuiGraphicsExtractor, centerX: Float, centerY: Float, radius: Float, color: Int) {
        rect(graphics, centerX - radius, centerY - radius, radius * 2f, radius * 2f, radius, color)
    }

    fun pill(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
        borderWidth: Float = 0f,
        borderColor: Int = 0,
    ) {
        rect(graphics, x, y, width, height, height / 2f, color, color, borderWidth, borderColor)
    }

    fun saturationField(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        hue: Float,
    ) {
        rect(graphics, x, y, width, height, radius, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), hue, 0, 0f, MODE_SV_FIELD)
    }

    fun hueBar(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
    ) {
        rect(graphics, x, y, width, height, radius, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0f, 0, 0f, MODE_HUE_BAR)
    }
}
