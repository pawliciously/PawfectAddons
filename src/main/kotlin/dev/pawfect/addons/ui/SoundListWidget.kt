package dev.pawfect.addons.ui

import dev.pawfect.addons.config.settings.SoundListSetting
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries

class SoundListWidget(private val sound: SoundListSetting) : Widget(sound) {

    private var collapsed = true
    private var scroll = 0f
    private var query = ""
    private var searchFocused = false
    private var draggingBar = false

    private val vanilla: List<String> by lazy {
        BuiltInRegistries.SOUND_EVENT.keySet().map { it.toString() }.sorted()
    }

    private var all: List<String> = emptyList()
    private var cachedQuery: String? = null
    private var cached: List<String> = emptyList()

    private fun refresh() {
        all = (runCatching { sound.extras() }.getOrDefault(emptyList()) + vanilla).distinct()
        cachedQuery = null
    }

    private fun matches(): List<String> {
        if (all.isEmpty()) refresh()
        if (cachedQuery == query) return cached
        val needle = query.trim().lowercase()
        cached = if (needle.isEmpty()) all else all.filter { it.contains(needle) }
        cachedQuery = query
        return cached
    }

    private fun listHeight(): Float = minOf(matches().size, MAX_ROWS) * ENTRY_HEIGHT

    private fun maxScroll(): Float = (matches().size * ENTRY_HEIGHT - listHeight()).coerceAtLeast(0f)

    override val height: Float
        get() = if (collapsed) ROW_HEIGHT else ROW_HEIGHT + SEARCH_HEIGHT + 4f + listHeight() + 4f

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        drawRow(graphics, mouseX, mouseY)

        val caret = if (collapsed) Icons.CARET_RIGHT else Icons.CARET_DOWN
        val baseline = y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f
        graphics.stringRight(caret, x + width - RIGHT_INSET, baseline, Theme.opaque(Theme.textDim))
        graphics.stringRight(
            Draw.truncate(sound.value.substringAfter(':'), width * 0.45f),
            x + width - RIGHT_INSET - Draw.width(caret) - 6f,
            baseline,
            Theme.opaque(Theme.accent),
        )

        if (collapsed) return

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val searchY = y + ROW_HEIGHT

        graphics.roundPanel(
            listX,
            searchY,
            listWidth,
            SEARCH_HEIGHT,
            FIELD_RADIUS,
            Theme.surface(Theme.background, 235),
            if (searchFocused) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )
        val shown = if (query.isEmpty() && !searchFocused) "Search sounds..." else query
        val caretMark = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        graphics.string(
            Draw.truncate(shown + caretMark, listWidth - 26f),
            listX + 6f,
            searchY + (SEARCH_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            if (query.isEmpty() && !searchFocused) Theme.opaque(Theme.textDim) else Theme.opaque(Theme.text),
        )
        graphics.stringRight(
            Icons.SEARCH,
            listX + listWidth - 6f,
            searchY + (SEARCH_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.textDim),
        )

        val listY = searchY + SEARCH_HEIGHT + 4f
        val rows = matches()
        if (rows.isEmpty()) {
            graphics.string("No sounds match", listX + 6f, listY + 3f, Theme.opaque(Theme.textDim))
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
        rows.forEachIndexed { index, id ->
            val rowY = listY + index * ENTRY_HEIGHT - scroll
            if (rowY + ENTRY_HEIGHT < listY || rowY > listY + listHeight()) return@forEachIndexed
            val hovered = Draw.inside(mouseX, mouseY, listX, rowY, listWidth, ENTRY_HEIGHT)
            val selected = id == sound.value
            if (hovered || selected) {
                graphics.roundRect(
                    listX + 1f,
                    rowY + 1f,
                    listWidth - 2f,
                    ENTRY_HEIGHT - 2f,
                    3f,
                    Theme.withAlpha(if (selected) Theme.accent else Theme.text, if (selected) 34 else 14),
                )
            }
            graphics.string(
                Draw.truncate(id.removePrefix("minecraft:"), listWidth - 12f),
                listX + 5f,
                rowY + (ENTRY_HEIGHT - Draw.LINE_HEIGHT) / 2f,
                if (selected) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text),
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

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false

        if (Draw.inside(mouseX, mouseY, x, y, width, ROW_HEIGHT)) {
            collapsed = !collapsed
            if (!collapsed) refresh()
            searchFocused = false
            UiSound.click()
            return true
        }
        if (collapsed) return false

        val listX = x + LABEL_INSET
        val listWidth = width - LABEL_INSET - RIGHT_INSET
        val searchY = y + ROW_HEIGHT

        if (Draw.inside(mouseX, mouseY, listX, searchY, listWidth, SEARCH_HEIGHT)) {
            searchFocused = true
            UiSound.click()
            return true
        }
        searchFocused = false

        val listY = searchY + SEARCH_HEIGHT + 4f

        if (maxScroll() > 0f && Draw.inside(mouseX, mouseY, listX + listWidth - BAR_WIDTH - 2f, listY, BAR_WIDTH + 4f, listHeight())) {
            draggingBar = true
            dragBar(mouseY, listY)
            return true
        }

        if (!Draw.inside(mouseX, mouseY, listX, listY, listWidth, listHeight())) return false

        val index = ((mouseY - listY + scroll) / ENTRY_HEIGHT).toInt()
        val id = matches().getOrNull(index) ?: return true
        sound.value = id
        sound.preview()
        UiSound.click()
        return true
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (!draggingBar) return
        dragBar(mouseY, y + ROW_HEIGHT + SEARCH_HEIGHT + 4f)
    }

    override fun mouseReleased(button: Int) {
        draggingBar = false
    }

    private fun thumbHeight(): Float =
        (listHeight() * listHeight() / (matches().size * ENTRY_HEIGHT)).coerceAtLeast(12f)

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
        if (collapsed || maxScroll() <= 0f) return false
        scroll = (scroll - amount).coerceIn(0f, maxScroll())
        return true
    }

    fun listBounds(): FloatArray {
        if (collapsed) return floatArrayOf(0f, 0f, 0f, 0f)
        val listY = y + ROW_HEIGHT + SEARCH_HEIGHT + 4f
        return floatArrayOf(x + LABEL_INSET, listY, width - LABEL_INSET - RIGHT_INSET, listHeight())
    }

    companion object {
        const val ENTRY_HEIGHT = 13f
        const val MAX_ROWS = 9
        const val SEARCH_HEIGHT = 14f
        const val BAR_WIDTH = 3f
    }
}
