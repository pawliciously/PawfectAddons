package dev.pawfect.addons.utils.renderables

import dev.pawfect.addons.utils.RenderContext

interface Renderable {

    val width: Int
    val height: Int

    fun render(absX: Int, absY: Int)

    fun isHovered(absX: Int, absY: Int, mouseX: Int, mouseY: Int): Boolean =
        mouseX >= absX && mouseX < absX + width && mouseY >= absY && mouseY < absY + height

    companion object {
        fun drawChild(child: Renderable, absX: Int, absY: Int, dx: Int, dy: Int) {
            RenderContext.pushPop {
                RenderContext.translate(dx.toFloat(), dy.toFloat())
                child.render(absX + dx, absY + dy)
            }
        }
    }
}

enum class HorizontalAlignment { LEFT, CENTER, RIGHT }

enum class VerticalAlignment { TOP, CENTER, BOTTOM }

internal fun HorizontalAlignment.offset(available: Int, used: Int): Int = when (this) {
    HorizontalAlignment.LEFT -> 0
    HorizontalAlignment.CENTER -> (available - used) / 2
    HorizontalAlignment.RIGHT -> available - used
}

internal fun VerticalAlignment.offset(available: Int, used: Int): Int = when (this) {
    VerticalAlignment.TOP -> 0
    VerticalAlignment.CENTER -> (available - used) / 2
    VerticalAlignment.BOTTOM -> available - used
}
