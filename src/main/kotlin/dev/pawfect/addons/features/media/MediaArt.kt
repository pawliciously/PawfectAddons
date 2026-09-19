package dev.pawfect.addons.features.media

import com.mojang.blaze3d.platform.NativeImage
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MediaArt {

    private val TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "media_art")

    private const val TARGET_LOGICAL = 32

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
            return
        }

        runCatching { load(Path.of(source), target) }.onFailure {
            lastError = "${it::class.simpleName}: ${it.message}"
            release()
        }
    }

    private fun targetPixels(): Int {
        val factor = McCompat.mc.window.guiScale.coerceIn(1, 8)
        return TARGET_LOGICAL * factor
    }

    private fun load(path: Path, target: Int) {
        if (!Files.exists(path)) {
            lastError = "file missing: $path"
            release()
            return
        }

        val source = Files.newInputStream(path).use { NativeImage.read(it) }
        val scaled = runCatching { downscale(source, target) }.getOrDefault(source)
        if (scaled !== source) source.close()

        release()

        val created = DynamicTexture({ "pawfect_media_art" }, scaled)
        McCompat.mc.textureManager.register(TEXTURE, created)
        texture = created
        width = scaled.width
        height = scaled.height
        available = width > 0 && height > 0
        lastError = if (available) "ok ${width}x${height}" else "zero size"
    }

    private fun downscale(source: NativeImage, target: Int): NativeImage {
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return source
        if (sourceWidth <= target && sourceHeight <= target) return source

        val ratio = min(target.toFloat() / sourceWidth, target.toFloat() / sourceHeight)
        val outWidth = max(1, (sourceWidth * ratio).roundToInt())
        val outHeight = max(1, (sourceHeight * ratio).roundToInt())

        val result = NativeImage(outWidth, outHeight, false)
        val stepX = sourceWidth.toFloat() / outWidth
        val stepY = sourceHeight.toFloat() / outHeight

        for (y in 0 until outHeight) {
            val startY = (y * stepY).toInt()
            val endY = max(startY + 1, ((y + 1) * stepY).toInt().coerceAtMost(sourceHeight))
            for (x in 0 until outWidth) {
                val startX = (x * stepX).toInt()
                val endX = max(startX + 1, ((x + 1) * stepX).toInt().coerceAtMost(sourceWidth))
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
