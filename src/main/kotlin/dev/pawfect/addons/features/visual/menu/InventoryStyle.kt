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
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import org.joml.Matrix3x2f

object InventoryStyle {

    private val config get() = ConfigManager.features.inventory

    private val startedAt = System.currentTimeMillis()

    private var drawnThisFrame = false

    private var tooltipDepth = 0

    @JvmStatic
    fun draws(screen: Any): Boolean = config.enabled && screen is AbstractContainerScreen<*>

    @JvmStatic
    fun active(): Boolean = draws(McCompat.mc.screen ?: return false)

    @JvmStatic
    fun endFrame() {
        drawnThisFrame = false
        tooltipDepth = 0
    }

    @JvmStatic
    fun beginTooltip() {
        tooltipDepth++
    }

    @JvmStatic
    fun endTooltip() {
        if (tooltipDepth > 0) tooltipDepth--
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
        if (!active() || tooltipDepth > 0) return false
        val screen = McCompat.mc.screen as? AbstractContainerScreen<*> ?: return false
        val geometry = screen as ContainerScreenAccessor
        val left = geometry.`pawfectaddons$leftPos`()
        val top = geometry.`pawfectaddons$topPos`()
        val panelWidth = geometry.`pawfectaddons$imageWidth`()
        val panelHeight = geometry.`pawfectaddons$imageHeight`()
        val fits = x == left && y == top && width == panelWidth && height == panelHeight
        val named = id.path.startsWith("textures/gui/container/") &&
            x >= left && y >= top && x + width <= left + panelWidth && y + height <= top + panelHeight
        if (!fits && !named) return false
        if (drawnThisFrame) return true
        drawnThisFrame = true
        draw(graphics, screen, left, top, panelWidth, panelHeight, screen.menu.slots, McCompat.mouseX, McCompat.mouseY)
        return true
    }

    @JvmStatic
    fun hidesSprite(id: Identifier): Boolean =
        active() && tooltipDepth == 0 && config.hideSlotIcons && id.path.startsWith("container/slot/")

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
        for (slot in slots) {
            if (!slot.isActive) continue
            plate(graphics, left + slot.x - 1f, top + slot.y - 1f, mouseX, mouseY)
        }
    }

    private fun plate(graphics: GuiGraphicsExtractor, x: Float, y: Float, mouseX: Int, mouseY: Int) {
        val alpha = (config.slotOpacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 2) return
        val hovered = mouseX >= x && mouseX < x + 18f && mouseY >= y && mouseY < y + 18f
        val fill = if (hovered) {
            (minOf(255, alpha + 40) shl 24) or (shade(accentColor(), 0.35f) and 0xFFFFFF)
        } else {
            (alpha shl 24) or (shade(bottomColor(), 0.8f) and 0xFFFFFF)
        }
        val border = if (hovered) {
            (minOf(255, alpha + 90) shl 24) or (accentColor() and 0xFFFFFF)
        } else {
            ((alpha / 2) shl 24) or (accentColor() and 0xFFFFFF)
        }
        Shapes.rect(graphics, x, y, 18f, 18f, config.slotRadius.coerceIn(0f, 8f), fill, fill, 1f, border)
    }

    @JvmStatic
    fun replaceSlotFrame(graphics: GuiGraphicsExtractor, sprite: Identifier, x: Int, y: Int): Boolean {
        if (!active() || tooltipDepth > 0) return false
        if (sprite != SLOT_FRAME) return false
        val screen = McCompat.mc.screen as? AbstractContainerScreen<*> ?: return false
        val geometry = screen as ContainerScreenAccessor
        val left = geometry.`pawfectaddons$leftPos`()
        val top = geometry.`pawfectaddons$topPos`()
        val offhand = screen.menu.slots.firstOrNull { it.container is Inventory && it.containerSlot == Inventory.SLOT_OFFHAND }
        if (offhand != null && x == left + offhand.x - 1 && y == top + offhand.y - 1) return true
        if (config.slotPlates) plate(graphics, x.toFloat(), y.toFloat(), McCompat.mouseX, McCompat.mouseY)
        return true
    }

    private val SLOT_FRAME: Identifier = Identifier.withDefaultNamespace("container/slot")

    private val NAV_TAB = Regex("""^(?:quick_nav/|container/creative_inventory/)tab_(top|bottom)_(selected|unselected)_\d+$""")

    @JvmStatic
    fun replaceNavTab(graphics: GuiGraphicsExtractor, sprite: Identifier, x: Int, y: Int, width: Int, height: Int, color: Int): Boolean {
        if (!active() || tooltipDepth > 0) return false
        val match = NAV_TAB.matchEntire(sprite.path) ?: return false
        val top = match.groupValues[1] == "top"
        val selected = match.groupValues[2] == "selected"
        val alpha = ((color ushr 24) and 0xFF) / 255f
        if (alpha <= 0.01f) return true

        val mouseX = McCompat.mouseX
        val mouseY = McCompat.mouseY
        val hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
        val fillRgb = when {
            selected -> shade(topColor(), 1.1f)
            hovered -> shade(bottomColor(), 1.9f)
            else -> shade(bottomColor(), 1.35f)
        }
        val fill = withAlpha(fillRgb, alpha)
        val border = withAlpha(accentColor(), alpha * if (selected) 0.85f else if (hovered) 0.55f else 0.3f)

        val overlap = 4f
        val left = x + 1f
        val tabWidth = width - 2f
        val radius = 5f
        val bodyTop: Float
        val bodyBottom: Float
        if (top) {
            bodyTop = y + 2f
            bodyBottom = y + height - if (selected) 0f else overlap
        } else {
            bodyTop = y + if (selected) 0f else overlap
            bodyBottom = y + height - 2f
        }
        Shapes.rect(graphics, left, bodyTop, tabWidth, bodyBottom - bodyTop, radius, fill, fill, 1f, border)
        val seam = radius + 1f
        if (top) {
            Shapes.rect(graphics, left + 1f, bodyBottom - seam, tabWidth - 2f, seam, 0f, fill)
        } else {
            Shapes.rect(graphics, left + 1f, bodyTop, tabWidth - 2f, seam, 0f, fill)
        }
        return true
    }

    private fun withAlpha(rgb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)
}
