package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.MenuSpot
import dev.pawfect.addons.mixin.LoadingOverlayAccessor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.screens.LoadingOverlay
import net.minecraft.util.Mth
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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object HubSceneRenderer {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HubScene")

    private val config get() = ConfigManager.features.menu

    private const val FOV = 70f
    private const val NEAR = 0.05f
    private const val FAR = 700f
    private const val INTRO_SECONDS = 10.0
    // How long the uploaded scene sits unseen before the fade, so its first frames have settled.
    private const val SETTLE_BEFORE_FADE_NANOS = 1_000_000_000L
    private const val FADE_SECONDS = 1.6
    private const val SETTLE_SECONDS = 2.5
    // Until the scene is ready the camera gazes up at the sky, then tips down into the descent.
    private const val GAZE_PITCH = -50.0
    private const val TIP_SECONDS = 2.5
    // A scripted camera path is flown at this pace, within these bounds.
    private const val PATH_SPEED = 14.0
    private const val MIN_PATH_SECONDS = 6.0
    private const val MAX_PATH_SECONDS = 20.0
    private const val EYE_HEIGHT = 1.62
    private const val UNIFORM_SIZE = 256
    private const val MOON_RADIUS = 0.05f
    private const val LOOK_YAW = 3.0
    private const val LOOK_PITCH = 1.5
    private const val LOOK_RATE = 1.1

    // A gap this long between frames means the menu was left, so the next frame starts a new visit.
    private const val VISIT_GAP_NANOS = 1_000_000_000L

    private val SKY = Vector3f(0.008f, 0.014f, 0.045f)

    private enum class State { IDLE, BUILDING, BUILT, READY, FAILED }

    private class Layer(val pipeline: RenderPipeline, val vertices: GpuBuffer, val indices: GpuBuffer?, val type: VertexFormat.IndexType?, val count: Int)

    private class Pose(val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double)

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var built: HubMesh? = null

    private var layers: List<Layer> = emptyList()
    private var origin = Vector3f()
    private var atlas: AbstractTexture? = null

    private var scene: MenuSpot? = null
    private var introStart = -1L
    private var readyAt = 0L
    private var introPlayed = false
    private var lastFrame = 0L
    private var settledAt = 0L
    private var lookYaw = 0.0
    private var lookPitch = 0.0
    private val clockStart = System.nanoTime()

    private val projection = Projection()
    private val projectionBuffer by lazy { ProjectionMatrixBuffer("pawfect hub scene") }
    private var uniforms: GpuBuffer? = null
    private val scratch: ByteBuffer = ByteBuffer.allocateDirect(UNIFORM_SIZE).order(ByteOrder.nativeOrder())

    private fun enabled(): Boolean = config.hubScene && state != State.FAILED

    // Each launch shows one captured area, picked at random and kept for the session.
    private fun scene(): MenuSpot? {
        scene?.let { return it }
        return HubScene.spots().filter { HubScene.file(it.area).exists() }.randomOrNull()?.also { scene = it }
    }

    /** True when the menu should show a captured area instead of its usual background. */
    @JvmStatic
    fun active(): Boolean {
        if (McCompat.mc.level != null || !enabled()) return false
        return scene() != null
    }

    /** Starts building as soon as resources finish loading, so the scene is ready before the menu shows. */
    fun preload() {
        if (state != State.IDLE || McCompat.mc.level != null || !enabled() || !resourcesReady()) return
        if (scene() == null) return
        startBuild()
    }

    private fun resourcesReady(): Boolean {
        val overlay = McCompat.mc.overlay ?: return true
        val loading = overlay as? LoadingOverlay ?: return false
        return (loading as LoadingOverlayAccessor).`pawfectaddons$reload`().isDone
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
        if (state == State.IDLE && resourcesReady()) startBuild()
        if (state == State.BUILT) upload()
        if (state == State.READY && mc.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) !== atlas) {
            release()
            state = State.IDLE
            return true
        }

        val target = mc.mainRenderTarget
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (width <= 0f || height <= 0f) return true

        val now = System.nanoTime()
        val newVisit = lastFrame == 0L || now - lastFrame > VISIT_GAP_NANOS
        val delta = if (lastFrame == 0L) 0.0 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.25)
        lastFrame = now
        val ready = state == State.READY && layers.isNotEmpty()
        if (!ready) readyAt = 0L else if (readyAt == 0L) readyAt = now
        if (ready && introStart < 0L && mc.overlay == null && now - readyAt >= SETTLE_BEFORE_FADE_NANOS) introStart = now
        val sinceStart = if (introStart < 0L) 0.0 else (now - introStart) / 1e9

        if (newVisit) {
            settledAt = if (introPlayed) now else 0L
            HubWalker.restart()
        }
        val camera = camera(sinceStart, delta)
        if (settledAt == 0L && introPlayed) settledAt = introStart + (introSeconds() * 1e9).toLong()
        // Seconds since the camera settled for this visit, counting up through the fly-in.
        val showtime = when {
            settledAt != 0L -> (now - settledAt) / 1e9
            introStart >= 0L -> sinceStart - introSeconds()
            else -> -introSeconds()
        }
        val rotation = Quaternionf().rotationYXZ((PI - Math.toRadians(camera.yaw)).toFloat(), (-Math.toRadians(camera.pitch)).toFloat(), 0f)
        val tanHalf = tan(Math.toRadians(FOV / 2.0)).toFloat()
        val fade = when {
            !ready -> 0f
            introPlayed && introStart < 0L -> 1f
            else -> smooth((sinceStart / FADE_SECONDS).coerceIn(0.0, 1.0)).toFloat()
        }
        val buffer = writeUniforms(rotation, tanHalf, width / height, fade, ((now - clockStart) / 1e9).toFloat())

        projection.setupPerspective(NEAR, FAR, FOV, width, height)
        val projectionSlice = projectionBuffer.getBuffer(projection)
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val sceneSlice = buffer.slice(0L, UNIFORM_SIZE.toLong())

        encoder.createRenderPass({ "pawfect hub sky" }, target.colorTextureView!!, OptionalInt.empty(), target.depthTextureView!!, OptionalDouble.of(1.0)).use { pass ->
            pass.setPipeline(HubPipelines.SKY)
            pass.setUniform("HubScene", sceneSlice)
            pass.draw(0, 6)
        }

        if (!ready) return true

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

        runCatching { HubWalker.draw(camera.x, camera.y, camera.z, rotation, view, projectionSlice, 2.0 * tanHalf / height, showtime) }
            .onFailure { HubWalker.fail(it) }
        return true
    }

    // The camera starts high above and behind the chosen spot and glides down in a curve that
    // slows as it lands. Once there it drifts gently and leans a few degrees toward the mouse.
    // The fly-in plays once per launch; later visits to the menu open on the settled view.
    private fun camera(sinceStart: Double, delta: Double): Pose {
        val spot = scene ?: MenuSpot()
        val endX = spot.x
        val endY = spot.y + EYE_HEIGHT
        val endZ = spot.z
        val yaw = spot.yaw.toDouble()
        val pitch = spot.pitch.toDouble()

        val intro = introSeconds()
        if (!introPlayed && sinceStart >= intro) introPlayed = true
        val settled = if (introPlayed) sinceStart - intro else -1.0
        val settle = if (introPlayed && introStart >= 0L && sinceStart < intro + SETTLE_SECONDS) {
            smooth((settled / SETTLE_SECONDS).coerceIn(0.0, 1.0))
        } else if (introPlayed) {
            1.0
        } else {
            0.0
        }

        val mc = McCompat.mc
        val window = mc.window
        val mouseX = if (window.screenWidth > 0) (mc.mouseHandler.xpos() / window.screenWidth * 2.0 - 1.0).coerceIn(-1.0, 1.0) else 0.0
        val mouseY = if (window.screenHeight > 0) (mc.mouseHandler.ypos() / window.screenHeight * 2.0 - 1.0).coerceIn(-1.0, 1.0) else 0.0
        val follow = 1.0 - exp(-delta * LOOK_RATE)
        lookYaw += (mouseX * LOOK_YAW * settle - lookYaw) * follow
        lookPitch += (mouseY * LOOK_PITCH * settle - lookPitch) * follow

        val drift = (System.nanoTime() - clockStart) / 1e9
        val swayYaw = sin(drift * 0.11) * 2.2 * settle
        val swayPitch = sin(drift * 0.083 + 1.3) * 0.8 * settle
        val bob = sin(drift * 0.21) * 0.06 * settle

        if (introPlayed) {
            return Pose(endX, endY + bob, endZ, yaw + swayYaw + lookYaw, pitch + swayPitch + lookPitch)
        }

        val t = smooth((sinceStart / intro).coerceIn(0.0, 1.0))
        val gaze = if (introStart < 0L) 1.0 else 1.0 - smooth((sinceStart / TIP_SECONDS).coerceIn(0.0, 1.0))
        val path = MenuScenes.forArea(spot.area)?.camera.orEmpty()
        if (path.size >= 2) return along(path, t, gaze)

        val radians = Math.toRadians(yaw)
        val forwardX = -sin(radians)
        val forwardZ = cos(radians)

        val p0x = endX - forwardX * 70.0
        val p0y = endY + 180.0
        val p0z = endZ - forwardZ * 70.0
        val p1x = p0x + forwardX * 30.0
        val p1y = p0y - 40.0
        val p1z = p0z + forwardZ * 30.0
        val p2x = endX - forwardX * 16.0
        val p2y = endY + 10.0
        val p2z = endZ - forwardZ * 16.0
        val u = 1.0 - t
        fun bezier(a: Double, b: Double, c: Double, d: Double) = u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d

        val descent = pitch + (52.0 - pitch) * u * u
        return Pose(
            bezier(p0x, p1x, p2x, endX),
            bezier(p0y, p1y, p2y, endY),
            bezier(p0z, p1z, p2z, endZ),
            yaw - 28.0 * u,
            descent + (GAZE_PITCH - descent) * gaze,
        )
    }

    private fun introSeconds(): Double {
        val path = MenuScenes.forArea(scene?.area)?.camera.orEmpty()
        if (path.size < 2) return INTRO_SECONDS
        return (pathLengths(path).sum() / PATH_SPEED).coerceIn(MIN_PATH_SECONDS, MAX_PATH_SECONDS)
    }

    private fun pathLengths(path: List<MenuScenes.Shot>): List<Double> =
        path.zipWithNext { a, b -> sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z)) }

    // Glides through the recorded shots on a Catmull-Rom curve, at an even pace by distance, so
    // the camera passes through every one of them facing the way it was recorded.
    private fun along(path: List<MenuScenes.Shot>, t: Double, gaze: Double): Pose {
        val lengths = pathLengths(path)
        var left = t * lengths.sum()
        var i = 0
        while (i < lengths.size - 1 && left > lengths[i]) {
            left -= lengths[i]
            i++
        }
        val u = if (lengths[i] > 0.0) (left / lengths[i]).coerceIn(0.0, 1.0) else 0.0
        val a = path[max(i - 1, 0)]
        val b = path[i]
        val c = path[i + 1]
        val d = path[min(i + 2, path.size - 1)]
        fun spline(p0: Double, p1: Double, p2: Double, p3: Double) =
            0.5 * (2 * p1 + (p2 - p0) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (3 * p1 - p0 - 3 * p2 + p3) * u * u * u)

        // Yaws are unwrapped against their neighbours so the camera always turns the short way.
        val yawB = b.yaw.toDouble()
        val yawA = yawB - Mth.wrapDegrees(yawB - a.yaw)
        val yawC = yawB + Mth.wrapDegrees(c.yaw - yawB)
        val yawD = yawC + Mth.wrapDegrees(d.yaw - yawC)
        val pitch = spline(a.pitch.toDouble(), b.pitch.toDouble(), c.pitch.toDouble(), d.pitch.toDouble())
        return Pose(
            spline(a.x, b.x, c.x, d.x),
            spline(a.y, b.y, c.y, d.y) + EYE_HEIGHT,
            spline(a.z, b.z, c.z, d.z),
            spline(yawA, yawB, yawC, yawD),
            pitch + (GAZE_PITCH - pitch) * gaze,
        )
    }

    private fun smooth(x: Double): Double = x * x * x * (x * (x * 6 - 15) + 10)

    // The moon sits a little to the side of where the settled camera looks, high enough to
    // clear the buildings but low enough to stay in frame.
    private fun moonDirection(): Vector3f {
        val yaw = Math.toRadians((scene?.yaw ?: 0f).toDouble() + 24.0)
        val elevation = Math.toRadians(27.0)
        return Vector3f(
            (-sin(yaw) * cos(elevation)).toFloat(),
            sin(elevation).toFloat(),
            (cos(yaw) * cos(elevation)).toFloat(),
        ).normalize()
    }

    private fun writeUniforms(rotation: Quaternionf, tanHalf: Float, aspect: Float, fade: Float, time: Float): GpuBuffer {
        val buffer = uniforms ?: RenderSystem.getDevice()
            .createBuffer({ "pawfect hub scene uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
            .also { uniforms = it }
        val right = rotation.transform(Vector3f(1f, 0f, 0f))
        val up = rotation.transform(Vector3f(0f, 1f, 0f))
        val forward = rotation.transform(Vector3f(0f, 0f, -1f))
        val moon = moonDirection()
        scratch.clear()
        scratch.putFloat(right.x).putFloat(right.y).putFloat(right.z).putFloat(tanHalf * aspect)
        scratch.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(tanHalf)
        scratch.putFloat(forward.x).putFloat(forward.y).putFloat(forward.z).putFloat(fade)
        scratch.putFloat(HubAtmosphere.FOG.x).putFloat(HubAtmosphere.FOG.y).putFloat(HubAtmosphere.FOG.z).putFloat(HubAtmosphere.FOG_START)
        scratch.putFloat(SKY.x).putFloat(SKY.y).putFloat(SKY.z).putFloat(HubAtmosphere.FOG_END)
        scratch.putFloat(moon.x).putFloat(moon.y).putFloat(moon.z).putFloat(MOON_RADIUS)
        scratch.putFloat(time).putFloat(HubAtmosphere.MOONLIGHT).putFloat(1f).putFloat(0f)
        while (scratch.position() < UNIFORM_SIZE) scratch.put(0)
        scratch.flip()
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0L, UNIFORM_SIZE.toLong()), scratch)
        return buffer
    }

    private fun startBuild() {
        val spot = scene() ?: return
        state = State.BUILDING
        val ox = floor(spot.x).toInt()
        val oy = floor(spot.y).toInt()
        val oz = floor(spot.z).toInt()
        val eyeX = (spot.x - ox).toFloat()
        val eyeY = (spot.y + EYE_HEIGHT - oy).toFloat()
        val eyeZ = (spot.z - oz).toFloat()
        Thread({
            try {
                val started = System.nanoTime()
                val world = HubWorld.load(HubScene.file(spot.area))
                if (world == null) {
                    logger.info("The saved hub scene is from an older version; it will be recaptured in the hub.")
                    state = State.FAILED
                    return@Thread
                }
                val loaded = System.nanoTime()
                val mesh = HubMesher.build(world, ox, oy, oz, eyeX, eyeY, eyeZ)
                logger.info(
                    "Built the hub scene: {} sections, {} quads in {} parts, read in {} ms, meshed in {} ms.",
                    world.allSections.size,
                    mesh.quads,
                    mesh.parts.size,
                    (loaded - started) / 1_000_000,
                    (System.nanoTime() - loaded) / 1_000_000,
                )
                built = mesh
                state = State.BUILT
                val script = MenuScenes.forArea(spot.area)
                if (script != null && script.walk.size >= 2) HubWalker.prepare(world, script) else HubWalker.forget()
            } catch (error: Throwable) {
                logger.error("Could not build the hub scene.", error)
                state = State.FAILED
            }
        }, "PawfectAddons Hub Scene").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }

    private fun upload() {
        val mesh = built ?: return
        built = null
        val device = RenderSystem.getDevice()
        // Opaque layers first, then translucent parts from the farthest band to the nearest.
        val rank = { which: ChunkSectionLayer ->
            when (which) {
                ChunkSectionLayer.SOLID -> 0
                ChunkSectionLayer.TRANSLUCENT -> 2
                else -> 1
            }
        }
        val sorted = mesh.parts
            .flatMap { part -> part.layers.entries.map { Triple(rank(it.key), part.distance, it) } }
            .sortedWith(compareBy<Triple<Int, Double, Map.Entry<ChunkSectionLayer, com.mojang.blaze3d.vertex.MeshData>>> { it.first }.thenByDescending { it.second })
            .map { it.third }
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
            if (state == State.BUILDING) return@execute
            release()
            state = State.IDLE
        }
    }
}
