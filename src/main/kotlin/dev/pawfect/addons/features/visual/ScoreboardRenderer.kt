package dev.pawfect.addons.features.visual

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.ScoreboardConfig.Background
import dev.pawfect.addons.config.features.ScoreboardConfig.Placement
import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.gpu.MenuBackgroundRenderState
import dev.pawfect.addons.ui.gpu.UiPipelines
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.renderables.Renderable
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import org.joml.Matrix3x2f
import java.util.Optional

/**
 * Draws the sidebar in place of vanilla's (see ScoreboardSidebarMixin). It hooks the same
 * spot custom-scoreboard mods cancel, and runs after them, so when SkyHanni's or NoammAddons'
 * scoreboard is on this one simply never draws. Lines are read the way vanilla reads them;
 * only the presentation changes: no red numbers, a themed panel, optional Hypixel clean-up.
 */
object ScoreboardRenderer {

    private val config get() = ConfigManager.features.scoreboard

    private const val PAD_X = 6
    private const val PAD_TOP = 5
    private const val PAD_BOTTOM = 5
    private const val LINE = 10
    private const val BLANK = 5
    private const val TITLE_GAP = 4
    private const val MAX_LINES = 15

    private val startedAt = System.nanoTime()

    private val DATE = Regex("""^\s*\d{1,2}/\d{1,2}/\d{2,4}""")
    private val WEBSITE = Regex("""(?:www|alpha)\.hypixel\.net""", RegexOption.IGNORE_CASE)

    /** Vanilla's order: highest score first, ties by holder name. */
    private val ORDER: Comparator<PlayerScoreEntry> =
        compareByDescending<PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }

    private class Line(val text: Component?, val height: Int)

    /** Returns true when it drew, so vanilla's sidebar is skipped. */
    @JvmStatic
    fun render(graphics: GuiGraphicsExtractor, objective: Objective): Boolean {
        if (!config.enabled) return false
        config.sanitize()
        val lines = lines(objective)
        val title = objective.displayName
        val font = McCompat.font

        val contentWidth = maxOf(font.width(title), lines.maxOfOrNull { it.text?.let(font::width) ?: 0 } ?: 0)
        val width = contentWidth + PAD_X * 2
        val height = PAD_TOP + LINE + TITLE_GAP + lines.sumOf { it.height } + PAD_BOTTOM - 1
        val card = Card(title, lines, width, height)

        if (config.placement == Placement.CUSTOM) {
            RenderContext.withContext(graphics) { config.position.render(card, "Scoreboard") }
        } else {
            // Vanilla's spot: hugging the right edge, centred a little below the middle.
            val x = graphics.guiWidth() - width - 2
            val y = graphics.guiHeight() / 2 + height / 3 - height
            graphics.pose().pushMatrix()
            graphics.pose().translate(x.toFloat(), y.toFloat())
            RenderContext.withContext(graphics) { card.render(x, y) }
            graphics.pose().popMatrix()
        }
        return true
    }

    private fun lines(objective: Objective): List<Line> {
        val scoreboard = objective.scoreboard
        val entries = scoreboard.listPlayerScores(objective)
            .filter { !it.isHidden }
            .sortedWith(ORDER)
            .take(MAX_LINES)

        val out = ArrayList<Line>(entries.size)
        for ((index, entry) in entries.withIndex()) {
            var text: Component = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())
            val plain = text.string

            if (plain.isBlank()) {
                out += Line(null, if (config.compactBlankLines) BLANK else LINE)
                continue
            }
            if (index == entries.lastIndex && config.hideWebsite && WEBSITE.containsMatchIn(plain)) continue
            if (index == 0 && config.hideServerId) {
                // Hypixel's first line is "MM/DD/YY m12AB": keep the date, drop the server.
                DATE.find(plain)?.let { text = keepFirst(text, it.range.last + 1) }
            }
            out += Line(text, LINE)
        }
        // A trailing spacer left behind by a hidden website line looks like padding; drop it.
        while (out.isNotEmpty() && out.last().text == null) out.removeAt(out.lastIndex)
        return out
    }

    /** The first [count] characters of [source], styles intact. */
    private fun keepFirst(source: Component, count: Int): Component {
        val out = Component.empty()
        var left = count
        source.visit(
            object : FormattedText.StyledContentConsumer<Unit> {
                override fun accept(style: Style, text: String): Optional<Unit> {
                    if (left <= 0) return Optional.of(Unit)
                    val piece = if (text.length > left) text.substring(0, left) else text
                    out.append(Component.literal(piece).setStyle(style))
                    left -= piece.length
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        )
        return out
    }

    private class Card(
        private val title: Component,
        private val lines: List<Line>,
        override val width: Int,
        override val height: Int,
    ) : Renderable {

        override fun render(absX: Int, absY: Int) {
            val graphics = RenderContext.graphics
            val w = width.toFloat()
            val h = height.toFloat()
            background(graphics, w, h)

            val font = McCompat.font
            val shadow = config.textShadow
            graphics.text(font, title, ((w - font.width(title)) / 2f).toInt(), PAD_TOP, WHITE, shadow)

            val ruleY = PAD_TOP + LINE + TITLE_GAP / 2 - 1f
            Shapes.rect(graphics, PAD_X.toFloat(), ruleY, w - PAD_X * 2, 1f, 0f, argb(Theme.accent, 0.55f), argb(Theme.accent, 0.55f))

            var y = PAD_TOP + LINE + TITLE_GAP
            for (line in lines) {
                line.text?.let { graphics.text(font, it, PAD_X, y, WHITE, shadow) }
                y += line.height
            }
        }

        private fun background(graphics: GuiGraphicsExtractor, w: Float, h: Float) {
            val opacity = config.opacity.coerceIn(0f, 1f)
            val radius = config.radius.coerceIn(0, 12)
            val style = config.background
            if (style == Background.GLASS) {
                val top = Theme.mix(Theme.palette.panel, 0xFFFFFF, 0.04f)
                val bottom = Theme.palette.background
                Shapes.rect(graphics, 0f, 0f, w, h, radius.toFloat(), argb(top, opacity), argb(bottom, opacity))
            } else {
                val state = MenuBackgroundRenderState(
                    Matrix3x2f(graphics.pose()),
                    0f,
                    0f,
                    w,
                    h,
                    argb(Theme.mix(Theme.palette.panel, 0x000000, 0.1f), opacity),
                    0xFF000000.toInt() or (Theme.palette.background and 0xFFFFFF),
                    Theme.palette.accent,
                    0.85f,
                    (System.nanoTime() - startedAt) / 1_000_000_000f,
                    style.style.toFloat(),
                    UiPipelines.INVENTORY_BACKGROUND,
                    radius,
                )
                (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addGuiElement(state)
            }
            if (config.outline) Shapes.outline(graphics, 0f, 0f, w, h, radius.toFloat(), argb(Theme.accent, 0.35f * opacity + 0.1f))
        }
    }

    private const val WHITE = -1

    private fun argb(rgb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)
}
