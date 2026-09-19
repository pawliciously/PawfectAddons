package dev.pawfect.addons.features.visual.motionblur

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalInt
import kotlin.math.sqrt
import kotlin.math.tan

object MotionBlur {

    private const val UNIFORM_STRIDE = 256

    private val logger = LoggerFactory.getLogger("PawfectAddons/MotionBlur")

    private val config get() = ConfigManager.features.motionBlur

    private var uniformBuffer: GpuBuffer? = null
    private var failed = false

    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var width = 0
    private var height = 0

    private val previousRight = Vector3f(1f, 0f, 0f)
    private val previousUp = Vector3f(0f, 1f, 0f)
    private val previousForward = Vector3f(0f, 0f, 1f)
    private var previousX = 0.0
    private var previousY = 0.0
    private var previousZ = 0.0
    private var primed = false

    private val scratch: ByteBuffer =
        ByteBuffer.allocateDirect(UNIFORM_STRIDE).order(ByteOrder.nativeOrder())

    fun isActive(): Boolean {
        if (failed || !config.enabled) return false
        val client = McCompat.mc
        return client.player != null && client.level != null
    }

    fun invalidate() {
        failed = false
    }

    fun shutdown() {
        runCatching { uniformBuffer?.close() }
        uniformBuffer = null
        releaseTarget()
        primed = false
    }

    private fun releaseTarget() {
        runCatching { view?.close() }
        runCatching { texture?.close() }
        view = null
        texture = null
        width = 0
        height = 0
    }

    @JvmStatic
    fun render() {
        if (!isActive()) {
            primed = false
            return
        }
        runCatching { draw() }.onFailure { fail(it) }
    }

    private fun draw() {
        val target = McCompat.mc.mainRenderTarget
        val depthView = target.depthTextureView ?: return
        val colorView = target.colorTextureView ?: return
        if (!ensureTarget(target.width, target.height)) return
        val sceneView = view ?: return

        val camera = McCompat.mc.gameRenderer.mainCamera
        val forward = Vector3f(camera.forwardVector())
        val up = Vector3f(camera.upVector())
        val right = Vector3f(forward).cross(up).normalize()
        val position = camera.position()

        if (!primed) {
            remember(right, up, forward, position.x, position.y, position.z)
            return
        }

        val turned = 1f - forward.dot(previousForward).coerceIn(-1f, 1f)
        val moved = if (config.includeMovement) {
            val dx = previousX - position.x
            val dy = previousY - position.y
            val dz = previousZ - position.z
            sqrt(dx * dx + dy * dy + dz * dz)
        } else {
            0.0
        }
        if (turned < 1e-7f && moved < 0.0015) {
            remember(right, up, forward, position.x, position.y, position.z)
            return
        }

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        val buffer = ensureBuffer()

        val aspect = target.width.toFloat() / target.height.toFloat()
        writeUniforms(encoder, buffer, camera.fov, aspect, right, up, forward, position.x, position.y, position.z)

        encoder.createRenderPass({ "pawfect_motion_blur" }, sceneView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(MotionBlurPipelines.BLUR)
            pass.bindTexture("ColorSampler", colorView, linear)
            pass.bindTexture("DepthSampler", depthView, nearest)
            pass.setUniform("MotionData", buffer.slice(0L, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }

        encoder.createRenderPass({ "pawfect_motion_blit" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(MotionBlurPipelines.BLIT)
            pass.bindTexture("ColorSampler", sceneView, nearest)
            pass.draw(0, 6)
        }

        remember(right, up, forward, position.x, position.y, position.z)
    }

    private fun remember(right: Vector3f, up: Vector3f, forward: Vector3f, x: Double, y: Double, z: Double) {
        previousRight.set(right)
        previousUp.set(up)
        previousForward.set(forward)
        previousX = x
        previousY = y
        previousZ = z
        primed = true
    }

    private fun ensureTarget(requestedWidth: Int, requestedHeight: Int): Boolean {
        val safeWidth = requestedWidth.coerceAtLeast(1)
        val safeHeight = requestedHeight.coerceAtLeast(1)
        if (safeWidth == width && safeHeight == height && view != null) return true

        releaseTarget()
        val device = RenderSystem.getDevice()
        val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC or
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT
        val created = device.createTexture("pawfect_motion_blur", usage, TextureFormat.RGBA8, safeWidth, safeHeight, 1, 1)
        texture = created
        view = device.createTextureView(created)
        width = safeWidth
        height = safeHeight
        return view != null
    }

    private fun writeUniforms(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        fov: Float,
        aspect: Float,
        right: Vector3f,
        up: Vector3f,
        forward: Vector3f,
        x: Double,
        y: Double,
        z: Double,
    ) {
        val tanHalf = tan(Math.toRadians(fov.coerceIn(1f, 179f).toDouble() / 2.0)).toFloat()
        val far = (McCompat.mc.options.getEffectiveRenderDistance() * 64f).coerceIn(192f, 4096f)

        scratch.clear()
        scratch.putFloat(right.x); scratch.putFloat(right.y); scratch.putFloat(right.z); scratch.putFloat(tanHalf * aspect)
        scratch.putFloat(up.x); scratch.putFloat(up.y); scratch.putFloat(up.z); scratch.putFloat(tanHalf)
        scratch.putFloat(forward.x); scratch.putFloat(forward.y); scratch.putFloat(forward.z); scratch.putFloat(0f)

        scratch.putFloat(previousRight.x); scratch.putFloat(previousRight.y); scratch.putFloat(previousRight.z); scratch.putFloat(0f)
        scratch.putFloat(previousUp.x); scratch.putFloat(previousUp.y); scratch.putFloat(previousUp.z); scratch.putFloat(0f)
        scratch.putFloat(previousForward.x); scratch.putFloat(previousForward.y); scratch.putFloat(previousForward.z); scratch.putFloat(0f)

        scratch.putFloat((previousX - x).toFloat())
        scratch.putFloat((previousY - y).toFloat())
        scratch.putFloat((previousZ - z).toFloat())
        scratch.putFloat(if (config.includeMovement) 1f else 0f)

        scratch.putFloat(config.strength.coerceIn(0f, 2f))
        scratch.putFloat(config.samples.coerceIn(2, 32).toFloat())
        scratch.putFloat(config.maxRadius.coerceIn(0.005f, 0.25f))
        scratch.putFloat(config.shutter.coerceIn(0.1f, 2f))

        scratch.putFloat(0.05f)
        scratch.putFloat(far)
        scratch.putFloat(0f)
        scratch.putFloat(0f)

        while (scratch.position() < UNIFORM_STRIDE) scratch.put(0)
        scratch.flip()

        encoder.writeToBuffer(buffer.slice(0L, UNIFORM_STRIDE.toLong()), scratch)
    }

    private fun ensureBuffer(): GpuBuffer {
        uniformBuffer?.let { return it }
        val created = RenderSystem.getDevice().createBuffer(
            { "pawfect_motion_blur_uniforms" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
            UNIFORM_STRIDE.toLong(),
        )
        uniformBuffer = created
        return created
    }

    private fun fail(error: Throwable) {
        failed = true
        logger.error("Motion blur disabled after an error", error)
        runCatching { ChatUtils.error("Motion blur disabled: ${error::class.simpleName}") }
        runCatching { shutdown() }
    }
}
