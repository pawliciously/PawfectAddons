package dev.pawfect.addons.features.visual

import com.mojang.blaze3d.vertex.PoseStack
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.AmbientMotesConfig.Look
import dev.pawfect.addons.config.features.AmbientMotesConfig.Palette
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.cosmetics.Form
import dev.pawfect.addons.features.cosmetics.Fx
import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Motes of light drifting through the world around you, lifting off the blocks out to a set
 * distance and then going wherever they like: wandering, circling a spot, darting about like
 * fireflies, or rising slowly away.
 *
 * Kept cheap: they only spawn where you're looking and where you can see, those you turn away
 * from are let go to spawn again in front of you, and a few each tick are checked for whether a
 * block has come between you and them, fading out if so. Each one is a couple of soft glows.
 */
object AmbientMotes {

    private val config get() = ModuleSwitch.view("visuals", ConfigManager.features.ambientMotes)

    private enum class Mood { DRIFT, ORBIT, DART, RISE }

    private class Mote {
        var x = 0.0; var y = 0.0; var z = 0.0
        var px = 0.0; var py = 0.0; var pz = 0.0
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        var ax = 0.0; var ay = 0.0; var az = 0.0
        var age = 0
        var life = 0
        var seed = 0
        var color = 0
        var mood = Mood.DRIFT
        var spark = false
        var scale = 1f
        var dartIn = 0
        var outOfView = 0
        var hidden = false
        var shown = 0f
        var lastShown = 0f
    }

    private val motes = ArrayList<Mote>(MAX)
    private val pool = ArrayDeque<Mote>()
    private val random = Random(System.nanoTime())
    private val cursor = BlockPos.MutableBlockPos()
    private var checkAt = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { tick() } }
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    private fun clear() {
        pool.addAll(motes)
        motes.clear()
    }

    // ------------------------------------------------------------------ tick

    private fun tick() {
        val client = McCompat.mc
        val level = client.level
        if (!config.enabled || level == null || client.player == null || (!config.inDungeons && SkyBlockData.inDungeons)) {
            if (motes.isNotEmpty()) clear()
            return
        }
        val camera = client.gameRenderer.mainCamera()
        val eye = camera.position()
        val forward = camera.forwardVector()
        val reach = reach()
        val cone = viewCone()

        var i = 0
        while (i < motes.size) {
            val m = motes[i]
            m.px = m.x; m.py = m.y; m.pz = m.z
            move(level, m, eye)
            m.age++
            m.lastShown = m.shown
            m.shown += ((if (m.hidden) 0f else 1f) - m.shown) * 0.2f
            val dx = m.x - eye.x
            val dy = m.y - eye.y
            val dz = m.z - eye.z
            val distSq = dx * dx + dy * dy + dz * dz
            val inView = inCone(dx, dy, dz, distSq, forward.x(), forward.y(), forward.z(), cone.cosWide)
            m.outOfView = if (inView) 0 else m.outOfView + 1
            if (m.age >= m.life || distSq > reach * reach * 1.3 || m.outOfView > LET_GO_TICKS) {
                pool.addLast(m)
                motes[i] = motes[motes.size - 1]
                motes.removeAt(motes.size - 1)
                continue
            }
            i++
        }

        // A few line of sight checks a tick, round robin, so the cost doesn't grow with the count.
        repeat(min(SIGHT_CHECKS, motes.size)) {
            if (checkAt >= motes.size) checkAt = 0
            val m = motes[checkAt++]
            m.hidden = blocked(level, eye, m.x, m.y, m.z)
        }

        val wanted = config.count.coerceIn(10, MAX)
        val attempts = min(wanted - motes.size, max(SPAWN_TRIES, wanted / 40).coerceAtMost(MAX_TRIES))
        repeat(attempts.coerceAtLeast(0)) { spawn(level, eye, forward.x(), forward.y(), forward.z(), camera, reach, cone) }
    }

    /** The farthest they go: your setting, but no further than the loaded world. */
    private fun reach(): Double {
        val loaded = (McCompat.mc.options.effectiveRenderDistance * 16 + 8).toDouble()
        return min(config.distance.toDouble(), loaded).coerceAtLeast(8.0)
    }

    private class Cone(val halfH: Float, val halfV: Float, val cosWide: Double)

    private fun viewCone(): Cone {
        val client = McCompat.mc
        val vertical = Math.toRadians(client.options.fov().get().toDouble()).toFloat()
        val window = client.window
        val aspect = window.width.toFloat() / window.height.coerceAtLeast(1)
        val halfV = vertical / 2f
        val halfH = atan(tan(halfV) * aspect)
        // Drawing is culled a little wider than spawning, so motes don't pop at the screen's edge.
        val wide = sqrt(halfH * halfH + halfV * halfV) + 0.15f
        return Cone(halfH, halfV, cos(wide.coerceAtMost(3.1f)).toDouble())
    }

    private fun inCone(dx: Double, dy: Double, dz: Double, distSq: Double, fx: Float, fy: Float, fz: Float, cosLimit: Double): Boolean {
        if (distSq < 4.0) return true
        val dot = dx * fx + dy * fy + dz * fz
        return dot > 0 && dot * dot > cosLimit * cosLimit * distSq
    }

    // ------------------------------------------------------------------ spawning

    private fun spawn(level: ClientLevel, eye: Vec3, fx: Float, fy: Float, fz: Float, camera: net.minecraft.client.Camera, reach: Double, cone: Cone) {
        val up = camera.upVector()
        val left = camera.leftVector()
        val th = tan((random.nextFloat() * 2f - 1f) * cone.halfH * 0.95f)
        val tv = tan((random.nextFloat() * 2f - 1f) * cone.halfV * 0.95f)
        var dx = fx - left.x() * th + up.x() * tv
        var dy = fy - left.y() * th + up.y() * tv
        var dz = fz - left.z() * th + up.z() * tv
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= len; dy /= len; dz /= len
        // Weighted toward you: most of what you see is close, and the far ones fill the distance.
        val distance = NEAR + (reach - NEAR) * random.nextDouble().pow(1.7)
        val surface = surface(level, eye.x + dx * distance, eye.y + dy * distance, eye.z + dz * distance) ?: return
        val x = surface.x + (random.nextDouble() - 0.5) * 0.9
        val y = surface.y + 0.15 + random.nextDouble() * 1.4
        val z = surface.z + (random.nextDouble() - 0.5) * 0.9
        val ox = x - eye.x
        val oy = y - eye.y
        val oz = z - eye.z
        val distSq = ox * ox + oy * oy + oz * oz
        if (distSq > reach * reach) return
        if (!inCone(ox, oy, oz, distSq, fx, fy, fz, cos(sqrt(cone.halfH * cone.halfH + cone.halfV * cone.halfV)).toDouble())) return
        if (blocked(level, eye, x, y, z)) return

        val m = pool.removeLastOrNull() ?: Mote()
        m.x = x; m.y = y; m.z = z
        m.px = x; m.py = y; m.pz = z
        m.ax = x; m.ay = y; m.az = z
        m.vx = 0.0; m.vy = 0.0; m.vz = 0.0
        m.age = 0
        m.life = 160 + random.nextInt(360)
        m.seed = random.nextInt()
        m.mood = MOODS[random.nextInt(MOODS.size)]
        val colors = palette()
        m.color = colors[random.nextInt(colors.size)]
        m.spark = when (config.look) {
            Look.GLOW -> false
            Look.SPARKS -> true
            Look.MIXED -> random.nextFloat() < 0.3f
        }
        m.scale = 0.7f + random.nextFloat() * 0.6f
        m.dartIn = 10 + random.nextInt(50)
        m.outOfView = 0
        m.hidden = false
        m.shown = 1f
        m.lastShown = 1f
        motes += m
    }

    /** The top of the nearest floor under a point, in loaded chunks; the ground's top if it's in open air. */
    private fun surface(level: ClientLevel, x: Double, y: Double, z: Double): Vec3? {
        val bx = kotlin.math.floor(x).toInt()
        val bz = kotlin.math.floor(z).toInt()
        if (!level.chunkSource.hasChunk(bx shr 4, bz shr 4)) return null
        val top = y.toInt() + 6
        for (by in top downTo top - SCAN_DEPTH) {
            cursor.set(bx, by, bz)
            val state = level.getBlockState(cursor)
            if (state.isAir || state.getCollisionShape(level, cursor).isEmpty) continue
            cursor.set(bx, by + 1, bz)
            val above = level.getBlockState(cursor)
            if (!above.getCollisionShape(level, cursor).isEmpty || !above.fluidState.isEmpty) continue
            return Vec3(bx + 0.5, by + 1.0, bz + 0.5)
        }
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)
        if (ground <= level.minY) return null
        return Vec3(bx + 0.5, ground.toDouble(), bz + 0.5)
    }

    /** Whether a block stands between your eye and a point. */
    private fun blocked(level: ClientLevel, eye: Vec3, x: Double, y: Double, z: Double): Boolean {
        val target = Vec3(x, y, z)
        val distSq = eye.distanceToSqr(target)
        if (distSq < 2.25) return false
        val hit = level.clip(ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
        if (hit.type == HitResult.Type.MISS) return false
        return hit.location.distanceToSqr(eye) < distSq - 0.6
    }

    // ------------------------------------------------------------------ movement

    private fun noise(seed: Int, channel: Int, t: Float): Double {
        val f1 = 0.35f + WorldFx.hash(seed, channel, 1) * 0.6f
        val f2 = 0.9f + WorldFx.hash(seed, channel, 2) * 1.3f
        val p1 = WorldFx.hash(seed, channel, 3) * WorldFx.TAU
        val p2 = WorldFx.hash(seed, channel, 4) * WorldFx.TAU
        return (sin(t * f1 + p1) + 0.55f * sin(t * f2 + p2)).toDouble()
    }

    private fun move(level: ClientLevel, m: Mote, eye: Vec3) {
        val t = m.age / 20f
        val speed = config.speed.toDouble().coerceIn(0.1, 4.0)
        when (m.mood) {
            Mood.DRIFT -> {
                steer(m, noise(m.seed, 0, t) * 0.045 * speed, (noise(m.seed, 1, t) * 0.02 + 0.006) * speed, noise(m.seed, 2, t) * 0.045 * speed, 0.06)
            }
            Mood.RISE -> {
                steer(m, noise(m.seed, 0, t) * 0.014 * speed, (0.018 + WorldFx.hash(m.seed, 5, 0) * 0.02) * speed, noise(m.seed, 2, t) * 0.014 * speed, 0.05)
            }
            Mood.ORBIT -> {
                val radius = 0.6 + WorldFx.hash(m.seed, 6, 0) * 1.4
                val turn = (0.6 + WorldFx.hash(m.seed, 7, 0) * 0.9) * speed * (if (m.seed and 1 == 0) 1 else -1)
                val angle = t * turn + WorldFx.hash(m.seed, 8, 0) * WorldFx.TAU
                val tx = m.ax + cos(angle) * radius
                val ty = m.ay + 0.4 + sin(t * 0.8f + m.seed) * 0.35
                val tz = m.az + sin(angle) * radius
                steer(m, (tx - m.x) * 0.12, (ty - m.y) * 0.12, (tz - m.z) * 0.12, 0.25)
            }
            Mood.DART -> {
                if (--m.dartIn <= 0) {
                    // A quick dash somewhere, then hanging there a moment like a firefly.
                    val yaw = random.nextDouble() * Math.PI * 2
                    val pitch = (random.nextDouble() - 0.4) * 1.2
                    val dash = (0.12 + random.nextDouble() * 0.12) * speed
                    m.vx = cos(yaw) * cos(pitch) * dash
                    m.vy = sin(pitch) * dash
                    m.vz = sin(yaw) * cos(pitch) * dash
                    m.dartIn = 20 + random.nextInt(60)
                } else {
                    m.vx = m.vx * 0.86 + noise(m.seed, 0, t * 3f) * 0.002
                    m.vy = m.vy * 0.86 + noise(m.seed, 1, t * 3f) * 0.002
                    m.vz = m.vz * 0.86 + noise(m.seed, 2, t * 3f) * 0.002
                }
            }
        }
        val nx = m.x + m.vx
        val ny = m.y + m.vy
        val nz = m.z + m.vz
        // Only the near ones bump into blocks; far off nobody can tell.
        val near = (nx - eye.x) * (nx - eye.x) + (ny - eye.y) * (ny - eye.y) + (nz - eye.z) * (nz - eye.z) < COLLIDE_RANGE * COLLIDE_RANGE
        if (near && solidAt(level, nx, ny, nz)) {
            m.vx *= -0.5; m.vy *= -0.5; m.vz *= -0.5
            if (m.mood == Mood.RISE) m.life = min(m.life, m.age + 40)
            return
        }
        m.x = nx; m.y = ny; m.z = nz
    }

    private fun steer(m: Mote, tx: Double, ty: Double, tz: Double, k: Double) {
        m.vx += (tx - m.vx) * k
        m.vy += (ty - m.vy) * k
        m.vz += (tz - m.vz) * k
    }

    private fun solidAt(level: ClientLevel, x: Double, y: Double, z: Double): Boolean {
        cursor.set(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(), kotlin.math.floor(z).toInt())
        val state = level.getBlockState(cursor)
        return !state.isAir && !state.getCollisionShape(level, cursor).isEmpty
    }

    private fun palette(): IntArray = when (config.palette) {
        Palette.THEME -> intArrayOf(Theme.accent, Theme.mix(Theme.accent, 0xFFFFFF, 0.45f), Theme.mix(Theme.accent, 0xFFFFFF, 0.2f))
        Palette.PRISM -> Form.PRISM.colors
        Palette.EMBER -> Form.EMBER.colors
        Palette.SERAPH -> Form.SERAPH.colors
        Palette.ABYSS -> Form.ABYSS.colors
        Palette.ASTRAL -> Form.ASTRAL.colors
        Palette.SYLPH -> Form.SYLPH.colors
        Palette.CUSTOM -> intArrayOf(config.customColor, Theme.mix(config.customColor, 0xFFFFFF, 0.35f))
    }

    // ------------------------------------------------------------------ drawing

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        if (motes.isEmpty() || !config.enabled) return
        val client = McCompat.mc
        val camera = client.gameRenderer.mainCamera()
        val eye = camera.position()
        val forward = camera.forwardVector()
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble()
        val cosLimit = viewCone().cosWide
        val reach = reach()
        val time = (System.currentTimeMillis() % 3_600_000L) / 1000f
        val size = config.size.coerceIn(0.2f, 4f)
        val bright = config.brightness.coerceIn(0.1f, 3f)

        WorldFx.submit(poseStack, collector, 1f, solids = {}, lights = { fx ->
            for (m in motes) {
                val x = m.px + (m.x - m.px) * partial - eye.x
                val y = m.py + (m.y - m.py) * partial - eye.y
                val z = m.pz + (m.z - m.pz) * partial - eye.z
                val distSq = x * x + y * y + z * z
                if (!inCone(x, y, z, distSq, forward.x(), forward.y(), forward.z(), cosLimit)) continue
                val dist = sqrt(distSq)
                val shown = m.lastShown + (m.shown - m.lastShown) * partial.toFloat()
                val life = fade(m.age + partial.toFloat(), m.life)
                val far = 1f - smooth(reach * 0.82, reach, dist)
                val alpha = shown * life * far
                if (alpha <= 0.01f) continue
                // Far motes grow so they still read as points of light, not vanish below a pixel.
                val grow = max(1.0, dist / 12.0).pow(0.85).toFloat()
                val r = BASE_RADIUS * size * m.scale * grow
                val twinkle = 0.7f + 0.3f * sin(time * (2.2f + WorldFx.hash(m.seed, 9, 0) * 2.5f) + m.seed)
                drawMote(fx, x.toFloat(), y.toFloat(), z.toFloat(), r, m, alpha * bright, twinkle, dist)
            }
        })
    }

    private fun drawMote(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, alpha: Float, twinkle: Float, dist: Double) {
        val segments = if (dist > 48) 6 else 10
        fx.soft(x, y, z, r * 3.4f, fx.light(argb(m.color, alpha * 0.22f * twinkle), true), segments)
        if (m.spark) {
            fx.star(x, y, z, r * 2.6f * twinkle, fx.light(argb(WorldFx.hot(m.color, 0.45f), alpha * twinkle), true))
        } else {
            fx.soft(x, y, z, r * 1.25f, fx.light(argb(WorldFx.hot(m.color, 0.35f), alpha * 0.7f), true), 6)
            fx.soft(x, y, z, r * 0.55f, fx.light(argb(WorldFx.hot(m.color, 0.8f), alpha * (0.6f + 0.4f * twinkle)), true), 6)
        }
    }

    private fun argb(color: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xFFFFFF)

    /** In over a second and a half, out over the last two. */
    private fun fade(age: Float, life: Int): Float = min(min(age / 30f, (life - age) / 40f), 1f).coerceAtLeast(0f)

    private fun smooth(from: Double, to: Double, value: Double): Float {
        val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()
    }

    private val MOODS = Mood.entries.toTypedArray()

    private const val MAX = 1000
    private const val NEAR = 3.0
    private const val SCAN_DEPTH = 30
    private const val SPAWN_TRIES = 6
    private const val MAX_TRIES = 16
    private const val SIGHT_CHECKS = 12
    private const val LET_GO_TICKS = 50
    private const val COLLIDE_RANGE = 48.0
    private const val BASE_RADIUS = 0.035f
}
