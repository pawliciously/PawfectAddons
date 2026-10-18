package dev.pawfect.addons.features.cosmetics

import dev.pawfect.addons.features.cosmetics.WorldFx.TAU
import dev.pawfect.addons.features.cosmetics.WorldFx.hash
import dev.pawfect.addons.features.cosmetics.celestial.Colors
import dev.pawfect.addons.features.cosmetics.celestial.Shapes
import dev.pawfect.addons.features.cosmetics.celestial.Solid
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val SUB = 3

private const val TWIST = 2.4f

private const val PI_F = PI.toFloat()

internal val PETAL = floatArrayOf(0f, 0.1f, 0.25f, 0.75f, 0.6f, 1f, 0.9f, 0.6f, 1f, 0f)

internal abstract class Ribbon(
    protected val trail: ResolvedTrail,
    protected val time: Float,
    protected val now: Long,
    protected val points: List<Trails.Point>,
    protected val camera: Vec3,
    protected val px: FloatArray,
    protected val py: FloatArray,
    protected val pz: FloatArray,
    protected val strength: FloatArray,
    protected val dist: FloatArray,
) {
    protected val size = px.size
    protected val tx = FloatArray(size)
    protected val ty = FloatArray(size)
    protected val tz = FloatArray(size)
    protected val additive = trail.glow
    private val v = Vector3f()

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

    protected fun s(i: Int) = i / (size - 1f)

    protected fun fade(i: Int) = s(i).pow(1.4f) * strength[i]

    protected fun width(i: Int) = trail.width * 0.5f * s(i).pow(0.7f)

    protected fun color(at: Float) = trail.palette.colorAt(at, time) or 0xFF000000.toInt()

    protected fun light(fx: Fx, color: Int, alpha: Float) =
        fx.light(color and 0xFFFFFF or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24), additive)

    protected fun seconds(p: Trails.Point) = (now - p.at) / 1000f

    protected fun age(p: Trails.Point) = (seconds(p) / trail.length).coerceIn(0f, 1f)

    protected fun rel(p: Trails.Point, out: Vector3f) = out.set((p.x - camera.x).toFloat(), (p.y - camera.y).toFloat(), (p.z - camera.z).toFloat())

    open fun solids(fx: Fx) {}

    abstract fun lights(fx: Fx)

    protected fun radial(i: Int, phi: Float, out: Vector3f, across: Vector3f) {
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

    protected fun side(i: Int, out: Vector3f): Boolean {
        out.set(ty[i] * -pz[i] - tz[i] * -py[i], tz[i] * -px[i] - tx[i] * -pz[i], tx[i] * -py[i] - ty[i] * -px[i])
        if (out.lengthSquared() < 1.0e-10f) return false
        out.normalize()
        return true
    }

    protected fun core(fx: Fx, spread: Float, alpha: Float, white: Float) {
        val side = Vector3f()
        var ok = false
        val prev = FloatArray(9)
        var prevMid = 0
        for (i in 0 until size) {
            if (!side(i, side)) {
                ok = false
                continue
            }
            side.mul(width(i) * spread)
            val mid = light(fx, WorldFx.hot(color(s(i)), white), fade(i) * alpha)
            if (ok) {
                for (o in intArrayOf(0, 6)) {
                    val sgn = if (o == 0) 1f else -1f
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

    protected fun helix(fx: Fx, k: Int, radius: Float, alpha: Float, white: Float, line: Float) {
        val r = Vector3f()
        val a = Vector3f()
        var lx = 0f
        var ly = 0f
        var lz = 0f
        var lw = 0f
        var lc = 0
        for (i in 0 until size) {
            radial(i, dist[i] * TWIST + k * PI_F + time * 0.9f, r, a)
            val w = width(i)
            val x = px[i] + r.x * w * radius
            val y = py[i] + r.y * w * radius
            val z = pz[i] + r.z * w * radius
            val lw1 = 0.006f + w * line
            val c = light(fx, WorldFx.hot(color(s(i) * 0.85f + k * 0.15f), white), fade(i) * alpha)
            if (i > 0) fx.line(lx, ly, lz, x, y, z, lw, lw1, lc, c)
            lx = x
            ly = y
            lz = z
            lw = lw1
            lc = c
        }
    }

    protected inline fun shed(share: Float, channel: Int, each: (p: Trails.Point, j: Int, keep: Float) -> Unit) {
        for ((j, p) in points.withIndex()) {
            if (hash(p.seed, 0, channel) > share) continue
            val keep = (1f - age(p)) * p.strength * (j / (points.size - 1f)).coerceAtLeast(0.2f)
            if (keep <= 0.02f) continue
            each(p, j, keep)
        }
    }

    companion object {
        fun of(trail: ResolvedTrail, track: Trails.Track, camera: Vec3, now: Long, time: Float): Ribbon? {
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
            return when (trail.form) {
                Form.PRISM -> SilkRibbon(trail, time, now, points, camera, px, py, pz, strength, dist)
                Form.EMBER -> FlameRibbon(trail, time, now, points, camera, px, py, pz, strength, dist)
                Form.SERAPH -> FeatherRibbon(trail, time, now, points, camera, px, py, pz, strength, dist)
                Form.ABYSS -> SmokeRibbon(trail, time, now, points, camera, px, py, pz, strength, dist)
                Form.ASTRAL -> StarRibbon(trail, time, now, points, camera, px, py, pz, strength, dist)
                Form.SYLPH -> PetalRibbon(trail, time, now, points, camera, px, py, pz, strength, dist)
            }
        }

        private fun spline(a: Double, b: Double, c: Double, d: Double, t: Float): Double {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (3 * b - a - 3 * c + d) * t3)
        }
    }
}

private class SilkRibbon(
    trail: ResolvedTrail, time: Float, now: Long, points: List<Trails.Point>, camera: Vec3,
    px: FloatArray, py: FloatArray, pz: FloatArray, strength: FloatArray, dist: FloatArray,
) : Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist) {

    override fun lights(fx: Fx) {
        for (k in 0..1) strand(fx, k)
        core(fx, 1.5f, 0.18f, 0.1f)
        core(fx, 0.5f, 0.7f, 0.45f)
        sparkles(fx)
    }

    private fun strand(fx: Fx, k: Int) {
        val r = Vector3f()
        val a = Vector3f()
        val prev = FloatArray(6)
        var prevSilk = 0
        for (i in 0 until size) {
            radial(i, dist[i] * TWIST + k * PI_F + time * 0.9f, r, a)
            val w = width(i)
            val cx = px[i] + r.x * w * 0.6f
            val cy = py[i] + r.y * w * 0.6f
            val cz = pz[i] + r.z * w * 0.6f
            val half = w * 0.34f
            val lit = Solid.lit(color(s(i) * 0.85f + k * 0.15f), r, 0.5f, 0.7f)
            val silk = light(fx, lit, fade(i) * 0.85f)
            if (i > 0) {
                fx.vertex(prev[0], prev[1], prev[2], prevSilk)
                fx.vertex(prev[3], prev[4], prev[5], prevSilk)
                fx.vertex(cx + a.x * half, cy + a.y * half, cz + a.z * half, silk)
                fx.vertex(cx - a.x * half, cy - a.y * half, cz - a.z * half, silk)
            }
            prev[0] = cx - a.x * half
            prev[1] = cy - a.y * half
            prev[2] = cz - a.z * half
            prev[3] = cx + a.x * half
            prev[4] = cy + a.y * half
            prev[5] = cz + a.z * half
            prevSilk = silk
        }
        helix(fx, k, 0.6f, 1f, 0.55f, 0.035f)
    }

    private fun sparkles(fx: Fx) {
        val at = Vector3f()
        shed(0.3f, 31) { p, _, keep ->
            val rise = seconds(p)
            val ang = hash(p.seed, 0, 32) * TAU
            val out = trail.width * 0.4f * hash(p.seed, 0, 33) + rise * 0.18f
            val twinkle = 0.45f + 0.55f * abs(sin(time * (3f + hash(p.seed, 0, 34) * 4f) + p.seed))
            rel(p, at)
            val c = light(fx, WorldFx.hot(color(hash(p.seed, 0, 36)), 0.5f), keep * twinkle)
            fx.star(
                at.x + cos(ang) * out, at.y + rise * 0.32f + (hash(p.seed, 0, 35) - 0.5f) * trail.width * 0.4f, at.z + sin(ang) * out,
                (0.05f + trail.width * 0.08f) * max(0.35f, 1f - age(p)), c,
            )
        }
    }
}

private class FlameRibbon(
    trail: ResolvedTrail, time: Float, now: Long, points: List<Trails.Point>, camera: Vec3,
    px: FloatArray, py: FloatArray, pz: FloatArray, strength: FloatArray, dist: FloatArray,
) : Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist) {

    override fun lights(fx: Fx) {
        core(fx, 1.2f, 0.35f, 0.2f)
        for (i in 0 until size) {
            val f = fade(i)
            if (f <= 0.02f) continue
            val w = width(i)
            val seed = dist[i] * 7.3f
            val flick = 0.65f + 0.35f * sin(time * 11f + seed) * sin(time * 4.3f + seed * 1.7f)
            val c = color(s(i) * 0.7f + 0.3f * abs(sin(seed)))
            val tall = w * (1.6f + 0.8f * abs(sin(seed * 2.1f))) * flick * (0.4f + 0.6f * f)
            val sway = sin(time * 5f + seed) * w * 0.35f
            fx.flame(px[i], py[i] - w * 0.4f, pz[i], w * 0.5f, tall, sway, light(fx, c, 0.75f * f), 0)
            fx.flame(px[i], py[i] - w * 0.4f, pz[i], w * 0.24f, tall * 0.55f, sway * 0.5f, light(fx, WorldFx.hot(c, 0.6f), 0.9f * f), 0)
        }
        val at = Vector3f()
        shed(0.45f, 41) { p, _, keep ->
            val rise = seconds(p)
            rel(p, at)
            val ang = hash(p.seed, 0, 42) * TAU
            val out = trail.width * 0.3f * hash(p.seed, 0, 43)
            val glow = keep * (0.6f + 0.4f * sin(time * 13f + p.seed))
            val c = color(0.6f + 0.4f * hash(p.seed, 0, 44))
            val x = at.x + cos(ang) * out + sin(rise * 3f + p.seed) * 0.08f
            val y = at.y + rise * (0.8f + hash(p.seed, 0, 45) * 0.6f)
            val z = at.z + sin(ang) * out
            fx.soft(x, y, z, 0.03f + trail.width * 0.05f, light(fx, WorldFx.hot(c, 0.6f), glow))
            fx.soft(x, y, z, 0.1f + trail.width * 0.12f, light(fx, c, 0.15f * glow))
        }
    }
}

private class FeatherRibbon(
    trail: ResolvedTrail, time: Float, now: Long, points: List<Trails.Point>, camera: Vec3,
    px: FloatArray, py: FloatArray, pz: FloatArray, strength: FloatArray, dist: FloatArray,
) : Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist) {

    private val at = Vector3f()
    private val rot = Quaternionf()

    private fun fall(p: Trails.Point, out: Vector3f): Vector3f {
        val t = seconds(p)
        rel(p, out)
        val ang = hash(p.seed, 0, 52) * TAU
        val out0 = trail.width * 0.5f * hash(p.seed, 0, 53)
        val sway = sin(t * 3f + p.seed) * 0.12f
        return out.add(cos(ang) * (out0 + sway), -t * 0.45f + abs(sway) * 0.4f, sin(ang) * (out0 + sway))
    }

    override fun solids(fx: Fx) {
        shed(0.35f, 51) { p, _, keep ->
            val t = seconds(p)
            fall(p, at)
            rot.rotationY(hash(p.seed, 0, 54) * TAU + t * 0.8f).rotateX(sin(t * 3f + p.seed) * 0.6f).rotateZ(0.3f)
            val len = (0.16f + trail.width * 0.35f) * (0.8f + 0.4f * hash(p.seed, 0, 55)) * keep.pow(0.3f)
            val c = color(hash(p.seed, 0, 56))
            fx.leaf(at.x, at.y, at.z, rot, len, len * 0.3f, Shapes.FEATHER, Colors.scale(c, 0.85f), WorldFx.hot(c, 0.45f), rib = true)
        }
    }

    override fun lights(fx: Fx) {
        core(fx, 1.6f, 0.15f, 0.2f)
        core(fx, 0.35f, 0.85f, 0.65f)
        helix(fx, 0, 0.35f, 0.5f, 0.7f, 0.02f)
        shed(0.35f, 51) { p, _, keep ->
            fall(p, at)
            val glint = max(0f, sin(time * 2.2f + p.seed * 1.3f)).pow(10f)
            val c = color(hash(p.seed, 0, 56))
            fx.soft(at.x, at.y, at.z, 0.12f + trail.width * 0.2f, light(fx, c, 0.12f * keep))
            if (glint > 0.03f) fx.star(at.x, at.y, at.z, (0.1f + trail.width * 0.15f) * glint, light(fx, WorldFx.hot(c, 0.7f), glint * keep))
        }
    }
}

private class SmokeRibbon(
    trail: ResolvedTrail, time: Float, now: Long, points: List<Trails.Point>, camera: Vec3,
    px: FloatArray, py: FloatArray, pz: FloatArray, strength: FloatArray, dist: FloatArray,
) : Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist) {

    override fun lights(fx: Fx) {
        val at = Vector3f()
        shed(0.7f, 61) { p, _, keep ->
            val t = seconds(p)
            rel(p, at)
            val r = (trail.width * 0.55f + t * 0.35f) * (0.8f + 0.4f * hash(p.seed, 0, 62))
            val drift = hash(p.seed, 0, 63) * TAU
            val smoke = fx.light(Colors.lerp(0xFF05030A.toInt(), color(hash(p.seed, 0, 64)), 0.08f) and 0xFFFFFF or ((keep * 0.5f * 255f).toInt() shl 24), false)
            fx.soft(at.x + cos(drift) * t * 0.15f, at.y + t * 0.25f, at.z + sin(drift) * t * 0.15f, r, smoke, 12)
        }
        core(fx, 0.9f, 0.2f, 0f)
        val sd = Vector3f()
        for (edge in floatArrayOf(1f, -1f)) {
            var lx = 0f
            var ly = 0f
            var lz = 0f
            var lc = 0
            var ok = false
            for (i in 0 until size) {
                if (!side(i, sd)) {
                    ok = false
                    continue
                }
                val w = width(i)
                val wave = 0.75f + 0.25f * sin(dist[i] * 5f - time * 3f + edge)
                val x = px[i] + sd.x * w * wave * edge
                val y = py[i] + sd.y * w * wave * edge
                val z = pz[i] + sd.z * w * wave * edge
                val c = light(fx, WorldFx.hot(color(s(i)), 0.35f), fade(i) * 0.95f)
                if (ok) fx.line(lx, ly, lz, x, y, z, 0.008f + w * 0.03f, 0.008f + w * 0.03f, lc, c)
                lx = x
                ly = y
                lz = z
                lc = c
                ok = true
            }
        }
    }
}

private class StarRibbon(
    trail: ResolvedTrail, time: Float, now: Long, points: List<Trails.Point>, camera: Vec3,
    px: FloatArray, py: FloatArray, pz: FloatArray, strength: FloatArray, dist: FloatArray,
) : Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist) {

    private val at = Vector3f()

    private fun star(p: Trails.Point, k: Int, out: Vector3f): Vector3f {
        rel(p, out)
        val w = trail.width * 0.6f
        val t = seconds(p)
        return out.add(
            (hash(p.seed, k, 71) - 0.5f) * w * 2f,
            (hash(p.seed, k, 72) - 0.5f) * w * 1.6f + t * 0.06f,
            (hash(p.seed, k, 73) - 0.5f) * w * 2f,
        )
    }

    override fun lights(fx: Fx) {
        core(fx, 1.8f, 0.14f, 0f)
        core(fx, 0.8f, 0.22f, 0.2f)
        val prev = Vector3f()
        var have = false
        var prevC = 0
        for ((j, p) in points.withIndex()) {
            val keep = (1f - age(p)) * p.strength * (j / (points.size - 1f)).coerceAtLeast(0.2f)
            if (keep <= 0.02f) {
                have = false
                continue
            }
            for (k in 0..2) {
                star(p, k, at)
                val tw = 0.3f + 0.7f * abs(sin(time * (1.5f + hash(p.seed, k, 74) * 3f) + p.seed + k)).pow(3f)
                val c = color(hash(p.seed, k, 75))
                val r = (0.03f + trail.width * 0.07f) * (0.6f + 0.8f * hash(p.seed, k, 76))
                fx.star(at.x, at.y, at.z, r * (1.5f + tw), light(fx, WorldFx.hot(c, 0.65f), keep * tw))
            }
            if (p.seed % 4 == 0) {
                star(p, 0, at)
                val c = light(fx, color(hash(p.seed, 0, 75)), keep * 0.35f)
                if (have) fx.line(prev.x, prev.y, prev.z, at.x, at.y, at.z, 0.005f, 0.005f, prevC, c)
                prev.set(at)
                prevC = c
                have = true
            }
        }
    }
}

private class PetalRibbon(
    trail: ResolvedTrail, time: Float, now: Long, points: List<Trails.Point>, camera: Vec3,
    px: FloatArray, py: FloatArray, pz: FloatArray, strength: FloatArray, dist: FloatArray,
) : Ribbon(trail, time, now, points, camera, px, py, pz, strength, dist) {

    private val at = Vector3f()
    private val rot = Quaternionf()

    private fun fall(p: Trails.Point, out: Vector3f): Vector3f {
        val t = seconds(p)
        rel(p, out)
        val ang = hash(p.seed, 0, 82) * TAU + t * 1.4f
        val r = trail.width * 0.5f * hash(p.seed, 0, 83) + 0.1f * sin(t * 2.5f + p.seed)
        return out.add(cos(ang) * r, -t * 0.3f + sin(t * 4f + p.seed) * 0.05f, sin(ang) * r)
    }

    override fun solids(fx: Fx) {
        shed(0.5f, 81) { p, _, keep ->
            val t = seconds(p)
            fall(p, at)
            rot.rotationY(hash(p.seed, 0, 84) * TAU + t * 2.2f).rotateX(sin(t * 3.5f + p.seed) * 0.9f).rotateZ(cos(t * 2.7f + p.seed) * 0.5f)
            val len = (0.08f + trail.width * 0.16f) * (0.8f + 0.4f * hash(p.seed, 0, 85)) * keep.pow(0.3f)
            val c = color(hash(p.seed, 0, 86))
            fx.leaf(at.x, at.y, at.z, rot, len, len * 0.7f, PETAL, Colors.scale(c, 0.9f), WorldFx.hot(c, 0.5f))
        }
    }

    override fun lights(fx: Fx) {
        core(fx, 1.1f, 0.12f, 0.3f)
        helix(fx, 0, 0.7f, 0.9f, 0.6f, 0.03f)
        shed(0.3f, 87) { p, _, keep ->
            val t = seconds(p)
            rel(p, at)
            val ang = hash(p.seed, 0, 88) * TAU
            val c = color(hash(p.seed, 0, 89))
            val x = at.x + cos(ang) * (trail.width * 0.4f + t * 0.1f)
            val y = at.y + t * 0.18f + sin(t * 3f + p.seed) * 0.05f
            val z = at.z + sin(ang) * (trail.width * 0.4f + t * 0.1f)
            fx.soft(x, y, z, 0.025f + trail.width * 0.04f, light(fx, WorldFx.hot(c, 0.7f), keep))
            fx.soft(x, y, z, 0.08f + trail.width * 0.1f, light(fx, c, 0.12f * keep))
        }
    }
}
