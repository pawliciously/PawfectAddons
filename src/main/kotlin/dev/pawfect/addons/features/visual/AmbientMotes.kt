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
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object AmbientMotes {

    private val config get() = ModuleSwitch.view("visuals", ConfigManager.features.ambientMotes)

    private enum class Mood { DRIFT, ORBIT, DART, RISE, EMBER, FEATHER, VOID, STAR, SHOOTING, FAIRY, SHARD }

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
        var spin = 0f
        var timer = 0
        var eventAt = -1000
    }

    private val motes = ArrayList<Mote>(MAX)
    private val pool = ArrayDeque<Mote>()
    private val random = Random(System.nanoTime())
    private val cursor = BlockPos.MutableBlockPos()
    private var lastPalette: Palette? = null

    private var windX = 0.0
    private var windZ = 0.0

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

    private fun tick() {
        val client = McCompat.mc
        val level = client.level
        if (!config.enabled || level == null || client.player == null || (!config.inDungeons && SkyBlockData.inDungeons)) {
            if (motes.isNotEmpty()) clear()
            return
        }
        if (config.palette != lastPalette) {
            clear()
            lastPalette = config.palette
        }
        val eye = client.gameRenderer.mainCamera().position()
        val reach = reach()
        val gust = level.gameTime / 240.0
        windX = sin(gust) * 0.012
        windZ = cos(gust * 0.7) * 0.012

        var i = 0
        while (i < motes.size) {
            val m = motes[i]
            m.px = m.x; m.py = m.y; m.pz = m.z
            move(level, m, eye)
            m.age++
            val dx = m.x - eye.x
            val dy = m.y - eye.y
            val dz = m.z - eye.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (m.age >= m.life || distSq > reach * reach * 1.3) {
                pool.addLast(m)
                motes[i] = motes[motes.size - 1]
                motes.removeAt(motes.size - 1)
                continue
            }
            i++
        }

        val wanted = config.count.coerceIn(10, MAX)
        val attempts = min(wanted - motes.size, max(SPAWN_TRIES, wanted / 40).coerceAtMost(MAX_TRIES))
        repeat(attempts.coerceAtLeast(0)) { spawn(level, eye, reach) }
    }

    private fun reach(): Double {
        val loaded = (McCompat.mc.options.effectiveRenderDistance * 16 + 8).toDouble()
        return min(config.distance.toDouble(), loaded).coerceAtLeast(8.0)
    }

    private fun moodFor(palette: Palette): Mood = when (palette) {
        Palette.EMBER -> Mood.EMBER
        Palette.SERAPH -> Mood.FEATHER
        Palette.ABYSS -> Mood.VOID
        Palette.ASTRAL -> if (random.nextFloat() < SHOOTING_CHANCE) Mood.SHOOTING else Mood.STAR
        Palette.SYLPH -> Mood.FAIRY
        Palette.PRISM -> Mood.SHARD
        Palette.THEME, Palette.CUSTOM -> WANDERING[random.nextInt(WANDERING.size)]
    }

    private fun spawn(level: ClientLevel, eye: Vec3, reach: Double) {
        val yaw = random.nextDouble() * Math.PI * 2
        val pitch = (random.nextDouble() - 0.6) * 1.0
        val dx = cos(yaw) * cos(pitch)
        val dy = sin(pitch)
        val dz = sin(yaw) * cos(pitch)
        val distance = NEAR + (reach - NEAR) * random.nextDouble().pow(1.7)
        val surface = surface(level, eye.x + dx * distance, eye.y + dy * distance, eye.z + dz * distance) ?: return
        val mood = moodFor(config.palette)
        val lift = when (mood) {
            Mood.EMBER -> 0.1 + random.nextDouble() * 0.5
            Mood.FEATHER -> 1.5 + random.nextDouble() * 4.0
            Mood.STAR -> 0.6 + random.nextDouble() * 3.5
            Mood.SHOOTING -> 7.0 + random.nextDouble() * 9.0
            else -> 0.15 + random.nextDouble() * 1.4
        }
        val x = surface.x + (random.nextDouble() - 0.5) * 0.9
        val y = surface.y + lift
        val z = surface.z + (random.nextDouble() - 0.5) * 0.9
        val ox = x - eye.x
        val oy = y - eye.y
        val oz = z - eye.z
        val distSq = ox * ox + oy * oy + oz * oz
        if (distSq > reach * reach) return
        if (solidAt(level, x, y, z)) return

        val speed = config.speed.toDouble().coerceIn(0.1, 4.0)
        val m = pool.removeLastOrNull() ?: Mote()
        m.x = x; m.y = y; m.z = z
        m.px = x; m.py = y; m.pz = z
        m.ax = x; m.ay = y; m.az = z
        m.vx = 0.0; m.vy = 0.0; m.vz = 0.0
        m.age = 0
        m.mood = mood
        m.life = when (mood) {
            Mood.EMBER -> 70 + random.nextInt(110)
            Mood.SHOOTING -> 18 + random.nextInt(14)
            Mood.STAR -> 300 + random.nextInt(400)
            else -> 180 + random.nextInt(340)
        }
        m.timer = when (mood) {
            Mood.DART -> 10 + random.nextInt(50)
            Mood.VOID -> 40 + random.nextInt(100)
            Mood.FAIRY -> 40 + random.nextInt(80)
            else -> 1
        }
        if (mood == Mood.SHOOTING) {
            val yaw = random.nextDouble() * Math.PI * 2
            val pitch = -(0.2 + random.nextDouble() * 0.3)
            val v = (0.35 + random.nextDouble() * 0.25) * speed
            m.vx = cos(yaw) * cos(pitch) * v
            m.vy = sin(pitch) * v
            m.vz = sin(yaw) * cos(pitch) * v
        }
        m.seed = random.nextInt()
        m.spin = random.nextFloat() * WorldFx.TAU
        m.eventAt = -1000
        val colors = palette()
        m.color = colors[random.nextInt(colors.size)]
        if (mood == Mood.VOID && colors.size >= 3) m.color = if (random.nextBoolean()) colors[0] else colors[2]
        m.spark = when (config.look) {
            Look.GLOW -> false
            Look.SPARKS -> true
            Look.MIXED -> random.nextFloat() < 0.3f
        }
        m.scale = 0.7f + random.nextFloat() * 0.6f
        motes += m
    }

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
        val s = m.seed
        when (m.mood) {
            Mood.DRIFT -> {
                steer(m, noise(s, 0, t) * 0.045 * speed, (noise(s, 1, t) * 0.02 + 0.006) * speed, noise(s, 2, t) * 0.045 * speed, 0.06)
            }
            Mood.RISE -> {
                steer(m, noise(s, 0, t) * 0.014 * speed, (0.018 + WorldFx.hash(s, 5, 0) * 0.02) * speed, noise(s, 2, t) * 0.014 * speed, 0.05)
            }
            Mood.ORBIT -> {
                val radius = 0.6 + WorldFx.hash(s, 6, 0) * 1.4
                val turn = (0.6 + WorldFx.hash(s, 7, 0) * 0.9) * speed * (if (s and 1 == 0) 1 else -1)
                val angle = t * turn + WorldFx.hash(s, 8, 0) * WorldFx.TAU
                val tx = m.ax + cos(angle) * radius
                val ty = m.ay + 0.4 + sin(t * 0.8f + s) * 0.35
                val tz = m.az + sin(angle) * radius
                steer(m, (tx - m.x) * 0.12, (ty - m.y) * 0.12, (tz - m.z) * 0.12, 0.25)
            }
            Mood.DART -> {
                if (--m.timer <= 0) {
                    val yaw = random.nextDouble() * Math.PI * 2
                    val pitch = (random.nextDouble() - 0.4) * 1.2
                    val dash = (0.12 + random.nextDouble() * 0.12) * speed
                    m.vx = cos(yaw) * cos(pitch) * dash
                    m.vy = sin(pitch) * dash
                    m.vz = sin(yaw) * cos(pitch) * dash
                    m.timer = 20 + random.nextInt(60)
                } else {
                    m.vx = m.vx * 0.86 + noise(s, 0, t * 3f) * 0.002
                    m.vy = m.vy * 0.86 + noise(s, 1, t * 3f) * 0.002
                    m.vz = m.vz * 0.86 + noise(s, 2, t * 3f) * 0.002
                }
            }
            Mood.EMBER -> {
                val f = m.age / m.life.toDouble()
                val rise = (0.035 + WorldFx.hash(s, 5, 0) * 0.035) * (1 - f * 0.7) * speed
                steer(m, (noise(s, 0, t * 4f) * 0.018 + windX) * speed, rise, (noise(s, 2, t * 4f) * 0.018 + windZ) * speed, 0.18)
            }
            Mood.FEATHER -> {
                val phase = t * (1.3f + WorldFx.hash(s, 6, 0) * 0.8f) * speed.toFloat() + WorldFx.hash(s, 7, 0) * WorldFx.TAU
                val heading = WorldFx.hash(s, 8, 0) * WorldFx.TAU
                val c = cos(phase).toDouble()
                val side = c * 0.035 * speed
                steer(m, cos(heading) * side + windX * 0.5, (0.003 - 0.02 * c * c) * speed, sin(heading) * side + windZ * 0.5, 0.3)
            }
            Mood.VOID -> {
                val tight = 1f - 0.75f * (m.age / m.life.toFloat())
                val radius = (0.5f + WorldFx.hash(s, 6, 0) * 1.2f) * tight
                val dir = if (s and 1 == 0) 1f else -1f
                m.spin += (0.9f + WorldFx.hash(s, 7, 0) * 0.6f) / tight * dir * speed.toFloat() / 20f
                m.ay -= 0.004 * speed
                if (--m.timer <= 0) blink(level, m, 1.5 + random.nextDouble() * 2.0)
                val tx = m.ax + cos(m.spin) * radius
                val ty = m.ay + sin(t * 0.9f + s) * 0.2
                val tz = m.az + sin(m.spin) * radius
                steer(m, (tx - m.x) * 0.15, (ty - m.y) * 0.15, (tz - m.z) * 0.15, 0.3)
            }
            Mood.STAR -> {
                steer(m, noise(s, 0, t * 0.3f) * 0.004 * speed, noise(s, 1, t * 0.3f) * 0.003 * speed, noise(s, 2, t * 0.3f) * 0.004 * speed, 0.05)
            }
            Mood.SHOOTING -> Unit
            Mood.FAIRY -> {
                m.ax += noise(s, 3, t * 0.25f) * 0.012 * speed
                m.ay += noise(s, 4, t * 0.2f) * 0.004 * speed
                m.az += noise(s, 5, t * 0.25f) * 0.012 * speed
                if (--m.timer <= 0) {
                    m.timer = 50 + random.nextInt(110)
                    val yaw = random.nextDouble() * Math.PI * 2
                    val hop = 1.5 + random.nextDouble() * 2.5
                    m.ax += cos(yaw) * hop
                    m.ay += (random.nextDouble() - 0.35) * 1.2
                    m.az += sin(yaw) * hop
                }
                m.spin += (1.6f + WorldFx.hash(s, 6, 0)) * speed.toFloat() / 20f
                val loop = 0.4f + WorldFx.hash(s, 7, 0) * 0.6f
                val heading = WorldFx.hash(s, 8, 0) * WorldFx.TAU
                val u = sin(m.spin) * loop
                val w = sin(m.spin * 2f) * loop * 0.5f
                val tx = m.ax + cos(heading) * u - sin(heading) * w
                val ty = m.ay + sin(m.spin * 1.5f) * 0.25f
                val tz = m.az + sin(heading) * u + cos(heading) * w
                seek(m, tx, ty, tz, 0.15, 0.28 * speed, 0.3)
            }
            Mood.SHARD -> {
                if (--m.timer <= 0) {
                    m.timer = 30 + random.nextInt(70)
                    val yaw = random.nextDouble() * Math.PI * 2
                    val pitch = (random.nextDouble() - 0.5) * 0.7
                    val glide = (0.025 + WorldFx.hash(s, 6, 0) * 0.03) * speed
                    m.vx = cos(yaw) * cos(pitch) * glide
                    m.vy = sin(pitch) * glide
                    m.vz = sin(yaw) * cos(pitch) * glide
                    m.eventAt = m.age
                }
                m.vy += sin(t * 2f + s) * 0.0006
            }
        }
        val nx = m.x + m.vx
        val ny = m.y + m.vy
        val nz = m.z + m.vz
        if (m.mood != Mood.SHOOTING) {
            val near = (nx - eye.x) * (nx - eye.x) + (ny - eye.y) * (ny - eye.y) + (nz - eye.z) * (nz - eye.z) < COLLIDE_RANGE * COLLIDE_RANGE
            if ((near || m.mood == Mood.FEATHER) && solidAt(level, nx, ny, nz)) {
                when (m.mood) {
                    Mood.FEATHER -> {
                        m.vx = 0.0; m.vy = 0.0; m.vz = 0.0
                        m.life = min(m.life, m.age + 30)
                    }
                    Mood.SHARD -> {
                        m.vx = -m.vx; m.vy = -m.vy; m.vz = -m.vz
                        m.eventAt = m.age
                    }
                    else -> {
                        m.vx *= -0.5; m.vy *= -0.5; m.vz *= -0.5
                        if (m.mood == Mood.RISE) m.life = min(m.life, m.age + 40)
                        if (m.mood == Mood.EMBER) m.life = min(m.life, m.age + 15)
                    }
                }
                return
            }
        }
        m.x = nx; m.y = ny; m.z = nz
    }

    private fun blink(level: ClientLevel, m: Mote, jump: Double) {
        m.timer = 60 + random.nextInt(100)
        val yaw = random.nextDouble() * Math.PI * 2
        val pitch = (random.nextDouble() - 0.5) * 0.8
        val dx = cos(yaw) * cos(pitch) * jump
        val dy = sin(pitch) * jump
        val dz = sin(yaw) * cos(pitch) * jump
        if (solidAt(level, m.x + dx, m.y + dy, m.z + dz)) return
        m.x += dx; m.y += dy; m.z += dz
        m.ax += dx; m.ay += dy; m.az += dz
        m.px = m.x; m.py = m.y; m.pz = m.z
        m.eventAt = m.age
    }

    private fun steer(m: Mote, tx: Double, ty: Double, tz: Double, k: Double) {
        m.vx += (tx - m.vx) * k
        m.vy += (ty - m.vy) * k
        m.vz += (tz - m.vz) * k
    }

    private fun seek(m: Mote, tx: Double, ty: Double, tz: Double, gain: Double, cap: Double, k: Double) {
        var dx = (tx - m.x) * gain
        var dy = (ty - m.y) * gain
        var dz = (tz - m.z) * gain
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len > cap) {
            val f = cap / len
            dx *= f; dy *= f; dz *= f
        }
        steer(m, dx, dy, dz, k)
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

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        if (motes.isEmpty() || !config.enabled) return
        val client = McCompat.mc
        val eye = client.gameRenderer.mainCamera().position()
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val reach = reach()
        val time = (System.currentTimeMillis() % 3_600_000L) / 1000f
        val size = config.size.coerceIn(0.2f, 4f)
        val bright = config.brightness.coerceIn(0.1f, 3f)

        WorldFx.submit(poseStack, collector, 1f, solids = {}, lights = { fx ->
            for (m in motes) {
                val x = (m.px + (m.x - m.px) * partial - eye.x).toFloat()
                val y = (m.py + (m.y - m.py) * partial - eye.y).toFloat()
                val z = (m.pz + (m.z - m.pz) * partial - eye.z).toFloat()
                val dist = sqrt(x * x + y * y + z * z)
                val age = m.age + partial
                val alpha = fade(m, age) * (1f - smooth(reach * 0.82, reach, dist.toDouble())) * bright
                if (alpha <= 0.01f) continue
                val grow = max(1f, dist / 12f).pow(0.85f)
                val r = BASE_RADIUS * size * m.scale * grow
                val segments = if (dist > 48) 6 else 10
                val near = dist < 64
                when (m.mood) {
                    Mood.EMBER -> ember(fx, x, y, z, r, m, alpha, age, time, grow, segments)
                    Mood.FEATHER -> feather(fx, x, y, z, r, m, alpha, time, segments, near)
                    Mood.VOID -> void(fx, x, y, z, r, m, alpha, age, segments, near)
                    Mood.STAR -> star(fx, x, y, z, r, m, alpha, time, segments)
                    Mood.SHOOTING -> shooting(fx, x, y, z, r, m, alpha)
                    Mood.FAIRY -> fairy(fx, x, y, z, r, m, alpha, time, grow, segments)
                    Mood.SHARD -> shard(fx, x, y, z, r, m, alpha, age, time, segments, near)
                    else -> wander(fx, x, y, z, r, m, alpha, time, segments)
                }
            }
        })
    }

    private fun Fx.glow(rgb: Int, alpha: Float): Int = light(argb(rgb, alpha), true)

    private fun Fx.tail(x: Float, y: Float, z: Float, m: Mote, length: Float, width: Float, color: Int) {
        line(x, y, z, x - m.vx.toFloat() * length, y - m.vy.toFloat() * length, z - m.vz.toFloat() * length, width, 0f, color, 0)
    }

    private fun wander(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, time: Float, segments: Int) {
        val twinkle = 0.7f + 0.3f * sin(time * (2.2f + WorldFx.hash(m.seed, 9, 0) * 2.5f) + m.seed)
        fx.soft(x, y, z, r * 3.4f, fx.glow(m.color, a * 0.22f * twinkle), segments)
        if (m.spark) {
            fx.star(x, y, z, r * 2.6f * twinkle, fx.glow(WorldFx.hot(m.color, 0.45f), a * twinkle))
        } else {
            fx.soft(x, y, z, r * 1.25f, fx.glow(WorldFx.hot(m.color, 0.35f), a * 0.7f), 6)
            fx.soft(x, y, z, r * 0.55f, fx.glow(WorldFx.hot(m.color, 0.8f), a * (0.6f + 0.4f * twinkle)), 6)
        }
    }

    private fun ember(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, age: Float, time: Float, grow: Float, segments: Int) {
        val f = (age / m.life).coerceIn(0f, 1f)
        val colors = Form.EMBER.colors
        val c = if (f < 0.4f) Theme.mix(colors[2], colors[1], f / 0.4f) else Theme.mix(colors[1], colors[0], (f - 0.4f) / 0.6f)
        val flicker = 0.65f + 0.35f * sin(time * 19f + m.seed) * sin(time * 7.3f + m.seed * 0.37f)
        fx.tail(x, y, z, m, 5f * max(1f, grow * 0.6f), r * 0.55f, fx.glow(WorldFx.hot(c, 0.3f), a * 0.7f * flicker))
        fx.soft(x, y, z, r * 2.6f, fx.glow(c, a * 0.28f * flicker), segments)
        fx.soft(x, y, z, r * 0.7f, fx.glow(WorldFx.hot(c, 0.65f * (1f - f)), a * flicker), 6)
    }

    private fun feather(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, time: Float, segments: Int, near: Boolean) {
        val breathe = 0.75f + 0.25f * sin(time * 1.4f + m.seed)
        fx.soft(x, y, z, r * 3.8f, fx.glow(m.color, a * 0.2f * breathe), segments)
        fx.soft(x, y, z, r * 1.1f, fx.glow(WorldFx.hot(m.color, 0.45f), a * 0.8f), 6)
        if (near) fx.halo(x, y, z, r * 1.9f, r * 2.5f, fx.glow(m.color, a * 0.3f * breathe), 12)
        val glint = max(0f, sin(time * 1.7f + m.seed * 0.13f)).pow(12)
        if (glint > 0.05f) fx.star(x, y, z, r * 3.4f * glint, fx.glow(WorldFx.hot(m.color, 0.6f), a * glint))
    }

    private fun void(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, age: Float, segments: Int, near: Boolean) {
        val since = age - m.eventAt
        val arrive = (since / 6f).coerceIn(0f, 1f)
        fx.soft(x, y, z, r * 4.4f, fx.glow(Form.ABYSS.colors[1], a * 0.45f), segments)
        if (near) {
            fx.halo(x, y, z, r * 1.1f, r * 1.9f, fx.glow(m.color, a * 0.6f * arrive), 12)
        } else {
            fx.soft(x, y, z, r * 1.6f, fx.glow(m.color, a * 0.4f * arrive), 6)
        }
        fx.soft(x, y, z, r * 0.4f * arrive, fx.glow(WorldFx.hot(m.color, 0.7f), a), 6)
        if (since < BLINK_RING) {
            val k = since / BLINK_RING
            fx.halo(x, y, z, r * (1.5f + k * 7f), r * (2.2f + k * 8f), fx.glow(m.color, a * (1f - k) * 0.7f), 14)
        }
    }

    private fun star(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, time: Float, segments: Int) {
        val rate = 1.5f + WorldFx.hash(m.seed, 9, 0) * 3f
        val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin(time * rate + m.seed)).pow(3)
        val flare = max(0f, sin(time * 0.37f + m.seed)).pow(30)
        fx.soft(x, y, z, r * 2.8f, fx.glow(m.color, a * 0.16f * twinkle), segments)
        fx.star(x, y, z, r * (2.2f + flare * 3f) * twinkle, fx.glow(WorldFx.hot(m.color, 0.55f), a * (twinkle + flare * 0.5f)))
    }

    private fun shooting(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float) {
        fx.tail(x, y, z, m, 9f, r * 2.2f, fx.glow(m.color, a * 0.22f))
        fx.tail(x, y, z, m, 9f, r * 0.7f, fx.glow(WorldFx.hot(m.color, 0.75f), a))
        fx.star(x, y, z, r * 2.6f, fx.glow(WorldFx.hot(m.color, 0.6f), a))
    }

    private fun fairy(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, time: Float, grow: Float, segments: Int) {
        val rate = 1.4f + WorldFx.hash(m.seed, 9, 0) * 1.2f
        val pulse = max(0f, sin(time * rate + m.seed))
        val glow = 0.15f + 0.85f * pulse * pulse
        fx.tail(x, y, z, m, 4f * max(1f, grow * 0.6f), r * 0.4f, fx.glow(m.color, a * 0.5f * glow))
        fx.soft(x, y, z, r * 3.2f, fx.glow(m.color, a * 0.26f * glow), segments)
        fx.soft(x, y, z, r * 0.6f, fx.glow(WorldFx.hot(m.color, 0.7f), a * (0.35f + 0.65f * glow)), 6)
        val dust = max(0f, sin(time * 5.1f + m.seed * 0.7f)).pow(20)
        if (dust > 0.05f) fx.star(x, y, z, r * 2f * dust, fx.glow(WorldFx.hot(m.color, 0.5f), a * dust))
    }

    private fun shard(fx: Fx, x: Float, y: Float, z: Float, r: Float, m: Mote, a: Float, age: Float, time: Float, segments: Int, near: Boolean) {
        val colors = Form.PRISM.colors
        val n = colors.size
        val cycle = time * 0.25f + WorldFx.hash(m.seed, 9, 0) * n
        val i = cycle.toInt()
        val c = Theme.mix(colors[i % n], colors[(i + 1) % n], cycle - i)
        val since = age - m.eventAt
        val k = (since / SHARD_FLASH).coerceIn(0f, 1f)
        val flash = (1f - k) * (1f - k)
        fx.soft(x, y, z, r * 3f, fx.glow(c, a * 0.2f), segments)
        fx.star(x, y, z, r * (1.8f + flash * 4.5f), fx.glow(WorldFx.hot(c, 0.45f + 0.45f * flash), a * (0.6f + 0.4f * flash)))
        if (flash > 0f && near) {
            fx.halo(x, y, z, r * (1f + k * 5f), r * (1.6f + k * 6f), fx.glow(colors[(i + 2) % n], a * (1f - k) * 0.6f), 14)
        }
    }

    private fun argb(color: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xFFFFFF)

    private fun fade(m: Mote, age: Float): Float {
        val fadeIn = when (m.mood) {
            Mood.SHOOTING -> 3f
            Mood.EMBER -> 6f
            else -> 30f
        }
        val fadeOut = when (m.mood) {
            Mood.SHOOTING -> 10f
            Mood.EMBER -> 35f
            else -> 40f
        }
        return min(min(age / fadeIn, (m.life - age) / fadeOut), 1f).coerceAtLeast(0f)
    }

    private fun smooth(from: Double, to: Double, value: Double): Float {
        val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()
    }

    private val WANDERING = arrayOf(Mood.DRIFT, Mood.ORBIT, Mood.DART, Mood.RISE)

    private const val MAX = 1000
    private const val NEAR = 3.0
    private const val SCAN_DEPTH = 30
    private const val SPAWN_TRIES = 6
    private const val MAX_TRIES = 16
    private const val COLLIDE_RANGE = 48.0
    private const val BASE_RADIUS = 0.035f
    private const val SHOOTING_CHANCE = 0.015f
    private const val BLINK_RING = 14f
    private const val SHARD_FLASH = 10f
}
