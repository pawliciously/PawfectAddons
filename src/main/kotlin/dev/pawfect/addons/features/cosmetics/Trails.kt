package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.features.cosmetics.WorldFx.TAU
import dev.pawfect.addons.features.cosmetics.WorldFx.hash
import dev.pawfect.addons.features.cosmetics.celestial.Solid
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two ribbons of silk twisting round each other down the path you take, a soft glowing core
 * between them, fine bright threads, and sparkles that come loose and float up as it fades.
 */
object Trails {

    private const val MAX_POINTS = 64
    private const val MAX_PLAYERS = 64
    private const val SAMPLE_MS = 20L
    private const val IDLE_MS = 4_000L
    private const val MIN_STEP = 0.02
    private const val BREAK_STEP = 8.0
    private const val FADE_FLOOR = 0.6f
    private const val FADE_CEIL = 4.2f
    private const val VIEW_LIMIT = 96.0

    /** Smoothing steps between samples, so the ribbons curve instead of kinking. */
    private const val SUB = 3

    /** Turns of the twist per block travelled. */
    private const val TWIST = 2.4f

    private val tracks = HashMap<UUID, Track>()
    private val showcase = HashMap<UUID, Track>()

    private val config get() = ModuleSwitch.view("cosmetics", ConfigManager.features.cosmetics)

    /** A sample of the path. [dist] is how far along the path it is, so the twist stays put on the ground. */
    internal class Point(val x: Double, val y: Double, val z: Double, val at: Long, val strength: Float, val dist: Float, val seed: Int)

    internal class Track {
        val points = ArrayDeque<Point>()
        var lastSample = 0L
        var lastSeen = 0L
        var count = 0
    }

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    fun onTick() {
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        tracks.entries.removeIf { now - it.value.lastSeen > IDLE_MS }
    }

    fun clear() {
        tracks.clear()
        showcase.clear()
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val mode = config.trails
        if (!config.enabled || mode == Visibility.NONE) return

        val client = McCompat.mc
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val self = client.player?.uuid
        val now = System.currentTimeMillis()
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)

        val hideSelf = !config.firstPerson && client.options.cameraType.isFirstPerson

        val jobs = ArrayList<Pair<ResolvedTrail, Track>>()

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (hideSelf && player.uuid == self) continue
            if (player.isSpectator || player.isInvisible) continue

            val trail = Cosmetics.trailFor(player.uuid) ?: continue
            val position = waist(player, partial)
            if (position.distanceToSqr(camera) > VIEW_LIMIT * VIEW_LIMIT) continue

            val track = tracks.getOrPut(player.uuid) { Track() }
            track.lastSeen = now
            sample(track, position, now)
            trim(track, now, trail.length)

            if (track.points.size >= 2) jobs += trail to track
        }

        if (tracks.size > MAX_PLAYERS) {
            val stale = tracks.entries.sortedBy { it.value.lastSeen }.take(tracks.size - MAX_PLAYERS)
            stale.forEach { tracks.remove(it.key) }
        }

        if (jobs.isEmpty()) return
        submit(poseStack, collector, jobs, camera, now, WorldFx.shade(level, camera))
    }

    /** Draws the trail of someone walking the menu scene, who has no entity in any world. */
    fun submitShowcase(poseStack: PoseStack, collector: SubmitNodeCollector, uuid: UUID, feet: Vec3, height: Double, camera: Vec3) {
        if (!Cosmetics.showsOffWorld(config.trails, uuid)) return
        val trail = Cosmetics.trailFor(uuid) ?: return
        val now = System.currentTimeMillis()
        val track = showcase.getOrPut(uuid) { Track() }
        sample(track, Vec3(feet.x, feet.y + height * 0.45, feet.z), now)
        trim(track, now, trail.length)
        if (track.points.size >= 2) submit(poseStack, collector, listOf(trail to track), camera, now, 0.9f)
    }

    private fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, jobs: List<Pair<ResolvedTrail, Track>>, camera: Vec3, now: Long, shade: Float) {
        val time = if (config.animate) (now % 600_000L) / 1000f else 0f
        val ribbons = jobs.mapNotNull { (trail, track) -> Ribbon.of(trail, track, camera, now, time) }
        if (ribbons.isEmpty()) return
        WorldFx.submit(poseStack, collector, shade, solids = {}, lights = { fx -> ribbons.forEach { it.draw(fx) } })
    }

    private fun waist(player: AbstractClientPlayer, partial: Float): Vec3 {
        val base = player.getPosition(partial)
        return Vec3(base.x, base.y + player.bbHeight * 0.45, base.z)
    }

    private fun sample(track: Track, position: Vec3, now: Long) {
        val last = track.points.lastOrNull()

        if (last != null) {
            val step = sqrt(
                (position.x - last.x) * (position.x - last.x) +
                    (position.y - last.y) * (position.y - last.y) +
                    (position.z - last.z) * (position.z - last.z),
            )

            if (step > BREAK_STEP) {
                track.points.clear()
            } else {
                if (now - track.lastSample < SAMPLE_MS) return
                if (step < MIN_STEP) return

                val seconds = ((now - last.at).coerceAtLeast(1L)) / 1000.0
                val speed = (step / seconds).toFloat()
                val strength = ((speed - FADE_FLOOR) / (FADE_CEIL - FADE_FLOOR)).coerceIn(0f, 1f)

                track.points.addLast(Point(position.x, position.y, position.z, now, strength, last.dist + step.toFloat(), track.count++))
                track.lastSample = now
                while (track.points.size > MAX_POINTS) track.points.removeFirst()
                return
            }
        }

        track.points.addLast(Point(position.x, position.y, position.z, now, 0f, 0f, track.count++))
        track.lastSample = now
    }

    private fun trim(track: Track, now: Long, length: Float) {
        val window = (length * 1000f).toLong()
        while (track.points.size > 2 && now - track.points.first().at > window) {
            track.points.removeFirst()
        }
        while (track.points.size > MAX_POINTS) track.points.removeFirst()
    }

    /** One trail smoothed into rings, camera relative, ready to draw. */
    internal class Ribbon(
        private val trail: ResolvedTrail,
        private val time: Float,
        private val now: Long,
        private val points: List<Point>,
        private val camera: Vec3,
        private val px: FloatArray,
        private val py: FloatArray,
        private val pz: FloatArray,
        private val strength: FloatArray,
        private val dist: FloatArray,
    ) {
        private val size = px.size
        private val tx = FloatArray(size)
        private val ty = FloatArray(size)
        private val tz = FloatArray(size)
        private val v = Vector3f()
        private val nrm = Vector3f()

        init {
            for (i in 0 until size) {
                val a = (i - 1).coerceAtLeast(0)
                val b = (i + 1).coerceAtMost(size - 1)
                v.set(px[b] - px[a], py[b] - py[a], pz[b] - pz[a])
                if (v.lengthSquared() < 1.0e-10f) v.set(1f, 0f, 0f)
                v.normalize()
                tx[i] = v.x
                ty[i] = v.y
                tz[i] = v.z
            }
        }

        private fun s(i: Int) = i / (size - 1f)

        private fun fade(i: Int) = s(i).pow(1.4f) * strength[i]

        private fun width(i: Int) = trail.width * 0.5f * s(i).pow(0.7f)

        fun draw(fx: Fx) {
            val additive = trail.glow
            for (k in 0..1) strand(fx, k, additive)
            core(fx, 1.5f, 0.18f, 0.1f, additive)
            core(fx, 0.5f, 0.7f, 0.45f, additive)
            sparkles(fx, additive)
        }

        /** Ring [i]'s frame: [out] gets the radial direction at twist angle [phi]. */
        private fun radial(i: Int, phi: Float, out: Vector3f, across: Vector3f) {
            // Up made square to the path; sideways when the path runs straight up.
            var nx = -tx[i] * ty[i]
            var ny = 1f - ty[i] * ty[i]
            var nz = -tz[i] * ty[i]
            if (nx * nx + ny * ny + nz * nz < 1.0e-4f) {
                nx = 1f - tx[i] * tx[i]
                ny = -ty[i] * tx[i]
                nz = -tz[i] * tx[i]
            }
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= nl
            ny /= nl
            nz /= nl
            val bx = ty[i] * nz - tz[i] * ny
            val by = tz[i] * nx - tx[i] * nz
            val bz = tx[i] * ny - ty[i] * nx
            val c = cos(phi)
            val sn = sin(phi)
            out.set(nx * c + bx * sn, ny * c + by * sn, nz * c + bz * sn)
            across.set(-nx * sn + bx * c, -ny * sn + by * c, -nz * sn + bz * c)
        }

        /** A silk band wound round the path, lit as it turns, with a bright thread down its middle. */
        private fun strand(fx: Fx, k: Int, additive: Boolean) {
            val r = Vector3f()
            val a = Vector3f()
            var lastL = 0f
            val prev = FloatArray(6)
            val thread = FloatArray(3)
            var prevSilk = 0
            var prevThread = 0
            for (i in 0 until size) {
                val phi = dist[i] * TWIST + k * PI.toFloat() + time * 0.9f
                radial(i, phi, r, a)
                val w = width(i)
                val f = fade(i)
                val cx = px[i] + r.x * w * 0.6f
                val cy = py[i] + r.y * w * 0.6f
                val cz = pz[i] + r.z * w * 0.6f
                val half = w * 0.34f
                val base = trail.palette.colorAt(s(i) * 0.85f + k * 0.15f, time) and 0xFFFFFF
                val lit = Solid.lit(base or 0xFF000000.toInt(), r, 0.5f, 0.7f) and 0xFFFFFF
                val silk = fx.light(lit or ((f * 0.85f * 255f).toInt().coerceIn(0, 255) shl 24), additive)
                val glint = fx.light(WorldFx.hot(base, 0.55f) and 0xFFFFFF or ((f * 255f).toInt().coerceIn(0, 255) shl 24), additive)
                val x0 = cx - a.x * half
                val y0 = cy - a.y * half
                val z0 = cz - a.z * half
                val x1 = cx + a.x * half
                val y1 = cy + a.y * half
                val z1 = cz + a.z * half
                if (i > 0) {
                    fx.vertex(prev[0], prev[1], prev[2], prevSilk)
                    fx.vertex(prev[3], prev[4], prev[5], prevSilk)
                    fx.vertex(x1, y1, z1, silk)
                    fx.vertex(x0, y0, z0, silk)
                    line(fx, thread[0], thread[1], thread[2], cx, cy, cz, lastL, 0.006f + w * 0.035f, prevThread, glint)
                }
                prev[0] = x0
                prev[1] = y0
                prev[2] = z0
                prev[3] = x1
                prev[4] = y1
                prev[5] = z1
                thread[0] = cx
                thread[1] = cy
                thread[2] = cz
                lastL = 0.006f + w * 0.035f
                prevSilk = silk
                prevThread = glint
            }
        }

        /** A soft band facing the camera down the middle: [c0] bright on the centre line, gone at the edges. */
        private fun core(fx: Fx, spread: Float, alpha: Float, white: Float, additive: Boolean) {
            val side = Vector3f()
            var ok = false
            val prev = FloatArray(9)
            var prevMid = 0
            for (i in 0 until size) {
                // Across the path as the camera sees it.
                side.set(ty[i] * -pz[i] - tz[i] * -py[i], tz[i] * -px[i] - tx[i] * -pz[i], tx[i] * -py[i] - ty[i] * -px[i])
                if (side.lengthSquared() < 1.0e-10f) {
                    ok = false
                    continue
                }
                side.normalize().mul(width(i) * spread)
                val base = trail.palette.colorAt(s(i), time) or 0xFF000000.toInt()
                val mid = fx.light(WorldFx.hot(base, white) and 0xFFFFFF or ((fade(i) * alpha * 255f).toInt().coerceIn(0, 255) shl 24), additive)
                if (ok) {
                    for (sgn in floatArrayOf(1f, -1f)) {
                        val o = if (sgn > 0f) 0 else 6
                        fx.vertex(prev[3], prev[4], prev[5], prevMid)
                        fx.vertex(prev[o], prev[o + 1], prev[o + 2], 0)
                        fx.vertex(px[i] + side.x * sgn, py[i] + side.y * sgn, pz[i] + side.z * sgn, 0)
                        fx.vertex(px[i], py[i], pz[i], mid)
                    }
                }
                prev[0] = px[i] + side.x
                prev[1] = py[i] + side.y
                prev[2] = pz[i] + side.z
                prev[3] = px[i]
                prev[4] = py[i]
                prev[5] = pz[i]
                prev[6] = px[i] - side.x
                prev[7] = py[i] - side.y
                prev[8] = pz[i] - side.z
                prevMid = mid
                ok = true
            }
        }

        /** Loose sparkles off the older parts of the trail, rising and twinkling as they go. */
        private fun sparkles(fx: Fx, additive: Boolean) {
            val life = trail.length * 1000f
            for ((j, p) in points.withIndex()) {
                if (hash(p.seed, 0, 31) > 0.3f) continue
                val age = ((now - p.at) / life).coerceIn(0f, 1f)
                val rise = (now - p.at) / 1000f
                val keep = (1f - age) * p.strength * (j / (points.size - 1f)).coerceAtLeast(0.2f)
                if (keep <= 0.02f) continue
                val ang = hash(p.seed, 0, 32) * TAU
                val out = trail.width * 0.4f * hash(p.seed, 0, 33) + rise * 0.18f
                val twinkle = 0.45f + 0.55f * abs(sin(time * (3f + hash(p.seed, 0, 34) * 4f) + p.seed))
                val x = (p.x - camera.x).toFloat() + cos(ang) * out
                val y = (p.y - camera.y).toFloat() + rise * 0.32f + (hash(p.seed, 0, 35) - 0.5f) * trail.width * 0.4f
                val z = (p.z - camera.z).toFloat() + sin(ang) * out
                val base = trail.palette.colorAt(hash(p.seed, 0, 36), time) or 0xFF000000.toInt()
                val c = fx.light(WorldFx.hot(base, 0.5f) and 0xFFFFFF or ((keep * twinkle * 255f).toInt().coerceIn(0, 255) shl 24), additive)
                fx.star(x, y, z, (0.05f + trail.width * 0.08f) * max(0.35f, 1f - age), c)
            }
        }

        /** A thin camera facing line from one point to the next, for the threads. */
        private fun line(fx: Fx, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, w: Float, w1: Float, c0: Int, c1: Int) {
            val dx = x1 - x0
            val dy = y1 - y0
            val dz = z1 - z0
            nrm.set(dy * z0 - dz * y0, dz * x0 - dx * z0, dx * y0 - dy * x0)
            if (nrm.lengthSquared() < 1.0e-12f) return
            nrm.normalize()
            fx.vertex(x0 + nrm.x * w, y0 + nrm.y * w, z0 + nrm.z * w, c0)
            fx.vertex(x1 + nrm.x * w1, y1 + nrm.y * w1, z1 + nrm.z * w1, c1)
            fx.vertex(x1 - nrm.x * w1, y1 - nrm.y * w1, z1 - nrm.z * w1, c1)
            fx.vertex(x0 - nrm.x * w, y0 - nrm.y * w, z0 - nrm.z * w, c0)
        }

        companion object {
            /** Smooths [track] through its samples with a Catmull-Rom curve. Null when there's nothing to draw. */
            fun of(trail: ResolvedTrail, track: Track, camera: Vec3, now: Long, time: Float): Ribbon? {
                val points = track.points.toList()
                if (points.size < 2) return null
                val n = (points.size - 1) * SUB + 1
                val px = FloatArray(n)
                val py = FloatArray(n)
                val pz = FloatArray(n)
                val strength = FloatArray(n)
                val dist = FloatArray(n)
                for (seg in 0 until points.size - 1) {
                    val p0 = points[(seg - 1).coerceAtLeast(0)]
                    val p1 = points[seg]
                    val p2 = points[seg + 1]
                    val p3 = points[(seg + 2).coerceAtMost(points.size - 1)]
                    val steps = if (seg == points.size - 2) SUB + 1 else SUB
                    for (j in 0 until steps) {
                        val t = j / SUB.toFloat()
                        val i = seg * SUB + j
                        px[i] = (spline(p0.x, p1.x, p2.x, p3.x, t) - camera.x).toFloat()
                        py[i] = (spline(p0.y, p1.y, p2.y, p3.y, t) - camera.y).toFloat()
                        pz[i] = (spline(p0.z, p1.z, p2.z, p3.z, t) - camera.z).toFloat()
                        strength[i] = p1.strength + (p2.strength - p1.strength) * t
                        dist[i] = p1.dist + (p2.dist - p1.dist) * t
                    }
                }
                if (strength.all { it <= 0.01f }) return null
                return Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist)
            }

            private fun spline(a: Double, b: Double, c: Double, d: Double, t: Float): Double {
                val t2 = t * t
                val t3 = t2 * t
                return 0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (3 * b - a - 3 * c + d) * t3)
            }
        }
    }
}
