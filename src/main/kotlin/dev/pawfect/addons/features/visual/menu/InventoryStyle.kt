package dev.pawfect.addons.features.visual.menu

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.mixin.ContainerScreenAccessor
import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.gpu.MenuBackgroundRenderState
import dev.pawfect.addons.ui.gpu.UiPipelines
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.NonNullList
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.Slot
import org.joml.Matrix3x2f

object InventoryStyle {

    private val config get() = ConfigManager.features.inventory

    private val startedAt = System.currentTimeMillis()

    private var drawnThisFrame = false

    @JvmStatic
    fun draws(screen: Any): Boolean = config.enabled && screen is AbstractContainerScreen<*>

    @JvmStatic
    fun active(): Boolean = draws(McCompat.mc.screen ?: return false)

    @JvmStatic
    fun endFrame() {
        drawnThisFrame = false
    }

    @JvmStatic
    fun replacePanel(
        graphics: GuiGraphicsExtractor,
        id: Identifier,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (!active()) return false
        val screen = McCompat.mc.screen as? AbstractContainerScreen<*> ?: return false
        val geometry = screen as ContainerScreenAccessor
        val left = geometry.`pawfectaddons$leftPos`()
        val top = geometry.`pawfectaddons$topPos`()
        val panelWidth = geometry.`pawfectaddons$imageWidth`()
        val panelHeight = geometry.`pawfectaddons$imageHeight`()
        val fits = x == left && y == top && width == panelWidth && height == panelHeight
        val named = id.path.startsWith("textures/gui/container/")
        if (!fits && !named) return false
        if (drawnThisFrame) return true
        drawnThisFrame = true
        draw(graphics, screen, left, top, panelWidth, panelHeight, screen.menu.slots, McCompat.mouseX, McCompat.mouseY)
        return true
    }

    @JvmStatic
    fun hidesSprite(id: Identifier): Boolean =
        active() && config.hideSlotIcons && id.path.startsWith("container/slot/")

    @JvmStatic
    fun hidesSlotHighlight(): Boolean = active() && config.hideSlotHighlight

    @JvmStatic
    fun hidesRecipeBook(): Boolean = config.hideRecipeBook

    private fun topColor(): Int = if (config.customColors) config.colorTop else shade(Theme.palette.panel, 1.0f)

    private fun bottomColor(): Int = if (config.customColors) config.colorBottom else shade(Theme.palette.background, 0.7f)

    private fun accentColor(): Int = if (config.customColors) config.colorAccent else Theme.palette.accent

    private fun shade(rgb: Int, factor: Float): Int {
        val red = (((rgb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val green = (((rgb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val blue = ((rgb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    private fun draw(
        graphics: GuiGraphicsExtractor,
        screen: Any,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        slots: NonNullList<Slot>?,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (!draws(screen) || width <= 0 || height <= 0) return
        config.sanitize()

        val pad = 1f
        val state = MenuBackgroundRenderState(
            Matrix3x2f(graphics.pose()),
            left - pad,
            top - pad,
            left + width + pad,
            top + height + pad,
            opaque(topColor()),
            opaque(bottomColor()),
            accentColor(),
            config.intensity.coerceIn(0f, 1f),
            (System.currentTimeMillis() - startedAt) / 1000f,
            config.style.id.toFloat(),
            UiPipelines.INVENTORY_BACKGROUND,
        )
        (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addGuiElement(state)

        if (!config.slotPlates || slots == null) return
        val alpha = (config.slotOpacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 2) return
        val radius = config.slotRadius.coerceIn(0f, 8f)
        val fill = (alpha shl 24) or (shade(bottomColor(), 0.8f) and 0xFFFFFF)
        val border = ((alpha / 2) shl 24) or (accentColor() and 0xFFFFFF)
        val hoverFill = (minOf(255, alpha + 40) shl 24) or (shade(accentColor(), 0.35f) and 0xFFFFFF)
        val hoverBorder = (minOf(255, alpha + 90) shl 24) or (accentColor() and 0xFFFFFF)

        for (slot in slots) {
            if (!slot.isActive) continue
            val x = left + slot.x - 1f
            val y = top + slot.y - 1f
            val hovered = mouseX >= x && mouseX < x + 18f && mouseY >= y && mouseY < y + 18f
            Shapes.rect(
                graphics,
                x,
                y,
                18f,
                18f,
                radius,
                if (hovered) hoverFill else fill,
                if (hovered) hoverFill else fill,
                1f,
                if (hovered) hoverBorder else border,
            )
        }
    }
}
