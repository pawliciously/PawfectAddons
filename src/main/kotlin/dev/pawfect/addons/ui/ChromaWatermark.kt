package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.PawfectSettings
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen

/**
 * What the Chroma menu folds into: a watermark in the corner with the moving strip, the tab
 * you left it on and your starred switches, lit when they're on. Opening the menu unfolds it.
 */
object ChromaWatermark {

    private val categories: List<SettingCategory> by lazy { PawfectSettings.buildSections().flatMap { it.categories } }

    fun render() {
        val theme = ConfigManager.features.theme
        if (!theme.chromaFolded || theme.classicMenu) return
        val screen = McCompat.mc.screen
        if (McCompat.hideGui || (screen != null && screen !is ChatScreen)) return
        if (!RenderContext.isActive) return
        val graphics = RenderContext.graphics
        UiScale.minWidth = 0f
        UiScale.minHeight = 0f
        UiScale.push(graphics)
        try {
            draw(graphics)
        } finally {
            UiScale.pop(graphics)
        }
    }

    private fun draw(graphics: GuiGraphicsExtractor) {
        val tab = Chroma.TABS.firstOrNull { it.id == ConfigManager.features.theme.chromaTab } ?: Chroma.TABS.first { it.id == "slayers" }
        val accent = tab.hue
        val pins = Chroma.pinnedToggles(categories).take(MAX_PINS)

        val brand = "PAWFECT"
        val fps = "${McCompat.mc.fps} fps"
        var width = PAD + Draw.width(brand) + GAP + Draw.width(tab.title) + GAP + Draw.width(fps) + PAD
        pins.forEach { (_, toggle) -> width += CHIP_GAP + DOT + 4f + Draw.width(toggle.name) }
        if (pins.isNotEmpty()) width += PAD

        val x = MARGIN
        val y = MARGIN
        graphics.dropShadow(x, y, width, HEIGHT, 5f, 10f, Theme.withAlpha(0x000000, 140), 2f)
        graphics.roundPanel(x, y, width, HEIGHT, 5f, Theme.surface(Theme.background, 238), Theme.opaque(Theme.border))
        Chroma.drawStrip(graphics, x + 5f, y + 0.5f, width - 10f, 2f, accent)

        val textY = y + (HEIGHT - Draw.LINE_HEIGHT) / 2f + 1f
        var cursor = x + PAD
        graphics.string(brand, cursor, textY, Theme.opaque(Theme.text), bold = true)
        cursor += Draw.width(brand) + GAP
        graphics.string(tab.title, cursor, textY, Theme.opaque(accent))
        cursor += Draw.width(tab.title) + GAP
        graphics.string(fps, cursor, textY, Theme.opaque(Theme.textDim))
        cursor += Draw.width(fps)

        pins.forEach { (category, toggle) ->
            cursor += CHIP_GAP
            val hue = Chroma.hueFor(category.id)
            val on = toggle.value
            graphics.circle(cursor + DOT / 2f, y + HEIGHT / 2f + 0.5f, DOT / 2f, if (on) Theme.opaque(hue) else Theme.withAlpha(Theme.textDim, 110))
            cursor += DOT + 4f
            graphics.string(toggle.name, cursor, textY, if (on) Theme.opaque(Theme.text) else Theme.withAlpha(Theme.textDim, 170))
            cursor += Draw.width(toggle.name)
        }
    }

    private const val MARGIN = 6f
    private const val HEIGHT = 18f
    private const val PAD = 8f
    private const val GAP = 7f
    private const val CHIP_GAP = 10f
    private const val DOT = 5f
    private const val MAX_PINS = 6
}
