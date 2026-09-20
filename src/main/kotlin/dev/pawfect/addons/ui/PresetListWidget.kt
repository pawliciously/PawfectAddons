package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigGuiManager
import dev.pawfect.addons.config.ConfigPresets
import dev.pawfect.addons.config.settings.PresetListSetting
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.utils.ChatUtils
import net.minecraft.client.gui.GuiGraphicsExtractor

class PresetListWidget(listing: PresetListSetting) : Widget(listing) {

    private var collapsed = true
    private var scroll = 0f
    private var draggingBar = false
    private var arming: String? = null

    private var cached: List<String> = emptyList()
    private var scanned = false

    private val rows: List<String>
        get() {
            if (!scanned) refresh()
            return cached
        }

    private fun refresh() {
        cached = ConfigPresets.names()
        scanned = true
        arming = null
    }

    private fun listHeight(): Float = minOf(rows.size, MAX_ROWS) * ENTRY_HEIGHT

    private fun maxScroll(): Float = (rows.size * ENTRY_HEIGHT - listHeight()).coerceAtLeast(0f)

    override val height: Float
        get() {
            if (collapsed) return ROW_HEIGHT
            if (rows.isEmpty()) return ROW_HEIGHT + EMPTY_HEIGHT
            return ROW_HEIGHT + listHeight() + 4f
        }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        drawRow(graphics, mouseX, mouseY)

        val baseline = y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f
        val caret = if (collapsed) Icons.CARET_RIGHT else Icons.CARET_DOWN
        graphics.stringRight(caret, x + width - RIGHT_INSET, baseline, Theme.opaque(Theme.textDim))
        graphics.stringRight(
            "${rows.size}",
            x + width - RIGHT_INSET - Draw.width(caret) - 6f,
            baseline,
            Theme.opaque(Theme.textDim),
        )

        if (collapsed) return

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val listY = y + ROW_HEIGHT

        if (rows.isEmpty()) {
            graphics.roundPanel(
                listX,
                listY,
                listWidth,
                EMPTY_HEIGHT - 4f,
                FIELD_RADIUS,
                Theme.surface(Theme.background, 200),
                Theme.opaque(Theme.border),
            )
            graphics.string(
                "Name one below and hit Save",
                listX + 6f,
                listY + (EMPTY_HEIGHT - 4f - Draw.LINE_HEIGHT) / 2f,
                Theme.opaque(Theme.textDim),
            )
            return
        }

        scroll = scroll.coerceIn(0f, maxScroll())
        graphics.roundPanel(
            listX,
            listY,
            listWidth,
            listHeight(),
            FIELD_RADIUS,
            Theme.surface(Theme.background, 200),
            Theme.opaque(Theme.border),
        )

        Shapes.pushScissor(graphics, listX, listY, listWidth, listHeight())
        rows.forEachIndexed { index, name ->
            val rowY = listY + index * ENTRY_HEIGHT - scroll
            if (rowY + ENTRY_HEIGHT < listY || rowY > listY + listHeight()) return@forEachIndexed

            val hovered = Draw.inside(mouseX, mouseY, listX, rowY, listWidth, ENTRY_HEIGHT)
            val armed = arming == name
            if (hovered || armed) {
                graphics.roundRect(
                    listX + 1f,
                    rowY + 1f,
                    listWidth - 2f,
                    ENTRY_HEIGHT - 2f,
                    3f,
                    Theme.withAlpha(if (armed) DANGER else Theme.text, if (armed) 34 else 14),
                )
            }

            val textColour = if (armed) Theme.opaque(DANGER) else Theme.opaque(Theme.text)
            graphics.string(
                Draw.truncate(name, listWidth - TRASH_WIDTH - 12f),
                listX + 5f,
                rowY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f,
                textColour,
            )

            if (armed) {
                graphics.stringRight(
                    "Sure?",
                    listX + listWidth - 5f,
                    rowY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f,
                    Theme.opaque(DANGER),
                )
            } else if (hovered) {
                graphics.stringRight(
                    Icons.CLOSE,
                    listX + listWidth - 6f,
                    rowY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f,
                    Theme.withAlpha(DANGER, 190),
                )
            }
        }
        Shapes.popScissor(graphics)

        if (maxScroll() > 0f) {
            val thumb = thumbHeight()
            val thumbY = listY + (scroll / maxScroll()) * (listHeight() - thumb)
            graphics.roundRect(
                listX + listWidth - BAR_WIDTH,
                listY,
                BAR_WIDTH,
                listHeight(),
                1.5f,
                Theme.withAlpha(Theme.border, 90),
            )
            graphics.pill(
                listX + listWidth - BAR_WIDTH + 0.5f,
                thumbY,
                BAR_WIDTH - 1f,
                thumb,
                Theme.withAlpha(Theme.accent, if (draggingBar) 230 else 160),
            )
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false

        if (Draw.inside(mouseX, mouseY, x, y, width, ROW_HEIGHT)) {
            collapsed = !collapsed
            if (!collapsed) refresh()
            UiSound.click()
            return true
        }
        if (collapsed || rows.isEmpty()) return false

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val listY = y + ROW_HEIGHT

        if (maxScroll() > 0f && Draw.inside(mouseX, mouseY, listX + listWidth - BAR_WIDTH - 2f, listY, BAR_WIDTH + 4f, listHeight())) {
            draggingBar = true
            dragBar(mouseY, listY)
            return true
        }

        if (!Draw.inside(mouseX, mouseY, listX, listY, listWidth, listHeight())) return false

        val index = ((mouseY - listY + scroll) / ENTRY_HEIGHT).toInt()
        val name = rows.getOrNull(index) ?: return true
        UiSound.click()

        if (mouseX >= listX + listWidth - TRASH_WIDTH) {
            if (arming == name) {
                ConfigPresets.delete(name)
                    .onSuccess { ChatUtils.success("Deleted the config $it.") }
                    .onFailure { ChatUtils.error("Could not delete that config.") }
                refresh()
            } else {
                arming = name
            }
            return true
        }

        arming = null
        ConfigPresets.load(name)
            .onSuccess {
                ChatUtils.success("Loaded the config $it.")
                PawfectAddons.queueScreen { ConfigGuiManager.open("theme") }
            }
            .onFailure { ChatUtils.error(it.message ?: "Could not load that config.") }
        return true
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (!draggingBar) return
        dragBar(mouseY, y + ROW_HEIGHT)
    }

    override fun mouseReleased(button: Int) {
        draggingBar = false
    }

    override fun loseFocus() {
        arming = null
    }

    private fun thumbHeight(): Float =
        (listHeight() * listHeight() / (rows.size * ENTRY_HEIGHT)).coerceAtLeast(12f)

    private fun dragBar(mouseY: Float, listY: Float) {
        val travel = listHeight() - thumbHeight()
        if (travel <= 0f) return
        val fraction = ((mouseY - listY - thumbHeight() / 2f) / travel).coerceIn(0f, 1f)
        scroll = fraction * maxScroll()
    }

    fun scrollBy(amount: Float): Boolean {
        if (collapsed || maxScroll() <= 0f) return false
        scroll = (scroll - amount).coerceIn(0f, maxScroll())
        return true
    }

    fun listBounds(): FloatArray {
        if (collapsed || rows.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(x + LABEL_INSET, y + ROW_HEIGHT, width - LABEL_INSET - RIGHT_INSET, listHeight())
    }

    companion object {
        const val ENTRY_HEIGHT = 13f
        const val MAX_ROWS = 8
        const val EMPTY_HEIGHT = 20f
        const val BAR_WIDTH = 3f
        const val TRASH_WIDTH = 16f
        const val DANGER = 0xFF6B5C
    }
}
