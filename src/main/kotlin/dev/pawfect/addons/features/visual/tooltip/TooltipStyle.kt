package dev.pawfect.addons.features.visual.tooltip

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.TooltipConfig.BorderSource
import dev.pawfect.addons.config.features.TooltipConfig.Frame
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundGradient
import dev.pawfect.addons.ui.Draw.roundOutline
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.UiFont
import dev.pawfect.addons.utils.ItemUtil.rarityColor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.world.item.ItemStack

object TooltipStyle {

    private val config get() = ConfigManager.features.tooltip

    private var item: ItemStack = ItemStack.EMPTY

    @JvmStatic
    fun setItem(stack: ItemStack?) {
        item = stack ?: ItemStack.EMPTY
    }

    @JvmStatic
    fun clearItem() {
        item = ItemStack.EMPTY
    }

    private fun enabled(): Boolean {
        config.sanitize()
        return config.enabled
    }

    @JvmStatic
    fun restyle(lines: List<Component>): List<Component> {
        if (!enabled() || !config.restyleFont) return lines
        val face = UiFont.face() ?: return lines
        return lines.map { reface(it, face) }
    }

    private fun reface(component: Component, face: FontDescription): Component {
        val style = component.style
        val current = style.font
        val wanted = if (current == null || current == FontDescription.DEFAULT) style.withFont(face) else style
        val copy = component.plainCopy().setStyle(wanted)
        component.siblings.forEach { copy.append(reface(it, face)) }
        return copy
    }

    @JvmStatic
    fun pushScale(graphics: GuiGraphicsExtractor, x: Int, y: Int): Boolean {
        if (!enabled()) return false
        val scale = config.scale.coerceIn(0.7f, 1.6f)
        if (scale in 0.999f..1.001f) return false
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(scale, scale)
        graphics.pose().translate(-x.toFloat(), -y.toFloat())
        return true
    }

    @JvmStatic
    fun popScale(graphics: GuiGraphicsExtractor, pushed: Boolean) {
        if (pushed) graphics.pose().popMatrix()
    }

    @JvmStatic
    fun drawBackground(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int): Boolean {
        if (!enabled()) return false
        if (width <= 0 || height <= 0) return false

        val inset = config.inset.coerceIn(0f, 10f)
        val left = x - inset
        val top = y - inset
        val boxWidth = width + inset * 2f
        val boxHeight = height + inset * 2f
        val radius = config.radius.coerceIn(0f, 10f)
        val alpha = (config.opacity.coerceIn(0.2f, 1f) * 255f).toInt().coerceIn(0, 255)
        val edge = borderColor()

        if (config.shadow) {
            graphics.dropShadow(left, top + 2f, boxWidth, boxHeight, radius + 2f, 7f, 0x66000000, 1.5f)
        }

        when (config.frame) {
            Frame.PLATE -> graphics.roundGradient(
                left,
                top,
                boxWidth,
                boxHeight,
                radius,
                Theme.withAlpha(Theme.mix(Theme.panel, edge, 0.10f), alpha),
                Theme.withAlpha(Theme.background, alpha),
            )

            Frame.FLAT -> graphics.roundRect(
                left,
                top,
                boxWidth,
                boxHeight,
                radius,
                Theme.withAlpha(Theme.background, alpha),
            )

            Frame.GLASS -> {
                graphics.roundRect(
                    left,
                    top,
                    boxWidth,
                    boxHeight,
                    radius,
                    Theme.withAlpha(Theme.background, (alpha * 0.72f).toInt().coerceIn(0, 255)),
                )
                graphics.roundGradient(
                    left,
                    top,
                    boxWidth,
                    boxHeight * 0.55f,
                    radius,
                    Theme.withAlpha(0xFFFFFF, 20),
                    Theme.withAlpha(0xFFFFFF, 0),
                )
            }
        }

        if (config.border != BorderSource.NONE) {
            graphics.roundOutline(left, top, boxWidth, boxHeight, radius, Theme.withAlpha(edge, 190), 1f)
        }

        return true
    }

    private fun borderColor(): Int = when (config.border) {
        BorderSource.RARITY -> item.rarityColor() ?: Theme.accent
        BorderSource.ACCENT -> Theme.accent
        BorderSource.BORDER -> Theme.border
        BorderSource.NONE -> Theme.border
    }
}
