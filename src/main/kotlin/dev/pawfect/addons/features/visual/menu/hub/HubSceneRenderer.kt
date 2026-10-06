package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
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
import java.util.Optional
import java.util.OptionalDouble
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
    // How long the uploaded scene settles before the intro starts, so its first frames have passed.
    private const val SETTLE_BEFORE_INTRO_NANOS = 1_000_000_000L
    private const val SETTLE_SECONDS = 2.5
    // Until the scene is ready the camera gazes up at the sky, then tips down into the descent.
    private const val GAZE_PITCH = -50.0
    private const val TIP_SECONDS = 2.5
    // A scripted camera path is flown at this pace, within these bounds.
    private const val PATH_SPEED = 14.0
    private const val MIN_PATH_SECONDS = 6.0
    private const val MAX_PATH_SECONDS = 20.0
    private const val FLIGHT_STEPS = 48
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

    private class Layer(val pipeline: RenderPipeline, val vertices: GpuBuffer, val indices: GpuBuffer?, val type: IndexType?, val count: Int)

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

    private fun captured(): List<MenuSpot> = HubScene.spots().filter { HubScene.file(it.area).exists() }

    /** True when there is more than one captured scene to switch between. */
    @JvmStatic
    fun canSwitch(): Boolean = enabled() && captured().size > 1

    /** The name of the scene being shown, for the menu's button and credit. */
    @JvmStatic
    fun sceneTitle(): String = MenuScenes.title(scene()?.area ?: HubScene.HUB)

    /** Moves on to the next captured scene and plays its intro, for the menu's background button. */
    @JvmStatic
    fun next() {
        if (state == State.BUILDING) return
        val scenes = captured()
        if (scenes.size < 2) return
        val at = scenes.indexOfFirst { it.area == scene?.area }
        scene = scenes[(at + 1) % scenes.size]
        release()
        state = State.IDLE
        HubWalker.forget()
        HubWalker.restart()
        introStart = -1L
        introPlayed = false
        readyAt = 0L
        settledAt = 0L
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
        val overlay = McCompat.mc.gui.overlay() ?: return true
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

        val target = mc.gameRenderer.mainRenderTarget()
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (width <= 0f || height <= 0f) return true

        val now = System.nanoTime()
        val newVisit = lastFrame == 0L || now - lastFrame > VISIT_GAP_NANOS
        val delta = if (lastFrame == 0L) 0.0 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.25)
        lastFrame = now
        val ready = state == State.READY && layers.isNotEmpty()
        if (!ready) readyAt = 0L else if (readyAt == 0L) readyAt = now
        if (ready && introStart < 0L && mc.gui.overlay() == null && now - readyAt >= SETTLE_BEFORE_INTRO_NANOS) introStart = now
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
        val buffer = writeUniforms(rotation, tanHalf, width / height, ((now - clockStart) / 1e9).toFloat())

        projection.setupPerspective(NEAR, FAR, FOV, width, height)
        val projectionSlice = projectionBuffer.getBuffer(projection)
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val sceneSlice = buffer.slice(0L, UNIFORM_SIZE.toLong())

        encoder.createRenderPass({ "pawfect hub sky" }, target.colorTextureView!!, Optional.empty(), target.depthTextureView!!, OptionalDouble.of(0.0)).use { pass ->
            pass.setPipeline(HubPipelines.SKY)
            pass.setUniform("HubScene", sceneSlice)
            pass.draw(6, 1, 0, 0)
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
        val quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)

        encoder.createRenderPass({ "pawfect hub terrain" }, target.colorTextureView!!, Optional.empty(), target.depthTextureView!!, OptionalDouble.empty()).use { pass ->
            pass.setUniform("Projection", projectionSlice)
            pass.setUniform("DynamicTransforms", transform)
            pass.setUniform("HubScene", sceneSlice)
            pass.bindTexture("Sampler0", texture.textureView, texture.sampler)
            for (layer in layers) {
                pass.setPipeline(layer.pipeline)
                pass.setVertexBuffer(0, layer.vertices.slice())
                if (layer.indices != null && layer.type != null) {
                    pass.setIndexBuffer(layer.indices, layer.type)
                } else {
                    pass.setIndexBuffer(quads.getBuffer(layer.count), quads.type())
                }
                pass.drawIndexed(layer.count, 1, 0, 0, 0)
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
        flight()?.let { return along(it, t, gaze) }

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
        val flight = flight() ?: return INTRO_SECONDS
        return (flight.length / PATH_SPEED).coerceIn(MIN_PATH_SECONDS, MAX_PATH_SECONDS)
    }

    /** A scripted camera path as dense samples of x, y, z, yaw and pitch, and how far along each one is. */
    private class Flight(val samples: Array<DoubleArray>, val along: DoubleArray) {
        val length: Double get() = along.last()
    }

    private var flight: Flight? = null
    private var flightArea: String? = null

    private fun flight(): Flight? {
        val area = scene?.area ?: return null
        if (area != flightArea) {
            flightArea = area
            flight = MenuScenes.forArea(area)?.camera?.takeIf { it.size >= 2 }?.let(::fly)
        }
        return flight
    }

    // Centripetal Catmull-Rom through the shots: unlike the plain kind it keeps its shape where
    // shots are unevenly spaced, instead of whipping through a short leg after a long one. The
    // curve is then measured by true distance, so the camera keeps an even pace along it.
    private fun fly(shots: List<MenuScenes.Shot>): Flight {
        val keys = ArrayList<DoubleArray>()
        for (shot in shots) {
            // Each yaw is unwrapped against the one before, so the camera turns the short way.
            val yaw = keys.lastOrNull()?.let { it[3] + Mth.wrapDegrees(shot.yaw - it[3]) } ?: shot.yaw.toDouble()
            keys += doubleArrayOf(shot.x, shot.y, shot.z, yaw, shot.pitch.toDouble())
        }
        val points = listOf(mirror(keys[0], keys[1])) + keys + listOf(mirror(keys.last(), keys[keys.size - 2]))
        val knots = DoubleArray(points.size)
        for (i in 1 until points.size) knots[i] = knots[i - 1] + max(sqrt(distance(points[i - 1], points[i])), 1e-4)

        val samples = ArrayList<DoubleArray>()
        for (s in 1 until points.size - 2) {
            for (k in 0 until FLIGHT_STEPS) {
                val at = knots[s] + (knots[s + 1] - knots[s]) * k / FLIGHT_STEPS
                samples += curvePoint(points[s - 1], points[s], points[s + 1], points[s + 2], knots[s - 1], knots[s], knots[s + 1], knots[s + 2], at)
            }
        }
        samples += keys.last()
        val along = DoubleArray(samples.size)
        for (i in 1 until samples.size) along[i] = along[i - 1] + distance(samples[i - 1], samples[i])
        return Flight(samples.toTypedArray(), along)
    }

    private fun mirror(end: DoubleArray, next: DoubleArray) = DoubleArray(end.size) { 2 * end[it] - next[it] }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]))

    // Barry and Goldman's pyramid of blends, which evaluates the curve at knot time [at].
    private fun curvePoint(
        p0: DoubleArray, p1: DoubleArray, p2: DoubleArray, p3: DoubleArray,
        t0: Double, t1: Double, t2: Double, t3: Double, at: Double,
    ): DoubleArray {
        fun mix(a: DoubleArray, b: DoubleArray, from: Double, to: Double) =
            DoubleArray(a.size) { ((to - at) * a[it] + (at - from) * b[it]) / (to - from) }
        val a1 = mix(p0, p1, t0, t1)
        val a2 = mix(p1, p2, t1, t2)
        val a3 = mix(p2, p3, t2, t3)
        return mix(mix(a1, a2, t0, t2), mix(a2, a3, t1, t3), t1, t2)
    }

    private fun along(flight: Flight, t: Double, gaze: Double): Pose {
        val reach = t * flight.length
        var lo = 0
        var hi = flight.along.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (flight.along[mid] <= reach) lo = mid else hi = mid
        }
        val span = flight.along[hi] - flight.along[lo]
        val blend = if (span > 0.0) ((reach - flight.along[lo]) / span).coerceIn(0.0, 1.0) else 0.0
        val a = flight.samples[lo]
        val b = flight.samples[hi]
        fun at(i: Int) = a[i] + (b[i] - a[i]) * blend
        val pitch = at(4)
        return Pose(at(0), at(1) + EYE_HEIGHT, at(2), at(3), pitch + (GAZE_PITCH - pitch) * gaze)
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

    private fun writeUniforms(rotation: Quaternionf, tanHalf: Float, aspect: Float, time: Float): GpuBuffer {
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
        scratch.putFloat(forward.x).putFloat(forward.y).putFloat(forward.z).putFloat(1f)
        scratch.putFloat(HubAtmosphere.HORIZON.x).putFloat(HubAtmosphere.HORIZON.y).putFloat(HubAtmosphere.HORIZON.z).putFloat(0f)
        scratch.putFloat(SKY.x).putFloat(SKY.y).putFloat(SKY.z).putFloat(0f)
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
