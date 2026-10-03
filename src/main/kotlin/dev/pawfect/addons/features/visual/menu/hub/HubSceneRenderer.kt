package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalDouble
import java.util.OptionalInt
import kotlin.io.path.exists
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

object HubSceneRenderer {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HubScene")

    private val config get() = ConfigManager.features.menu

    private const val FOV = 70f
    private const val NEAR = 0.05f
    private const val FAR = 700f
    private const val FOG_START = 70f
    private const val FOG_END = 165f
    private const val INTRO_SECONDS = 7.0
    private const val EYE_HEIGHT = 1.62
    private const val UNIFORM_SIZE = 256

    private val FOG = Vector3f(0.76f, 0.85f, 1f)
    private val SKY = Vector3f(0.45f, 0.63f, 1f)

    private enum class State { IDLE, BUILDING, BUILT, READY, FAILED }

    private class Layer(val pipeline: RenderPipeline, val vertices: GpuBuffer, val indices: GpuBuffer?, val type: VertexFormat.IndexType?, val count: Int)

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var built: HubMesh? = null

    private var layers: List<Layer> = emptyList()
    private var origin = Vector3f()
    private var atlas: AbstractTexture? = null
    private var readyAt = -1L
    private var introPlayed = false

    private val projection = Projection()
    private val projectionBuffer by lazy { ProjectionMatrixBuffer("pawfect hub scene") }
    private var uniforms: GpuBuffer? = null
    private val scratch: ByteBuffer = ByteBuffer.allocateDirect(UNIFORM_SIZE).order(ByteOrder.nativeOrder())

    /** True when the menu should show the hub instead of its usual background. */
    @JvmStatic
    fun active(): Boolean {
        val mc = McCompat.mc
        if (mc.level != null || !config.hubScene || !config.anchorSet || state == State.FAILED) return false
        return state != State.IDLE || HubScene.file.exists()
    }

    /** Draws the scene where the panorama would go. False hands the frame back to the panorama. */
    @JvmStatic
    fun draw(): Boolean {
        if (!active()) return false
        return runCatching { drawFrame() }.getOrElse {
            logger.error("The hub scene stopped after a draw error.", it)
            state = State.FAILED
            release()
            false
        }
    }

    private fun drawFrame(): Boolean {
        val mc = McCompat.mc
        if (mc.overlay != null) return false
        when (state) {
            State.IDLE -> startBuild()
            State.BUILT -> upload()
            else -> Unit
        }
        val currentAtlas = mc.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)
        if (state == State.READY && currentAtlas !== atlas) {
            release()
            state = State.IDLE
            return drawFrame()
        }

        val target = mc.mainRenderTarget
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (width <= 0f || height <= 0f) return true

        val camera = camera()
        val rotation = Quaternionf().rotationYXZ((PI - Math.toRadians(camera.yaw)).toFloat(), (-Math.toRadians(camera.pitch)).toFloat(), 0f)
        val tanHalf = tan(Math.toRadians(FOV / 2.0)).toFloat()
        val buffer = writeUniforms(rotation, tanHalf, width / height)

        projection.setupPerspective(NEAR, FAR, FOV, width, height)
        val projectionSlice = projectionBuffer.getBuffer(projection)
        val device = RenderSystem.getDevice()
        val encoder = device.createCommandEncoder()
        val sceneSlice = buffer.slice(0L, UNIFORM_SIZE.toLong())

        encoder.createRenderPass({ "pawfect hub sky" }, target.colorTextureView!!, OptionalInt.empty(), target.depthTextureView!!, OptionalDouble.of(1.0)).use { pass ->
            pass.setPipeline(HubPipelines.SKY)
            pass.setUniform("HubScene", sceneSlice)
            pass.draw(0, 6)
        }

        if (state != State.READY || layers.isEmpty()) return true

        val view = Matrix4f().rotation(Quaternionf(rotation).conjugate())
        val offset = Vector3f(
            (origin.x - camera.x).toFloat(),
            (origin.y - camera.y).toFloat(),
            (origin.z - camera.z).toFloat(),
        )
        val transform = RenderSystem.getDynamicUniforms().writeTransform(view, Vector4f(1f, 1f, 1f, 1f), offset, Matrix4f())
        val texture = atlas ?: return true
        val quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)

        encoder.createRenderPass({ "pawfect hub terrain" }, target.colorTextureView!!, OptionalInt.empty(), target.depthTextureView!!, OptionalDouble.empty()).use { pass ->
            pass.setUniform("Projection", projectionSlice)
            pass.setUniform("DynamicTransforms", transform)
            pass.setUniform("HubScene", sceneSlice)
            pass.bindTexture("Sampler0", texture.textureView, texture.sampler)
            for (layer in layers) {
                pass.setPipeline(layer.pipeline)
                pass.setVertexBuffer(0, layer.vertices)
                if (layer.indices != null && layer.type != null) {
                    pass.setIndexBuffer(layer.indices, layer.type)
                } else {
                    pass.setIndexBuffer(quads.getBuffer(layer.count), quads.type())
                }
                pass.drawIndexed(0, 0, layer.count, 1)
            }
        }
        return true
    }

    private class Pose(val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double)

    // The camera starts high above and behind the chosen spot, glides down in a curve that
    // slows as it lands, then drifts gently. The fly-in plays once per launch.
    private fun camera(): Pose {
        val endX = config.anchorX
        val endY = config.anchorY + EYE_HEIGHT
        val endZ = config.anchorZ
        val yaw = config.anchorYaw.toDouble()
        val pitch = config.anchorPitch.toDouble()
        val now = System.nanoTime()
        if (readyAt < 0L) readyAt = now
        val seconds = (now - readyAt) / 1e9

        val idle = if (introPlayed) seconds + INTRO_SECONDS else seconds
        val sway = smooth(((idle - INTRO_SECONDS) / 2.5).coerceIn(0.0, 1.0))
        val drift = idle - INTRO_SECONDS
        val swayYaw = sin(drift * 0.11) * 2.6 * sway
        val swayPitch = sin(drift * 0.083 + 1.3) * 0.9 * sway
        val bob = sin(drift * 0.21) * 0.06 * sway

        if (introPlayed || seconds >= INTRO_SECONDS) {
            if (seconds >= INTRO_SECONDS) introPlayed = true
            return Pose(endX, endY + bob, endZ, yaw + swayYaw, pitch + swayPitch)
        }

        val t = smooth(seconds / INTRO_SECONDS)
        val radians = Math.toRadians(yaw)
        val forwardX = -sin(radians)
        val forwardZ = cos(radians)

        val p0x = endX - forwardX * 70.0
        val p0y = endY + 80.0
        val p0z = endZ - forwardZ * 70.0
        val p1x = p0x + forwardX * 30.0
        val p1y = p0y - 12.0
        val p1z = p0z + forwardZ * 30.0
        val p2x = endX - forwardX * 16.0
        val p2y = endY + 10.0
        val p2z = endZ - forwardZ * 16.0
        val u = 1.0 - t
        fun bezier(a: Double, b: Double, c: Double, d: Double) = u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d

        return Pose(
            bezier(p0x, p1x, p2x, endX),
            bezier(p0y, p1y, p2y, endY),
            bezier(p0z, p1z, p2z, endZ),
            yaw - 28.0 * (1.0 - t),
            pitch + (52.0 - pitch) * (1.0 - t) * (1.0 - t),
        )
    }

    private fun smooth(x: Double): Double = x * x * x * (x * (x * 6 - 15) + 10)

    private fun writeUniforms(rotation: Quaternionf, tanHalf: Float, aspect: Float): GpuBuffer {
        val buffer = uniforms ?: RenderSystem.getDevice()
            .createBuffer({ "pawfect hub scene uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
            .also { uniforms = it }
        val right = rotation.transform(Vector3f(1f, 0f, 0f))
        val up = rotation.transform(Vector3f(0f, 1f, 0f))
        val forward = rotation.transform(Vector3f(0f, 0f, -1f))
        scratch.clear()
        scratch.putFloat(right.x).putFloat(right.y).putFloat(right.z).putFloat(tanHalf * aspect)
        scratch.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(tanHalf)
        scratch.putFloat(forward.x).putFloat(forward.y).putFloat(forward.z).putFloat(0f)
        scratch.putFloat(FOG.x).putFloat(FOG.y).putFloat(FOG.z).putFloat(FOG_START)
        scratch.putFloat(SKY.x).putFloat(SKY.y).putFloat(SKY.z).putFloat(FOG_END)
        while (scratch.position() < UNIFORM_SIZE) scratch.put(0)
        scratch.flip()
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0L, UNIFORM_SIZE.toLong()), scratch)
        return buffer
    }

    private fun startBuild() {
        state = State.BUILDING
        val ox = floor(config.anchorX).toInt()
        val oy = floor(config.anchorY).toInt()
        val oz = floor(config.anchorZ).toInt()
        val eyeX = (config.anchorX - ox).toFloat()
        val eyeY = (config.anchorY + EYE_HEIGHT - oy).toFloat()
        val eyeZ = (config.anchorZ - oz).toFloat()
        Thread({
            try {
                val started = System.nanoTime()
                val world = HubWorld.load(HubScene.file)
                if (world == null) {
                    logger.info("The saved hub scene is from an older version; it will be recaptured in the hub.")
                    state = State.FAILED
                    return@Thread
                }
                val mesh = HubMesher.build(world, ox, oy, oz, eyeX, eyeY, eyeZ)
                logger.info(
                    "Built the hub scene: {} sections, {} quads in {} ms.",
                    world.allSections.size,
                    mesh.quads,
                    (System.nanoTime() - started) / 1_000_000,
                )
                built = mesh
                state = State.BUILT
            } catch (error: Throwable) {
                logger.error("Could not build the hub scene.", error)
                state = State.FAILED
            }
        }, "PawfectAddons Hub Scene").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun upload() {
        val mesh = built ?: return
        built = null
        val device = RenderSystem.getDevice()
        val order = listOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT)
        val sorted = mesh.layers.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) 1 else i } }
        layers = sorted.mapNotNull { (which, data) ->
            val draw = data.drawState()
            if (draw.indexCount() == 0) return@mapNotNull null
            val pipeline = when (which) {
                ChunkSectionLayer.SOLID -> HubPipelines.SOLID
                ChunkSectionLayer.TRANSLUCENT -> HubPipelines.TRANSLUCENT
                else -> HubPipelines.CUTOUT
            }
            val vertices = device.createBuffer({ "pawfect hub ${which.name.lowercase()}" }, GpuBuffer.USAGE_VERTEX, data.vertexBuffer())
            val sortedIndices = data.indexBuffer()
            val indices = sortedIndices?.let { device.createBuffer({ "pawfect hub ${which.name.lowercase()} order" }, GpuBuffer.USAGE_INDEX, it) }
            Layer(pipeline, vertices, indices, if (indices != null) draw.indexType() else null, draw.indexCount())
        }
        origin = Vector3f(mesh.originX.toFloat(), mesh.originY.toFloat(), mesh.originZ.toFloat())
        atlas = McCompat.mc.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)
        mesh.close()
        state = State.READY
    }

    private fun release() {
        layers.forEach { layer ->
            runCatching { layer.vertices.close() }
            runCatching { layer.indices?.close() }
        }
        layers = emptyList()
        built?.close()
        built = null
        atlas = null
    }

    /** Throws the scene away so the next menu frame rebuilds it, after a new capture. */
    fun invalidate() {
        if (state == State.BUILDING) return
        McCompat.mc.execute {
            release()
            state = State.IDLE
        }
    }
}
