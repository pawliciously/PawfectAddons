package dev.pawfect.addons.ui

import dev.pawfect.addons.config.settings.GuideSetting
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.string
import net.minecraft.client.gui.GuiGraphicsExtractor

class GuideWidget(private val guide: GuideSetting) : Widget(guide) {

    private var wrapped: List<String> = emptyList()
    private var wrappedFor = -1f
    private var wrappedSource: List<String> = emptyList()

    private val buttonAnim = Anim(160L)

    override val height: Float
        get() {
            val lines = layout()
            val text = lines.size * LINE_SPACING
            val button = if (guide.label() != null) BUTTON_HEIGHT + GAP else 0f
            return INSET * 2f + text + button + MARGIN * 2f
        }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val lines = layout()

        val panelX = x + MARGIN
        val panelY = y + MARGIN
        val panelWidth = (width - MARGIN * 2f).coerceAtLeast(1f)
        val panelHeight = height - MARGIN * 2f

        graphics.roundPanel(
            panelX,
            panelY,
            panelWidth,
            panelHeight,
            ROW_RADIUS,
            Theme.withAlpha(Theme.text, 10),
            Theme.withAlpha(Theme.border, 140),
        )

        var textY = panelY + INSET
        for (line in lines) {
            graphics.string(line, panelX + INSET, textY, Theme.opaque(Theme.textDim))
            textY += LINE_SPACING
        }

        val label = guide.label() ?: return

        val buttonWidth = Draw.width(label) + 16f
        val buttonX = panelX + INSET
        val buttonY = panelY + panelHeight - INSET - BUTTON_HEIGHT
        val over = Draw.inside(mouseX, mouseY, buttonX, buttonY, buttonWidth, BUTTON_HEIGHT)
        val anim = buttonAnim.update(if (over) 1f else 0f)

        if (anim > 0.01f) {
            graphics.dropShadow(
                buttonX,
                buttonY,
                buttonWidth,
                BUTTON_HEIGHT,
                FIELD_RADIUS,
                5f,
                Theme.withAlpha(Theme.accent, (80 * anim).toInt()),
            )
        }

        graphics.roundPanel(
            buttonX,
            buttonY,
            buttonWidth,
            BUTTON_HEIGHT,
            FIELD_RADIUS,
            Theme.withAlpha(Theme.accent, (30 + 45 * anim).toInt()),
            Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), anim),
        )
        graphics.string(
            label,
            buttonX + 8f,
            buttonY + (BUTTON_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.accent),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        val label = guide.label() ?: return false

        val panelX = x + MARGIN
        val panelHeight = height - MARGIN * 2f
        val buttonWidth = Draw.width(label) + 16f
        val buttonX = panelX + INSET
        val buttonY = y + MARGIN + panelHeight - INSET - BUTTON_HEIGHT

        if (!Draw.inside(mouseX, mouseY, buttonX, buttonY, buttonWidth, BUTTON_HEIGHT)) return false

        UiSound.click()
        guide.action()
        return true
    }

    private fun layout(): List<String> {
        val available = (width - MARGIN * 2f - INSET * 2f).coerceAtLeast(40f)
        val source = guide.lines()
        if (available == wrappedFor && source == wrappedSource) return wrapped

        wrappedFor = available
        wrappedSource = source
        wrapped = source.flatMap { wrap(it, available) }
        return wrapped
    }

    private fun wrap(text: String, available: Float): List<String> {
        if (Draw.width(text) <= available) return listOf(text)

        val lines = ArrayList<String>()
        val builder = StringBuilder()

        for (word in text.split(' ')) {
            val candidate = if (builder.isEmpty()) word else "$builder $word"
            if (Draw.width(candidate) <= available || builder.isEmpty()) {
                builder.setLength(0)
                builder.append(candidate)
                continue
            }
            lines.add(builder.toString())
            builder.setLength(0)
            builder.append(word)
        }

        if (builder.isNotEmpty()) lines.add(builder.toString())
        return lines
    }

    private companion object {
        const val MARGIN = 3f
        const val INSET = 7f
        const val GAP = 5f
        const val LINE_SPACING = 11f
        const val BUTTON_HEIGHT = 13f
    }
}
