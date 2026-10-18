package dev.pawfect.addons.features.cosmetics

import dev.pawfect.addons.features.cosmetics.WorldFx.TAU
import dev.pawfect.addons.features.cosmetics.celestial.Colors
import dev.pawfect.addons.features.cosmetics.celestial.Shapes
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI_F = PI.toFloat()

internal abstract class Bloom(val ring: Ripples.Ring, camera: Vec3, now: Long, protected val time: Float) {
    protected val ripple = ring.ripple
    protected val palette = ripple.palette ?: ring.trail.palette
    protected val power = ring.power
    protected val seed = ring.seed * 7919 + 17
    protected val age = ((now - ring.bornAt).toFloat() / ring.lifeMs).coerceIn(0f, 1f)
    protected val seconds = (now - ring.bornAt) / 1000f
    protected val additive = ring.trail.glow

    protected val front = (age * ripple.speed).coerceIn(0f, 1f).pow(0.55f) * ripple.size * power
    protected val fade = envelope(age) * power
    protected val scale = sqrt(ripple.size / 1.6f) * power
    protected val thick = ripple.thickness * power

    protected val cx = (ring.center.x - camera.x).toFloat()
    protected val cy = (ring.center.y - camera.y).toFloat()
    protected val cz = (ring.center.z - camera.z).toFloat()

    protected fun h(i: Int, channel: Int) = WorldFx.hash(seed, i, channel)

    protected fun color(at: Float) = palette.colorAt(at, time) or 0xFF000000.toInt()

    protected fun light(fx: Fx, color: Int, alpha: Float) =
        fx.light(color and 0xFFFFFF or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24), additive)

    open fun solids(fx: Fx) {}

    abstract fun lights(fx: Fx)

    protected fun groundRing(fx: Fx, r: Float, width: Float, rim: Int, body: Int, lift: Float = 0f) {
        if (r <= 0.01f) return
        fx.band(cx, cy + lift, cz, (r - width * 2.2f).coerceAtLeast(0f), r - width * 0.25f, 0, body)
        fx.band(cx, cy + lift, cz, r - width * 0.25f, r, body, rim)
        fx.band(cx, cy + lift + 0.002f, cz, r, r + width * 0.6f, rim, 0)
    }

    protected fun flash(fx: Fx, c: Int) {
        val f = (1f - age / 0.25f).coerceIn(0f, 1f)
        if (f <= 0f) return
        val hot = light(fx, WorldFx.hot(c, 0.6f), f * power * 0.8f)
        fx.soft(cx, cy + 0.15f, cz, (0.5f + 0.4f * age) * scale, hot, 14)
        fx.band(cx, cy + 0.003f, cz, 0f, 0.9f * scale, hot, 0)
    }

    private fun envelope(age: Float): Float {
        val rise = (age / 0.12f).coerceIn(0f, 1f)
        val fall = ((1f - age) / 0.88f).coerceIn(0f, 1f)
        return rise * fall.pow(1.15f)
    }

    companion object {
        fun of(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float): Bloom = when (ring.ripple.form) {
            Form.PRISM -> ShardBloom(ring, camera, now, time)
            Form.EMBER -> FireBloom(ring, camera, now, time)
            Form.SERAPH -> HaloBloom(ring, camera, now, time)
            Form.ABYSS -> VoidBloom(ring, camera, now, time)
            Form.ASTRAL -> StarBloom(ring, camera, now, time)
            Form.SYLPH -> PetalBloom(ring, camera, now, time)
        }
    }
}

private class ShardBloom(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float) : Bloom(ring, camera, now, time) {
    private val spin = Quaternionf()

    override fun solids(fx: Fx) {
        if (front <= 0.01f) return
        for (i in 0 until SHARDS) {
            val delay = h(i, 1) * 0.12f
            val local = ((age - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val pop = sin(PI_F * (local / 0.35f).coerceAtMost(1f) * 0.5f)
            val sink = if (local < 0.55f) 1f else (1f - (local - 0.55f) / 0.45f).coerceAtLeast(0f)
            val grow = pop * sink
            if (grow <= 0.01f) continue
            val a = (i + h(i, 2) * 0.6f) / SHARDS * TAU
            val r = front * (0.78f + h(i, 3) * 0.12f)
            val tall = (0.14f + h(i, 4) * 0.12f) * scale
            val wide = tall * (0.32f + h(i, 5) * 0.1f)
            spin.identity().rotateY(-a).rotateZ(-(0.35f + h(i, 6) * 0.25f)).rotateY(h(i, 7) * TAU)
            fx.crystal(cx + cos(a) * r, cy - tall * 0.9f + tall * 1.25f * grow, cz + sin(a) * r, spin, wide * grow, tall * grow, color(i / SHARDS.toFloat()), 0.9f)
        }
    }

    override fun lights(fx: Fx) {
        if (fade <= 0.003f) return
        val c = color(age)
        val rim = light(fx, WorldFx.hot(c, 0.3f), fade)
        val body = light(fx, c, fade * 0.45f)
        groundRing(fx, front, thick, rim, body)
        val trailRing = fx.dim(body, 0.7f)
        fx.band(cx, cy + 0.001f, cz, (front * 0.6f - thick).coerceAtLeast(0f), front * 0.6f, 0, trailRing)
        fx.band(cx, cy + 0.001f, cz, front * 0.6f, front * 0.6f + thick * 0.4f, trailRing, 0)
        fx.wall(cx, cz, front, cy, cy + 0.42f * scale * (1f - age).pow(0.8f), fx.dim(rim, 0.55f), 0)
        flash(fx, c)
        val reach = ripple.size * power * ripple.speed
        for (i in 0 until SPARKS) {
            val a = h(i, 20) * TAU
            val out = reach * (0.55f + h(i, 21) * 0.6f)
            val upward = (2.2f + h(i, 22) * 2.2f) * scale
            val t = seconds * (0.8f + h(i, 23) * 0.4f)
            val y = upward * t - 0.5f * 7f * t * t
            if (y < -0.05f) continue
            val d = out * (1f - (1f - (t / ripple.life).coerceIn(0f, 1f)).pow(2f))
            val glow = (1f - age) * (0.5f + 0.5f * sin(time * 9f + i * 1.7f).let { it * it })
            fx.star(cx + cos(a) * d, cy + y.coerceAtLeast(0f), cz + sin(a) * d, (0.06f + 0.05f * h(i, 25)) * scale, light(fx, WorldFx.hot(color(h(i, 24)), 0.5f), glow * power))
        }
    }

    private companion object {
        const val SHARDS = 14
        const val SPARKS = 22
    }
}

private class FireBloom(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float) : Bloom(ring, camera, now, time) {

    override fun lights(fx: Fx) {
        if (fade <= 0.003f) return
        val c = color(age * 0.6f)
        fx.band(cx, cy, cz, 0f, front, light(fx, Colors.scale(c, 0.6f), fade * 0.18f), light(fx, c, fade * 0.35f))
        groundRing(fx, front, thick, light(fx, WorldFx.hot(c, 0.4f), fade), light(fx, c, fade * 0.4f), 0.001f)
        val tall = 0.55f * scale * (1f - age).pow(0.7f)
        for (i in 0 until FLAMES) {
            val a = (i + h(i, 1) * 0.5f) / FLAMES * TAU
            val flick = 0.6f + 0.4f * sin(time * 12f + i * 2.3f) * sin(time * 5.1f + i)
            val fc = color(h(i, 2))
            val x = cx + cos(a) * front
            val z = cz + sin(a) * front
            val height = tall * flick * (0.7f + 0.6f * h(i, 3))
            val sway = sin(time * 6f + i) * height * 0.15f
            fx.flame(x, cy, z, 0.07f * scale + thick * 0.3f, height, sway, light(fx, fc, fade * 0.85f), 0)
            fx.flame(x, cy, z, 0.035f * scale + thick * 0.15f, height * 0.55f, sway * 0.5f, light(fx, WorldFx.hot(fc, 0.6f), fade), 0)
        }
        flash(fx, c)
        for (i in 0 until EMBERS) {
            val a = h(i, 20) * TAU
            val t = seconds * (0.7f + h(i, 21) * 0.5f)
            val d = ripple.size * power * (0.2f + h(i, 22) * 0.9f) * (1f - exp(-t * 2.5f))
            val y = (0.6f + h(i, 23) * 1.4f) * scale * t + sin(t * 4f + i) * 0.05f
            val glow = (1f - age) * (0.6f + 0.4f * sin(time * 15f + i * 1.3f))
            val ec = color(0.5f + 0.5f * h(i, 24))
            val x = cx + cos(a) * d + sin(t * 3f + i) * 0.06f
            val z = cz + sin(a) * d
            fx.soft(x, cy + y, z, 0.035f * scale, light(fx, WorldFx.hot(ec, 0.6f), glow * power))
            fx.soft(x, cy + y, z, 0.12f * scale, light(fx, ec, 0.14f * glow))
        }
    }

    private companion object {
        const val FLAMES = 40
        const val EMBERS = 26
    }
}

private class HaloBloom(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float) : Bloom(ring, camera, now, time) {
    private val rot = Quaternionf()

    private fun feather(i: Int, out: FloatArray) {
        val a = (i + h(i, 30) * 0.5f) / FEATHERS * TAU
        val t = seconds * (0.85f + h(i, 31) * 0.3f)
        val d = ripple.size * power * (0.35f + 0.4f * h(i, 32)) * (1f - exp(-t * 3f))
        val up = (0.5f + 0.4f * h(i, 33)) * scale
        val y = up * (1f - exp(-t * 4f)) - max(0f, t - 0.3f) * 0.35f
        val sway = sin(t * 3.5f + i) * 0.08f
        out[0] = cx + cos(a) * d - sin(a) * sway
        out[1] = cy + max(0.02f, y)
        out[2] = cz + sin(a) * d + cos(a) * sway
        out[3] = a
        out[4] = t
    }

    override fun solids(fx: Fx) {
        val f = FloatArray(5)
        val shrink = ((1f - age) / 0.25f).coerceAtMost(1f)
        if (shrink <= 0f) return
        for (i in 0 until FEATHERS) {
            feather(i, f)
            rot.rotationY(-f[3]).rotateZ(0.5f - f[4] * 0.6f).rotateX(sin(f[4] * 3.5f + i) * 0.5f)
            val len = (0.18f + 0.08f * h(i, 34)) * scale * shrink
            val c = color(h(i, 35))
            fx.leaf(f[0], f[1], f[2], rot, len, len * 0.3f, Shapes.FEATHER, Colors.scale(c, 0.85f), WorldFx.hot(c, 0.45f), rib = true)
        }
    }

    override fun lights(fx: Fx) {
        if (fade <= 0.003f) return
        val c = color(age)
        val rise = cy + 0.08f + age * 0.7f * scale
        fx.torus(cx, rise, cz, front * 0.92f, 0.03f * scale + thick * 0.08f, WorldFx.hot(c, 0.2f), fade)
        groundRing(fx, front, thick, light(fx, WorldFx.hot(c, 0.4f), fade * 0.7f), light(fx, c, fade * 0.25f))
        val tall = 1.4f * scale * (1f - age).pow(0.6f)
        for (i in 0 until RAYS) {
            val a = (i + 0.5f) / RAYS * TAU + time * 0.15f
            val shimmer = 0.6f + 0.4f * sin(time * 3f + i * 1.9f)
            fx.flame(cx + cos(a) * front * 0.92f, cy, cz + sin(a) * front * 0.92f, 0.03f * scale + thick * 0.1f, tall * (0.6f + 0.4f * h(i, 40)), 0f, light(fx, WorldFx.hot(c, 0.5f), fade * 0.55f * shimmer), 0)
        }
        flash(fx, c)
        val f = FloatArray(5)
        for (i in 0 until FEATHERS) {
            feather(i, f)
            val glint = max(0f, sin(time * 2.5f + i * 1.7f)).pow(10f) * (1f - age)
            if (glint > 0.03f) fx.star(f[0], f[1], f[2], 0.18f * scale * glint, light(fx, WorldFx.hot(c, 0.7f), glint))
        }
    }

    private companion object {
        const val FEATHERS = 12
        const val RAYS = 12
    }
}

private class VoidBloom(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float) : Bloom(ring, camera, now, time) {

    override fun lights(fx: Fx) {
        if (fade <= 0.003f) return
        val c = color(age)
        val full = ripple.size * power
        val dark = full * (1f - exp(-age * 9f))
        val shadow = fx.light(0x07040C or ((fade * 0.6f * 255f).toInt().coerceIn(0, 255) shl 24), false)
        fx.band(cx, cy, cz, 0f, dark * 0.85f, shadow, shadow)
        fx.band(cx, cy, cz, dark * 0.85f, dark, shadow, 0)
        val close = full * (1f - ((age - 0.1f) / 0.75f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) })
        groundRing(fx, close, thick, light(fx, WorldFx.hot(c, 0.35f), fade), light(fx, c, fade * 0.35f), 0.002f)
        fx.wall(cx, cz, close, cy, cy + 0.25f * scale, light(fx, c, fade * 0.35f), 0)
        for (k in 0 until TENDRILS) {
            val a0 = k / TENDRILS.toFloat() * TAU + h(k, 1)
            var lx = 0f
            var ly = 0f
            var lz = 0f
            for (j in 0..8) {
                val f = j / 8f
                val r = close + (full - close) * f * 0.8f
                val a = a0 + f * 1.4f + time * 0.4f
                val x = cx + cos(a) * r
                val z = cz + sin(a) * r
                val y = cy + 0.01f + sin(f * PI_F) * 0.06f * scale
                if (j > 0) fx.line(lx, ly, lz, x, y, z, 0.012f * scale, 0.004f, light(fx, c, fade * (1f - f) * 0.8f), light(fx, c, fade * (1f - f) * 0.3f))
                lx = x
                ly = y
                lz = z
            }
        }
        val pinch = (1f - abs(age - 0.85f) / 0.12f).coerceIn(0f, 1f)
        if (pinch > 0f) {
            fx.star(cx, cy + 0.2f, cz, 0.7f * scale * pinch, light(fx, WorldFx.hot(c, 0.7f), pinch * power))
            fx.soft(cx, cy + 0.2f, cz, 0.5f * scale * pinch, light(fx, c, 0.5f * pinch))
        }
    }

    private companion object {
        const val TENDRILS = 10
    }
}

private class StarBloom(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float) : Bloom(ring, camera, now, time) {

    override fun lights(fx: Fx) {
        if (fade <= 0.003f) return
        val c = color(age)
        groundRing(fx, front, thick * 0.6f, light(fx, WorldFx.hot(c, 0.5f), fade * 0.8f), light(fx, c, fade * 0.2f))
        flash(fx, c)
        val full = ripple.size * power
        for (i in 0 until METEORS) {
            val a = (i + h(i, 1) * 0.6f) / METEORS * TAU
            val t = (seconds * (0.9f + 0.4f * h(i, 2))) / ripple.life
            val d = full * (1.05f + 0.3f * h(i, 3)) * (1f - (1f - t.coerceIn(0f, 1f)).pow(3f))
            val y = cy + 0.05f + (0.15f + 0.5f * h(i, 4)) * scale * sin(PI_F * t.coerceIn(0f, 1f))
            val x = cx + cos(a) * d
            val z = cz + sin(a) * d
            val tail = 0.35f * scale * (1f - age)
            val mc = color(h(i, 5))
            val head = light(fx, WorldFx.hot(mc, 0.6f), fade)
            fx.line(x - cos(a) * tail, y - 0.02f, z - sin(a) * tail, x, y, z, 0.002f, 0.02f * scale, 0, head)
            fx.star(x, y, z, 0.12f * scale, head)
        }
        var lx = 0f
        var ly = 0f
        var lz = 0f
        for (k in 0..CONSTELLATION) {
            val i = k % CONSTELLATION
            val a = (i + h(i, 10) * 0.7f) / CONSTELLATION * TAU
            val r = front * (0.75f + 0.3f * h(i, 11))
            val x = cx + cos(a) * r
            val y = cy + 0.1f + 0.4f * scale * h(i, 12) * (1f - age)
            val z = cz + sin(a) * r
            val tw = 0.4f + 0.6f * abs(sin(time * 3f + i * 2.1f))
            val sc = light(fx, WorldFx.hot(color(h(i, 13)), 0.6f), fade * tw)
            if (k < CONSTELLATION) fx.star(x, y, z, 0.1f * scale * (1f + tw), sc)
            if (k > 0) fx.line(lx, ly, lz, x, y, z, 0.006f, 0.006f, light(fx, c, fade * 0.35f), light(fx, c, fade * 0.35f))
            lx = x
            ly = y
            lz = z
        }
    }

    private companion object {
        const val METEORS = 16
        const val CONSTELLATION = 7
    }
}

private class PetalBloom(ring: Ripples.Ring, camera: Vec3, now: Long, time: Float) : Bloom(ring, camera, now, time) {
    private val rot = Quaternionf()

    override fun solids(fx: Fx) {
        val shrink = ((1f - age) / 0.3f).coerceAtMost(1f)
        if (shrink <= 0f) return
        val open = (age / 0.4f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        for (layer in 0..1) {
            val count = if (layer == 0) 10 else 7
            for (i in 0 until count) {
                val a = (i + layer * 0.5f) / count * TAU + age * 0.6f
                val len = (if (layer == 0) 0.55f else 0.35f) * scale * (0.85f + 0.3f * h(i + layer * 20, 1)) * shrink
                val lift = 1.35f - open * (if (layer == 0) 1.2f else 0.9f)
                rot.rotationY(-a).rotateZ(lift)
                val c = color(h(i + layer * 20, 2))
                val r = front * 0.15f * open
                fx.leaf(cx + cos(a) * r, cy + 0.03f + layer * 0.02f, cz + sin(a) * r, rot, len, len * 0.55f, PETAL, Colors.scale(c, 0.85f), WorldFx.hot(c, 0.5f))
            }
        }
    }

    override fun lights(fx: Fx) {
        if (fade <= 0.003f) return
        val c = color(age)
        groundRing(fx, front, thick, light(fx, WorldFx.hot(c, 0.5f), fade * 0.7f), light(fx, c, fade * 0.3f))
        fx.soft(cx, cy + 0.12f, cz, 0.35f * scale, light(fx, WorldFx.hot(c, 0.6f), fade * 0.5f))
        for (i in 0 until POLLEN) {
            val a = h(i, 20) * TAU + seconds * 0.4f
            val t = seconds * (0.6f + h(i, 21) * 0.5f)
            val d = ripple.size * power * 0.5f * h(i, 22) * (1f - exp(-t * 2f))
            val y = cy + 0.05f + (0.3f + h(i, 23) * 0.6f) * scale * t
            val glow = (1f - age) * (0.5f + 0.5f * sin(time * 4f + i * 1.7f))
            val pc = color(h(i, 24))
            val x = cx + cos(a) * d
            val z = cz + sin(a) * d
            fx.soft(x, y, z, 0.03f * scale, light(fx, WorldFx.hot(pc, 0.7f), glow))
            fx.soft(x, y, z, 0.1f * scale, light(fx, pc, 0.12f * glow))
        }
    }

    private companion object {
        const val POLLEN = 24
    }
}
