package dev.pawfect.addons.features.cosmetics

import dev.pawfect.addons.features.cosmetics.WorldFx.TAU
import dev.pawfect.addons.features.cosmetics.celestial.Colors
import dev.pawfect.addons.features.cosmetics.celestial.Shapes
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI_F = PI.toFloat()

/**
 * One player's motes for a frame, in their theme. Each theme says where a mote is at any moment
 * and what it looks like; the solid pass and the light pass both read the same places.
 */
internal abstract class Swarm(job: Motes.Job, camera: Vec3, protected val time: Float) {
    protected val motes = job.motes
    protected val count = job.count
    protected val seed = job.seed
    protected val drift = time * motes.drift
    protected val additive = motes.glow

    // Camera relative, so the floats stay precise far from spawn.
    protected val ax = (job.anchor.x - camera.x).toFloat()
    protected val ay = (job.anchor.y - camera.y).toFloat()
    protected val az = (job.anchor.z - camera.z).toFloat()
    private val lagX = (job.follow.x - job.anchor.x).toFloat()
    private val lagY = (job.follow.y - job.anchor.y).toFloat()
    private val lagZ = (job.follow.z - job.anchor.z).toFloat()

    protected val px = FloatArray(count)
    protected val py = FloatArray(count)
    protected val pz = FloatArray(count)
    private val scratch = Vector3f()

    protected fun h(i: Int, channel: Int) = WorldFx.hash(seed, i, channel)

    /** Mote [i]'s colour from the palette, opaque. */
    protected fun color(i: Int, at: Float = h(i, 8)) = motes.palette.colorAt(at, time) or 0xFF000000.toInt()

    /** A light colour at [alpha], added or laid over as glass depending on the glow setting. */
    protected fun light(fx: Fx, color: Int, alpha: Float) =
        fx.light(color and 0xFFFFFF or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24), additive)

    /** Where mote [i] is, [back] seconds ago, before the swarm's lag. */
    protected abstract fun place(i: Int, back: Float, out: Vector3f)

    /** Places mote [i] [back] seconds ago, hanging back toward the lagging swarm centre by its own amount. */
    protected fun at(i: Int, back: Float, out: Vector3f): Vector3f {
        place(i, back, out)
        val hang = 0.25f + 0.75f * h(i, 16)
        return out.add(lagX * hang, lagY * hang, lagZ * hang)
    }

    protected fun settle() {
        for (i in 0 until count) {
            at(i, 0f, scratch)
            px[i] = scratch.x
            py[i] = scratch.y
            pz[i] = scratch.z
        }
    }

    open fun solids(fx: Fx) {}

    abstract fun lights(fx: Fx)

    /** A ribbon down mote [i]'s own path behind it, thinning and fading, [steps] long. */
    protected fun streak(fx: Fx, i: Int, head: Int, width: Float, steps: Int = 6, every: Float = 0.05f) {
        var x0 = px[i]
        var y0 = py[i]
        var z0 = pz[i]
        var w0 = width
        var c0 = head
        for (k in 0 until steps) {
            at(i, (k + 1) * every, scratch)
            val f = 1f - (k + 1f) / steps
            val c1 = fx.dim(head, f * f)
            val w1 = width * f
            fx.line(x0, y0, z0, scratch.x, scratch.y, scratch.z, w0, w1, c0, c1)
            x0 = scratch.x
            y0 = scratch.y
            z0 = scratch.z
            w0 = w1
            c0 = c1
        }
    }

    /** A tilted circle round the player, the shape most themes orbit on. */
    protected fun orbit(i: Int, t: Float, ring: Float, out: Vector3f): Vector3f {
        val dir = if (h(i, 2) < 0.8f) 1f else -1f
        val rate = dir * (0.35f + h(i, 3) * 0.5f) / (0.4f + ring)
        val angle = h(i, 0) * TAU + t * rate
        val tilt = (h(i, 4) - 0.5f) * 1.1f
        val node = h(i, 5) * TAU
        val bob = sin(t * (0.5f + h(i, 6) * 0.6f) + i) * motes.height * 0.08f
        val level = (h(i, 15) - 0.5f) * motes.height * 0.6f
        val cx = cos(angle) * ring
        val cz = sin(angle) * ring
        val nx = cos(node)
        val nz = sin(node)
        val along = cx * nx + cz * nz
        val across = -cx * nz + cz * nx
        return out.set(
            ax + along * nx - across * cos(tilt) * nz,
            ay + level + bob + across * sin(tilt),
            az + along * nz + across * cos(tilt) * nx,
        )
    }

    protected fun ringOf(i: Int) = motes.radius * 0.5f * (0.35f + 0.65f * sqrt(h(i, 1)))

    companion object {
        fun of(job: Motes.Job, camera: Vec3, time: Float): Swarm = when (job.motes.form) {
            Form.PRISM -> PrismSwarm(job, camera, time)
            Form.EMBER -> EmberSwarm(job, camera, time)
            Form.SERAPH -> SeraphSwarm(job, camera, time)
            Form.ABYSS -> AbyssSwarm(job, camera, time)
            Form.ASTRAL -> AstralSwarm(job, camera, time)
            Form.SYLPH -> SylphSwarm(job, camera, time)
        }
    }
}

/** Little cut gems on tilted orbits, each in a soft glow with a twinkle and a comet tail. */
private class PrismSwarm(job: Motes.Job, camera: Vec3, time: Float) : Swarm(job, camera, time) {
    private val size = FloatArray(count)
    private val shine = FloatArray(count)
    private val spin = Array(count) { Quaternionf() }

    init {
        settle()
        for (i in 0 until count) {
            val tint = h(i, 8)
            val breath = 0.8f + 0.2f * sin(drift * (1.2f + h(i, 9)) + tint * TAU)
            size[i] = motes.size * (0.7f + 0.6f * h(i, 10)) * breath
            shine[i] = max(0f, sin(drift * (0.5f + h(i, 7) * 1.1f) + tint * TAU)).pow(6f)
            val turn = drift * (0.6f + h(i, 11) * 1.4f)
            spin[i].rotationXYZ(h(i, 12) * TAU + turn, h(i, 13) * TAU + turn * 0.7f, h(i, 14) * TAU)
        }
    }

    override fun place(i: Int, back: Float, out: Vector3f) {
        orbit(i, drift - back * motes.drift, ringOf(i), out)
    }

    override fun solids(fx: Fx) {
        for (i in 0 until count) fx.crystal(px[i], py[i], pz[i], spin[i], size[i] * 0.8f, size[i] * 1.7f, color(i))
    }

    override fun lights(fx: Fx) {
        for (i in 0 until count) {
            val r = size[i]
            val c = color(i)
            fx.soft(px[i], py[i], pz[i], r * 5f, light(fx, WorldFx.hot(c, 0.1f), 0.22f + 0.2f * shine[i]))
            streak(fx, i, light(fx, WorldFx.hot(c, 0.25f), 0.56f), r * 0.55f)
            if (shine[i] > 0.02f) fx.star(px[i], py[i], pz[i], r * (2.5f + 4f * shine[i]), light(fx, WorldFx.hot(c, 0.6f), shine[i] * 0.9f))
        }
    }
}

/** Embers lifting off you, flickering hot with small flames licking up from them, cooling as they rise. */
private class EmberSwarm(job: Motes.Job, camera: Vec3, time: Float) : Swarm(job, camera, time) {
    private val life = FloatArray(count)

    init {
        for (i in 0 until count) life[i] = life(i, 0f)
        settle()
    }

    private fun life(i: Int, back: Float): Float {
        val period = 2.2f + h(i, 3) * 1.8f
        val t = (drift - back * motes.drift) / period + h(i, 0)
        return t - kotlin.math.floor(t)
    }

    override fun place(i: Int, back: Float, out: Vector3f) {
        val l = life(i, back)
        val t = drift - back * motes.drift
        val r = motes.radius * 0.38f * sqrt(h(i, 1))
        val a = h(i, 2) * TAU + l * (1.5f + h(i, 4))
        val swirl = sin(t * 2.3f + i * 1.7f) * 0.12f * (0.4f + l)
        out.set(
            ax + cos(a) * r + swirl,
            ay - motes.height * 0.45f + l * motes.height * 1.05f,
            az + sin(a) * r + cos(t * 1.9f + i) * 0.12f * (0.4f + l),
        )
    }

    override fun lights(fx: Fx) {
        for (i in 0 until count) {
            val l = life[i]
            val flicker = 0.7f + 0.3f * sin(time * 17f + i * 3.1f) * sin(time * 7.3f + i)
            val glow = sin(PI_F * l).pow(0.6f) * flicker
            if (glow <= 0.02f) continue
            val c = color(i, l * 0.8f + h(i, 8) * 0.2f)
            val s = motes.size * (1.25f - 0.75f * l) * (0.8f + 0.4f * h(i, 10))
            val hot = WorldFx.hot(c, 0.55f * (1f - l))
            fx.flame(px[i], py[i] - s * 0.5f, pz[i], s * 0.9f, s * (3.5f + 2f * h(i, 11)) * (1f - 0.5f * l), sin(time * 6f + i) * s, light(fx, hot, 0.9f * glow), 0)
            fx.soft(px[i], py[i], pz[i], s * 1.3f, light(fx, WorldFx.hot(c, 0.7f), glow))
            fx.soft(px[i], py[i], pz[i], s * 5f, light(fx, c, 0.16f * glow))
            streak(fx, i, light(fx, c, 0.4f * glow), s * 0.35f, 4, 0.06f)
        }
    }
}

/** Gold feathers drifting down round you like falling leaves, catching the light as they turn. */
private class SeraphSwarm(job: Motes.Job, camera: Vec3, time: Float) : Swarm(job, camera, time) {
    private val life = FloatArray(count)
    private val rot = Array(count) { Quaternionf() }

    init {
        for (i in 0 until count) {
            life[i] = life(i)
            val l = life[i]
            val sway = sin(l * TAU * 2f + i)
            val heading = h(i, 2) * TAU + l * 0.8f
            rot[i].rotationY(-heading).rotateX(sway * 0.55f).rotateZ(0.25f + 0.2f * cos(l * TAU * 2f + i))
        }
        settle()
    }

    private fun life(i: Int): Float {
        val t = drift / (4f + h(i, 3) * 3f) + h(i, 0)
        return t - kotlin.math.floor(t)
    }

    override fun place(i: Int, back: Float, out: Vector3f) {
        val t = (drift - back * motes.drift) / (4f + h(i, 3) * 3f) + h(i, 0)
        val l = t - kotlin.math.floor(t)
        val r = ringOf(i)
        val a = h(i, 2) * TAU + l * 0.8f
        val sway = sin(l * TAU * 2f + i) * 0.22f
        out.set(
            ax + cos(a) * r - sin(a) * sway,
            ay + motes.height * (0.55f - l * 1.1f) + abs(sway) * 0.3f,
            az + sin(a) * r + cos(a) * sway,
        )
    }

    private fun grow(l: Float) = (l / 0.1f).coerceAtMost(1f) * ((1f - l) / 0.12f).coerceAtMost(1f)

    override fun solids(fx: Fx) {
        for (i in 0 until count) {
            val len = motes.size * 5f * (0.8f + 0.4f * h(i, 10)) * grow(life[i])
            val c = color(i)
            fx.leaf(px[i], py[i], pz[i], rot[i], len, len * 0.3f, Shapes.FEATHER, Colors.scale(c, 0.85f), WorldFx.hot(c, 0.45f), rib = true)
        }
    }

    override fun lights(fx: Fx) {
        for (i in 0 until count) {
            val g = grow(life[i])
            if (g <= 0.02f) continue
            val c = color(i)
            val len = motes.size * 5f * (0.8f + 0.4f * h(i, 10)) * g
            fx.soft(px[i], py[i], pz[i], len * 0.9f, light(fx, c, 0.12f * g))
            val glint = max(0f, sin(time * (1.3f + h(i, 7)) + i * 2.1f)).pow(12f)
            if (glint > 0.02f) fx.star(px[i], py[i], pz[i], len * 0.9f * glint, light(fx, WorldFx.hot(c, 0.7f), glint))
        }
    }
}

/** Dark matter: glossy black orbs spiralling in toward you, each in a ring of coloured light. */
private class AbyssSwarm(job: Motes.Job, camera: Vec3, time: Float) : Swarm(job, camera, time) {
    private val life = FloatArray(count)

    init {
        for (i in 0 until count) life[i] = life(i, 0f)
        settle()
    }

    private fun life(i: Int, back: Float): Float {
        val t = (drift - back * motes.drift) / (5f + h(i, 3) * 3f) + h(i, 0)
        return t - kotlin.math.floor(t)
    }

    override fun place(i: Int, back: Float, out: Vector3f) {
        val l = life(i, back)
        val t = drift - back * motes.drift
        val r = motes.radius * 0.5f * (0.5f + 0.5f * h(i, 1)) * (1f - 0.85f * l)
        val a = h(i, 2) * TAU + t * (0.4f + 0.3f * h(i, 4)) + l * l * 6f
        val level = (h(i, 15) - 0.5f) * motes.height * 0.6f * (1f - 0.6f * l)
        out.set(ax + cos(a) * r, ay + level + sin(t + i) * 0.05f, az + sin(a) * r)
    }

    private fun radius(i: Int) = motes.size * 1.2f * sin(PI_F * life[i]).pow(0.5f) * (0.8f + 0.4f * h(i, 10))

    override fun solids(fx: Fx) {
        for (i in 0 until count) fx.orb(px[i], py[i], pz[i], radius(i), Colors.lerp(0xFF07050C.toInt(), color(i), 0.1f), 1f)
    }

    override fun lights(fx: Fx) {
        for (i in 0 until count) {
            val s = radius(i)
            if (s <= 0.002f) continue
            val c = color(i)
            val pulse = 0.75f + 0.25f * sin(time * 2.6f + i * 1.3f)
            fx.halo(px[i], py[i], pz[i], s * 0.85f, s * 2.4f, light(fx, WorldFx.hot(c, 0.2f), 0.8f * pulse), 12)
            fx.soft(px[i], py[i], pz[i], s * 5.5f, light(fx, c, 0.1f))
            streak(fx, i, light(fx, c, 0.45f), s * 0.6f, 7, 0.07f)
        }
    }
}

/** Stars turning slowly round you, the nearest ones joined by faint lines into constellations. */
private class AstralSwarm(job: Motes.Job, camera: Vec3, time: Float) : Swarm(job, camera, time) {
    private val near = IntArray(count) { -1 }

    init {
        settle()
        for (i in 0 until count) {
            var best = Float.MAX_VALUE
            for (j in 0 until count) {
                if (j == i) continue
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val dz = pz[i] - pz[j]
                val d = dx * dx + dy * dy + dz * dz
                if (d < best) {
                    best = d
                    near[i] = j
                }
            }
        }
    }

    override fun place(i: Int, back: Float, out: Vector3f) {
        // Slower than the gems, so the shapes hold long enough to read.
        orbit(i, (drift - back * motes.drift) * 0.45f, ringOf(i), out)
    }

    private fun twinkle(i: Int) = 0.35f + 0.65f * abs(sin(time * (0.8f + h(i, 7) * 1.6f) + i * 1.9f)).pow(3f)

    override fun lights(fx: Fx) {
        val reach = motes.radius * 0.55f
        for (i in 0 until count) {
            val j = near[i]
            if (j < 0 || (near[j] == i && j < i)) continue
            val dx = px[i] - px[j]
            val dy = py[i] - py[j]
            val dz = pz[i] - pz[j]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > reach) continue
            val a = 0.3f * (1f - d / reach)
            val w = 0.004f + motes.size * 0.06f
            fx.line(px[i], py[i], pz[i], px[j], py[j], pz[j], w, w, light(fx, color(i), a), light(fx, color(j), a))
        }
        for (i in 0 until count) {
            val tw = twinkle(i)
            val c = color(i)
            val s = motes.size * (0.6f + 0.8f * h(i, 10))
            fx.star(px[i], py[i], pz[i], s * (2.5f + 2.5f * tw), light(fx, WorldFx.hot(c, 0.65f), 0.4f + 0.6f * tw))
            fx.soft(px[i], py[i], pz[i], s * 4f, light(fx, c, 0.12f * tw))
        }
    }
}

/** Tiny butterflies of light wandering round you, wings beating, trailing a little pollen. */
private class SylphSwarm(job: Motes.Job, camera: Vec3, time: Float) : Swarm(job, camera, time) {
    private val heading = FloatArray(count)
    private val ahead = Vector3f()

    init {
        settle()
        for (i in 0 until count) {
            at(i, 0.08f, ahead)
            heading[i] = atan2(px[i] - ahead.x, pz[i] - ahead.z)
        }
    }

    override fun place(i: Int, back: Float, out: Vector3f) {
        val t = (drift - back * motes.drift) * 0.6f
        val r = ringOf(i)
        out.set(
            ax + sin(t * (0.7f + h(i, 3) * 0.6f) + h(i, 0) * TAU) * r,
            ay + (h(i, 15) - 0.5f) * motes.height * 0.6f + sin(t * (1.3f + h(i, 6)) + i) * motes.height * 0.1f,
            az + cos(t * (0.5f + h(i, 4) * 0.6f) + h(i, 5) * TAU) * r,
        )
    }

    override fun solids(fx: Fx) {
        val q = Quaternionf()
        for (i in 0 until count) {
            val s = motes.size * 2.6f * (0.8f + 0.4f * h(i, 10))
            val beat = sin(time * 15f * (0.8f + 0.4f * h(i, 11)) + i) * 0.85f + 0.35f
            val c = color(i)
            val tip = WorldFx.hot(c, 0.5f)
            for (side in floatArrayOf(1f, -1f)) {
                // Forewing angled ahead, hindwing behind and smaller; both beat together.
                q.rotationY(heading[i]).rotateZ(side * beat)
                if (side < 0f) q.rotateY(PI_F)
                fx.leaf(px[i], py[i], pz[i], Quaternionf(q).rotateY(-0.45f * side), s, s * 0.8f, WING, c, tip)
                fx.leaf(px[i], py[i], pz[i], Quaternionf(q).rotateY(0.5f * side), s * 0.7f, s * 0.6f, WING, c, tip)
            }
        }
    }

    override fun lights(fx: Fx) {
        for (i in 0 until count) {
            val c = color(i)
            val s = motes.size * (0.8f + 0.4f * h(i, 10))
            fx.soft(px[i], py[i], pz[i], s * 1.4f, light(fx, WorldFx.hot(c, 0.7f), 0.85f))
            fx.soft(px[i], py[i], pz[i], s * 5f, light(fx, c, 0.14f))
            streak(fx, i, light(fx, WorldFx.hot(c, 0.4f), 0.35f), s * 0.25f, 6, 0.08f)
        }
    }

    private companion object {
        val WING = floatArrayOf(0f, 0.25f, 0.35f, 1f, 0.8f, 0.85f, 1f, 0f)
    }
}
