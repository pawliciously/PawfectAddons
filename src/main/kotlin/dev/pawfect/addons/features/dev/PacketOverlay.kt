package dev.pawfect.addons.features.dev

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.UiSound
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.renderables.Renderable
import net.minecraft.client.gui.GuiGraphicsExtractor

object PacketOverlay {

    private const val HEADER = 12
    private const val ROW = 9
    private const val DETAIL_LINE = 8
    private const val DETAIL_ROWS = 8
    private const val PADDING = 4
    private const val SAMPLE_TIME = "00:00:00.000"
    private const val COLOR_IN = 0x7FB8FF
    private const val COLOR_OUT = 0xFFB86B

    private val config get() = ConfigManager.features.dev

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    private var scroll = 0
    private var selected = 0
    private var detail: List<String> = emptyList()

    private var seenVersion = -1
    private var rows: List<PacketEntry> = emptyList()

    fun render() {
        if (McCompat.mc.screen != null && !GuiEditManager.isEditorOpen()) return
        draw()
    }

    @JvmStatic
    fun renderOverScreen(graphics: GuiGraphicsExtractor) {
        if (McCompat.mc.screen == null || GuiEditManager.isEditorOpen()) return
        RenderContext.withContext(graphics) { draw() }
    }

    private fun draw() {
        if (!config.overlayEnabled) return
        PacketLog.markViewed()
        refresh()
        config.position.render(Panel(), "Packet Log")
    }

    private fun refresh() {
        val version = PacketLog.version
        if (version == seenVersion) return
        seenVersion = version
        rows = PacketLog.snapshot()
    }

    private fun visibleRows(): Int = config.overlayRows.coerceIn(3, 30)

    private fun timeWidth(): Int = McCompat.font.width(SAMPLE_TIME) + 5

    private fun flowWidth(): Int = McCompat.font.width("OUT") + 5

    private fun fit(text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (McCompat.font.width(text) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() && McCompat.font.width("$cut...") > maxWidth) {
            cut = cut.dropLast(1)
        }
        return "$cut..."
    }

    private fun panelWidth(): Int = config.overlayWidth.coerceIn(160, 420)

    private fun detailLines(): Int {
        if (selected == 0 || !config.overlayDetail || detail.isEmpty()) return 0
        return minOf(detail.size, DETAIL_ROWS)
    }

    private fun panelHeight(): Int {
        var value = HEADER + visibleRows() * ROW + PADDING
        val lines = detailLines()
        if (lines > 0) value += lines * DETAIL_LINE + PADDING
        return value
    }

    private fun maxScroll(): Int = (rows.size - visibleRows()).coerceAtLeast(0)

    @JvmStatic
    fun mouseClicked(mouseX: Double, mouseY: Double): Boolean {
        if (!config.overlayEnabled || !config.overlayClickable) return false
        if (GuiEditManager.isEditorOpen()) return false

        val scale = config.position.effectiveScale
        val width = panelWidth()
        val height = panelHeight()
        val absX = config.position.getAbsX((width * scale).toInt())
        val absY = config.position.getAbsY((height * scale).toInt())

        val localX = (mouseX - absX) / scale
        val localY = (mouseY - absY) / scale
        if (localX < 0 || localX > width || localY < 0 || localY > height) return false

        if (localY < HEADER) {
            dragging = true
            dragOffsetX = mouseX.toInt() - absX
            dragOffsetY = mouseY.toInt() - absY
            return true
        }

        val listTop = HEADER
        val listBottom = HEADER + visibleRows() * ROW
        if (localY in listTop.toDouble()..listBottom.toDouble()) {
            val index = ((localY - listTop) / ROW).toInt() + scroll
            val entry = rows.getOrNull(index)
            if (entry == null) {
                dragging = true
                dragOffsetX = mouseX.toInt() - absX
                dragOffsetY = mouseY.toInt() - absY
                return true
            }
            if (entry.seq == selected) {
                selected = 0
                detail = emptyList()
            } else {
                selected = entry.seq
                detail = runCatching { PacketLog.detail(entry) }
                    .getOrElse { listOf("<detail failed: ${it.javaClass.simpleName}>") }
            }
            UiSound.click()
            return true
        }

        dragging = true
        dragOffsetX = mouseX.toInt() - absX
        dragOffsetY = mouseY.toInt() - absY
        return true
    }

    @JvmStatic
    fun mouseDragged(mouseX: Double, mouseY: Double): Boolean {
        if (!dragging) return false
        val scale = config.position.effectiveScale
        val width = (panelWidth() * scale).toInt()
        val height = (panelHeight() * scale).toInt()
        val maxX = (McCompat.scaledWidth - width).coerceAtLeast(0)
        val maxY = (McCompat.scaledHeight - height).coerceAtLeast(0)
        config.position.moveTo(
            (mouseX.toInt() - dragOffsetX).coerceIn(0, maxX),
            (mouseY.toInt() - dragOffsetY).coerceIn(0, maxY),
        )
        return true
    }

    @JvmStatic
    fun mouseReleased(): Boolean {
        if (!dragging) return false
        dragging = false
        return true
    }

    @JvmStatic
    fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        if (!config.overlayEnabled || !config.overlayClickable) return false
        if (GuiEditManager.isEditorOpen()) return false

        val scale = config.position.effectiveScale
        val width = panelWidth()
        val height = panelHeight()
        val absX = config.position.getAbsX((width * scale).toInt())
        val absY = config.position.getAbsY((height * scale).toInt())

        val localX = (mouseX - absX) / scale
        val localY = (mouseY - absY) / scale
        if (localX < 0 || localX > width || localY < 0 || localY > height) return false

        scroll = (scroll - amount.toInt()).coerceIn(0, maxScroll())
        return true
    }

    fun reset() {
        scroll = 0
        selected = 0
        detail = emptyList()
        dragging = false
    }

    private class Panel : Renderable {

        override val width: Int get() = panelWidth()

        override val height: Int get() = panelHeight()

        override fun render(absX: Int, absY: Int) {
            val graphics = RenderContext.graphics
            val panelWidth = width.toFloat()
            val panelHeight = height.toFloat()

            graphics.roundPanel(
                0f,
                0f,
                panelWidth,
                panelHeight,
                4f,
                Theme.surface(Theme.background, 220),
                Theme.opaque(Theme.border),
            )

            val live = PacketLog.capturing
            RenderContext.drawString("PACKETS", 5, 3, Theme.opaque(Theme.textDim), false)
            val status = if (live) "REC ${rows.size}" else "IDLE ${rows.size}"
            RenderContext.drawString(
                status,
                width - 5 - McCompat.font.width(status),
                3,
                Theme.opaque(if (live) COLOR_IN else Theme.textDim),
                false,
            )

            scroll = scroll.coerceIn(0, maxScroll())
            val shown = visibleRows()
            for (line in 0 until shown) {
                val entry = rows.getOrNull(scroll + line) ?: break
                val rowY = HEADER + line * ROW
                if (entry.seq == selected) {
                    graphics.roundRect(
                        1f,
                        rowY.toFloat(),
                        panelWidth - 2f,
                        ROW.toFloat(),
                        2f,
                        Theme.withAlpha(Theme.accent, 40),
                    )
                }
                val timeWidth = timeWidth()
                val flowWidth = flowWidth()
                RenderContext.drawString(entry.time, 5, rowY + 1, Theme.opaque(Theme.textDim), false)
                RenderContext.drawString(
                    if (entry.inbound) "IN" else "OUT",
                    5 + timeWidth,
                    rowY + 1,
                    Theme.opaque(if (entry.inbound) COLOR_IN else COLOR_OUT),
                    false,
                )
                RenderContext.drawString(
                    fit(entry.name, width - timeWidth - flowWidth - 10),
                    5 + timeWidth + flowWidth,
                    rowY + 1,
                    Theme.opaque(if (entry.seq == selected) Theme.accent else Theme.text),
                    false,
                )
            }

            if (rows.isEmpty()) {
                RenderContext.drawString(
                    "Nothing captured yet",
                    5,
                    HEADER + 1,
                    Theme.opaque(Theme.textDim),
                    false,
                )
            }

            val lines = detailLines()
            if (lines <= 0) return

            val detailTop = HEADER + shown * ROW + PADDING
            graphics.roundRect(
                1f,
                detailTop.toFloat() - 2f,
                panelWidth - 2f,
                (lines * DETAIL_LINE).toFloat() + 2f,
                2f,
                Theme.withAlpha(Theme.text, 12),
            )
            for (index in 0 until lines) {
                RenderContext.drawString(
                    fit(detail[index], width - 10),
                    5,
                    detailTop + index * DETAIL_LINE,
                    Theme.opaque(if (index < 3) Theme.accent else Theme.text),
                    false,
                )
            }
        }
    }
}
