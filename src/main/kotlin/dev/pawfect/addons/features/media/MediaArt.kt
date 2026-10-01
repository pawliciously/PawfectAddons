package dev.pawfect.addons.features.media

import com.mojang.blaze3d.platform.NativeImage
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The current track's thumbnail as a texture: cropped to a square (video thumbnails are
 * 16:9), scaled to the card's size at the GUI scale, with the rounded corners baked into
 * its alpha. Also pulls a palette out of it for the card's backdrop.
 */
object MediaArt {

    private val TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "media_art")

    /** Size and corner radius on screen, in GUI pixels. */
    const val SIZE = 40
    const val RADIUS = 7f

    private var loadedVersion = -1
    private var loadedTarget = -1
    private var sourcePath: String? = null
    private var texture: DynamicTexture? = null

    var available: Boolean = false
        private set

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    /** Four backdrop colours from the art, or null without art. */
    var palette: IntArray? = null
        private set

    var lastError: String = "none"
        private set

    val identifier: Identifier get() = TEXTURE

    fun tick() {
        val version = MediaBridge.artVersion
        val target = targetPixels()
        if (version == loadedVersion && target == loadedTarget) return

        val path = MediaBridge.artPath
        if (version != loadedVersion) sourcePath = path
        loadedVersion = version
        loadedTarget = target

        val source = sourcePath ?: run {
            release()
            palette = null
            return
        }

        runCatching { load(Path.of(source), target) }.onFailure {
            lastError = "${it::class.simpleName}: ${it.message}"
            release()
            palette = null
        }
    }

    /** One texel per screen pixel: GUI textures aren't filtered, so any mismatch shimmers. */
    private fun targetPixels(): Int {
        val factor = McCompat.mc.window.guiScale.coerceIn(1, 8)
        val hud = ConfigManager.features.media.position.effectiveScale
        return (SIZE * factor * hud).roundToInt().coerceAtLeast(8)
    }

    private fun load(path: Path, target: Int) {
        if (!Files.exists(path)) {
            lastError = "file missing: $path"
            release()
            palette = null
            return
        }

        val source = Files.newInputStream(path).use { NativeImage.read(it) }
        val square = try {
            squared(source, target)
        } finally {
            source.close()
        }
        palette = MediaPalette.extract(square)
        roundCorners(square, RADIUS / SIZE * square.width)

        release()
        val created = DynamicTexture({ "pawfect_media_art" }, square)
        McCompat.mc.textureManager.register(TEXTURE, created)
        texture = created
        width = square.width
        height = square.height
        available = width > 0 && height > 0
        lastError = if (available) "ok ${width}x${height}" else "zero size"
    }

    /** The centred square of [source], box-filtered down to at most [target] pixels. */
    private fun squared(source: NativeImage, target: Int): NativeImage {
        val side = min(source.width, source.height)
        require(side > 0) { "empty image" }
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val out = min(side, target)
        val result = NativeImage(out, out, false)
        val step = side.toFloat() / out

        for (y in 0 until out) {
            val startY = top + (y * step).toInt()
            val endY = max(startY + 1, top + ((y + 1) * step).toInt().coerceAtMost(side))
            for (x in 0 until out) {
                val startX = left + (x * step).toInt()
                val endX = max(startX + 1, left + ((x + 1) * step).toInt().coerceAtMost(side))
                result.setPixel(x, y, average(source, startX, startY, endX, endY))
            }
        }
        return result
    }

    private fun average(image: NativeImage, startX: Int, startY: Int, endX: Int, endY: Int): Int {
        var alpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L

        for (y in startY until endY) {
            for (x in startX until endX) {
                val pixel = image.getPixel(x, y)
                alpha += (pixel ushr 24) and 0xFF
                red += (pixel ushr 16) and 0xFF
                green += (pixel ushr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        if (count == 0L) return 0

        return (((alpha / count).toInt() and 0xFF) shl 24) or
            (((red / count).toInt() and 0xFF) shl 16) or
            (((green / count).toInt() and 0xFF) shl 8) or
            ((blue / count).toInt() and 0xFF)
    }

    /** Fades the corners out with a one-pixel anti-aliased edge, so a plain blit looks rounded. */
    private fun roundCorners(image: NativeImage, radius: Float) {
        val size = image.width
        val r = radius.coerceIn(0f, size / 2f)
        if (r < 1f) return
        val reach = r.toInt() + 1
        for (y in 0 until size) {
            val cy = if (y < reach) r - (y + 0.5f) else if (y >= size - reach) (y + 0.5f) - (size - r) else continue
            for (x in 0 until size) {
                val cx = if (x < reach) r - (x + 0.5f) else if (x >= size - reach) (x + 0.5f) - (size - r) else continue
                if (cx <= 0f || cy <= 0f) continue
                val coverage = (r - sqrt(cx * cx + cy * cy) + 0.5f).coerceIn(0f, 1f)
                if (coverage >= 1f) continue
                val pixel = image.getPixel(x, y)
                val alpha = (((pixel ushr 24) and 0xFF) * coverage).roundToInt()
                image.setPixel(x, y, (alpha shl 24) or (pixel and 0xFFFFFF))
            }
        }
    }

    private fun release() {
        available = false
        width = 0
        height = 0
        texture?.let {
            runCatching { McCompat.mc.textureManager.release(TEXTURE) }
            runCatching { it.close() }
        }
        texture = null
    }
}
