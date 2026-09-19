package dev.pawfect.addons.features.visual.skybox

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.SkyboxConfig.SkyEffect
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalInt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object Skybox {

    private const val UNIFORM_STRIDE = 256
    private const val LUT_INTERVAL_MS = 50L
    private const val STAR_CHANCE = 0.11f

    private val logger = LoggerFactory.getLogger("PawfectAddons/Skybox")

    private val config get() = ConfigManager.features.skybox

    private var uniformBuffer: GpuBuffer? = null
    private var failed = false

    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var width = 0
    private var height = 0

    private var scaledTexture: GpuTexture? = null
    private var scaledView: GpuTextureView? = null
    private var scaledWidth = 0
    private var scaledHeight = 0

    private var clock = 0.0
    private var lastFrame = 0L
    private var lastLut = 0L
    private var lastSignature = 0
    private var lutDirty = true

    private val scratch: ByteBuffer =
        ByteBuffer.allocateDirect(UNIFORM_STRIDE).order(ByteOrder.nativeOrder())

    @JvmStatic
    fun isActive(): Boolean {
        config.sanitize()
        if (failed || !config.enabled) return false
        val client = McCompat.mc
        if (client.player == null || client.level == null) return false
        if (config.onlyAtNight && !isNight()) return false
        return true
    }

    fun invalidate() {
        failed = false
        lutDirty = true
    }

    fun shutdown() {
        runCatching { uniformBuffer?.close() }
        uniformBuffer = null
        releaseTarget()
        releaseScaled()
    }

    private fun releaseScaled() {
        runCatching { scaledView?.close() }
        runCatching { scaledTexture?.close() }
        scaledView = null
        scaledTexture = null
        scaledWidth = 0
        scaledHeight = 0
    }

    private fun releaseTarget() {
        runCatching { view?.close() }
        runCatching { texture?.close() }
        view = null
        texture = null
        width = 0
        height = 0
        lutDirty = true
    }

    private fun isNight(): Boolean {
        val level = McCompat.mc.level ?: return false
        val time = level.defaultClockTime % 24000L
        return time in 13000L..23000L
    }

    @JvmStatic
    fun renderSky() {
        if (!isActive()) return
        runCatching { draw() }.onFailure { fail(it) }
    }

    private fun draw() {
        val target = McCompat.mc.mainRenderTarget
        val colorView = target.colorTextureView ?: return

        val quality = config.quality
        if (!ensureTarget(quality.lutWidth, quality.lutHeight)) return
        val lutView = view ?: return

        val now = System.currentTimeMillis()
        val delta = if (lastFrame == 0L) 0.0 else (now - lastFrame).coerceIn(0L, 100L) / 1000.0
        lastFrame = now
        clock += delta * config.speed.coerceIn(0f, 3f)

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val wrap = RenderSystem.getSamplerCache().getSampler(
            AddressMode.REPEAT,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            false,
        )
        val buffer = ensureBuffer()

        // TODO drop the default here if the fps hit turns out to be real
        val scale = config.renderScale.coerceIn(0.25f, 1f)
        val scaled = scale < 0.999f
        if (!scaled && scaledView != null) releaseScaled()

        writeUniforms(encoder, buffer, target.width.toFloat() / target.height.toFloat(), scaled)

        val signature = signature()
        val animated = config.speed > 0.001f && lutAnimates()
        val refresh = lutDirty || signature != lastSignature || (animated && now - lastLut >= LUT_INTERVAL_MS)

        if (refresh) {
            encoder.createRenderPass({ "pawfect_sky_lut" }, lutView, OptionalInt.of(0)).use { pass ->
                pass.setPipeline(SkyboxPipelines.LUT)
                pass.setUniform("SkyData", buffer.slice(0L, UNIFORM_STRIDE.toLong()))
                pass.draw(0, 6)
            }
            lastLut = now
            lastSignature = signature
            lutDirty = false
        }

        if (!scaled) {
            encoder.createRenderPass({ "pawfect_sky_composite" }, colorView, OptionalInt.empty()).use { pass ->
                pass.setPipeline(SkyboxPipelines.COMPOSITE)
                pass.bindTexture("SkySampler", lutView, wrap)
                pass.setUniform("SkyData", buffer.slice(0L, UNIFORM_STRIDE.toLong()))
                pass.draw(0, 6)
            }
            return
        }

        val lowView = ensureScaled(
            (target.width * scale).toInt().coerceAtLeast(1),
            (target.height * scale).toInt().coerceAtLeast(1),
        ) ?: return
        encoder.createRenderPass({ "pawfect_sky_composite_scaled" }, lowView, OptionalInt.of(0)).use { pass ->
            pass.setPipeline(SkyboxPipelines.COMPOSITE)
            pass.bindTexture("SkySampler", lutView, wrap)
            pass.setUniform("SkyData", buffer.slice(0L, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }
        encoder.createRenderPass({ "pawfect_sky_upscale" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(SkyboxPipelines.UPSCALE)
            pass.bindTexture("SkySampler", lowView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
            pass.draw(0, 6)
        }
    }

    private fun lutAnimates(): Boolean =
        config.effect == SkyEffect.LIQUID || config.effect == SkyEffect.NEBULA

    private fun signature(): Int {
        var hash = config.effect.ordinal
        hash = hash * 31 + config.quality.ordinal
        hash = hash * 31 + config.intensity.toRawBits()
        hash = hash * 31 + config.primary()
        hash = hash * 31 + config.secondary()
        hash = hash * 31 + config.accent()
        hash = hash * 31 + config.featureScale.toRawBits()
        hash = hash * 31 + config.seed
        return hash
    }

    private fun ensureTarget(requestedWidth: Int, requestedHeight: Int): Boolean {
        val safeWidth = requestedWidth.coerceAtLeast(1)
        val safeHeight = requestedHeight.coerceAtLeast(1)
        if (safeWidth == width && safeHeight == height && view != null) return true

        releaseTarget()
        val device = RenderSystem.getDevice()
        val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC or
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT
        val created = device.createTexture("pawfect_sky_lut", usage, TextureFormat.RGBA8, safeWidth, safeHeight, 1, 1)
        texture = created
        view = device.createTextureView(created)
        width = safeWidth
        height = safeHeight
        lutDirty = true
        return view != null
    }

    private fun ensureScaled(requestedWidth: Int, requestedHeight: Int): GpuTextureView? {
        if (requestedWidth == scaledWidth && requestedHeight == scaledHeight && scaledView != null) return scaledView
        releaseScaled()
        val device = RenderSystem.getDevice()
        val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT
        val created = device.createTexture("pawfect_sky_scaled", usage, TextureFormat.RGBA8, requestedWidth, requestedHeight, 1, 1)
        scaledTexture = created
        scaledView = device.createTextureView(created)
        scaledWidth = requestedWidth
        scaledHeight = requestedHeight
        return scaledView
    }

    private fun fromAngles(elevationDegrees: Float, azimuthDegrees: Float): Vector3f {
        val elevation = Math.toRadians(elevationDegrees.toDouble())
        val azimuth = Math.toRadians(azimuthDegrees.toDouble())
        val horizontal = cos(elevation)
        return Vector3f(
            (cos(azimuth) * horizontal).toFloat(),
            sin(elevation).toFloat(),
            (sin(azimuth) * horizontal).toFloat(),
        ).normalize()
    }

    private fun keyDirection(): Vector3f = when (config.effect) {
        SkyEffect.GALAXY -> fromAngles(28f, 45f)
        SkyEffect.AURORA -> fromAngles(12f, 0f)
        else -> fromAngles(35f, 90f)
    }

    private fun writeUniforms(encoder: CommandEncoder, buffer: GpuBuffer, aspect: Float, scaled: Boolean) {
        val camera = McCompat.mc.gameRenderer.mainCamera
        val forward = Vector3f(camera.forwardVector())
        val up = Vector3f(camera.upVector())
        val right = Vector3f(forward).cross(up).normalize()

        val fov = camera.fov.coerceIn(1f, 179f)
        val tanHalf = tan(Math.toRadians(fov.toDouble() / 2.0)).toFloat()

        val quality = config.quality
        val key = keyDirection()
        val colour = config.primary()
        val colour2 = config.secondary()
        val colour3 = config.accent()

        scratch.clear()
        scratch.putFloat(right.x); scratch.putFloat(right.y); scratch.putFloat(right.z)
        scratch.putFloat(tanHalf * aspect)

        scratch.putFloat(up.x); scratch.putFloat(up.y); scratch.putFloat(up.z)
        scratch.putFloat(tanHalf)

        scratch.putFloat(forward.x); scratch.putFloat(forward.y); scratch.putFloat(forward.z)
        scratch.putFloat(clock.toFloat())

        scratch.putFloat(key.x); scratch.putFloat(key.y); scratch.putFloat(key.z)
        scratch.putFloat(1f)

        scratch.putFloat(((colour shr 16) and 0xFF) / 255f)
        scratch.putFloat(((colour shr 8) and 0xFF) / 255f)
        scratch.putFloat((colour and 0xFF) / 255f)
        scratch.putFloat(0f)

        scratch.putFloat(config.effect.id.toFloat())
        scratch.putFloat(config.intensity.coerceIn(0f, 1f))
        scratch.putFloat(config.speed.coerceIn(0f, 3f))
        scratch.putFloat(config.brightness.coerceIn(0.2f, 3f))

        scratch.putFloat(quality.marchSteps.toFloat())
        scratch.putFloat(config.featureScale.coerceIn(0.3f, 3f))
        scratch.putFloat(quality.starLayers.toFloat())
        scratch.putFloat(quality.lutHeight.toFloat())

        scratch.putFloat(config.seed.coerceIn(1, 999).toFloat() * 0.618f)
        scratch.putFloat(if (scaled) 1f else 0f)
        scratch.putFloat(1f)
        scratch.putFloat(STAR_CHANCE)

        scratch.putFloat(((colour2 shr 16) and 0xFF) / 255f)
        scratch.putFloat(((colour2 shr 8) and 0xFF) / 255f)
        scratch.putFloat((colour2 and 0xFF) / 255f)
        scratch.putFloat(0f)

        scratch.putFloat(((colour3 shr 16) and 0xFF) / 255f)
        scratch.putFloat(((colour3 shr 8) and 0xFF) / 255f)
        scratch.putFloat((colour3 and 0xFF) / 255f)
        scratch.putFloat(0f)

        while (scratch.position() < UNIFORM_STRIDE) scratch.put(0)
        scratch.flip()

        encoder.writeToBuffer(buffer.slice(0L, UNIFORM_STRIDE.toLong()), scratch)
    }

    private fun ensureBuffer(): GpuBuffer {
        uniformBuffer?.let { return it }
        val created = RenderSystem.getDevice().createBuffer(
            { "pawfect_sky_uniforms" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
            UNIFORM_STRIDE.toLong(),
        )
        uniformBuffer = created
        return created
    }

    private fun fail(error: Throwable) {
        failed = true
        logger.error("Skybox disabled after an error", error)
        runCatching { ChatUtils.error("Skybox disabled: ${error::class.simpleName}") }
        runCatching { shutdown() }
    }
}
