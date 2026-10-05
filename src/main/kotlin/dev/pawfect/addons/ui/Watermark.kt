package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.data.ServerTps
import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The PawfectAddons watermark: a small panel with Chroma's moving strip along its top, in the
 * colour of the menu tab you last had open. Every reading can be switched off, and it moves
 * with the HUD editor like the other overlays.
 */
object Watermark {

    private class Part(val text: String, val color: Int)

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun render() {
        val theme = ConfigManager.features.theme
        if (!theme.watermark || McCompat.hideGui || !RenderContext.isActive) return
        val screen = McCompat.mc.screen
        if (screen != null && screen !is ChatScreen && !GuiEditManager.isEditorOpen()) return

        val parts = parts()
        val width = PAD * 2f + Draw.width(BRAND) + parts.sumOf { (SEPARATOR + Draw.width(it.text)).toDouble() }.toFloat()
        val position = theme.watermarkPosition
        val scale = position.effectiveScale
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (HEIGHT * scale).toInt()
        val x = position.getAbsX(scaledWidth)
        val y = position.getAbsY(scaledHeight)

        RenderContext.pushPop {
            RenderContext.translate(x.toFloat(), y.toFloat())
            if (scale != 1f) RenderContext.scale(scale)
            draw(RenderContext.graphics, width, parts)
        }
        GuiEditManager.register(position, HudDefaults.WATERMARK, scaledWidth, scaledHeight)
    }

    private fun parts(): List<Part> {
        val theme = ConfigManager.features.theme
        val mc = McCompat.mc
        val list = ArrayList<Part>()
        if (theme.watermarkFps) list += Part("${mc.fps} fps", Theme.opaque(Theme.text))
        if (theme.watermarkTps) {
            val tps = ServerTps.tps
            list += if (tps == null) Part("-- tps", Theme.opaque(Theme.textDim))
            else Part("%.1f tps".format(tps), health(tps >= 19.5f, tps >= 15f))
        }
        if (theme.watermarkPing) {
            val player = McCompat.player
            val ping = player?.let { mc.connection?.getPlayerInfo(it.uuid)?.latency }
            list += if (ping == null) Part("-- ms", Theme.opaque(Theme.textDim)) else Part("$ping ms", health(ping < 80, ping < 160))
        }
        if (theme.watermarkName) list += Part(mc.user.name, Theme.opaque(Theme.text))
        if (theme.watermarkServer) list += Part(HypixelLocation.lobby ?: "--", Theme.opaque(Theme.text))
        if (theme.watermarkTime) list += Part(LocalTime.now().format(clock), Theme.opaque(Theme.text))
        return list
    }

    /** Green when it's healthy, yellow when it's slipping, red when it's bad. */
    private fun health(good: Boolean, okay: Boolean): Int = when {
        good -> Theme.opaque(GOOD)
        okay -> Theme.opaque(OKAY)
        else -> Theme.opaque(BAD)
    }

    private fun draw(graphics: GuiGraphicsExtractor, width: Float, parts: List<Part>) {
        val accent = (Chroma.TABS.firstOrNull { it.id == ConfigManager.features.theme.chromaTab } ?: Chroma.TABS.first { it.id == "slayers" }).hue
        graphics.dropShadow(0f, 0f, width, HEIGHT, RADIUS, 10f, Theme.withAlpha(0x000000, 140), 2f)
        graphics.roundPanel(0f, 0f, width, HEIGHT, RADIUS, Theme.surface(Theme.background, 238), Theme.opaque(Theme.border))
        Chroma.drawStrip(graphics, 1f, 1f, width - 2f, 2f, accent, radius = RADIUS - 1f)

        val textY = (HEIGHT - Draw.LINE_HEIGHT) / 2f + 1f
        var cursor = PAD
        graphics.string(BRAND, cursor, textY, Theme.opaque(Theme.text), bold = true)
        cursor += Draw.width(BRAND)
        parts.forEach { part ->
            graphics.circle(cursor + SEPARATOR / 2f, HEIGHT / 2f + 1f, 1.3f, Theme.withAlpha(accent, 200))
            cursor += SEPARATOR
            graphics.string(part.text, cursor, textY, part.color)
            cursor += Draw.width(part.text)
        }
    }

    private const val BRAND = "PAWFECT"
    private const val HEIGHT = 17f
    private const val PAD = 7f
    private const val SEPARATOR = 11f
    private const val RADIUS = 5f
    private const val GOOD = 0x7EE08A
    private const val OKAY = 0xFFD23F
    private const val BAD = 0xFF6B6B
}
