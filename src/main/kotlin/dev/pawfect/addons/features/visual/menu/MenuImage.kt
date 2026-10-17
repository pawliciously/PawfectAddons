package dev.pawfect.addons.features.visual.menu

import com.mojang.blaze3d.platform.NativeImage
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.GuiPositionEditorScreen
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadata

/**
 * A picture or animated gif in a corner of the screen in every menu (not chat), drawn behind the menu's own
 * window. It comes from a link (downloaded once and cached) or a file in config/pawfectaddons/menu-images;
 * left empty it's the dancing Zelda gif. Moved and resized in the position editor like any HUD element.
 */
object MenuImage {

    /** The default, fetched on first use rather than shipped in the mod. */
    const val DEFAULT_URL = "https://gifdb.com/images/high/princess-zelda-dancing-qr4xllxpu42ajb01.gif"

    private val logger = LoggerFactory.getLogger("PawfectAddons/MenuImage")
    val folder: Path get() = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("menu-images")
    private val cacheFolder: Path get() = folder.resolve(".cache")

    /** Height in GUI pixels at scale 1. */
    private const val BASE_HEIGHT = 120
    /** Frames are shrunk to this many pixels on their longest side before upload. */
    private const val FRAME_SIZE = 320
    private const val MAX_FRAMES = 300
    private const val MAX_DOWNLOAD = 25L * 1024 * 1024
    /** A typed source has to sit still this long before it's loaded, so half a link isn't fetched. */
    private const val SETTLE_MS = 800L
    private val EXTENSIONS = setOf("png", "gif", "jpg", "jpeg", "bmp")

    private class Frame(val id: Identifier, val delayMs: Int)
    private class Decoded(val key: String, val frames: List<Pair<BufferedImage, Int>>)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PawfectAddons MenuImage").apply { isDaemon = true } }
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    private var frames: List<Frame> = emptyList()
    private var width = 0
    private var height = 0
    private var totalMs = 1
    /** What's showing (or loading): source plus cutout setting plus file time. */
    private var key: String? = null
    @Volatile private var ready: Decoded? = null
    @Volatile private var loading: String? = null
    private var seenSource = ""
    private var seenAt = 0L
    private var lastCheck = 0L

    @Volatile
    var status: String = "Not loaded yet."
        private set

    private val config get() = ConfigManager.features.menu

    /** Called right after a screen draws its background, so the screen's window goes on top. */
    @JvmStatic
    fun renderBehind(screen: Screen, graphics: GuiGraphicsExtractor) {
        if (!config.imageEnabled || screen is ChatScreen || screen is GuiPositionEditorScreen) return
        draw(graphics)
    }

    /** The position editor draws its own backdrop, so it calls this to show the picture while you move it. */
    @JvmStatic
    fun renderInEditor(graphics: GuiGraphicsExtractor) {
        if (config.imageEnabled) draw(graphics)
    }

    private fun draw(graphics: GuiGraphicsExtractor) {
        update()
        if (frames.isEmpty()) return
        val position = config.imagePosition
        val h = (BASE_HEIGHT * position.effectiveScale).toInt().coerceAtLeast(8)
        val w = (h * width.toFloat() / height).toInt().coerceAtLeast(1)
        val x = position.getAbsX(w)
        val y = position.getAbsY(h)
        var t = (System.currentTimeMillis() % totalMs).toInt()
        val frame = frames.firstOrNull { t -= it.delayMs; t < 0 } ?: frames.last()
        val alpha = (config.imageOpacity.coerceIn(0.05f, 1f) * 255).toInt()
        graphics.blit(RenderPipelines.GUI_TEXTURED, frame.id, x, y, 0f, 0f, w, h, width, height, width, height, (alpha shl 24) or 0xFFFFFF)
        GuiEditManager.register(position, HudDefaults.MENU_IMAGE, w, h)
    }

    /** Picks up a new source or a changed file, and uploads frames a background load has finished. */
    private fun update() {
        ready?.let { done ->
            ready = null
            if (done.key == key) upload(done)
        }
        val now = System.currentTimeMillis()
        val source = config.imageSource.trim()
        if (source != seenSource) {
            seenSource = source
            seenAt = now
        }
        if (now - seenAt < SETTLE_MS || now - lastCheck < 500) return
        lastCheck = now
        val wanted = wantedKey(source)
        if (wanted == key) return
        key = wanted
        release()
        loading = wanted
        status = "Loading..."
        val cut = config.imageCutWhite
        worker.execute { load(wanted, source, cut) }
    }

    private fun wantedKey(source: String): String {
        val local = localFile(source)
        val stamp = local?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) } ?: 0L
        return "$source|${config.imageCutWhite}|$stamp"
    }

    private fun localFile(source: String): Path? {
        if (source.isEmpty() || isLink(source)) return null
        val path = runCatching { Path.of(source) }.getOrNull() ?: return null
        return if (path.isAbsolute) path else folder.resolve(source)
    }

    private fun isLink(source: String) = source.startsWith("http://", true) || source.startsWith("https://", true)

    // Background work

    private fun load(key: String, source: String, cut: Boolean) {
        runCatching {
            val file = when {
                source.isEmpty() -> download(DEFAULT_URL)
                isLink(source) -> download(source)
                else -> localFile(source)!!.also { require(Files.isRegularFile(it)) { "No file called $source in the menu-images folder." } }
            }
            val decoded = decode(file, cut)
            require(decoded.isNotEmpty()) { "That file has no pictures in it." }
            if (loading == key) {
                ready = Decoded(key, decoded)
                status = "Showing ${if (source.isEmpty()) "the default" else source.substringAfterLast('/')}: ${decoded.size} frame(s)."
            }
        }.onFailure {
            logger.warn("Could not load the menu image {}", source.ifEmpty { DEFAULT_URL }, it)
            if (loading == key) status = "Couldn't load it: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** Fetches a link once; later loads use the copy in menu-images/.cache. */
    private fun download(url: String): Path {
        Files.createDirectories(cacheFolder)
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = cacheFolder.resolve(name)
        if (Files.isRegularFile(cached) && Files.size(cached) > 0) return cached
        status = "Downloading..."
        val uri = URI.create(url)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (PawfectAddons)")
            // Some gif sites only serve the file to their own pages
            .header("Referer", "${uri.scheme}://${uri.host}/")
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() in 200..299) { "The link answered ${response.statusCode()}." }
        val bytes = response.body().use { it.readNBytes((MAX_DOWNLOAD + 1).toInt()) }
        require(bytes.size <= MAX_DOWNLOAD) { "That file is over 25 MB." }
        require(looksLikeImage(bytes)) { "That link isn't a picture (try the direct .gif or .png link)." }
        val temp = cacheFolder.resolve("$name.part")
        Files.write(temp, bytes)
        Files.move(temp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return cached
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val bmp = b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()
        return gif || png || jpg || bmp
    }

    /** Every frame drawn onto the full canvas (gif frames are often only the part that changed), with its delay. */
    private fun decode(path: Path, cut: Boolean): List<Pair<BufferedImage, Int>> {
        Files.newInputStream(path).use { stream ->
            ImageIO.createImageInputStream(stream).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                    ?: throw IllegalArgumentException("Not a picture Minecraft can read (use png, gif or jpg).")
                try {
                    reader.input = input
                    if (reader.formatName.lowercase() != "gif") return listOf(finish(reader.read(0), cut) to 0)
                    val count = minOf(reader.getNumImages(true), MAX_FRAMES)
                    var canvasW = reader.getWidth(0)
                    var canvasH = reader.getHeight(0)
                    reader.streamMetadata?.let { meta ->
                        val screen = (meta.getAsTree("javax_imageio_gif_stream_1.0") as Element).getElementsByTagName("LogicalScreenDescriptor").item(0) as? Element
                        canvasW = screen?.getAttribute("logicalScreenWidth")?.toIntOrNull() ?: canvasW
                        canvasH = screen?.getAttribute("logicalScreenHeight")?.toIntOrNull() ?: canvasH
                    }
                    val canvas = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)
                    val out = ArrayList<Pair<BufferedImage, Int>>()
                    for (i in 0 until count) {
                        val image = reader.read(i)
                        val info = frameInfo(reader.getImageMetadata(i))
                        val before = if (info.disposal == "restoreToPrevious") copy(canvas) else null
                        canvas.createGraphics().apply { drawImage(image, info.x, info.y, null); dispose() }
                        out.add(finish(copy(canvas), cut) to (if (info.delayCs <= 1) 100 else info.delayCs * 10))
                        when (info.disposal) {
                            "restoreToBackgroundColor" -> canvas.createGraphics().apply {
                                composite = AlphaComposite.Clear
                                fillRect(info.x, info.y, image.width, image.height)
                                dispose()
                            }
                            "restoreToPrevious" -> before?.let {
                                canvas.createGraphics().apply { composite = AlphaComposite.Src; drawImage(it, 0, 0, null); dispose() }
                            }
                        }
                    }
                    return out
                } finally {
                    reader.dispose()
                }
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

    private fun finish(image: BufferedImage, cut: Boolean): BufferedImage {
        val argb = copy(image)
        if (cut) {
            trimTopLine(argb)
            cutWhite(argb)
        }
        return shrunk(argb)
    }

    private fun background(argb: Int): Boolean =
        (argb ushr 24) < 16 || ((argb shr 16 and 0xFF) >= 235 && (argb shr 8 and 0xFF) >= 235 && (argb and 0xFF) >= 235)

    /**
     * Some gifs carry a thin line along the very top (the Zelda one does, 2 px across nearly the whole width in
     * most frames). Any of the first few rows that runs across more than 60% of the picture is cleared.
     */
    private fun trimTopLine(image: BufferedImage) {
        for (y in 0 until minOf(4, image.height)) {
            var minX = -1
            var maxX = -1
            for (x in 0 until image.width) {
                if (background(image.getRGB(x, y))) continue
                if (minX < 0) minX = x
                maxX = x
            }
            if (minX >= 0 && maxX - minX > image.width * 0.6) for (x in 0 until image.width) image.setRGB(x, y, 0)
        }
    }

    /** Clears near-white pixels joined to the edge of the picture: the background, not white bits inside it. */
    private fun cutWhite(image: BufferedImage) {
        val w = image.width
        val h = image.height
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
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
            val x = i % w
            val y = i / w
            if (seen[i] || !background(image.getRGB(x, y))) continue
            seen[i] = true
            image.setRGB(x, y, 0)
            if (x > 0) stack.add(i - 1)
            if (x < w - 1) stack.add(i + 1)
            if (y > 0) stack.add(i - w)
            if (y < h - 1) stack.add(i + w)
        }
    }

    private fun shrunk(image: BufferedImage): BufferedImage {
        val scale = FRAME_SIZE.toDouble() / maxOf(image.width, image.height)
        if (scale >= 1.0) return image
        val out = BufferedImage((image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
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

    // Render thread

    private fun upload(done: Decoded) {
        release()
        frames = done.frames.mapIndexed { i, (image, delay) ->
            val id = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "menu_image_$i")
            McCompat.mc.textureManager.register(id, DynamicTexture({ "pawfect_menu_image_$i" }, toNative(image)))
            Frame(id, delay)
        }
        width = done.frames.first().first.width + 2
        height = done.frames.first().first.height + 2
        totalMs = frames.sumOf { it.delayMs }.coerceAtLeast(1)
    }

    /**
     * With a clear 1 px border: scaled drawing samples past the edge and wraps around, which would put a line of
     * the bottom row along the top.
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

    // Settings buttons

    /** Picture files in the folder, sorted by name. */
    fun folderFiles(): List<String> = runCatching {
        Files.createDirectories(folder)
        Files.list(folder).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.').lowercase() in EXTENSIONS }
                .map { it.fileName.toString() }.sorted().toList()
        }
    }.getOrDefault(emptyList())

    /** Switches to the next picture in the folder. False if the folder is empty. */
    fun nextFromFolder(): Boolean {
        val files = folderFiles()
        if (files.isEmpty()) return false
        val at = files.indexOf(config.imageSource.trim())
        config.imageSource = files[(at + 1) % files.size]
        return true
    }

    fun openFolder(): Result<Unit> = runCatching {
        Files.createDirectories(folder)
        Util.getPlatform().openPath(folder)
    }
}
