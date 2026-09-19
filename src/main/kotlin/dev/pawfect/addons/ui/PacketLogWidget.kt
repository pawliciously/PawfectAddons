package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.PacketLogSetting
import dev.pawfect.addons.features.dev.PacketEntry
import dev.pawfect.addons.features.dev.PacketLog
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import net.minecraft.client.gui.GuiGraphicsExtractor

class PacketLogWidget(private val log: PacketLogSetting) : Widget(log) {

    private var scroll = 0f
    private var detailScroll = 0f
    private var query = ""
    private var searchFocused = false
    private var draggingBar = false

    private var selected = 0
    private var detail: List<String> = emptyList()

    private var seenVersion = -1
    private var seenQuery: String? = null
    private var rows: List<PacketEntry> = emptyList()

    private var mouseInDetail = false

    private val config get() = ConfigManager.features.dev

    private fun refresh() {
        val version = PacketLog.version
        if (version == seenVersion && seenQuery == query) return
        seenVersion = version
        seenQuery = query
        val needle = query.trim().lowercase()
        val all = PacketLog.snapshot()
        rows = if (needle.isEmpty()) all else all.filter { it.name.contains(needle) }
    }

    private fun listHeight(): Float = LIST_ROWS * ENTRY_HEIGHT

    private fun maxScroll(): Float = (rows.size * ENTRY_HEIGHT - listHeight()).coerceAtLeast(0f)

    private fun detailHeight(): Float = if (selected == 0) 0f else DETAIL_ROWS * DETAIL_LINE

    private fun maxDetailScroll(): Float =
        (detail.size * DETAIL_LINE - detailHeight()).coerceAtLeast(0f)

    override val height: Float
        get() {
            val base = ROW_HEIGHT + CONTROLS_HEIGHT + 4f + listHeight() + 4f
            return if (selected == 0) base else base + detailHeight() + 4f
        }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        PacketLog.markViewed()
        refresh()
        drawRow(graphics, mouseX, mouseY)

        val baseline = y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f
        val live = PacketLog.capturing
        graphics.stringRight(
            if (live) "RECORDING" else "IDLE",
            x + width - RIGHT_INSET,
            baseline,
            if (live) Theme.opaque(COLOR_IN) else Theme.opaque(Theme.textDim),
        )
        graphics.stringRight(
            "${rows.size} / ${PacketLog.size()}",
            x + width - RIGHT_INSET - Draw.width("RECORDING") - 8f,
            baseline,
            Theme.opaque(Theme.textDim),
        )

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val controlsY = y + ROW_HEIGHT

        drawControls(graphics, listX, controlsY, listWidth, mouseX, mouseY)

        val listY = controlsY + CONTROLS_HEIGHT + 4f
        drawList(graphics, listX, listY, listWidth, mouseX, mouseY)

        if (selected != 0) {
            val detailY = listY + listHeight() + 4f
            mouseInDetail = Draw.inside(mouseX, mouseY, listX, detailY, listWidth, detailHeight())
            drawDetail(graphics, listX, detailY, listWidth)
        } else {
            mouseInDetail = false
        }
    }

    private fun drawControls(
        graphics: GuiGraphicsExtractor,
        listX: Float,
        controlsY: Float,
        listWidth: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        var chipX = listX + listWidth
        for (chip in CHIPS.reversed()) {
            val chipWidth = Draw.width(chip) + CHIP_PAD * 2f
            chipX -= chipWidth + CHIP_GAP
            val on = chipState(chip)
            val hovered = Draw.inside(mouseX, mouseY, chipX, controlsY, chipWidth, CHIP_HEIGHT)
            graphics.roundPanel(
                chipX,
                controlsY,
                chipWidth,
                CHIP_HEIGHT,
                FIELD_RADIUS,
                if (on) Theme.withAlpha(Theme.accent, 46) else Theme.surface(Theme.background, 210),
                if (on || hovered) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
            )
            graphics.string(
                chip,
                chipX + CHIP_PAD,
                controlsY + (CHIP_HEIGHT - Draw.LINE_HEIGHT) / 2f,
                if (on) Theme.opaque(Theme.accent) else Theme.opaque(Theme.textDim),
            )
        }

        val searchWidth = (chipX - CHIP_GAP - listX).coerceAtLeast(40f)
        graphics.roundPanel(
            listX,
            controlsY,
            searchWidth,
            CHIP_HEIGHT,
            FIELD_RADIUS,
            Theme.surface(Theme.background, 235),
            if (searchFocused) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )
        val shown = if (query.isEmpty() && !searchFocused) "Filter by name..." else query
        val caret = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        graphics.string(
            Draw.truncate(shown + caret, searchWidth - 22f),
            listX + 6f,
            controlsY + (CHIP_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            if (query.isEmpty() && !searchFocused) Theme.opaque(Theme.textDim) else Theme.opaque(Theme.text),
        )
        graphics.stringRight(
            Icons.SEARCH,
            listX + searchWidth - 6f,
            controlsY + (CHIP_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.textDim),
        )
    }

    private fun drawList(
        graphics: GuiGraphicsExtractor,
        listX: Float,
        listY: Float,
        listWidth: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        graphics.roundPanel(
            listX,
            listY,
            listWidth,
            listHeight(),
            FIELD_RADIUS,
            Theme.surface(Theme.background, 200),
            Theme.opaque(Theme.border),
        )

        if (rows.isEmpty()) {
            graphics.string(
                if (PacketLog.size() == 0) "Nothing captured yet" else "No packets match",
                listX + 6f,
                listY + 5f,
                Theme.opaque(Theme.textDim),
            )
            return
        }

        scroll = scroll.coerceIn(0f, maxScroll())
        val first = (scroll / ENTRY_HEIGHT).toInt().coerceAtLeast(0)
        val last = (first + LIST_ROWS + 1).coerceAtMost(rows.size)

        Shapes.pushScissor(graphics, listX, listY, listWidth, listHeight())
        for (index in first until last) {
            val entry = rows[index]
            val rowY = listY + index * ENTRY_HEIGHT - scroll
            val hovered = Draw.inside(mouseX, mouseY, listX, rowY, listWidth, ENTRY_HEIGHT)
            val isSelected = entry.seq == selected
            if (hovered || isSelected) {
                graphics.roundRect(
                    listX + 1f,
                    rowY + 1f,
                    listWidth - 2f,
                    ENTRY_HEIGHT - 2f,
                    3f,
                    Theme.withAlpha(if (isSelected) Theme.accent else Theme.text, if (isSelected) 34 else 14),
                )
            }
            val textY = rowY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f
            graphics.string(entry.time, listX + 5f, textY, Theme.opaque(Theme.textDim))
            graphics.string(
                if (entry.inbound) "IN" else "OUT",
                listX + 5f + TIME_WIDTH,
                textY,
                Theme.opaque(if (entry.inbound) COLOR_IN else COLOR_OUT),
            )
            graphics.string(
                Draw.truncate(entry.name, listWidth - TIME_WIDTH - FLOW_WIDTH - 14f),
                listX + 5f + TIME_WIDTH + FLOW_WIDTH,
                textY,
                if (isSelected) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text),
            )
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

    private fun drawDetail(graphics: GuiGraphicsExtractor, listX: Float, detailY: Float, listWidth: Float) {
        graphics.roundPanel(
            listX,
            detailY,
            listWidth,
            detailHeight(),
            FIELD_RADIUS,
            Theme.surface(Theme.background, 225),
            Theme.opaque(Theme.border),
        )
        detailScroll = detailScroll.coerceIn(0f, maxDetailScroll())

        Shapes.pushScissor(graphics, listX, detailY, listWidth, detailHeight())
        detail.forEachIndexed { index, line ->
            val lineY = detailY + 2f + index * DETAIL_LINE - detailScroll
            if (lineY + DETAIL_LINE < detailY || lineY > detailY + detailHeight()) return@forEachIndexed
            graphics.string(
                Draw.truncate(line, listWidth - 12f),
                listX + 5f,
                lineY,
                if (index < 3) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text),
            )
        }
        Shapes.popScissor(graphics)

        if (maxDetailScroll() > 0f) {
            val thumb = (detailHeight() * detailHeight() / (detail.size * DETAIL_LINE)).coerceAtLeast(12f)
            val thumbY = detailY + (detailScroll / maxDetailScroll()) * (detailHeight() - thumb)
            graphics.pill(
                listX + listWidth - BAR_WIDTH - 0.5f,
                thumbY,
                BAR_WIDTH - 1f,
                thumb,
                Theme.withAlpha(Theme.accent, 150),
            )
        }
    }

    private fun chipState(chip: String): Boolean = when (chip) {
        "IN" -> config.captureInbound
        "OUT" -> config.captureOutbound
        "QUIET" -> config.hideSpam
        else -> false
    }

    private fun toggleChip(chip: String) {
        when (chip) {
            "IN" -> config.captureInbound = !config.captureInbound
            "OUT" -> config.captureOutbound = !config.captureOutbound
            "QUIET" -> config.hideSpam = !config.hideSpam
            "CLR" -> {
                PacketLog.clear()
                selected = 0
                detail = emptyList()
                scroll = 0f
            }
        }
        seenVersion = -1
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val controlsY = y + ROW_HEIGHT

        var chipX = listX + listWidth
        for (chip in CHIPS.reversed()) {
            val chipWidth = Draw.width(chip) + CHIP_PAD * 2f
            chipX -= chipWidth + CHIP_GAP
            if (Draw.inside(mouseX, mouseY, chipX, controlsY, chipWidth, CHIP_HEIGHT)) {
                toggleChip(chip)
                searchFocused = false
                UiSound.click()
                return true
            }
        }

        val searchWidth = (chipX - CHIP_GAP - listX).coerceAtLeast(40f)
        if (Draw.inside(mouseX, mouseY, listX, controlsY, searchWidth, CHIP_HEIGHT)) {
            searchFocused = true
            UiSound.click()
            return true
        }
        searchFocused = false

        val listY = controlsY + CONTROLS_HEIGHT + 4f

        if (maxScroll() > 0f &&
            Draw.inside(mouseX, mouseY, listX + listWidth - BAR_WIDTH - 2f, listY, BAR_WIDTH + 4f, listHeight())
        ) {
            draggingBar = true
            dragBar(mouseY, listY)
            return true
        }

        if (!Draw.inside(mouseX, mouseY, listX, listY, listWidth, listHeight())) return false

        val index = ((mouseY - listY + scroll) / ENTRY_HEIGHT).toInt()
        val entry = rows.getOrNull(index) ?: return true
        if (entry.seq == selected) {
            selected = 0
            detail = emptyList()
        } else {
            selected = entry.seq
            detail = runCatching { PacketLog.detail(entry) }
                .getOrElse { listOf("<detail failed: ${it.javaClass.simpleName}>") }
            detailScroll = 0f
        }
        UiSound.click()
        return true
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (!draggingBar) return
        dragBar(mouseY, y + ROW_HEIGHT + CONTROLS_HEIGHT + 4f)
    }

    override fun mouseReleased(button: Int) {
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
        if (!searchFocused) return false
        when (keyCode) {
            256 -> {
                searchFocused = false
                return true
            }
            259 -> {
                if (query.isNotEmpty()) query = query.dropLast(1)
                scroll = 0f
                return true
            }
        }
        return false
    }

    override fun charTyped(character: Char): Boolean {
        if (!searchFocused) return false
        if (character.code < 32) return false
        if (query.length < 40) query += character
        scroll = 0f
        return true
    }

    override fun loseFocus() {
        searchFocused = false
    }

    fun scrollBy(amount: Float): Boolean {
        if (mouseInDetail && maxDetailScroll() > 0f) {
            detailScroll = (detailScroll - amount).coerceIn(0f, maxDetailScroll())
            return true
        }
        if (maxScroll() <= 0f) return false
        scroll = (scroll - amount).coerceIn(0f, maxScroll())
        return true
    }

    fun listBounds(): FloatArray {
        val listY = y + ROW_HEIGHT + CONTROLS_HEIGHT + 4f
        val total = listHeight() + if (selected == 0) 0f else detailHeight() + 4f
        return floatArrayOf(x + LABEL_INSET, listY, width - LABEL_INSET - RIGHT_INSET, total)
    }

    companion object {
        const val CONTROLS_HEIGHT = 15f
        const val CHIP_HEIGHT = 13f
        const val CHIP_PAD = 5f
        const val CHIP_GAP = 3f
        const val ENTRY_HEIGHT = 11f
        const val LIST_ROWS = 14
        const val DETAIL_LINE = 9f
        const val DETAIL_ROWS = 14
        const val BAR_WIDTH = 3f
        const val TIME_WIDTH = 54f
        const val FLOW_WIDTH = 22f
        const val COLOR_IN = 0x7FB8FF
        const val COLOR_OUT = 0xFFB86B

        private val CHIPS = listOf("IN", "OUT", "QUIET", "CLR")
    }
}
