package dev.pawfect.addons.features.dev

import com.mojang.blaze3d.platform.NativeImage
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadata

/**
 * Dev only: an animated gif in the bottom left corner of the screen in every menu (not chat), drawn behind
 * the menu's own window. The gif is config/pawfectaddons/dev/menu.gif and isn't part of the mod. A plain
 * white background around the picture is cut out when it loads.
 */
object MenuGif {

    private val logger = LoggerFactory.getLogger("PawfectAddons/MenuGif")
    private val file: Path get() = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev").resolve("menu.gif")

    /** Frames are shrunk to this many pixels on their longest side before upload. */
    private const val FRAME_SIZE = 256
    /** Share of the screen height the gif takes up, and its most in GUI pixels. */
    private const val HEIGHT = 0.4f
    private const val MAX_HEIGHT = 180

    private class Frame(val id: Identifier, val texture: DynamicTexture, val delayMs: Int)

    private var frames: List<Frame> = emptyList()
    private var width = 0
    private var height = 0
    private var totalMs = 0
    private var loadedStamp = -1L

    val active: Boolean get() = ConfigManager.features.dev.menuGif && DevAccount.isDev()

    /** Called right after a screen draws its background, so the screen's window goes on top. */
    @JvmStatic
    fun renderBehind(screen: Screen, graphics: GuiGraphicsExtractor) {
        if (!active || screen is ChatScreen) return
        if (!load() || frames.isEmpty()) return
        var t = (System.currentTimeMillis() % totalMs).toInt()
        val frame = frames.firstOrNull { t -= it.delayMs; t < 0 } ?: frames.last()
        val h = minOf((screen.height * HEIGHT).toInt(), MAX_HEIGHT)
        val w = h * width / height
        graphics.blit(RenderPipelines.GUI_TEXTURED, frame.id, 0, screen.height - h, 0f, 0f, w, h, width, height, width, height, -1)
    }

    /** Loads the gif, again whenever the file changes. False if there's none or it won't decode. */
    private fun load(): Boolean {
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
        if (stamp == loadedStamp) return frames.isNotEmpty()
        loadedStamp = stamp
        release()
        if (stamp == 0L) return false
        return runCatching {
            val decoded = decode(file)
            frames = decoded.mapIndexed { i, (image, delay) ->
                val id = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "dev_menu_gif_$i")
                val texture = DynamicTexture({ "pawfect_dev_menu_gif_$i" }, toNative(image))
                McCompat.mc.textureManager.register(id, texture)
                Frame(id, texture, delay)
            }
            width = decoded.first().first.width + 2
            height = decoded.first().first.height + 2
            totalMs = frames.sumOf { it.delayMs }.coerceAtLeast(1)
            true
        }.onFailure { logger.warn("Couldn't load {}", file, it) }.getOrDefault(false)
    }

    /** Every frame drawn onto the full canvas (gif frames are often only the part that changed), with its delay. */
    private fun decode(path: Path): List<Pair<BufferedImage, Int>> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        Files.newInputStream(path).use { stream ->
            ImageIO.createImageInputStream(stream).use { input ->
                reader.input = input
                val count = reader.getNumImages(true)
                var canvasW = reader.getWidth(0)
                var canvasH = reader.getHeight(0)
                reader.streamMetadata?.let { meta ->
                    (meta.getAsTree("javax_imageio_gif_stream_1.0") as Element).getElementsByTagName("LogicalScreenDescriptor").item(0)?.let {
                        it as Element
                        canvasW = it.getAttribute("logicalScreenWidth").toIntOrNull() ?: canvasW
                        canvasH = it.getAttribute("logicalScreenHeight").toIntOrNull() ?: canvasH
                    }
                }
                val canvas = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)
                val out = ArrayList<Pair<BufferedImage, Int>>()
                for (i in 0 until count) {
                    val image = reader.read(i)
                    val info = frameInfo(reader.getImageMetadata(i))
                    val before = if (info.disposal == "restoreToPrevious") copy(canvas) else null
                    canvas.createGraphics().apply { drawImage(image, info.x, info.y, null); dispose() }
                    out.add(shrunk(cutWhite(trimTopLine(copy(canvas)))) to (if (info.delayCs <= 1) 100 else info.delayCs * 10))
                    when (info.disposal) {
                        "restoreToBackgroundColor" -> canvas.createGraphics().apply {
                            composite = java.awt.AlphaComposite.Clear
                            fillRect(info.x, info.y, image.width, image.height)
                            dispose()
                        }
                        "restoreToPrevious" -> before?.let { canvas.createGraphics().apply { composite = java.awt.AlphaComposite.Src; drawImage(it, 0, 0, null); dispose() } }
                    }
                }
                reader.dispose()
                return out
            }
        }
    }

    private class FrameInfo(val x: Int, val y: Int, val delayCs: Int, val disposal: String)

    private fun frameInfo(meta: IIOMetadata): FrameInfo {
        val root = meta.getAsTree("javax_imageio_gif_image_1.0") as Element
        val descriptor = root.getElementsByTagName("ImageDescriptor").item(0) as? Element
        val control = root.getElementsByTagName("GraphicControlExtension").item(0) as? Element
        return FrameInfo(
            descriptor?.getAttribute("imageLeftPosition")?.toIntOrNull() ?: 0,
            descriptor?.getAttribute("imageTopPosition")?.toIntOrNull() ?: 0,
            control?.getAttribute("delayTime")?.toIntOrNull() ?: 10,
            control?.getAttribute("disposalMethod") ?: "none",
        )
    }

    /**
     * Some gifs carry a thin line along the very top (the Zelda one does, 2 px across nearly the whole width in
     * most frames). Any of the first few rows that runs across more than 60% of the picture is cleared.
     */
    private fun trimTopLine(image: BufferedImage): BufferedImage {
        for (y in 0 until minOf(4, image.height)) {
            var minX = -1
            var maxX = -1
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val background = (argb ushr 24) < 16 || ((argb shr 16 and 0xFF) >= 235 && (argb shr 8 and 0xFF) >= 235 && (argb and 0xFF) >= 235)
                if (background) continue
                if (minX < 0) minX = x
                maxX = x
            }
            if (minX >= 0 && maxX - minX > image.width * 0.6) {
                for (x in 0 until image.width) image.setRGB(x, y, 0)
            }
        }
        return image
    }

    /** Clears near-white pixels joined to the edge of the picture: the background, not white bits inside it. */
    private fun cutWhite(image: BufferedImage): BufferedImage {
        val w = image.width
        val h = image.height
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        fun white(i: Int): Boolean {
            val argb = image.getRGB(i % w, i / w)
            if ((argb ushr 24) < 16) return true
            return (argb shr 16 and 0xFF) >= 235 && (argb shr 8 and 0xFF) >= 235 && (argb and 0xFF) >= 235
        }
        for (x in 0 until w) {
            stack.add(x)
            stack.add((h - 1) * w + x)
        }
        for (y in 0 until h) {
            stack.add(y * w)
            stack.add(y * w + w - 1)
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (seen[i] || !white(i)) continue
            seen[i] = true
            image.setRGB(i % w, i / w, 0)
            val x = i % w
            val y = i / w
            if (x > 0) stack.add(i - 1)
            if (x < w - 1) stack.add(i + 1)
            if (y > 0) stack.add(i - w)
            if (y < h - 1) stack.add(i + w)
        }
        return image
    }

    private fun shrunk(image: BufferedImage): BufferedImage {
        val scale = FRAME_SIZE.toDouble() / maxOf(image.width, image.height)
        if (scale >= 1.0) return image
        val out = BufferedImage((image.width * scale).toInt(), (image.height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image, 0, 0, out.width, out.height, null)
            dispose()
        }
        return out
    }

    private fun copy(image: BufferedImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return out
    }

    /**
     * With a clear 1 px border: scaled drawing samples past the edge and wraps around, which put a line of the
     * bottom row (her collar) along the top.
     */
    private fun toNative(image: BufferedImage): NativeImage {
        val native = NativeImage(image.width + 2, image.height + 2, true)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) native.setPixel(x + 1, y + 1, image.getRGB(x, y))
        }
        return native
    }

    private fun release() {
        for (frame in frames) McCompat.mc.textureManager.release(frame.id)
        frames = emptyList()
    }
}
