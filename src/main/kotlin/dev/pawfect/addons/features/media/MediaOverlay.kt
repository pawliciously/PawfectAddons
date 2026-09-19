package dev.pawfect.addons.features.media

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.UiSound
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.renderables.Renderable
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import kotlin.math.abs
import kotlin.math.roundToInt

object MediaOverlay {

    private val config get() = ConfigManager.features.media

    private const val PADDING = 8
    private const val ART = 32
    private const val PROGRESS_BLOCK = 18
    private const val CONTROL_BLOCK = 16
    private const val RADIUS = 8f
    private const val ART_RADIUS = 6f
    private const val CONTROL_GAP = 14f

    private enum class Control { PREVIOUS, TOGGLE, NEXT }

    private class Hitbox(val x: Float, val y: Float, val width: Float, val height: Float, val control: Control)

    fun render() {
        if (McCompat.mc.screen != null && !GuiEditManager.isEditorOpen()) return
        draw()
    }

    @JvmStatic
    fun renderOverScreen(graphics: GuiGraphicsExtractor) {
        if (McCompat.mc.screen == null || GuiEditManager.isEditorOpen()) return
        RenderContext.withContext(graphics) { draw() }
    }

    @JvmStatic
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    @JvmStatic
    fun mouseReleased(): Boolean {
        if (!dragging) return false
        dragging = false
        return true
    }

    @JvmStatic
    fun mouseDragged(mouseX: Double, mouseY: Double): Boolean {
        if (!dragging) return false
        val scale = config.position.effectiveScale
        val width = (cardWidth() * scale).toInt()
        val height = (cardHeight() * scale).toInt()
        val maxX = (McCompat.scaledWidth - width).coerceAtLeast(0)
        val maxY = (McCompat.scaledHeight - height).coerceAtLeast(0)
        config.position.moveTo(
            (mouseX.toInt() - dragOffsetX).coerceIn(0, maxX),
            (mouseY.toInt() - dragOffsetY).coerceIn(0, maxY),
        )
        return true
    }

    @JvmStatic
    fun mouseClicked(mouseX: Double, mouseY: Double): Boolean {
        if (!config.enabled || !config.clickableInMenus) return false
        if (GuiEditManager.isEditorOpen()) return false
        val track = MediaBridge.track
        if (track == null && config.hideWhenStopped) return false

        val scale = config.position.effectiveScale
        val cardWidth = cardWidth()
        val cardHeight = cardHeight()
        val absX = config.position.getAbsX((cardWidth * scale).toInt())
        val absY = config.position.getAbsY((cardHeight * scale).toInt())

        if (config.showControls && track != null) {
            if (clickControls(mouseX, mouseY, absX, absY, scale, cardWidth, track)) return true
        }

        val insideX = mouseX >= absX && mouseX <= absX + cardWidth * scale
        val insideY = mouseY >= absY && mouseY <= absY + cardHeight * scale
        if (insideX && insideY) {
            dragging = true
            dragOffsetX = mouseX.toInt() - absX
            dragOffsetY = mouseY.toInt() - absY
            return true
        }
        return false
    }

    private fun clickControls(
        mouseX: Double,
        mouseY: Double,
        absX: Int,
        absY: Int,
        scale: Float,
        cardWidth: Int,
        track: MediaBridge.Track,
    ): Boolean {
        for (box in hitboxes(cardWidth.toFloat())) {
            val left = absX + box.x * scale
            val top = absY + box.y * scale
            if (mouseX < left || mouseX > left + box.width * scale) continue
            if (mouseY < top || mouseY > top + box.height * scale) continue
            when (box.control) {
                Control.PREVIOUS -> if (track.canPrev) MediaBridge.previous()
                Control.TOGGLE -> MediaBridge.togglePlayback()
                Control.NEXT -> if (track.canNext) MediaBridge.next()
            }
            UiSound.click()
            return true
        }
        return false
    }

    private fun draw() {
        if (!config.enabled) return
        MediaArt.tick()
        val track = MediaBridge.track
        if (track == null && config.hideWhenStopped) return
        config.position.render(MediaRenderable(track), "Media")
    }

    private fun cardWidth(): Int = config.width.coerceIn(120, 320)

    private fun cardHeight(): Int {
        var value = PADDING * 2 + ART
        if (config.showProgress) value += PROGRESS_BLOCK
        if (config.showControls) value += CONTROL_BLOCK
        return value
    }

    private fun controlsTop(): Float {
        var cursor = PADDING + ART + 2f
        if (config.showProgress) cursor += PROGRESS_BLOCK
        return cursor
    }

    private fun controlGlyphs(playing: Boolean): List<Pair<String, Control>> = listOf(
        Icons.SKIP_BACK to Control.PREVIOUS,
        (if (playing) Icons.PAUSE else Icons.PLAY) to Control.TOGGLE,
        Icons.SKIP_FORWARD to Control.NEXT,
    )

    private fun hitboxes(cardWidth: Float): List<Hitbox> {
        val playing = MediaBridge.track?.playing ?: false
        val glyphs = controlGlyphs(playing)

        var totalWidth = 0f
        glyphs.forEach { totalWidth += Draw.width(it.first) + CONTROL_GAP }
        totalWidth -= CONTROL_GAP

        val top = controlsTop()
        var cursor = (cardWidth - totalWidth) / 2f
        val boxes = ArrayList<Hitbox>(glyphs.size)
        glyphs.forEach { entry ->
            val glyphWidth = Draw.width(entry.first)
            boxes.add(
                Hitbox(
                    cursor - CONTROL_GAP / 2f,
                    top,
                    glyphWidth + CONTROL_GAP,
                    Draw.LINE_HEIGHT + 6f,
                    entry.second,
                ),
            )
            cursor += glyphWidth + CONTROL_GAP
        }
        return boxes
    }

    private class MediaRenderable(private val track: MediaBridge.Track?) : Renderable {

        override val width: Int get() = cardWidth()

        override val height: Int get() = cardHeight()

        override fun render(absX: Int, absY: Int) {
            val graphics = RenderContext.graphics
            val cardWidth = width.toFloat()
            val cardHeight = height.toFloat()

            Shapes.shadow(graphics, 0f, 0f, cardWidth, cardHeight, RADIUS, 8f, surface(0x000000, 130), 2f)
            Shapes.panel(
                graphics,
                0f,
                0f,
                cardWidth,
                cardHeight,
                RADIUS,
                surface(Theme.palette.background, 245),
                alpha(Theme.palette.border, 255),
            )

            val textLeft = (if (config.showArt) PADDING + ART + PADDING else PADDING).toFloat()
            if (config.showArt) drawArt(graphics)

            val title = track?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing"
            val artist = track?.artist.orEmpty()
            val available = cardWidth - textLeft - PADDING

            graphics.string(
                Draw.truncate(title, available),
                textLeft,
                PADDING + 2f,
                alpha(Theme.palette.text, 255),
                bold = true,
            )

            if (config.showArtist && artist.isNotBlank()) {
                graphics.string(
                    Draw.truncate(artist, available),
                    textLeft,
                    PADDING + 13f,
                    alpha(Theme.palette.textDim, 255),
                )
            }

            if (config.showSource && track != null) {
                graphics.string(
                    Draw.truncate(sourceName(track.app), available),
                    textLeft,
                    PADDING + 23f,
                    alpha(Theme.palette.textDim, 190),
                )
            }

            var cursor = PADDING + ART + 2f
            if (config.showProgress) {
                drawProgress(graphics, cardWidth, cursor)
                cursor += PROGRESS_BLOCK
            }
            if (config.showControls) drawControls(graphics, cardWidth, cursor)
        }

        private fun drawArt(graphics: GuiGraphicsExtractor) {
            if (MediaArt.available) {
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    MediaArt.identifier,
                    PADDING,
                    PADDING,
                    0f,
                    0f,
                    ART,
                    ART,
                    MediaArt.width,
                    MediaArt.height,
                    MediaArt.width,
                    MediaArt.height,
                )
                Shapes.outline(
                    graphics,
                    PADDING.toFloat(),
                    PADDING.toFloat(),
                    ART.toFloat(),
                    ART.toFloat(),
                    ART_RADIUS,
                    alpha(0xFFFFFF, 55),
                )
                return
            }

            val tint = artTint(track?.title.orEmpty() + track?.artist.orEmpty())
            Shapes.gradient(
                graphics,
                PADDING.toFloat(),
                PADDING.toFloat(),
                ART.toFloat(),
                ART.toFloat(),
                ART_RADIUS,
                alpha(tint, 255),
                alpha(shade(tint), 255),
            )
            Shapes.outline(
                graphics,
                PADDING.toFloat(),
                PADDING.toFloat(),
                ART.toFloat(),
                ART.toFloat(),
                ART_RADIUS,
                alpha(0xFFFFFF, 45),
            )

            val glyph = Icons.MUSIC
            graphics.string(
                glyph,
                PADDING + (ART - Draw.width(glyph)) / 2f,
                PADDING + (ART - Draw.LINE_HEIGHT) / 2f,
                alpha(0xFFFFFF, 220),
            )
        }

        private fun drawProgress(graphics: GuiGraphicsExtractor, cardWidth: Float, top: Float) {
            val position = MediaBridge.smoothPosition()
            val duration = track?.duration ?: 0.0
            val fraction = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f

            val barLeft = PADDING.toFloat()
            val barWidth = cardWidth - PADDING * 2f
            val barY = top + 4f

            Shapes.pill(graphics, barLeft, barY, barWidth, 3f, alpha(Theme.palette.border, 200))
            if (fraction > 0f) {
                Shapes.gradientHorizontal(
                    graphics,
                    barLeft,
                    barY,
                    barWidth * fraction,
                    3f,
                    1.5f,
                    alpha(Theme.mix(Theme.palette.accent, 0x000000, 0.35f), 255),
                    alpha(Theme.mix(Theme.palette.accent, 0xFFFFFF, 0.35f), 255),
                )
            }

            graphics.string(clock(position), barLeft, barY + 6f, alpha(Theme.palette.textDim, 220))
            graphics.stringRight(
                clock(duration),
                barLeft + barWidth,
                barY + 6f,
                alpha(Theme.palette.textDim, 220),
            )
        }

        private fun drawControls(graphics: GuiGraphicsExtractor, cardWidth: Float, top: Float) {
            val playing = track?.playing ?: false
            val glyphs = controlGlyphs(playing)
            val boxes = hitboxes(cardWidth)

            glyphs.forEachIndexed { index, entry ->
                val glyph = entry.first
                val enabled = when (entry.second) {
                    Control.PREVIOUS -> track?.canPrev ?: false
                    Control.NEXT -> track?.canNext ?: false
                    Control.TOGGLE -> true
                }
                val color = when {
                    !enabled -> alpha(Theme.palette.textDim, 90)
                    entry.second == Control.TOGGLE -> alpha(Theme.palette.accent, 255)
                    else -> alpha(Theme.palette.text, 210)
                }
                val box = boxes[index]
                graphics.string(glyph, box.x + CONTROL_GAP / 2f, top + 3f, color)
            }
        }

        private fun alpha(rgb: Int, value: Int): Int =
            (value.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        private fun surface(rgb: Int, value: Int): Int {
            val mix = 0.35f + 0.65f * config.opacity.coerceIn(0.1f, 1f)
            val scaled = (value * mix).roundToInt().coerceIn(0, 255)
            return (scaled shl 24) or (rgb and 0xFFFFFF)
        }

        private fun shade(rgb: Int): Int {
            val red = ((rgb shr 16) and 0xFF) * 45 / 100
            val green = ((rgb shr 8) and 0xFF) * 45 / 100
            val blue = (rgb and 0xFF) * 45 / 100
            return (red shl 16) or (green shl 8) or blue
        }

        private fun artTint(key: String): Int {
            if (key.isBlank()) return Theme.palette.accent
            val hue = abs(key.hashCode()) % 360 / 360f
            return java.awt.Color.HSBtoRGB(hue, 0.55f, 0.75f) and 0xFFFFFF
        }

        private fun sourceName(app: String): String {
            if (app.isBlank()) return ""
            val trimmed = app.substringAfterLast('.').substringBefore(".exe")
            return trimmed.replaceFirstChar { it.uppercase() }
        }

        private fun clock(seconds: Double): String {
            if (seconds <= 0.0) return "0:00"
            val total = seconds.toInt()
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }
    }
}
