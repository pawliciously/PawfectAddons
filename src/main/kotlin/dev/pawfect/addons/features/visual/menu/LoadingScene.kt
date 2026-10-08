package dev.pawfect.addons.features.visual.menu

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.mixin.AbstractTextureAccessor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.util.OptionalDouble
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The loading screen, in place of Mojang's: a night sky where stars join up into a paw, the paw
 * fills in white and the name rises below it. How far the scene has got follows how far the
 * game has loaded; once it's done, the paw fills and the name comes in as the screen fades.
 *
 * It runs before any resource pack is loaded, so it only uses the two GUI pipelines the game
 * compiles up front, and textures read straight from the jar or drawn here.
 */
object LoadingScene {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Loading")

    /** The night sky behind everything, in place of Mojang's red. */
    @JvmField
    val BACKGROUND: Int = 0xFF0F0C1D.toInt()

    private val DOT = id("loading/dot")
    private val LINE = id("loading/line")
    private val PAW = id("loading/paw")
    private val WORDMARK = id("loading/wordmark")

    private const val DOT_SIZE = 64
    private const val LINE_SIZE = 64
    private var pawSize = 512
    private var wordWidth = 1
    private var wordHeight = 1

    private var ready = false
    private var failed = false

    private var owner: Any? = null
    private var startedAt = 0L
    private var lastFrame = 0L
    private var shown = 0f

    // ------------------------------------------------------------------ timeline, in seconds of the scene

    /** Where loading starts the scene and where it has got to when everything is loaded. */
    private const val LOAD_FROM = 0.4f
    private const val LOAD_TO = 3.0f
    /** How fast the rest plays once loading is done: the paw fills and the name rises within the second before the fade. */
    private const val AFTER_RATE = 1.35f
    private const val FILL_AT = 3.0f
    private const val NAME_AT = 3.4f

    // The paw's outline as stars, in the paw image's own 100 by 100 frame.
    private val PAD = arrayOf(
        floatArrayOf(50f, 49f), floatArrayOf(30f, 57f), floatArrayOf(21f, 73f), floatArrayOf(32f, 88f),
        floatArrayOf(50f, 84f), floatArrayOf(68f, 88f), floatArrayOf(79f, 73f), floatArrayOf(70f, 57f),
    )
    private val TOES = arrayOf(floatArrayOf(17f, 45f), floatArrayOf(37f, 25f), floatArrayOf(63f, 25f), floatArrayOf(83f, 45f))

    private class Star(val x: Float, val y: Float, val r: Float, val a: Float, val speed: Float, val phase: Float)

    private val field: List<Star> = Random(26).let { rng ->
        List(170) { Star(rng.nextFloat(), rng.nextFloat() * 0.92f, 0.35f + rng.nextFloat(), 0.25f + rng.nextFloat() * 0.6f, 0.8f + rng.nextFloat() * 2.2f, rng.nextFloat() * PI.toFloat() * 2f) }
    }

    /** Whether the loading screen is ours. */
    @JvmStatic
    fun active(): Boolean = !failed && runCatching { ConfigManager.features.menu.pawLoadingScreen }.getOrDefault(true)

    /**
     * Draws the scene for [overlay] at [alpha], with loading at [progress] and [fadeOutStart] the
     * time it finished (-1 while still loading). Returns false when it couldn't, so the caller
     * draws Mojang's logo instead.
     */
    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor, overlay: Any, alpha: Float, progress: Float, fadeOutStart: Long): Boolean {
        if (!active() || !ensureTextures()) return false
        return try {
            scene(graphics, overlay, alpha.coerceIn(0f, 1f), progress, fadeOutStart)
            true
        } catch (e: Throwable) {
            failed = true
            logger.warn("Loading screen failed, falling back to the vanilla one", e)
            false
        }
    }

    private fun scene(g: GuiGraphicsExtractor, overlay: Any, alpha: Float, progress: Float, fadeOutStart: Long) {
        val now = Util.getMillis()
        if (overlay !== owner) {
            owner = overlay
            startedAt = now
            lastFrame = now
            shown = 0f
        }
        val real = (now - startedAt) / 1000f
        val target = if (fadeOutStart > -1L) LOAD_TO + (now - fadeOutStart) / 1000f * AFTER_RATE
        else LOAD_FROM + progress.coerceIn(0f, 1f) * (LOAD_TO - LOAD_FROM)
        // Follows loading smoothly and never goes backwards, so a jump in progress glides instead of snapping.
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        if (target > shown) shown += (target - shown) * min(1f, dt * 7f)
        val t = shown

        val w = g.guiWidth().toFloat()
        val h = g.guiHeight().toFloat()
        val unit = w / 1000f

        // Background stars, twinkling.
        val fieldIn = ease(real / 0.8f)
        for (star in field) {
            val twinkle = 0.7f + 0.3f * sin(real * star.speed + star.phase)
            dot(g, star.x * w, star.y * h, star.r * unit * 1.6f, 0xE9E4FF, star.a * twinkle * fieldIn * alpha)
        }

        val size = h * 0.42f
        val cx = w * 0.5f
        val cy = h * 0.4f
        fun px(p: FloatArray) = cx + (p[0] - 50f) / 100f * size
        fun py(p: FloatArray) = cy + (p[1] - 57f) / 100f * size

        // Two unbroken strokes glide from star to star, round the pad and across the toes.
        val fill = ease((t - FILL_AT) / 0.7f)
        val stroke = 0.6f * (1f - fill) * alpha
        val thickness = maxOf(1f, unit * 2.2f)
        if (stroke > 0f) {
            trace(g, (PAD + arrayOf(PAD[0])).map { floatArrayOf(px(it), py(it)) }, smooth((t - 1.2f) / 1.7f), thickness, 0xCFC6FF, stroke)
            trace(g, TOES.map { floatArrayOf(px(it), py(it)) }, smooth((t - 1.75f) / 1.15f), thickness, 0xCFC6FF, stroke)
        }

        // The paw fills in solid white.
        if (fill > 0f) image(g, PAW, pawSize, pawSize, cx - 0.5f * size, cy - 0.57f * size, size, size, 0xFFFFFF, fill * alpha)

        // The paw's stars blink on in turn and go once the paw is solid.
        (TOES + PAD).forEachIndexed { i, p ->
            val k = ease((t - 0.5f - i * 0.065f) / 0.25f) * (1f - fill)
            if (k <= 0f) return@forEachIndexed
            val r = unit * 4.2f
            dot(g, px(p), py(p), r * 3f * (1.6f - 0.6f * k), 0xFFFFFF, 0.18f * k * alpha)
            dot(g, px(p), py(p), r, 0xFFFFFF, k * alpha)
        }

        // The name rises in under the paw.
        val name = ease((t - NAME_AT) / 0.8f)
        if (name > 0f) {
            val height = w * 0.051f
            val width = height * wordWidth / wordHeight
            val y = h * 0.80f - height / 2f + (1f - name) * height * 0.35f
            image(g, WORDMARK, wordWidth, wordHeight, cx - width / 2f, y, width, height, 0xFFFFFF, name * alpha)
        }

        // A thin bar for how far loading has got, gone once it's done.
        val bar = (1f - ease(t - LOAD_TO)) * alpha
        if (bar > 0f) {
            val barWidth = w * 0.22f
            val barY = h * 0.93f
            val left = cx - barWidth / 2f
            rect(g, left, barY, barWidth, maxOf(1f, unit * 1.6f), 0xFFFFFF, 0.12f * bar)
            rect(g, left, barY, barWidth * progress.coerceIn(0f, 1f), maxOf(1f, unit * 1.6f), 0xFFFFFF, 0.55f * bar)
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun argb(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

    private fun image(g: GuiGraphicsExtractor, id: Identifier, texW: Int, texH: Int, x: Float, y: Float, width: Float, height: Float, rgb: Int, alpha: Float) {
        if (alpha <= 0f) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(width / texW, height / texH)
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, texW, texH, texW, texH, texW, texH, argb(rgb, alpha))
        pose.popMatrix()
    }

    private fun dot(g: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, rgb: Int, alpha: Float) =
        image(g, DOT, DOT_SIZE, DOT_SIZE, x - radius, y - radius, radius * 2f, radius * 2f, rgb, alpha)

    private fun rect(g: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, rgb: Int, alpha: Float) {
        if (alpha <= 0f || width <= 0f) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(width, height)
        g.fill(0, 0, 1, 1, argb(rgb, alpha))
        pose.popMatrix()
    }

    private fun line(g: GuiGraphicsExtractor, ax: Float, ay: Float, bx: Float, by: Float, thickness: Float, rgb: Int, alpha: Float) {
        val length = hypot(bx - ax, by - ay)
        if (length < 0.01f) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(ax, ay)
        pose.rotate(atan2(by - ay, bx - ax))
        pose.translate(0f, -thickness / 2f)
        pose.scale(length / LINE_SIZE, thickness / LINE_SIZE)
        g.blit(RenderPipelines.GUI_TEXTURED, LINE, 0, 0, 0f, 0f, LINE_SIZE, LINE_SIZE, LINE_SIZE, LINE_SIZE, LINE_SIZE, LINE_SIZE, argb(rgb, alpha))
        pose.popMatrix()
    }

    /** The polyline through [points] drawn up to [progress] of its length, as one stroke. */
    private fun trace(g: GuiGraphicsExtractor, points: List<FloatArray>, progress: Float, thickness: Float, rgb: Int, alpha: Float) {
        if (progress <= 0f) return
        val lengths = (1 until points.size).map { hypot(points[it][0] - points[it - 1][0], points[it][1] - points[it - 1][1]) }
        var left = lengths.sum() * progress
        for (i in 1 until points.size) {
            if (left <= 0f) break
            val part = min(1f, left / lengths[i - 1])
            val a = points[i - 1]
            val b = points[i]
            line(g, a[0], a[1], a[0] + (b[0] - a[0]) * part, a[1] + (b[1] - a[1]) * part, thickness, rgb, alpha)
            // A round joint, so corners don't show gaps.
            if (part >= 1f && i < points.size - 1) dot(g, b[0], b[1], thickness * 0.5f, rgb, alpha)
            left -= lengths[i - 1]
        }
    }

    private fun ease(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return if (c < 0.5f) 4f * c * c * c else 1f - (-2f * c + 2f).pow(3) / 2f
    }

    // ------------------------------------------------------------------ textures

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    private fun ensureTextures(): Boolean {
        if (ready) return true
        if (failed) return false
        try {
            register(DOT, softDot())
            register(LINE, softLine())
            val paw = readImage("paw.png")
            pawSize = paw.width
            register(PAW, paw)
            val word = readImage("wordmark.png")
            wordWidth = word.width
            wordHeight = word.height
            register(WORDMARK, word)
            ready = true
        } catch (e: Throwable) {
            failed = true
            logger.warn("Could not set up the loading screen, using the vanilla one", e)
        }
        return ready
    }

    /** Read straight from the jar: resource packs aren't loaded yet the first time this shows. */
    private fun readImage(name: String): NativeImage {
        val stream = LoadingScene::class.java.getResourceAsStream("/assets/${PawfectAddons.MOD_ID}/textures/gui/loading/$name")
            ?: error("missing $name")
        return stream.use { NativeImage.read(it) }
    }

    private fun register(id: Identifier, image: NativeImage) {
        val texture = DynamicTexture({ id.toString() }, image)
        runCatching {
            val sampler = RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty(),
            )
            (texture as AbstractTextureAccessor).`pawfectaddons$setSampler`(sampler)
        }
        McCompat.mc.textureManager.register(id, texture)
    }

    /** A white disc with a soft edge, for stars and joints. */
    private fun softDot(): NativeImage {
        val image = NativeImage(DOT_SIZE, DOT_SIZE, false)
        val half = DOT_SIZE / 2f
        for (y in 0 until DOT_SIZE) for (x in 0 until DOT_SIZE) {
            val d = sqrt((x + 0.5f - half).pow(2) + (y + 0.5f - half).pow(2)) / half
            val a = ((1f - d) / 0.45f).coerceIn(0f, 1f)
            image.setPixel(x, y, argb(0xFFFFFF, a * a * (3f - 2f * a)))
        }
        return image
    }

    /** A white band with soft top and bottom edges, stretched along each line. */
    private fun softLine(): NativeImage {
        val image = NativeImage(LINE_SIZE, LINE_SIZE, false)
        val half = LINE_SIZE / 2f
        for (y in 0 until LINE_SIZE) {
            val d = kotlin.math.abs(y + 0.5f - half) / half
            val a = ((1f - d) / 0.4f).coerceIn(0f, 1f)
            val color = argb(0xFFFFFF, a * a * (3f - 2f * a))
            for (x in 0 until LINE_SIZE) image.setPixel(x, y, color)
        }
        return image
    }
}
