package dev.pawfect.addons.ui

import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor

object Draw {

    fun GuiGraphicsExtractor.rect(x: Float, y: Float, width: Float, height: Float, color: Int) {
        if (width <= 0f || height <= 0f) return
        pose().pushMatrix()
        pose().translate(x, y)
        pose().scale(width, height)
        fill(0, 0, 1, 1, color)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.gradient(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        top: Int,
        bottom: Int,
    ) {
        if (width <= 0f || height <= 0f) return
        pose().pushMatrix()
        pose().translate(x, y)
        pose().scale(width, height)
        fillGradient(0, 0, 1, 1, top, bottom)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.gradientHorizontal(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        left: Int,
        right: Int,
    ) {
        if (width <= 0f || height <= 0f) return
        pose().pushMatrix()
        pose().translate(x, y + height)
        pose().rotate((-Math.PI / 2.0).toFloat())
        pose().scale(height, width)
        fillGradient(0, 0, 1, 1, left, right)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.border(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
        thickness: Float = 1f,
    ) {
        rect(x, y, width, thickness, color)
        rect(x, y + height - thickness, width, thickness, color)
        rect(x, y + thickness, thickness, height - thickness * 2f, color)
        rect(x + width - thickness, y + thickness, thickness, height - thickness * 2f, color)
    }

    fun GuiGraphicsExtractor.panel(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        fillColor: Int,
        borderColor: Int,
    ) {
        rect(x, y, width, height, fillColor)
        border(x, y, width, height, borderColor)
    }

    fun GuiGraphicsExtractor.roundRect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) {
        Shapes.rect(this, x, y, width, height, radius, color)
    }

    fun GuiGraphicsExtractor.roundGradient(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        top: Int,
        bottom: Int,
    ) {
        Shapes.gradient(this, x, y, width, height, radius, top, bottom)
    }

    fun GuiGraphicsExtractor.roundPanel(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        fill: Int,
        borderColor: Int,
        thickness: Float = 1f,
    ) {
        Shapes.panel(this, x, y, width, height, radius, fill, borderColor, thickness)
    }

    fun GuiGraphicsExtractor.roundOutline(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        thickness: Float = 1f,
    ) {
        Shapes.outline(this, x, y, width, height, radius, color, thickness)
    }

    fun GuiGraphicsExtractor.dropShadow(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        spread: Float,
        color: Int,
        offsetY: Float = 0f,
    ) {
        Shapes.shadow(this, x, y, width, height, radius, spread, color, offsetY)
    }

    fun GuiGraphicsExtractor.pill(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
        thickness: Float = 0f,
        borderColor: Int = 0,
    ) {
        Shapes.pill(this, x, y, width, height, color, thickness, borderColor)
    }

    fun GuiGraphicsExtractor.circle(centerX: Float, centerY: Float, radius: Float, color: Int) {
        Shapes.circle(this, centerX, centerY, radius, color)
    }

    fun GuiGraphicsExtractor.string(
        text: String,
        x: Float,
        y: Float,
        color: Int,
        shadow: Boolean = false,
        bold: Boolean = false,
    ) {
        pose().pushMatrix()
        pose().translate(x, y + Theme.textOffset)
        text(McCompat.font, UiFont.component(text, bold), 0, 0, color, shadow || Theme.fontShadow)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.icon(glyph: String, x: Float, y: Float, color: Int) {
        pose().pushMatrix()
        pose().translate(x, y + Theme.textOffset)
        text(McCompat.font, UiFont.iconComponent(glyph), 0, 0, color, Theme.fontShadow)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.brand(text: String, x: Float, y: Float, color: Int, shadow: Boolean = false) {
        pose().pushMatrix()
        pose().translate(x, y)
        text(McCompat.font, UiFont.brandComponent(text), 0, 0, color, shadow)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.stringScaled(
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        color: Int,
        shadow: Boolean = false,
    ) {
        pose().pushMatrix()
        pose().translate(x, y + Theme.textOffset)
        if (scale != 1f) pose().scale(scale, scale)
        text(McCompat.font, UiFont.component(text), 0, 0, color, shadow || Theme.fontShadow)
        pose().popMatrix()
    }

    fun GuiGraphicsExtractor.stringRight(text: String, right: Float, y: Float, color: Int, shadow: Boolean = false) {
        string(text, right - width(text), y, color, shadow)
    }

    fun GuiGraphicsExtractor.stringCentered(text: String, centerX: Float, y: Float, color: Int, shadow: Boolean = false) {
        string(text, centerX - width(text) / 2f, y, color, shadow)
    }

    fun width(text: String): Float = UiFont.width(text)

    fun brandWidth(text: String): Float = UiFont.brandWidth(text)

    const val BRAND_HEIGHT = 27f

    const val LINE_HEIGHT = 9f

    fun inside(mouseX: Float, mouseY: Float, x: Float, y: Float, width: Float, height: Float): Boolean =
        mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height

    fun truncate(text: String, maxWidth: Float): String {
        if (width(text) <= maxWidth) return text
        val ellipsis = "..."
        val limit = maxWidth - width(ellipsis)
        if (limit <= 0f) return ellipsis
        val builder = StringBuilder()
        for (character in text) {
            if (width(builder.toString() + character) > limit) break
            builder.append(character)
        }
        return builder.toString() + ellipsis
    }
}
