package dev.pawfect.addons.config.core

import com.google.gson.annotations.Expose
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.renderables.Renderable

class Position @JvmOverloads constructor(
    x: Int = 0,
    y: Int = 0,
    scale: Float = DEFAULT_SCALE,
) {

    @Expose
    var x: Int = x

    @Expose
    var y: Int = y

    @Expose
    var scale: Float = scale
        get() = if (field <= 0f) DEFAULT_SCALE else field

    @Transient
    var label: String = "Unnamed"
        internal set

    val effectiveScale: Float get() = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun moveTo(newX: Int, newY: Int) {
        x = newX
        y = newY
    }

    fun resetScale() {
        scale = DEFAULT_SCALE
    }

    fun getAbsX(objectWidth: Int): Int = resolve(x, McCompat.scaledWidth, objectWidth)

    fun getAbsY(objectHeight: Int): Int = resolve(y, McCompat.scaledHeight, objectHeight)

    private fun resolve(axis: Int, screenLength: Int, objectLength: Int): Int {
        val resolved = if (axis < 0) screenLength + axis - objectLength else axis
        return resolved.coerceIn(0, (screenLength - objectLength).coerceAtLeast(0))
    }

    fun render(renderable: Renderable, label: String) {
        val scale = effectiveScale
        val width = (renderable.width * scale).toInt()
        val height = (renderable.height * scale).toInt()
        val absX = getAbsX(width)
        val absY = getAbsY(height)

        RenderContext.pushPop {
            RenderContext.translate(absX.toFloat(), absY.toFloat())
            if (scale != 1f) RenderContext.scale(scale)
            renderable.render(absX, absY)
        }

        GuiEditManager.register(this, label, width, height)
    }

    companion object {
        const val DEFAULT_SCALE = 1f
        const val MIN_SCALE = 0.3f
        const val MAX_SCALE = 4f
    }
}
