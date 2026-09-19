package dev.pawfect.addons.utils.renderables

import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.world.item.ItemStack

class TextRenderable(
    val text: String,
    private val scale: Double = 1.0,
    private val color: Int = 0xFFFFFFFF.toInt(),
    private val shadow: Boolean = true,
) : Renderable {

    override val width: Int by lazy { (McCompat.font.width(text) * scale).toInt() }
    override val height: Int = (McCompat.font.lineHeight * scale).toInt()

    override fun render(absX: Int, absY: Int) {
        if (scale == 1.0) {
            RenderContext.drawString(text, 0, 0, color, shadow)
            return
        }
        RenderContext.pushPop {
            RenderContext.scale(scale.toFloat())
            RenderContext.drawString(text, 0, 0, color, shadow)
        }
    }
}

class ItemRenderable(
    private val stack: ItemStack,
    private val scale: Double = 1.0,
) : Renderable {

    override val width: Int = (ICON_SIZE * scale).toInt()
    override val height: Int = (ICON_SIZE * scale).toInt()

    override fun render(absX: Int, absY: Int) {
        RenderContext.pushPop {
            if (scale != 1.0) RenderContext.scale(scale.toFloat())
            RenderContext.drawItem(stack, 0, 0)
        }
    }

    companion object {
        const val ICON_SIZE = 16
    }
}

class SpacerRenderable(override val width: Int, override val height: Int) : Renderable {
    override fun render(absX: Int, absY: Int) = Unit
}
