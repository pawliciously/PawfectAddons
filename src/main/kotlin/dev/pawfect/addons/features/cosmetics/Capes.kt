package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.mixin.AbstractTextureAccessor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Optional
import java.util.OptionalDouble
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.ImageInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object Capes {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Capes")

    private const val SCALE_HIGH = 16
    private const val SCALE_MID = 12
    private const val SCALE_LOW = 8
    private const val MAX_FRAMES = 48
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val MAX_CAPES = 24
    private const val DEFAULT_DELAY_MS = 100
    private const val MIN_DELAY_MS = 20
    private const val IDLE_MS = 3_000L

    private const val FACE_W = 10
    private const val FACE_H = 16
    private const val SHEET_W = 64
    private const val SHEET_H = 32

    private val config get() = ConfigManager.features.cosmetics

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val loaded = LinkedHashMap<String, Cape>()
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()
    private val finished = ConcurrentLinkedQueue<Baked>()

    @Volatile
    private var wanted: Set<String>? = null

    @JvmStatic
    fun apply(uuid: UUID, state: AvatarRenderState) {
        val mode = config.capes
        if (!config.enabled || mode == Visibility.NONE) return
        if (mode == Visibility.SELF && uuid != McCompat.mc.player?.uuid) return

        val url = Cosmetics.capeFor(uuid) ?: return
        val cape = obtain(url) ?: return
        cape.lastSeen = System.currentTimeMillis()
        if (cape.texture == null) return

        val patch = PlayerSkin.Patch.create(
            Optional.empty(),
            Optional.of(ClientAsset.ResourceTexture(cape.id, cape.id)),
            Optional.empty(),
            Optional.empty(),
        )
        state.skin = state.skin.with(patch)
        state.showCape = true
    }

    fun retain(urls: Set<String>) {
        wanted = urls
    }

    fun onTick() {
        drain()
        prune()
        animate()
    }

    private fun obtain(url: String): Cape? {
        loaded[url]?.let { return it }
        if (url in failed || url in pending) return null
        if (loaded.size + pending.size >= MAX_CAPES) return null
        pending.add(url)
        Thread({ work(url) }, "PawfectAddons Cape").apply { isDaemon = true }.start()
        return null
    }

    private fun work(url: String) {
        val baked = runCatching { bake(url) }.getOrElse {
            logger.warn("Could not load the cape at {}.", url, it)
            null
        }
        finished.add(baked ?: Baked(url, null, emptyList(), IntArray(0), 0))
    }

    private fun bake(url: String): Baked? {
        val bytes = download(url) ?: return null
        val decoded = decode(bytes) ?: return null
        if (decoded.frames.isEmpty()) return null

        val scale = scaleFor(decoded.frames.size)
        val width = FACE_W * scale
        val height = FACE_H * scale

        val frames = decoded.frames.map { resample(it, width, height) }
        val sheet = NativeImage(SHEET_W * scale, SHEET_H * scale, false)
        sheet.fillRect(0, 0, sheet.width, sheet.height, 0)
        paint(sheet, frames[0], scale)

        return Baked(url, sheet, frames, decoded.delays, scale)
    }

    private fun scaleFor(frames: Int): Int = when {
        frames <= 8 -> SCALE_HIGH
        frames <= 24 -> SCALE_MID
        else -> SCALE_LOW
    }

    private fun drain() {
        while (true) {
            val baked = finished.poll() ?: return
            pending.remove(baked.url)

            if (baked.sheet == null || baked.frames.isEmpty()) {
                failed.add(baked.url)
                continue
            }

            if (loaded.containsKey(baked.url)) {
                baked.close()
                continue
            }

            val cape = Cape(identifierFor(baked.url), baked.sheet, baked.frames, baked.delays, baked.scale)
            val texture = runCatching {
                val created = DynamicTexture({ "pawfect_cape" }, cape.sheet)
                McCompat.mc.textureManager.register(cape.id, created)
                smooth(created)
                created
            }.getOrElse {
                logger.warn("Could not register the cape texture for {}.", baked.url, it)
                cape.closeFrames()
                runCatching { cape.sheet.close() }
                failed.add(baked.url)
                null
            } ?: continue

            cape.texture = texture
            cape.lastSeen = System.currentTimeMillis()
            loaded[baked.url] = cape
        }
    }

    private fun prune() {
        wanted?.let { keep ->
            wanted = null
            failed.retainAll(keep)
            loaded.keys.toList().forEach { if (it !in keep) release(it) }
        }

        while (loaded.size > MAX_CAPES) {
            val oldest = loaded.entries.minByOrNull { it.value.lastSeen } ?: break
            release(oldest.key)
        }
    }

    // TODO swap for a real cloth sim eventually
    private fun animate() {
        if (!config.animate) return
        val now = System.currentTimeMillis()

        for (cape in loaded.values) {
            if (cape.frames.size < 2) continue
            if (now - cape.lastSeen > IDLE_MS) continue
            val target = frameAt(cape, now)
            if (target == cape.index) continue
            show(cape, target)
        }
    }

    private fun frameAt(cape: Cape, now: Long): Int {
        var position = (now % cape.total).toInt()
        for (index in cape.delays.indices) {
            position -= cape.delays[index]
            if (position < 0) return index
        }
        return cape.delays.size - 1
    }

    private fun show(cape: Cape, index: Int) {
        val texture = cape.texture ?: return
        paint(cape.sheet, cape.frames[index], cape.scale)
        runCatching { texture.upload() }.onFailure {
            logger.warn("Could not upload a cape frame.", it)
            return
        }
        cape.index = index
    }

    private fun release(url: String) {
        val cape = loaded.remove(url) ?: return
        runCatching { McCompat.mc.textureManager.release(cape.id) }
        runCatching { cape.texture?.close() }
        cape.closeFrames()
    }

    private fun paint(sheet: NativeImage, frame: NativeImage, scale: Int) {
        val width = FACE_W * scale
        val height = FACE_H * scale

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = frame.getPixel(x, y)
                sheet.setPixel(scale + x, scale + y, pixel)
                sheet.setPixel(12 * scale + width - 1 - x, scale + y, shade(pixel))
            }
        }

        for (y in 0 until height) {
            val left = shade(frame.getPixel(0, y))
            val right = shade(frame.getPixel(width - 1, y))
            for (x in 0 until scale) {
                sheet.setPixel(x, scale + y, left)
                sheet.setPixel(11 * scale + x, scale + y, right)
            }
        }

        for (x in 0 until width) {
            val top = shade(frame.getPixel(x, 0))
            val bottom = shade(frame.getPixel(x, height - 1))
            for (y in 0 until scale) {
                sheet.setPixel(scale + x, y, top)
                sheet.setPixel(11 * scale + x, y, bottom)
            }
        }
    }

    private fun shade(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        val red = (((argb ushr 16) and 0xFF) * 3) / 4
        val green = (((argb ushr 8) and 0xFF) * 3) / 4
        val blue = ((argb and 0xFF) * 3) / 4
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun smooth(texture: DynamicTexture) {
        runCatching {
            val sampler = RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty(),
            )
            (texture as AbstractTextureAccessor).`pawfectaddons$setSampler`(sampler)
        }.onFailure { logger.warn("Falling back to point filtering for capes.", it) }
    }

    private fun identifierFor(url: String): Identifier {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "capes/$name")
    }

    private fun download(url: String): ByteArray? {
        val uri = URI.create(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) return null

        val request = HttpRequest.newBuilder(uri)
            .header("User-Agent", "PawfectAddons")
            .header("Accept", "image/png,image/gif,image/jpeg")
            .timeout(Duration.ofSeconds(25))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            logger.warn("Cape request returned HTTP {} for {}", response.statusCode(), url)
            response.body().close()
            return null
        }

        response.body().use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (buffer.size() + read > MAX_BYTES) {
                    logger.warn("Cape at {} is larger than {} bytes.", url, MAX_BYTES)
                    return null
                }
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): Decoded? {
        if (isGif(bytes)) {
            decodeGif(bytes)?.let { return it }
        }
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return Decoded(listOf(image), intArrayOf(DEFAULT_DELAY_MS))
    }

    private fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
    }

    private fun decodeGif(bytes: ByteArray): Decoded? {
        val readers = ImageIO.getImageReadersByFormatName("gif")
        if (!readers.hasNext()) return null

        val reader = readers.next()
        var stream: ImageInputStream? = null
        try {
            stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
            reader.setInput(stream, false, false)

            val count = reader.getNumImages(true)
            if (count <= 0) return null

            val descriptors = (0 until count).map { describe(reader, it) }
            var width = 0
            var height = 0
            for (index in 0 until count) {
                width = max(width, descriptors[index].x + reader.getWidth(index))
                height = max(height, descriptors[index].y + reader.getHeight(index))
            }
            if (width <= 0 || height <= 0) return null

            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = canvas.createGraphics()
            val frames = ArrayList<BufferedImage>()
            val delays = ArrayList<Int>()
            val step = max(1, ceil(count.toDouble() / MAX_FRAMES).toInt())

            var previous: BufferedImage? = null
            var carried = 0

            for (index in 0 until count) {
                val descriptor = descriptors[index]
                if (descriptor.disposal == RESTORE_PREVIOUS) previous = copy(canvas)

                val frame = reader.read(index)
                graphics.composite = AlphaComposite.SrcOver
                graphics.drawImage(frame, descriptor.x, descriptor.y, null)

                carried += descriptor.delayMs
                if (index % step == step - 1 || index == count - 1) {
                    frames.add(copy(canvas))
                    delays.add(max(MIN_DELAY_MS, carried))
                    carried = 0
                }

                when (descriptor.disposal) {
                    RESTORE_BACKGROUND -> {
                        graphics.composite = AlphaComposite.Clear
                        graphics.fillRect(descriptor.x, descriptor.y, frame.width, frame.height)
                    }

                    RESTORE_PREVIOUS -> previous?.let {
                        graphics.composite = AlphaComposite.Src
                        graphics.drawImage(it, 0, 0, null)
                    }
                }
            }

            graphics.dispose()
            if (frames.isEmpty()) return null
            return Decoded(frames, delays.toIntArray())
        } finally {
            runCatching { reader.dispose() }
            runCatching { stream?.close() }
        }
    }

    private fun describe(reader: ImageReader, index: Int): GifFrame {
        val root = reader.getImageMetadata(index).getAsTree(GIF_METADATA) as IIOMetadataNode

        var x = 0
        var y = 0
        var delay = DEFAULT_DELAY_MS
        var disposal = 0

        child(root, "ImageDescriptor")?.let {
            x = it.getAttribute("imageLeftPosition").toIntOrNull() ?: 0
            y = it.getAttribute("imageTopPosition").toIntOrNull() ?: 0
        }

        child(root, "GraphicControlExtension")?.let {
            val hundredths = it.getAttribute("delayTime").toIntOrNull() ?: 0
            delay = if (hundredths <= 0) DEFAULT_DELAY_MS else hundredths * 10
            disposal = when (it.getAttribute("disposalMethod")) {
                "restoreToBackgroundColor" -> RESTORE_BACKGROUND
                "restoreToPrevious" -> RESTORE_PREVIOUS
                else -> 0
            }
        }

        return GifFrame(max(0, x), max(0, y), delay.coerceAtLeast(MIN_DELAY_MS), disposal)
    }

    private fun child(parent: IIOMetadataNode, name: String): IIOMetadataNode? {
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is IIOMetadataNode && node.nodeName == name) return node
        }
        return null
    }

    private fun copy(source: BufferedImage): BufferedImage {
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        graphics.composite = AlphaComposite.Src
        graphics.drawImage(source, 0, 0, null)
        graphics.dispose()
        return result
    }

    private fun resample(source: BufferedImage, width: Int, height: Int): NativeImage {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val pixels = IntArray(sourceWidth * sourceHeight)
        source.getRGB(0, 0, sourceWidth, sourceHeight, pixels, 0, sourceWidth)

        val ratio = width.toDouble() / height
        var cropWidth = sourceWidth.toDouble()
        var cropHeight = sourceHeight.toDouble()
        if (sourceWidth.toDouble() / sourceHeight > ratio) {
            cropWidth = sourceHeight * ratio
        } else {
            cropHeight = sourceWidth / ratio
        }

        val offsetX = (sourceWidth - cropWidth) / 2.0
        val offsetY = (sourceHeight - cropHeight) / 2.0
        val stepX = cropWidth / width
        val stepY = cropHeight / height

        val result = NativeImage(width, height, false)
        for (y in 0 until height) {
            val top = offsetY + y * stepY
            for (x in 0 until width) {
                val left = offsetX + x * stepX
                result.setPixel(
                    x,
                    y,
                    sample(pixels, sourceWidth, sourceHeight, left, top, left + stepX, top + stepY),
                )
            }
        }
        return result
    }

    private fun sample(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Int {
        if (right - left <= 1.0 && bottom - top <= 1.0) {
            return bilinear(pixels, width, height, (left + right) / 2.0 - 0.5, (top + bottom) / 2.0 - 0.5)
        }

        var alpha = 0.0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0

        var y = floor(top).toInt()
        while (y < bottom) {
            val rowHeight = min(bottom, (y + 1).toDouble()) - max(top, y.toDouble())
            val row = y.coerceIn(0, height - 1) * width
            var x = floor(left).toInt()
            while (x < right) {
                val area = (min(right, (x + 1).toDouble()) - max(left, x.toDouble())) * rowHeight
                if (area > 0.0) {
                    val pixel = pixels[row + x.coerceIn(0, width - 1)]
                    val coverage = (((pixel ushr 24) and 0xFF) / 255.0) * area
                    alpha += coverage
                    red += ((pixel ushr 16) and 0xFF) * coverage
                    green += ((pixel ushr 8) and 0xFF) * coverage
                    blue += (pixel and 0xFF) * coverage
                    weight += area
                }
                x++
            }
            y++
        }

        if (weight <= 0.0) return 0
        return unmix(alpha / weight, red, green, blue, alpha)
    }

    private fun bilinear(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val leftIndex = floor(x).toInt()
        val topIndex = floor(y).toInt()
        val fractionX = x - leftIndex
        val fractionY = y - topIndex

        var alpha = 0.0
        var red = 0.0
        var green = 0.0
        var blue = 0.0

        for (step in 0 until 4) {
            val offsetX = step and 1
            val offsetY = step shr 1
            val share = (if (offsetX == 0) 1.0 - fractionX else fractionX) *
                (if (offsetY == 0) 1.0 - fractionY else fractionY)
            if (share <= 0.0) continue

            val column = (leftIndex + offsetX).coerceIn(0, width - 1)
            val row = (topIndex + offsetY).coerceIn(0, height - 1)
            val pixel = pixels[row * width + column]
            val coverage = (((pixel ushr 24) and 0xFF) / 255.0) * share

            alpha += coverage
            red += ((pixel ushr 16) and 0xFF) * coverage
            green += ((pixel ushr 8) and 0xFF) * coverage
            blue += (pixel and 0xFF) * coverage
        }

        return unmix(alpha, red, green, blue, alpha)
    }

    private fun unmix(opacity: Double, red: Double, green: Double, blue: Double, coverage: Double): Int {
        if (coverage <= 0.0) return 0
        val a = (opacity.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        val r = ((red / coverage).coerceIn(0.0, 255.0) + 0.5).toInt()
        val g = ((green / coverage).coerceIn(0.0, 255.0) + 0.5).toInt()
        val b = ((blue / coverage).coerceIn(0.0, 255.0) + 0.5).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val GIF_METADATA = "javax_imageio_gif_image_1.0"
    private const val RESTORE_BACKGROUND = 2
    private const val RESTORE_PREVIOUS = 3

    private class GifFrame(val x: Int, val y: Int, val delayMs: Int, val disposal: Int)

    private class Decoded(val frames: List<BufferedImage>, val delays: IntArray)

    private class Baked(
        val url: String,
        val sheet: NativeImage?,
        val frames: List<NativeImage>,
        val delays: IntArray,
        val scale: Int,
    ) {
        fun close() {
            runCatching { sheet?.close() }
            frames.forEach { runCatching { it.close() } }
        }
    }

    private class Cape(
        val id: Identifier,
        val sheet: NativeImage,
        val frames: List<NativeImage>,
        val delays: IntArray,
        val scale: Int,
    ) {
        var texture: DynamicTexture? = null
        var index = 0
        var lastSeen = 0L

        val total: Int = delays.sum().coerceAtLeast(1)

        fun closeFrames() {
            frames.forEach { runCatching { it.close() } }
        }
    }
}
