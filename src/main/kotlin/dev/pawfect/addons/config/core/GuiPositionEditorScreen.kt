package dev.pawfect.addons.config.core

import com.mojang.blaze3d.platform.InputConstants
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.ui.Draw.border
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class GuiPositionEditorScreen : Screen(Component.literal("PawfectAddons Position Editor")) {

    private class Button(val x: Float, val y: Float, val width: Float, val label: String, val action: () -> Unit) {
        fun contains(mouseX: Float, mouseY: Float): Boolean =
            mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BUTTON_HEIGHT
    }

    private var dragging: Position? = null
    private var grabOffsetX = 0
    private var grabOffsetY = 0
    private var confirmingReset = false

    private val theme get() = ConfigManager.features.theme

    private val gridEnabled: Boolean get() = theme.hudGridEnabled
    private val gridSize: Int get() = theme.hudGridSize.coerceIn(2, 50)

    override fun isPauseScreen(): Boolean = false

    private fun buttons(): List<Button> {
        val barY = height - BUTTON_HEIGHT - 8f
        var x = 8f
        val list = ArrayList<Button>()

        val gridLabel = if (gridEnabled) "§aGrid ${gridSize}px" else "§7Grid Off"
        list.add(Button(x, barY, 78f, gridLabel) { theme.hudGridEnabled = !theme.hudGridEnabled })
        x += 82f
        list.add(Button(x, barY, 20f, "§7-") { theme.hudGridSize = (gridSize - 2).coerceAtLeast(2) })
        x += 24f
        list.add(Button(x, barY, 20f, "§7+") { theme.hudGridSize = (gridSize + 2).coerceAtMost(50) })
        x += 28f
        list.add(
            Button(x, barY, 96f, if (confirmingReset) "§c§lConfirm?" else "§cReset All") {
                if (!confirmingReset) {
                    confirmingReset = true
                } else {
                    confirmingReset = false
                    HudDefaults.resetAll(GuiEditManager.activeEntries().map { it.first })
                }
            },
        )
        return list
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        graphics.fill(0, 0, width, height, BACKDROP_COLOR)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)

        if (gridEnabled) drawGrid(graphics)

        var hoveredLabel: String? = null
        var hoveredPosition: Position? = null

        for ((position, elementWidth, elementHeight) in GuiEditManager.activeEntries()) {
            val x = position.getAbsX(elementWidth)
            val y = position.getAbsY(elementHeight)
            val hovered = mouseX >= x && mouseX < x + elementWidth && mouseY >= y && mouseY < y + elementHeight

            if (hovered) {
                hoveredLabel = position.label
                hoveredPosition = position
            }

            graphics.fill(
                x - BORDER,
                y - BORDER,
                x + elementWidth + BORDER,
                y + elementHeight + BORDER,
                if (hovered || dragging === position) HOVER_COLOR else IDLE_COLOR,
            )
        }

        val lines = if (hoveredLabel != null) {
            val position = hoveredPosition
            val coords = if (position == null) "" else " §8(${position.x}, ${position.y}) §8@ §7${"%.1f".format(position.effectiveScale)}x"
            listOf(
                "§d$hoveredLabel$coords",
                "§7Drag to move · Scroll to resize · §fRight-click to reset this element",
            )
        } else {
            listOf(
                "§dPawfectAddons Position Editor",
                "§7Hover an element to move it. Elements must be visible to appear here.",
                "§8Escape saves and closes · scroll on empty space changes grid size",
            )
        }

        var textY = 8f
        for (line in lines) {
            graphics.string(line, 8f, textY, WHITE, true)
            textY += 11f
        }

        drawButtons(graphics, mouseX.toFloat(), mouseY.toFloat())
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor) {
        val step = gridSize
        var x = 0
        while (x <= width) {
            graphics.fill(x, 0, x + 1, height, GRID_COLOR)
            x += step
        }
        var y = 0
        while (y <= height) {
            graphics.fill(0, y, width, y + 1, GRID_COLOR)
            y += step
        }
        graphics.fill(width / 2, 0, width / 2 + 1, height, CENTRE_COLOR)
        graphics.fill(0, height / 2, width, height / 2 + 1, CENTRE_COLOR)
    }

    private fun drawButtons(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        for (button in buttons()) {
            val hovered = button.contains(mouseX, mouseY)
            graphics.rect(button.x, button.y, button.width, BUTTON_HEIGHT, if (hovered) BUTTON_HOVER else BUTTON_IDLE)
            graphics.border(
                button.x,
                button.y,
                button.width,
                BUTTON_HEIGHT,
                if (hovered) Theme.opaque(Theme.accent) else BUTTON_BORDER,
            )
            graphics.string(
                button.label,
                button.x + 6f,
                button.y + (BUTTON_HEIGHT - 8f) / 2f,
                WHITE,
                true,
            )
        }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mouseX = click.x().toInt()
        val mouseY = click.y().toInt()

        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (button in buttons()) {
                if (button.contains(mouseX.toFloat(), mouseY.toFloat())) {
                    button.action()
                    return true
                }
            }
        }

        for ((position, elementWidth, elementHeight) in GuiEditManager.activeEntries().reversed()) {
            val x = position.getAbsX(elementWidth)
            val y = position.getAbsY(elementHeight)
            val hovered = mouseX >= x && mouseX < x + elementWidth && mouseY >= y && mouseY < y + elementHeight
            if (!hovered) continue

            when (click.button()) {
                InputConstants.MOUSE_BUTTON_LEFT -> {
                    dragging = position
                    grabOffsetX = mouseX - x
                    grabOffsetY = mouseY - y
                }

                InputConstants.MOUSE_BUTTON_RIGHT -> if (!HudDefaults.reset(position)) position.resetScale()
            }
            return true
        }

        confirmingReset = false
        return super.mouseClicked(click, doubled)
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        val position = dragging ?: return super.mouseDragged(click, offsetX, offsetY)
        val entry = GuiEditManager.activeEntries().firstOrNull { it.first === position }
            ?: return super.mouseDragged(click, offsetX, offsetY)

        applyDrag(
            position = position,
            elementWidth = entry.second,
            elementHeight = entry.third,
            targetX = click.x().toInt() - grabOffsetX,
            targetY = click.y().toInt() - grabOffsetY,
        )
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (verticalAmount == 0.0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        for ((position, elementWidth, elementHeight) in GuiEditManager.activeEntries().reversed()) {
            val x = position.getAbsX(elementWidth)
            val y = position.getAbsY(elementHeight)
            if (mouseX < x || mouseX >= x + elementWidth || mouseY < y || mouseY >= y + elementHeight) continue

            val step = if (verticalAmount > 0) SCALE_STEP else -SCALE_STEP
            position.scale = (position.scale + step).coerceIn(Position.MIN_SCALE, Position.MAX_SCALE)
            return true
        }

        if (gridEnabled) {
            val step = if (verticalAmount > 0) 2 else -2
            theme.hudGridSize = (gridSize + step).coerceIn(2, 50)
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            KEY_G -> {
                theme.hudGridEnabled = !theme.hudGridEnabled
                return true
            }

            KEY_R -> {
                val hovered = hoveredPositionAt(McCompat.mouseX, McCompat.mouseY)
                if (hovered != null) {
                    HudDefaults.reset(hovered)
                    return true
                }
            }
        }
        return super.keyPressed(event)
    }

    private fun hoveredPositionAt(mouseX: Int, mouseY: Int): Position? {
        for ((position, elementWidth, elementHeight) in GuiEditManager.activeEntries().reversed()) {
            val x = position.getAbsX(elementWidth)
            val y = position.getAbsY(elementHeight)
            if (mouseX >= x && mouseX < x + elementWidth && mouseY >= y && mouseY < y + elementHeight) return position
        }
        return null
    }

    private fun applyDrag(position: Position, elementWidth: Int, elementHeight: Int, targetX: Int, targetY: Int) {
        val screenWidth = McCompat.scaledWidth
        val screenHeight = McCompat.scaledHeight

        var wantedX = targetX
        var wantedY = targetY
        if (gridEnabled) {
            val step = gridSize
            wantedX = Math.round(wantedX.toFloat() / step) * step
            wantedY = Math.round(wantedY.toFloat() / step) * step
        }

        val clampedX = wantedX.coerceIn(0, (screenWidth - elementWidth).coerceAtLeast(0))
        val clampedY = wantedY.coerceIn(0, (screenHeight - elementHeight).coerceAtLeast(0))

        val anchoredRight = clampedX + elementWidth / 2 > screenWidth / 2
        val anchoredBottom = clampedY + elementHeight / 2 > screenHeight / 2

        position.moveTo(
            if (anchoredRight) minOf(clampedX - screenWidth + elementWidth, -1) else clampedX,
            if (anchoredBottom) minOf(clampedY - screenHeight + elementHeight, -1) else clampedY,
        )
    }

    override fun onClose() {
        ConfigManager.save(ConfigFileType.FEATURES, "closed position editor")
        super.onClose()
    }

    private companion object {
        const val BORDER = 2
        const val SCALE_STEP = 0.1f
        const val BUTTON_HEIGHT = 16f
        const val KEY_G = 71
        const val KEY_R = 82
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BACKDROP_COLOR = 0x40101010
        const val IDLE_COLOR = 0x60404040
        const val HOVER_COLOR = 0x90F0F0F0.toInt()
        const val GRID_COLOR = 0x1AFFFFFF
        const val CENTRE_COLOR = 0x50FFFFFF
        const val BUTTON_IDLE = 0xC0141420.toInt()
        const val BUTTON_HOVER = 0xE01E1E2E.toInt()
        const val BUTTON_BORDER = 0x80505060.toInt()
    }
}
