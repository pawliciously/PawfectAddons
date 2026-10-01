package dev.pawfect.addons.features.media

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.icon
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringScaled
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.UiFont
import dev.pawfect.addons.ui.UiSound
import dev.pawfect.addons.ui.gpu.MediaBackdropRenderState
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.renderables.Renderable
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

object MediaOverlay {

    private val config get() = ConfigManager.features.media

    private const val PAD = 9f
    private const val ART = MediaArt.SIZE.toFloat()
    private const val RADIUS = 11f
    private const val TEXT_GAP = 9f
    private const val PROGRESS_HEIGHT = 14f
    private const val CONTROLS_HEIGHT = 20f
    private const val PLAY_RADIUS = 8.5f
    private const val SIDE_SPACING = 25f
    private const val BUTTON = 18f

    private const val MARQUEE_PAUSE = 2.5f
    private const val MARQUEE_SPEED = 22f
    private const val MARQUEE_GAP = 28f

    private enum class Control { PREVIOUS, TOGGLE, NEXT }

    private class Hitbox(val x: Float, val y: Float, val width: Float, val height: Float, val control: Control) {
        fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height
    }

    private var lastFrame = 0L
    private var phase = 0f
    private var awake = 0f
    private val shown: IntArray = MediaPalette.IDLE.copyOf()
    private var seedKey = ""
    private var seedPalette = MediaPalette.IDLE
    private var marqueeKey = ""
    private var marqueeStart = 0L

    fun render() {
        if (McCompat.mc.screen != null && !GuiEditManager.isEditorOpen()) return
        draw()
    }

    @JvmStatic
    fun renderOverScreen(graphics: GuiGraphicsExtractor) {
        if (McCompat.mc.screen == null || GuiEditManager.isEditorOpen()) return
        RenderContext.withContext(graphics) { draw() }
    }

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
        val width = cardWidth()
        val height = cardHeight()
        val absX = config.position.getAbsX((width * scale).toInt())
        val absY = config.position.getAbsY((height * scale).toInt())
        val localX = ((mouseX - absX) / scale).toFloat()
        val localY = ((mouseY - absY) / scale).toFloat()

        if (config.showControls && track != null) {
            hitboxes(width.toFloat()).firstOrNull { it.contains(localX, localY) }?.let { box ->
                when (box.control) {
                    Control.PREVIOUS -> if (track.canPrev) MediaBridge.previous()
                    Control.TOGGLE -> MediaBridge.togglePlayback()
                    Control.NEXT -> if (track.canNext) MediaBridge.next()
                }
                UiSound.click()
                return true
            }
        }

        if (localX in 0f..width.toFloat() && localY in 0f..height.toFloat()) {
            dragging = true
            dragOffsetX = mouseX.toInt() - absX
            dragOffsetY = mouseY.toInt() - absY
            return true
        }
        return false
    }

    private fun draw() {
        if (!config.enabled) return
        MediaArt.tick()
        val track = MediaBridge.track
        if (track == null && config.hideWhenStopped) return
        animate(track)
        config.position.render(MediaRenderable(track), "Media")
    }

    private fun cardWidth(): Int = config.width.coerceIn(140, 320)

    private fun progressTop(): Float = PAD + ART + 8f

    private fun controlsTop(): Float = if (config.showProgress) progressTop() + PROGRESS_HEIGHT + 3f else PAD + ART + 6f

    private fun cardHeight(): Int {
        var bottom = PAD + ART
        if (config.showProgress) bottom = progressTop() + PROGRESS_HEIGHT
        if (config.showControls) bottom = controlsTop() + CONTROLS_HEIGHT
        return ceil(bottom + PAD).toInt()
    }

    private fun hitboxes(cardWidth: Float): List<Hitbox> {
        val centerY = controlsTop() + CONTROLS_HEIGHT / 2f
        val centerX = cardWidth / 2f
        return listOf(
            Control.PREVIOUS to centerX - SIDE_SPACING,
            Control.TOGGLE to centerX,
            Control.NEXT to centerX + SIDE_SPACING,
        ).map { (control, x) -> Hitbox(x - BUTTON / 2f, centerY - BUTTON / 2f, BUTTON, BUTTON, control) }
    }

    private fun animate(track: MediaBridge.Track?) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrame = now

        val target = if (track?.playing == true) 1f else 0.2f
        awake += (target - awake) * ease(dt, 2.5f)
        phase += dt * (0.2f + 0.9f * awake)

        val goal = MediaArt.palette ?: when {
            track == null -> MediaPalette.IDLE
            else -> {
                val key = track.title + "\u0000" + track.artist
                if (key != seedKey) {
                    seedKey = key
                    seedPalette = MediaPalette.fromSeed(key)
                }
                seedPalette
            }
        }
        val blend = ease(dt, 3f)
        for (i in shown.indices) shown[i] = MediaPalette.mix(shown[i], goal[i], blend)
    }

    private fun ease(dt: Float, rate: Float): Float = 1f - exp(-dt * rate)

    private fun seconds(): Float = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000f

    private class MediaRenderable(private val track: MediaBridge.Track?) : Renderable {

        override val width: Int get() = cardWidth()

        override val height: Int get() = cardHeight()

        override fun render(absX: Int, absY: Int) {
            val graphics = RenderContext.graphics
            val w = width.toFloat()
            val h = height.toFloat()
            val opacity = config.opacity.coerceIn(0.1f, 1f)

            Shapes.shadow(graphics, 0f, 0f, w, h, RADIUS, 14f, argb(MediaPalette.light(shown[0]), 40f * opacity), 3f)
            Shapes.shadow(graphics, 0f, 0f, w, h, RADIUS, 6f, argb(0x000000, 120f * opacity), 2f)
            (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addGuiElement(
                MediaBackdropRenderState(Matrix3x2f(graphics.pose()), 0f, 0f, w, h, RADIUS, shown.copyOf(), opacity, phase, awake),
            )

            val textLeft = if (config.showArt) PAD + ART + TEXT_GAP else PAD + 2f
            if (config.showArt) drawArt(graphics)
            drawText(graphics, textLeft, w - PAD - textLeft)

            if (config.showProgress) drawProgress(graphics, w)
            if (config.showControls) drawControls(graphics, w, absX, absY)
        }

        private fun drawArt(graphics: GuiGraphicsExtractor) {
            Shapes.shadow(graphics, PAD, PAD, ART, ART, MediaArt.RADIUS, 5f, argb(0x000000, 140f), 1.5f)
            if (MediaArt.available) {
                val size = ART.toInt()
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    MediaArt.identifier,
                    PAD.toInt(),
                    PAD.toInt(),
                    0f,
                    0f,
                    size,
                    size,
                    MediaArt.width,
                    MediaArt.height,
                    MediaArt.width,
                    MediaArt.height,
                )
            } else {
                Shapes.gradient(
                    graphics, PAD, PAD, ART, ART, MediaArt.RADIUS,
                    argb(MediaPalette.light(shown[0]), 90f), argb(shown[1], 255f),
                )
                val glyph = Icons.MUSIC
                graphics.icon(glyph, PAD + ART / 2f - inkCentre(glyph), PAD + (ART - Draw.LINE_HEIGHT) / 2f, argb(0xFFFFFF, 230f))
            }
            Shapes.outline(graphics, PAD, PAD, ART, ART, MediaArt.RADIUS, argb(0xFFFFFF, 34f))
        }

        private fun drawText(graphics: GuiGraphicsExtractor, left: Float, available: Float) {
            val title = track?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing"
            val artist = track?.artist.orEmpty().ifBlank { if (track == null) "Play something on this PC" else "" }
            val compact = !config.showArtist || artist.isBlank()
            val titleY = PAD + if (compact) 9f else 4f

            drawTitle(graphics, title, left, titleY, available)
            if (!compact) {
                graphics.string(Draw.truncate(artist, available), left, PAD + 15f, argb(0xFFFFFF, 185f), shadow = true)
            }

            if (track != null) {
                val baseY = PAD + ART - 4f
                drawEqualizer(graphics, left, baseY)
                if (config.showSource) {
                    val source = sourceName(track.app)
                    if (source.isNotEmpty()) {
                        graphics.stringScaled(
                            Draw.truncate(source, (available - 12f) / 0.8f), left + 12f, baseY - 6.2f, 0.8f,
                            argb(0xFFFFFF, 150f), shadow = true,
                        )
                    }
                }
            }
        }

        private fun drawTitle(graphics: GuiGraphicsExtractor, title: String, left: Float, y: Float, available: Float) {
            val white = argb(0xFFFFFF, 255f)
            val titleWidth = McCompat.font.width(UiFont.component(title, true)).toFloat()
            if (titleWidth <= available) {
                graphics.string(title, left, y, white, shadow = true, bold = true)
                return
            }
            if (!config.scrollLongTitles) {
                graphics.string(Draw.truncate(title, available - 2f), left, y, white, shadow = true, bold = true)
                return
            }

            if (title != marqueeKey) {
                marqueeKey = title
                marqueeStart = System.currentTimeMillis()
            }
            val travel = titleWidth + MARQUEE_GAP
            val cycle = MARQUEE_PAUSE + travel / MARQUEE_SPEED
            val t = ((System.currentTimeMillis() - marqueeStart) / 1000f) % cycle
            val offset = if (t < MARQUEE_PAUSE) 0f else (t - MARQUEE_PAUSE) * MARQUEE_SPEED

            Shapes.pushScissor(graphics, left, y - 2f, available, 13f)
            graphics.string(title, left - offset, y, white, shadow = true, bold = true)
            if (offset > 0f) graphics.string(title, left - offset + travel, y, white, shadow = true, bold = true)
            Shapes.popScissor(graphics)
        }

        private fun drawEqualizer(graphics: GuiGraphicsExtractor, left: Float, baseY: Float) {
            val time = seconds()
            val colour = argb(MediaPalette.light(shown[0]), 235f)
            val speeds = floatArrayOf(7.1f, 9.3f, 6.2f)
            val offsets = floatArrayOf(0f, 1.9f, 3.7f)
            for (i in 0 until 3) {
                val bounce = 0.5f + 0.5f * sin(time * speeds[i] + offsets[i]) * sin(time * speeds[i] * 0.37f + offsets[i] * 2f)
                val height = 2f + 5.5f * bounce * ((awake - 0.2f) / 0.8f).coerceIn(0f, 1f)
                Shapes.rect(graphics, left + i * 3.2f, baseY - height, 2f, height, 1f, colour)
            }
        }

        private fun drawProgress(graphics: GuiGraphicsExtractor, cardWidth: Float) {
            val position = MediaBridge.smoothPosition()
            val duration = track?.duration ?: 0.0
            val fraction = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f

            val left = PAD
            val barWidth = cardWidth - PAD * 2f
            val top = progressTop()

            Shapes.pill(graphics, left, top, barWidth, 3f, argb(0xFFFFFF, 56f))
            if (fraction > 0f) {
                Shapes.shadow(graphics, left, top, barWidth * fraction, 3f, 1.5f, 3f, argb(MediaPalette.light(shown[0]), 70f))
                Shapes.pill(graphics, left, top, barWidth * fraction, 3f, argb(0xFFFFFF, 240f))
                Shapes.circle(graphics, left + barWidth * fraction, top + 1.5f, 2.6f, argb(0xFFFFFF, 255f))
            }

            val remaining = (duration - position).coerceAtLeast(0.0)
            val textY = top + 6.5f
            val dim = argb(0xFFFFFF, 165f)
            graphics.stringScaled(clock(position), left, textY, 0.8f, dim, shadow = true)
            val end = if (duration > 0.0) "-${clock(remaining)}" else clock(0.0)
            graphics.stringScaled(end, left + barWidth - Draw.width(end) * 0.8f, textY, 0.8f, dim, shadow = true)
        }

        private fun drawControls(graphics: GuiGraphicsExtractor, cardWidth: Float, absX: Int, absY: Int) {
            val playing = track?.playing ?: false
            val hoverable = McCompat.mc.screen != null && config.clickableInMenus
            val scale = config.position.effectiveScale
            val mouseX = (McCompat.mouseX - absX) / scale
            val mouseY = (McCompat.mouseY - absY) / scale

            for (box in hitboxes(cardWidth)) {
                val cx = box.x + box.width / 2f
                val cy = box.y + box.height / 2f
                val hovered = hoverable && track != null && box.contains(mouseX, mouseY)
                if (box.control == Control.TOGGLE) {
                    val radius = PLAY_RADIUS + if (hovered) 0.8f else 0f
                    Shapes.shadow(graphics, cx - radius, cy - radius, radius * 2f, radius * 2f, radius, 3f, argb(0x000000, 90f), 1f)
                    Shapes.circle(graphics, cx, cy, radius, argb(0xFFFFFF, if (track == null) 120f else 250f))
                    val glyph = if (playing) Icons.PAUSE else Icons.PLAY
                    graphics.icon(
                        glyph,
                        cx - inkCentre(glyph),
                        cy - Draw.LINE_HEIGHT / 2f + 0.5f,
                        argb(MediaPalette.mix(shown[0], 0x000000, 0.45f), 255f),
                    )
                    continue
                }
                val enabled = when (box.control) {
                    Control.PREVIOUS -> track?.canPrev ?: false
                    else -> track?.canNext ?: false
                }
                if (hovered && enabled) Shapes.circle(graphics, cx, cy, 8f, argb(0xFFFFFF, 38f))
                val glyph = if (box.control == Control.PREVIOUS) Icons.SKIP_BACK else Icons.SKIP_FORWARD
                graphics.icon(
                    glyph,
                    cx - inkCentre(glyph),
                    cy - Draw.LINE_HEIGHT / 2f + 0.5f,
                    argb(0xFFFFFF, if (enabled) 235f else 80f),
                )
            }
        }

        private fun inkCentre(glyph: String): Float = when (glyph) {
            Icons.PAUSE -> 2.8f
            Icons.PLAY -> 3.0f
            else -> 3.5f
        }

        private fun argb(rgb: Int, alpha: Float): Int = (alpha.toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        private fun sourceName(app: String): String {
            if (app.isBlank()) return ""
            val trimmed = app.substringAfterLast('.').substringBefore(".exe")
            return when (trimmed.lowercase()) {
                "spotify" -> "Spotify"
                "chrome" -> "Chrome"
                "msedge" -> "Edge"
                "firefox" -> "Firefox"
                "opera", "opera_gx" -> "Opera"
                "brave" -> "Brave"
                "applemusic", "zunemusic" -> "Music"
                else -> trimmed.replaceFirstChar { it.uppercase() }
            }
        }

        private fun clock(seconds: Double): String {
            if (seconds <= 0.0) return "0:00"
            val total = seconds.toInt()
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val secs = (total % 60).toString().padStart(2, '0')
            return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$secs" else "$minutes:$secs"
        }
    }
}
