package dev.pawfect.addons.utils

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

object RenderContext {

    private var current: GuiGraphicsExtractor? = null
    private var depth = 0

    val graphics: GuiGraphicsExtractor
        get() = current ?: error("No render context is active. Draw calls must happen inside withContext { }.")

    val isActive: Boolean get() = current != null

    inline fun <T> withContext(context: GuiGraphicsExtractor, block: () -> T): T {
        push(context)
        try {
            return block()
        } finally {
            pop()
        }
    }

    fun push(context: GuiGraphicsExtractor) {
        depth++
        if (current == null) current = context
    }

    fun pop() {
        depth--
        if (depth <= 0) {
            current = null
            depth = 0
        }
    }

    inline fun pushPop(block: () -> Unit) {
        graphics.pose().pushMatrix()
        try {
            block()
        } finally {
            graphics.pose().popMatrix()
        }
    }

    fun translate(x: Float, y: Float) = graphics.pose().translate(x, y)

    fun scale(factor: Float) = graphics.pose().scale(factor)

    fun drawString(text: String, x: Int, y: Int, color: Int = 0xFFFFFFFF.toInt(), shadow: Boolean = true) {
        graphics.text(McCompat.font, text, x, y, color, shadow)
    }

    fun drawItem(stack: ItemStack, x: Int, y: Int) = graphics.item(stack, x, y)

    fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) =
        graphics.fill(left, top, right, bottom, color)
}
