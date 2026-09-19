package dev.pawfect.addons.features.visual.handchams

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.HandChamsConfig
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.ColorInt
import dev.pawfect.addons.utils.McCompat
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalInt

object HandChams {

    private const val UNIFORM_STRIDE = 256
    private const val MAX_UNIFORM_SLICES = 32
    private const val HAND_DEPTH_LIMIT = 0.999999f

    private val END_SKY = Identifier.withDefaultNamespace("textures/environment/end_sky.png")
    private val END_PORTAL = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png")

    private val logger = LoggerFactory.getLogger("PawfectAddons/HandChams")

    private val config get() = ConfigManager.features.handChams

    private var uniformBuffer: GpuBuffer? = null
    private var sliceCursor = 0
    private var failed = false

    private var lastError: String? = null
    private var compositedFrames = 0L
    private var skipReason = "not run yet"
    private val startedAt = System.currentTimeMillis()

    private val scratch: ByteBuffer =
        ByteBuffer.allocateDirect(UNIFORM_STRIDE).order(ByteOrder.nativeOrder())

    @JvmStatic
    val isEnabled: Boolean
        get() {
            if (failed || !config.anyActive) return false
            val client = McCompat.mc
            if (client.player == null || client.level == null) return false
            return client.options.cameraType.isFirstPerson
        }

    @JvmStatic
    fun hideEnchantGlint(): Boolean =
        runCatching { ConfigManager.features.handChams.hideEnchantGlint }.getOrDefault(false)

    fun invalidate() {
        failed = false
        lastError = null
    }

    fun status(): List<String> {
        val target = runCatching { McCompat.mc.mainRenderTarget }.getOrNull()
        return listOf(
            "active=${config.anyActive} body=${config.bodyActive} failed=$failed",
            "composites=$compositedFrames lastSkip=$skipReason",
            "resolution=${if (config.halfResolution) "half" else "full"} copies=0",
            "targets ready=${ChamsTargets.ready} full=${ChamsTargets.fullWidth}x${ChamsTargets.fullHeight}",
            "mainTarget=${target?.width}x${target?.height} depth=${target?.depthTexture != null}",
            "levels=" + ChamsTargets.levels.joinToString(",") { "${it.width}x${it.height}" },
            "lastError=" + (lastError ?: "none"),
        )
    }

    fun shutdown() {
        runCatching {
            ChamsTargets.close()
            uniformBuffer?.close()
        }
        uniformBuffer = null
    }

    @JvmStatic
    fun renderEffect() {
        if (!isEnabled) {
            skipReason = if (failed) "failed" else if (!config.anyActive) "no effects enabled" else "not first person"
            return
        }
        runCatching {
            if (runPasses()) compositedFrames++
        }.onFailure { fail("rendering hand chams", it) }
    }

    private fun runPasses(): Boolean {
        val target = McCompat.mc.mainRenderTarget
        val depthView = target.depthTextureView
        val colorView = target.colorTextureView
        if (depthView == null || colorView == null) {
            skipReason = "main render target has no colour or depth view"
            return false
        }

        val divisor = if (config.halfResolution) 2 else 1
        if (!ChamsTargets.ensure(target.width, target.height, divisor)) {
            skipReason = "could not allocate chams textures"
            return false
        }

        sliceCursor = 0
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        val buffer = ensureUniformBuffer()

        val mask = ChamsTargets.mask!!
        val glow = ChamsTargets.glow!!
        val row = ChamsTargets.outlineRow!!

        val maskSlice = nextSlice()
        writeVec4(encoder, buffer, maskSlice, HAND_DEPTH_LIMIT, 0f, 0f, 0f)

        encoder.createRenderPass({ "pawfect_chams_mask" }, mask.view, OptionalInt.of(0)).use { pass ->
            pass.setPipeline(ChamsPipelines.MASK)
            pass.bindTexture("DepthSampler", depthView, sampler)
            pass.setUniform("MaskData", buffer.slice(maskSlice, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }

        val glowSource = if (config.edgeSoftness > 0.002f) {
            val glowResult = pyramidBlur(encoder, buffer, sampler, mask.view, mask.width, mask.height, 1, 1f)
            encoder.copyTextureToTexture(glowResult.texture, glow.texture, 0, 0, 0, 0, 0, glow.width, glow.height)
            glow.view
        } else {
            mask.view
        }

        runTrail(encoder, buffer, sampler, mask)
        runOutlineRow(encoder, buffer, nearest, mask, row)

        val blurResult = blitBlur(
            encoder, buffer, sampler, colorView, target.width, target.height,
            ChamsTargets.levels[0], ChamsPipelines.KAWASE_DOWN, 0.1f,
        )

        val compositeSlice = nextSlice()
        writeComposite(encoder, buffer, compositeSlice, mask.width, mask.height)

        val portalSky = portalTexture(END_SKY) ?: mask.view
        val portal = portalTexture(END_PORTAL) ?: mask.view
        val repeat = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)

        encoder.createRenderPass({ "pawfect_chams_composite" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(ChamsPipelines.COMPOSITE)
            pass.bindTexture("BlurSampler", blurResult.view, sampler)
            pass.bindTexture("MaskSampler", mask.view, sampler)
            pass.bindTexture("GlowSampler", glowSource, sampler)
            pass.bindTexture("PortalSkySampler", portalSky, repeat)
            pass.bindTexture("PortalSampler", portal, repeat)
            pass.bindTexture("TrailSampler", (ChamsTargets.trail ?: mask).view, sampler)
            pass.bindTexture("RowSampler", row.view, sampler)
            pass.setUniform("CompositeData", buffer.slice(compositeSlice, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }

        if (config.trailEnabled) ChamsTargets.swapTrail()

        skipReason = "ok"
        return true
    }

    private fun runOutlineRow(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        sampler: GpuSampler,
        mask: Surface,
        row: Surface,
    ) {
        if (!config.outlineEnabled) return

        val slice = nextSlice()
        val reach = kotlin.math.ceil(config.outlineThickness.coerceIn(0.5f, 6f))
        writeVec4(encoder, buffer, slice, 0f, 0f, reach, 0f)

        encoder.createRenderPass({ "pawfect_chams_outline_row" }, row.view, OptionalInt.of(0)).use { pass ->
            pass.setPipeline(ChamsPipelines.OUTLINE_ROW)
            pass.bindTexture("MaskSampler", mask.view, sampler)
            pass.setUniform("RowData", buffer.slice(slice, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }
    }

    private fun runTrail(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        sampler: GpuSampler,
        mask: Surface,
    ) {
        val trail = ChamsTargets.trail ?: return
        val history = ChamsTargets.trailHistory ?: return
        if (!config.trailEnabled) return

        val slice = nextSlice()
        writeVec4(encoder, buffer, slice, config.trailDecay.coerceIn(0.1f, 0.99f), 0f, 0f, 0f)

        encoder.createRenderPass({ "pawfect_chams_trail" }, trail.view, OptionalInt.of(0)).use { pass ->
            pass.setPipeline(ChamsPipelines.TRAIL)
            pass.bindTexture("MaskSampler", mask.view, sampler)
            pass.bindTexture("HistorySampler", history.view, sampler)
            pass.setUniform("TrailData", buffer.slice(slice, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }
    }


    private fun pyramidBlur(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        sampler: GpuSampler,
        sourceView: GpuTextureView,
        sourceWidth: Int,
        sourceHeight: Int,
        depth: Int,
        radius: Float,
    ): Surface {
        val levels = ChamsTargets.levels
        val steps = depth.coerceIn(1, levels.size - 1)
        val offset = radius.coerceIn(0.1f, 6f)

        var currentView = sourceView
        var currentWidth = sourceWidth
        var currentHeight = sourceHeight
        var result = levels[0]

        for (level in 1..steps) {
            result = blitBlur(
                encoder, buffer, sampler, currentView, currentWidth, currentHeight,
                levels[level], ChamsPipelines.KAWASE_DOWN, offset,
            )
            currentView = result.view
            currentWidth = result.width
            currentHeight = result.height
        }
        for (level in steps - 1 downTo 0) {
            result = blitBlur(
                encoder, buffer, sampler, currentView, currentWidth, currentHeight,
                levels[level], ChamsPipelines.KAWASE_UP, offset,
            )
            currentView = result.view
            currentWidth = result.width
            currentHeight = result.height
        }
        return result
    }

    private fun blitBlur(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        sampler: GpuSampler,
        sourceView: GpuTextureView,
        sourceWidth: Int,
        sourceHeight: Int,
        destination: Surface,
        pipeline: RenderPipeline,
        offset: Float,
    ): Surface {
        val slice = nextSlice()
        writeVec4(encoder, buffer, slice, sourceWidth.toFloat(), sourceHeight.toFloat(), offset, 0f)

        encoder.createRenderPass({ "pawfect_chams_blur" }, destination.view, OptionalInt.of(0)).use { pass ->
            pass.setPipeline(pipeline)
            pass.bindTexture("Sampler0", sourceView, sampler)
            pass.setUniform("KawaseData", buffer.slice(slice, UNIFORM_STRIDE.toLong()))
            pass.draw(0, 6)
        }
        return destination
    }

    private fun portalTexture(id: Identifier): GpuTextureView? {
        if (config.overlay != HandChamsConfig.OverlayEffect.END_PORTAL) return null
        return runCatching { McCompat.mc.textureManager.getTexture(id).textureView }.getOrNull()
    }

    private fun writeComposite(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        offset: Long,
        maskWidth: Int,
        maskHeight: Int,
    ) {
        val softness = config.edgeSoftness.coerceIn(0f, 1f)
        val edgeLo = (0.5f - softness * 0.45f).coerceIn(0.01f, 0.98f)
        val edgeHi = (edgeLo + 0.02f + softness * 0.45f).coerceIn(0.02f, 1f)
        val seconds = (System.currentTimeMillis() - startedAt) / 1000f * config.overlaySpeed.coerceIn(0f, 4f)

        scratch.clear()
        putColor(config.tintColor, config.tintStrength.coerceIn(0f, 1f) * ColorInt.alpha(config.tintColor))
        putColor(config.outlineColor, config.outlineOpacity.coerceIn(0f, 1f) * ColorInt.alpha(config.outlineColor))
        scratch.putFloat(if (config.bodyActive) 1f else 0f)
        scratch.putFloat(config.opacity.coerceIn(0f, 1f))
        scratch.putFloat(if (config.saturationEnabled) config.saturation.coerceIn(0f, 3f) else 1f)
        scratch.putFloat(softness)
        scratch.putFloat(edgeLo)
        scratch.putFloat(edgeHi)
        scratch.putFloat(if (config.outlineEnabled) 1f else 0f)
        scratch.putFloat(config.overlay.id.toFloat())
        scratch.putFloat(config.overlayStrength.coerceIn(0f, 1f))
        scratch.putFloat(seconds)
        scratch.putFloat(config.debugView.id.toFloat())
        scratch.putFloat(config.outlineSoftness.coerceIn(0.02f, 1f))
        scratch.putFloat(if (config.overlayCustomColors) 1f else 0f)
        scratch.putFloat(config.portalLayers.coerceIn(1, 16).toFloat())
        scratch.putFloat(if (config.tintEnabled) 1f else 0f)
        scratch.putFloat(0f)
        putColor(config.overlayColorA, ColorInt.alpha(config.overlayColorA))
        putColor(config.overlayColorB, ColorInt.alpha(config.overlayColorB))
        putColor(config.overlayColorC, ColorInt.alpha(config.overlayColorC))
        putColor(config.trailColor, ColorInt.alpha(config.trailColor))
        scratch.putFloat(if (config.trailEnabled) 1f else 0f)
        scratch.putFloat(config.trailStrength.coerceIn(0f, 2f))
        scratch.putFloat(if (config.trailTinted) 1f else 0f)
        scratch.putFloat(0f)
        scratch.putFloat(1f / maskWidth.coerceAtLeast(1).toFloat())
        scratch.putFloat(1f / maskHeight.coerceAtLeast(1).toFloat())
        scratch.putFloat(config.outlineThickness.coerceIn(0.5f, 6f))
        scratch.putFloat(0f)
        pad()

        encoder.writeToBuffer(buffer.slice(offset, UNIFORM_STRIDE.toLong()), scratch)
    }

    private fun putColor(value: Int, alpha: Float) {
        val rgb = ColorInt.rgb(value)
        scratch.putFloat(((rgb shr 16) and 0xFF) / 255f)
        scratch.putFloat(((rgb shr 8) and 0xFF) / 255f)
        scratch.putFloat((rgb and 0xFF) / 255f)
        scratch.putFloat(alpha)
    }

    private fun writeVec4(
        encoder: CommandEncoder,
        buffer: GpuBuffer,
        offset: Long,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ) {
        scratch.clear()
        scratch.putFloat(x)
        scratch.putFloat(y)
        scratch.putFloat(z)
        scratch.putFloat(w)
        pad()
        encoder.writeToBuffer(buffer.slice(offset, UNIFORM_STRIDE.toLong()), scratch)
    }

    private fun pad() {
        while (scratch.position() < UNIFORM_STRIDE) scratch.put(0)
        scratch.flip()
    }

    private fun nextSlice(): Long {
        val offset = sliceCursor.toLong() * UNIFORM_STRIDE
        sliceCursor = (sliceCursor + 1) % MAX_UNIFORM_SLICES
        return offset
    }

    private fun ensureUniformBuffer(): GpuBuffer {
        uniformBuffer?.let { return it }
        val created = RenderSystem.getDevice().createBuffer(
            { "pawfect_chams_uniforms" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
            (UNIFORM_STRIDE * MAX_UNIFORM_SLICES).toLong(),
        )
        uniformBuffer = created
        return created
    }

    private fun fail(what: String, error: Throwable) {
        failed = true
        lastError = "$what: ${error::class.simpleName}: ${error.message}"
        skipReason = "failed"
        logger.error("Hand chams disabled after an error while {}", what, error)
        runCatching { ChatUtils.error("Hand chams disabled: $what (${error::class.simpleName})") }
        runCatching { shutdown() }
    }
}
