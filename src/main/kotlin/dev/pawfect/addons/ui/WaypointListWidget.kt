package dev.pawfect.addons.ui

import dev.pawfect.addons.config.settings.WaypointListSetting
import dev.pawfect.addons.features.dungeon.DungeonWaypoints
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundOutline
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color as AwtColor

class WaypointListWidget(listing: WaypointListSetting) : Widget(listing) {

    private var scroll = 0f
    private var editing: DungeonWaypoints.Waypoint? = null
    private var buffer = ""
    private var expanded: DungeonWaypoints.Waypoint? = null
    private var collapsed = false

    private var hue = 0f
    private var saturation = 1f
    private var brightness = 1f

    private var draggingField = false
    private var draggingHue = false
    private var draggingBar = false

    private val rows: List<DungeonWaypoints.Waypoint> get() = DungeonWaypoints.all()

    private fun listHeight(): Float = minOf(rows.size, MAX_ROWS) * ENTRY_HEIGHT

    private fun maxScroll(): Float = (rows.size * ENTRY_HEIGHT - listHeight()).coerceAtLeast(0f)

    override val height: Float
        get() {
            if (collapsed) return ROW_HEIGHT
            if (rows.isEmpty()) return ROW_HEIGHT + EMPTY_HEIGHT
            var total = ROW_HEIGHT + listHeight() + 4f
            if (expanded != null) total += FIELD_HEIGHT + 6f
            return total
        }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        drawRow(graphics, mouseX, mouseY)

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val listY = y + ROW_HEIGHT

        val caret = if (collapsed) Icons.CARET_RIGHT else Icons.CARET_DOWN
        graphics.stringRight(
            caret,
            x + width - RIGHT_INSET,
            y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.textDim),
        )
        graphics.stringRight(
            "${rows.size}",
            x + width - RIGHT_INSET - Draw.width(caret) - 6f,
            y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.textDim),
        )

        if (collapsed) return

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
                "Look at a block in a dungeon, then /pa dw add",
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
        rows.forEachIndexed { index, waypoint ->
            val rowY = listY + index * ENTRY_HEIGHT - scroll
            if (rowY + ENTRY_HEIGHT >= listY && rowY <= listY + listHeight()) {
                drawEntry(graphics, waypoint, listX, rowY, listWidth, mouseX, mouseY)
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

        val target = expanded ?: return
        val pickerY = listY + listHeight() + 4f
        val fieldWidth = listWidth - HUE_WIDTH - 6f

        Shapes.saturationField(graphics, listX, pickerY, fieldWidth, FIELD_HEIGHT, FIELD_RADIUS, hue)
        graphics.roundOutline(listX, pickerY, fieldWidth, FIELD_HEIGHT, FIELD_RADIUS, Theme.opaque(Theme.border))
        val cursorX = listX + fieldWidth * saturation
        val cursorY = pickerY + FIELD_HEIGHT * (1f - brightness)
        graphics.circle(cursorX, cursorY, 3.2f, Theme.withAlpha(0x000000, 150))
        graphics.circle(cursorX, cursorY, 2.6f, Theme.opaque(0xFFFFFF))
        graphics.circle(cursorX, cursorY, 1.4f, Theme.opaque(target.color))

        val hueX = listX + fieldWidth + 6f
        Shapes.hueBar(graphics, hueX, pickerY, HUE_WIDTH, FIELD_HEIGHT, HUE_WIDTH / 2f)
        graphics.roundOutline(hueX, pickerY, HUE_WIDTH, FIELD_HEIGHT, HUE_WIDTH / 2f, Theme.opaque(Theme.border))
        graphics.pill(hueX - 2f, pickerY + FIELD_HEIGHT * hue - 1.5f, HUE_WIDTH + 4f, 3f, Theme.opaque(0xFFFFFF))
    }

    private fun drawEntry(
        graphics: GuiGraphicsExtractor,
        waypoint: DungeonWaypoints.Waypoint,
        entryX: Float,
        entryY: Float,
        entryWidth: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        val hovered = Draw.inside(mouseX, mouseY, entryX, entryY, entryWidth, ENTRY_HEIGHT)
        if (hovered) {
            graphics.roundRect(entryX + 1f, entryY + 1f, entryWidth - 2f, ENTRY_HEIGHT - 2f, 3f, Theme.withAlpha(Theme.text, 14))
        }

        val closeX = entryX + entryWidth - 11f
        val swatchX = entryX + entryWidth - 16f - SWATCH_WIDTH
        val eyeX = swatchX - 8f - ICON_GAP
        val tagX = eyeX - ICON_GAP
        val isEditing = editing === waypoint
        val caret = if (isEditing && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        val label = (if (isEditing) buffer else waypoint.name) + caret

        graphics.string(
            Draw.truncate(label, tagX - entryX - 10f),
            entryX + 5f,
            entryY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            if (isEditing) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text),
        )

        graphics.roundPanel(
            swatchX,
            entryY + (ENTRY_HEIGHT - SWATCH_HEIGHT) / 2f,
            SWATCH_WIDTH,
            SWATCH_HEIGHT,
            3f,
            Theme.opaque(waypoint.color),
            Theme.withAlpha(0xFFFFFF, 60),
        )

        val iconY = entryY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f

        graphics.string(
            Icons.TAG,
            tagX - Draw.width(Icons.TAG) / 2f,
            iconY,
            if (waypoint.showName) Theme.opaque(Theme.accent) else Theme.withAlpha(Theme.textDim, 120),
        )

        val eye = if (waypoint.visible) Icons.EYE else Icons.EYE_OFF
        graphics.string(
            eye,
            eyeX - Draw.width(eye) / 2f,
            iconY,
            if (waypoint.visible) Theme.opaque(Theme.accent) else Theme.withAlpha(Theme.textDim, 120),
        )

        graphics.string(
            Icons.CLOSE,
            closeX - Draw.width(Icons.CLOSE) / 2f,
            iconY,
            if (hovered) Theme.opaque(0xFF5555) else Theme.opaque(Theme.textDim),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false

        if (Draw.inside(mouseX, mouseY, x, y, width, ROW_HEIGHT)) {
            commit()
            collapsed = !collapsed
            UiSound.click()
            return true
        }
        if (collapsed) return false

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val listY = y + ROW_HEIGHT

        val target = expanded
        if (target != null) {
            val pickerY = listY + listHeight() + 4f
            val fieldWidth = listWidth - HUE_WIDTH - 6f
            if (Draw.inside(mouseX, mouseY, listX, pickerY, fieldWidth, FIELD_HEIGHT)) {
                draggingField = true
                updateField(mouseX, mouseY, listX, pickerY, fieldWidth, target)
                return true
            }
            val hueX = listX + fieldWidth + 6f
            if (Draw.inside(mouseX, mouseY, hueX, pickerY, HUE_WIDTH, FIELD_HEIGHT)) {
                draggingHue = true
                updateHue(mouseY, pickerY, target)
                return true
            }
        }

        if (maxScroll() > 0f && Draw.inside(mouseX, mouseY, listX + listWidth - BAR_WIDTH - 2f, listY, BAR_WIDTH + 4f, listHeight())) {
            commit()
            draggingBar = true
            dragBar(mouseY, listY)
            return true
        }

        if (!Draw.inside(mouseX, mouseY, listX, listY, listWidth, listHeight())) {
            commit()
            return false
        }

        val index = ((mouseY - listY + scroll) / ENTRY_HEIGHT).toInt()
        val waypoint = rows.getOrNull(index) ?: return true

        val closeX = listX + listWidth - 17f
        val swatchX = listX + listWidth - 16f - SWATCH_WIDTH
        val eyeX = swatchX - 8f - ICON_GAP
        val tagX = eyeX - ICON_GAP

        commit()
        when {
            mouseX >= closeX -> {
                DungeonWaypoints.remove(waypoint)
                if (expanded === waypoint) expanded = null
            }
            mouseX >= swatchX -> {
                expanded = if (expanded === waypoint) null else waypoint
                expanded?.let { syncFrom(it.color) }
            }
            mouseX >= eyeX - ICON_GAP / 2f -> {
                waypoint.visible = !waypoint.visible
                DungeonWaypoints.save()
            }
            mouseX >= tagX - ICON_GAP / 2f -> {
                waypoint.showName = !waypoint.showName
                DungeonWaypoints.save()
            }
            else -> {
                editing = waypoint
                buffer = waypoint.name
            }
        }
        UiSound.click()
        return true
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (draggingBar) {
            dragBar(mouseY, y + ROW_HEIGHT)
            return
        }
        val target = expanded ?: return
        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val pickerY = y + ROW_HEIGHT + listHeight() + 4f
        val fieldWidth = listWidth - HUE_WIDTH - 6f
        if (draggingField) updateField(mouseX, mouseY, listX, pickerY, fieldWidth, target)
        if (draggingHue) updateHue(mouseY, pickerY, target)
    }

    override fun mouseReleased(button: Int) {
        draggingField = false
        draggingHue = false
        draggingBar = false
    }

    private fun thumbHeight(): Float =
        (listHeight() * listHeight() / (rows.size * ENTRY_HEIGHT)).coerceAtLeast(12f)

    private fun dragBar(mouseY: Float, listY: Float) {
        val travel = listHeight() - thumbHeight()
        if (travel <= 0f) return
        val fraction = ((mouseY - listY - thumbHeight() / 2f) / travel).coerceIn(0f, 1f)
        scroll = fraction * maxScroll()
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (editing == null) return false
        when (keyCode) {
            256 -> {
                editing = null
                return true
            }
            257, 335 -> {
                commit()
                return true
            }
            259 -> {
                if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
                return true
            }
        }
        return false
    }

    override fun charTyped(character: Char): Boolean {
        if (editing == null) return false
        if (character.code < 32) return false
        if (buffer.length < 32) buffer += character
        return true
    }

    override fun loseFocus() = commit()

    fun scrollBy(amount: Float): Boolean {
        if (collapsed || maxScroll() <= 0f) return false
        scroll = (scroll - amount).coerceIn(0f, maxScroll())
        return true
    }

    fun listBounds(): FloatArray =
        if (collapsed) floatArrayOf(0f, 0f, 0f, 0f)
        else floatArrayOf(x + LABEL_INSET, y + ROW_HEIGHT, width - LABEL_INSET - RIGHT_INSET, listHeight())

    private fun commit() {
        val active = editing ?: return
        editing = null
        val trimmed = buffer.trim()
        if (trimmed.isNotEmpty() && trimmed != active.name) {
            active.name = trimmed
            DungeonWaypoints.save()
        }
    }

    private fun syncFrom(rgb: Int) {
        val hsb = AwtColor.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
        hue = hsb[0]
        saturation = hsb[1]
        brightness = hsb[2]
    }

    private fun apply(waypoint: DungeonWaypoints.Waypoint) {
        waypoint.color = AwtColor.HSBtoRGB(hue, saturation, brightness) and 0xFFFFFF
        DungeonWaypoints.save()
    }

    private fun updateField(
        mouseX: Float,
        mouseY: Float,
        fieldX: Float,
        fieldY: Float,
        fieldWidth: Float,
        waypoint: DungeonWaypoints.Waypoint,
    ) {
        saturation = ((mouseX - fieldX) / fieldWidth).coerceIn(0f, 1f)
        brightness = 1f - ((mouseY - fieldY) / FIELD_HEIGHT).coerceIn(0f, 1f)
        apply(waypoint)
    }

    private fun updateHue(mouseY: Float, barY: Float, waypoint: DungeonWaypoints.Waypoint) {
        hue = ((mouseY - barY) / FIELD_HEIGHT).coerceIn(0f, 1f)
        apply(waypoint)
    }

    companion object {
        const val ENTRY_HEIGHT = 15f
        const val MAX_ROWS = 8
        const val EMPTY_HEIGHT = 20f
        const val SWATCH_WIDTH = 20f
        const val SWATCH_HEIGHT = 9f
        const val FIELD_HEIGHT = 42f
        const val HUE_WIDTH = 8f
        const val ICON_GAP = 13f
        const val BAR_WIDTH = 3f
    }
}
