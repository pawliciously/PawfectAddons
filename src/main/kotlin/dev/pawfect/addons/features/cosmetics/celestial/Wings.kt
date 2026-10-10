package dev.pawfect.addons.features.cosmetics.celestial

import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object Wings {
    val ALL: List<WingStyle> = listOf(Seraph, Abyssal, Prism, Phoenix, CelestialWings, Sylph)
}

private const val PI_F = PI.toFloat()

private fun Random.pick(list: List<Feather>) = list[nextInt(list.size)]

/** White and gold, a slow gold light running over the feathers and motes drifting down. */
object Seraph : WingStyle(
    "seraph",
    Rig(0.36f, 0.42f, 0.34f, sweep = 26f, lift = 42f, elbowSweep = -4f, elbowLift = -16f, wristSweep = 8f, wristLift = -22f, amp = 7f, period = 3.6f, lag = 0.55f, scale = 0.85f),
) {
    override val baseHue = 0.11f

    private const val GOLD = 0xFFFFC860.toInt()
    private const val GOLD_DEEP = 0xFFE89A30.toInt()

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val feathers = Layout.feathered(rig, 11L)
        for (f in feathers) {
            val z = Layout.z(f.row)
            val (c0, c1) = when (f.row) {
                0 -> 0xF5FFFCF2.toInt() to 0xF5FFE2A0.toInt()
                1 -> 0xF5FFFFFF.toInt() to 0xF5FFF0C8.toInt()
                2 -> 0xF5FFFFFF.toInt() to 0xF5FFF6DC.toInt()
                else -> 0xF8FFFFFF.toInt() to 0xF8FFF9EC.toInt()
            }
            Shapes.blade(fill, f, Shapes.FEATHER, z, c0, c1, 0)
            for (s in SIDES) Shapes.blade(glow, f, Shapes.FEATHER, z + s * 0.0015f, GOLD, GOLD, 1)
            if (f.row <= 2) for (s in SIDES) Shapes.spine(glow, f, Shapes.FEATHER, 0.009f, z + s * 0.003f, GOLD_DEEP, GOLD, 2, 0.92f)
        }
        val rnd = Random(12L)
        val outer = feathers.filter { it.row <= 1 }
        repeat(14) {
            val f = rnd.pick(outer)
            val a = 0.6f + 0.4f * rnd.nextFloat()
            sparks += Spark(f.seg, f.px(a, 0f), f.py(a, 0f), 0.02f, rnd.nextFloat())
        }
    }

    private fun band(u: Float, v: Float, t: Float): Float {
        val phase = (t * 0.3f) % 1.9f - 0.45f
        val d = (u * 0.9f + v * 0.3f - phase) * 4f
        return exp(-d * d)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        1 -> Colors.alpha(color, 0.03f + 0.55f * band(u, v, t) * (0.35f + 0.65f * v))
        2 -> Colors.alpha(color, 0.25f + 0.6f * band(u, v, t) + 0.1f * sin(t * 2f + seed * 9f))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.2f + s.seed)
        val glow = sin(PI_F * life)
        out.x = s.x + 0.04f * sin(t * 1.1f + s.seed * 30f)
        out.y = s.y - life * 0.6f
        out.z = s.z + 0.05f * sin(t * 0.9f + s.seed * 17f)
        out.size = 0.024f * glow
        out.color = Colors.alpha(GOLD, 0.9f * glow)
        return true
    }
}

/** Obsidian bone and a dark membrane, violet pulses running out along the veins. */
object Abyssal : WingStyle(
    "abyssal",
    Rig(0.34f, 0.4f, 0.1f, sweep = 24f, lift = 38f, elbowSweep = -6f, elbowLift = -10f, wristSweep = 12f, wristLift = -30f, amp = 10f, period = 4.2f, lag = 0.6f, scale = 0.9f),
) {
    override val baseHue = 0.76f

    private const val VIOLET = 0xFFB048FF.toInt()
    private const val RINGS = 6

    private class Ray(val seg: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float, var u: Float = 0f) {
        fun x(r: Float) = x0 + (x1 - x0) * r
        fun y(r: Float) = y0 + (y1 - y0) * r
    }

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val rest = Layout.restAngles(rig)
        fun ray(seg: Int, fromX: Float, angle: Float, dist: Float): Ray {
            val a = (angle - rest[seg]) * DEG
            return Ray(seg, fromX, 0f, fromX + cos(a) * dist, sin(a) * dist)
        }

        val fingers = listOf(ray(2, 0f, 30f, 0.8f), ray(2, 0f, 0f, 0.95f), ray(2, 0f, -32f, 0.82f), ray(2, 0f, -62f, 0.66f))
        val rays = ArrayList<Ray>()
        for (i in fingers.indices) {
            rays += fingers[i]
            if (i < fingers.size - 1) {
                val a = fingers[i]
                val b = fingers[i + 1]
                rays += Ray(2, 0f, 0f, (a.x1 + b.x1) * 0.5f * 0.78f, (a.y1 + b.y1) * 0.5f * 0.78f)
            }
        }
        rays += ray(2, 0f, -98f, 0.6f)
        rays += ray(1, rig.l1 * 0.5f, -92f, 0.5f)
        rays += ray(0, rig.l0 * 0.5f, -93f, 0.46f)
        rays += ray(0, 0f, -90f, 0.5f)
        rays.forEachIndexed { i, r -> r.u = i / (rays.size - 1f) }

        // Membrane, with a faint violet copy on both faces for the pulse to wash over.
        for (k in 0 until rays.size - 1) {
            val a = rays[k]
            val b = rays[k + 1]
            for (i in 0 until RINGS) {
                val r0 = i / RINGS.toFloat()
                val r1 = (i + 1) / RINGS.toFloat()
                fun v(ray: Ray, r: Float, z: Float, color: Int, kind: Int) = Vtx(ray.seg, ray.x(r), ray.y(r), z, color, ray.u, r, kind, 0f)
                val inner = 0xF22A1238.toInt()
                val edge = 0xE4120818.toInt()
                fill.quad(
                    v(a, r0, 0f, Colors.lerp(inner, edge, r0), 0), v(a, r1, 0f, Colors.lerp(inner, edge, r1), 0),
                    v(b, r1, 0f, Colors.lerp(inner, edge, r1), 0), v(b, r0, 0f, Colors.lerp(inner, edge, r0), 0),
                )
                for (s in SIDES) {
                    val z = s * 0.0015f
                    glow.quad(v(a, r0, z, VIOLET, 5), v(a, r1, z, VIOLET, 5), v(b, r1, z, VIOLET, 5), v(b, r0, z, VIOLET, 5))
                }
            }
        }

        // Veins down every ray that isn't a finger.
        for (r in rays) {
            if (r in fingers) continue
            for (s in SIDES) {
                for (i in 0 until RINGS) {
                    val r0 = i / RINGS.toFloat()
                    val r1 = (i + 1) / RINGS.toFloat()
                    Shapes.line(glow, r.seg, r.x(r0), r.y(r0), r.x(r1), r.y(r1), 0.009f * (1f - r0 * 0.6f), 0.009f * (1f - r1 * 0.6f), s * 0.006f, VIOLET, VIOLET, r.u, r.u, r0, r1, 3, 0f)
                }
            }
        }

        // Bones, claws and a glowing seam along each finger.
        val bone = 0xFF1C1426.toInt()
        val boneTip = 0xFF0C0810.toInt()
        for (s in SIDES) {
            val z = s * 0.007f
            Shapes.line(fill, 0, 0f, 0f, rig.l0, 0f, 0.055f, 0.045f, z, bone, bone, 0f, 0f, 0f, 0f, 0, 0f)
            Shapes.line(fill, 1, 0f, 0f, rig.l1, 0f, 0.045f, 0.038f, z, bone, bone, 0f, 0f, 0f, 0f, 0, 0f)
            Shapes.diamond(fill, 2, 0f, 0f, s * 0.008f, 0.04f, bone, 0f, 0f, 0, 0f)
            val thumb = ray(2, 0f, 105f, 0.15f)
            Shapes.line(fill, 2, 0f, 0f, thumb.x1, thumb.y1, 0.035f, 0f, z, bone, boneTip, 0f, 0f, 0f, 0f, 0, 0f)
            Shapes.line(glow, 2, thumb.x(0.6f), thumb.y(0.6f), thumb.x1, thumb.y1, 0.012f, 0f, s * 0.0085f, VIOLET, VIOLET, 0f, 0f, 0f, 0f, 4, 0f)
            for (f in fingers) {
                for (i in 0 until 3) {
                    val r0 = i / 3f
                    val r1 = (i + 1) / 3f
                    Shapes.line(fill, 2, f.x(r0), f.y(r0), f.x(r1), f.y(r1), 0.04f - 0.03f * r0, 0.04f - 0.03f * r1, z, Colors.lerp(bone, boneTip, r0), Colors.lerp(bone, boneTip, r1), f.u, f.u, r0, r1, 0, 0f)
                    Shapes.line(glow, 2, f.x(r0), f.y(r0), f.x(r1), f.y(r1), 0.006f, 0.004f, s * 0.0085f, VIOLET, VIOLET, f.u, f.u, r0, r1, 4, 0f)
                }
                Shapes.line(fill, 2, f.x1, f.y1, f.x(1.12f), f.y(1.12f), 0.014f, 0f, z, boneTip, boneTip, f.u, f.u, 1f, 1.12f, 0, 0f)
                Shapes.line(glow, 2, f.x1, f.y1, f.x(1.12f), f.y(1.12f), 0.01f, 0f, s * 0.0085f, VIOLET, VIOLET, f.u, f.u, 1f, 1.12f, 4, 0f)
            }
        }

        val rnd = Random(21L)
        repeat(16) {
            val r = rays[rnd.nextInt(rays.size)]
            val at = 0.7f + 0.3f * rnd.nextFloat()
            sparks += Spark(r.seg, r.x(at), r.y(at), 0f, rnd.nextFloat())
        }
    }

    /** Linear in v along each ray, so membrane, veins and bones bend together. */
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        p.z += v * 0.03f * sin(t * 1.9f + u * 1.2f)
    }

    private fun wave(v: Float, t: Float): Float = max(0f, sin((v * 1.4f - t * 0.55f) * 2f * PI_F)).pow(4f)

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        3 -> Colors.alpha(color, 0.18f + 0.82f * wave(v, t))
        4 -> Colors.alpha(color, 0.3f + 0.2f * sin(t * 1.5f))
        5 -> Colors.alpha(color, 0.16f * wave(v, t))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.3f + s.seed)
        out.x = s.x + 0.05f * sin(s.seed * 40f + t) * life
        out.y = s.y - life * 0.5f
        out.z = s.z + 0.05f * cos(s.seed * 23f + t * 0.8f) * life
        out.size = 0.02f * (1f - life)
        out.color = Colors.alpha(VIOLET, 0.85f * (1f - life))
        return true
    }
}

/** Floating crystal shards in slow shifting colour, light catching across them. */
object Prism : WingStyle(
    "prism",
    Rig(0.36f, 0.42f, 0.34f, sweep = 24f, lift = 40f, elbowSweep = -4f, elbowLift = -14f, wristSweep = 8f, wristLift = -20f, amp = 3.5f, period = 5.5f, lag = 0.5f, scale = 0.85f),
) {
    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val shards = Layout.feathered(rig, 37L, len = 1.05f, width = 0.72f).filter { it.row != 4 }
        for (f in shards) {
            val z = Layout.z(f.row)
            Shapes.blade(fill, f, Shapes.SHARD, z, 0x88FFFFFF.toInt(), 0xC8FFFFFF.toInt(), 20)
            for (s in SIDES) {
                Shapes.blade(glow, f, Shapes.SHARD, z + s * 0.0015f, -1, -1, 21)
                Shapes.outline(glow, f, Shapes.SHARD, 0.005f, z + s * 0.003f, -1, 22)
            }
        }
        val rnd = Random(38L)
        repeat(22) {
            val f = rnd.pick(shards)
            val a = 0.3f + 0.9f * rnd.nextFloat()
            sparks += Spark(f.seg, f.px(a, (rnd.nextFloat() - 0.5f) * 0.2f), f.py(a, (rnd.nextFloat() - 0.5f) * 0.2f), (rnd.nextFloat() - 0.5f) * 0.08f, rnd.nextFloat())
        }
    }

    /** Each shard drifts as one piece, so it floats apart from its neighbours. */
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        p.x += 0.01f * sin(t * 0.9f + seed * 4f)
        p.y += 0.015f * sin(t * 1.1f + seed * 9.7f)
        p.z += 0.03f * sin(t * 1.4f + seed * 6.283f)
    }

    private fun hue(u: Float, v: Float, t: Float) = Paint.hue(u * 0.55f + v * 0.18f + t * 0.06f)

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int {
        val h = hue(u, v, t)
        return when (kind) {
            20 -> Colors.rgb(color, Colors.hsv(h, 0.4f, 1f))
            21 -> {
                val phase = (t * 0.42f) % 2.1f - 0.55f
                val d = (u - v * 0.45f - phase) * 5f
                Colors.alpha(Colors.hsv(h + 0.08f, 0.2f, 1f), 0.05f + 0.65f * exp(-d * d))
            }
            else -> Colors.alpha(Colors.hsv(h + 0.15f, 0.55f, 1f), 0.4f + 0.25f * sin(t * 2f + seed * 9f))
        }
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val tw = max(0f, sin(t * 1.7f + s.seed * 50f)).pow(3f)
        out.x = s.x
        out.y = s.y + 0.02f * sin(t + s.seed * 11f)
        out.z = s.z
        out.size = 0.026f * tw
        out.color = Colors.alpha(Colors.hsv(Paint.hue(s.seed + t * 0.06f), 0.35f, 1f), 0.9f * tw)
        return true
    }
}

/** Fire feathers that fade out at the tips, flames licking past them and embers rising. */
object Phoenix : WingStyle(
    "phoenix",
    Rig(0.36f, 0.42f, 0.34f, sweep = 22f, lift = 44f, elbowSweep = -4f, elbowLift = -16f, wristSweep = 8f, wristLift = -24f, amp = 13f, period = 2.4f, lag = 0.5f, scale = 0.85f),
) {
    override val baseHue = 0.08f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val feathers = Layout.feathered(rig, 53L, len = 1.18f, width = 0.8f)
        for (f in feathers) {
            val z = Layout.z(f.row)
            val (c0, c1) = when (f.row) {
                0 -> 0xF2FFC23A.toInt() to 0x26FF2400
                1 -> 0xF2FFC844.toInt() to 0x30FF3008
                2 -> 0xF2FFD25A.toInt() to 0x40FF4810
                3 -> 0xF8FFE08A.toInt() to 0xC8FF8A1C.toInt()
                else -> 0xFAFFF0C0.toInt() to 0xE0FFB040.toInt()
            }
            Shapes.blade(fill, f, Shapes.FLAME, z, c0, c1, 10)
            for (s in SIDES) {
                Shapes.blade(glow, f, Shapes.FLAME, z + s * 0.0015f, -1, -1, 11)
                if (f.row <= 1) Shapes.blade(glow, f, Shapes.FLAME, z + s * 0.002f, -1, -1, 12, from = 0.62f, to = 1.25f, widthMul = 0.55f)
            }
        }
        val rnd = Random(54L)
        val outer = feathers.filter { it.row <= 1 }
        repeat(30) {
            val f = rnd.pick(outer)
            val a = 0.95f + 0.15f * rnd.nextFloat()
            sparks += Spark(f.seg, f.px(a, 0f), f.py(a, 0f), 0f, rnd.nextFloat())
        }
    }

    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        p.z += v * 0.024f * sin(t * 4f + u * 7f + seed * 6.283f)
        p.y += v * 0.018f * sin(t * 10.5f + seed * 30f)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        10 -> {
            val flick = 0.5f + 0.5f * sin(t * 9f + seed * 31f + v * 5f)
            Colors.rgb(color, Colors.lerp(color, 0xFFFFF2A0.toInt(), 0.28f * flick * v))
        }
        11 -> {
            val flick = 0.5f + 0.5f * sin(t * 13f + seed * 17f + v * 3f)
            Colors.alpha(Colors.lerp(0xFFFFE27A.toInt(), 0xFFFF3A00.toInt(), v), (1f - 0.75f * v) * (0.22f + 0.25f * flick))
        }
        else -> {
            val along = ((v - 0.62f) / 0.63f).coerceIn(0f, 1f)
            val f = 0.5f + 0.5f * sin(t * 7.5f + seed * 23f)
            val g = 0.5f + 0.5f * sin(t * 12.3f + seed * 7f)
            Colors.alpha(Colors.lerp(0xFFFFB030.toInt(), 0xFFFF2000.toInt(), along), (0.15f + 0.55f * f * g) * (1f - along))
        }
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.55f + s.seed)
        out.x = s.x + 0.06f * sin(s.seed * 40f + t * 2.3f) * life
        out.y = s.y + life * 0.75f
        out.z = s.z + 0.06f * cos(s.seed * 31f + t * 1.7f) * life
        out.size = 0.03f * (1f - life).pow(0.7f) + 0.004f
        out.color = Colors.alpha(Colors.lerp(0xFFFFE070.toInt(), 0xFFFF2800.toInt(), life), 0.95f * (1f - life).pow(1.3f))
        return true
    }
}

/** Night sky feathers full of twinkling stars and faint constellations, nebula light drifting through. */
object CelestialWings : WingStyle(
    "celestial",
    Rig(0.36f, 0.42f, 0.34f, sweep = 24f, lift = 40f, elbowSweep = -4f, elbowLift = -14f, wristSweep = 8f, wristLift = -20f, amp = 6f, period = 4.8f, lag = 0.55f, scale = 0.85f),
) {
    override val baseHue = 0.6f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val feathers = Layout.feathered(rig, 23L)
        for (f in feathers) {
            val z = Layout.z(f.row)
            val (c0, c1) = when (f.row) {
                0 -> 0xF20B1236.toInt() to 0xEA1F3A86.toInt()
                1 -> 0xF20A1030.toInt() to 0xEA192E70.toInt()
                2 -> 0xF209102C.toInt() to 0xEA16285F.toInt()
                else -> 0xF5070B22.toInt() to 0xF0101A46.toInt()
            }
            Shapes.blade(fill, f, Shapes.FEATHER, z, c0, c1, 0)
            for (s in SIDES) {
                Shapes.blade(glow, f, Shapes.FEATHER, z + s * 0.0015f, -1, -1, 8)
                if (f.row <= 1) Shapes.spine(glow, f, Shapes.FEATHER, 0.006f, z + s * 0.003f, 0xFF7FE8FF.toInt(), 0xFF7FE8FF.toInt(), 9, 0.92f)
            }
        }

        val rnd = Random(24L)
        val starry = feathers.filter { it.row <= 3 }
        fun star(f: Feather, a: Float, side: Float, r: Float, color: Int) {
            for (s in SIDES) {
                val z = Layout.z(f.row) + s * 0.004f
                Shapes.diamond(glow, f.seg, f.px(a, side), f.py(a, side), z, r, color, f.u, a, 7, f.seed)
            }
        }
        repeat(70) {
            val f = rnd.pick(starry)
            val color = Colors.lerp(-1, 0xFFA8E0FF.toInt(), rnd.nextFloat())
            star(f, 0.12f + 0.8f * rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * f.width * 0.6f, 0.008f + 0.014f * rnd.nextFloat().pow(2f), color)
        }

        // A few bright stars with a glint cross.
        val primaries = feathers.filter { it.row == 0 }
        repeat(7) {
            val f = rnd.pick(primaries)
            val a = 0.3f + 0.6f * rnd.nextFloat()
            val x = f.px(a, 0f)
            val y = f.py(a, 0f)
            star(f, a, 0f, 0.016f, -1)
            for (s in SIDES) {
                val z = Layout.z(f.row) + s * 0.0045f
                Shapes.line(glow, f.seg, x - 0.05f, y, x + 0.05f, y, 0.005f, 0.005f, z, -1, -1, f.u, f.u, a, a, 7, f.seed)
                Shapes.line(glow, f.seg, x, y - 0.05f, x, y + 0.05f, 0.005f, 0.005f, z, -1, -1, f.u, f.u, a, a, 7, f.seed)
            }
        }

        // Constellations strung down three primaries.
        for (index in intArrayOf(2, 5, 7)) {
            val f = primaries[index]
            val points = listOf(0.35f to 0.02f, 0.6f to -0.025f, 0.85f to 0.015f)
            for ((a, side) in points) star(f, a, side, 0.014f, -1)
            for (i in 0 until points.size - 1) {
                val (a0, s0) = points[i]
                val (a1, s1) = points[i + 1]
                for (s in SIDES) {
                    Shapes.line(glow, f.seg, f.px(a0, s0), f.py(a0, s0), f.px(a1, s1), f.py(a1, s1), 0.004f, 0.004f, Layout.z(0) + s * 0.0035f, 0xFF9FD8FF.toInt(), 0xFF9FD8FF.toInt(), f.u, f.u, a0, a1, 10, f.seed)
                }
            }
        }

        repeat(18) {
            val f = rnd.pick(feathers.filter { it.row <= 1 })
            val a = 0.5f + 0.5f * rnd.nextFloat()
            sparks += Spark(f.seg, f.px(a, 0f), f.py(a, 0f), 0.02f, rnd.nextFloat())
        }
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        8 -> {
            val m = 0.5f + 0.5f * sin(u * 3.4f - t * 0.35f + v * 1.5f)
            val glow = 0.05f + 0.13f * (0.5f + 0.5f * sin(u * 5f + t * 0.6f + seed * 6f))
            Colors.alpha(Colors.lerp(0xFF3AD8FF.toInt(), 0xFF9B5CFF.toInt(), m), glow * (0.3f + 0.7f * v))
        }
        9 -> Colors.alpha(color, 0.3f + 0.15f * sin(t * 1.2f + u * 4f))
        7 -> {
            val tw = 0.5f + 0.5f * sin(t * (2f + seed * 2f) + v * 91f + seed * 40f)
            Colors.alpha(color, 0.35f + 0.65f * tw * tw * tw)
        }
        10 -> Colors.alpha(color, 0.18f + 0.1f * sin(t * 0.8f + seed * 5f))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.18f + s.seed)
        val glow = sin(PI_F * life) * (0.6f + 0.4f * sin(t * 8f + s.seed * 60f))
        out.x = s.x + 0.03f * sin(t * 0.7f + s.seed * 13f)
        out.y = s.y - life * 0.55f
        out.z = s.z
        out.size = 0.018f * glow
        out.color = Colors.alpha(Colors.lerp(-1, 0xFF8FE6FF.toInt(), s.seed), 0.9f * glow)
        return true
    }
}

/** Fairy wings: two thin iridescent lobes and a streamer each side, fluttering fast and shedding glitter. */
object Sylph : WingStyle(
    "sylph",
    Rig(0f, 0f, 0f, sweep = 16f, lift = 8f, elbowSweep = 6f, elbowLift = -10f, wristSweep = 0f, wristLift = 0f, amp = 14f, period = 1.1f, lag = 0.35f, scale = 0.9f, tilt = 8f),
) {
    private const val STEPS = 14

    private class Lobe(val seg: Int, angle: Float, val length: Float, val half: Float, val seed: Float) {
        private val dx = cos(angle * DEG)
        private val dy = sin(angle * DEG)

        fun width(s: Float) = half * sin(PI_F * s.pow(0.8f)).coerceAtLeast(0f).pow(0.75f) * (1f - 0.15f * s)

        /** A point on the lobe: s along it from the root, side across it in blocks. */
        fun x(s: Float, side: Float) = dx * length * s - dy * (side + length * 0.06f * sin(PI_F * s))
        fun y(s: Float, side: Float) = dy * length * s + dx * (side + length * 0.06f * sin(PI_F * s))
    }

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val rest = Layout.restAngles(rig)
        val lobes = listOf(
            Lobe(0, 46f - rest[0], 1f, 0.27f, 0.1f),
            Lobe(1, -30f - rest[1], 0.62f, 0.17f, 0.5f),
            Lobe(1, -62f - rest[1], 0.5f, 0.045f, 0.8f),
        )
        for ((index, lobe) in lobes.withIndex()) {
            val z = index * -0.004f
            fun v(s: Float, side: Float, color: Int, kind: Int, zz: Float = z) =
                Vtx(lobe.seg, lobe.x(s, side), lobe.y(s, side), zz, color, s, if (side == 0f) 0f else 1f, kind, lobe.seed)

            val center = 0x48FFFFFF
            val edge = 0xA8FFFFFF.toInt()
            for (i in 0 until STEPS) {
                val s0 = i / STEPS.toFloat()
                val s1 = (i + 1) / STEPS.toFloat()
                val w0 = lobe.width(s0)
                val w1 = lobe.width(s1)
                fill.quad(v(s0, 0f, center, 30), v(s1, 0f, center, 30), v(s1, w1, edge, 30), v(s0, w0, edge, 30))
                fill.quad(v(s0, 0f, center, 30), v(s0, -w0, edge, 30), v(s1, -w1, edge, 30), v(s1, 0f, center, 30))
                for (s in SIDES) {
                    val zz = z + s * 0.003f
                    for (side in SIDES) {
                        Shapes.line(glow, lobe.seg, lobe.x(s0, side * w0), lobe.y(s0, side * w0), lobe.x(s1, side * w1), lobe.y(s1, side * w1), 0.007f, 0.007f, zz, -1, -1, s0, s1, 1f, 1f, 31, lobe.seed)
                    }
                    Shapes.line(glow, lobe.seg, lobe.x(s0, 0f), lobe.y(s0, 0f), lobe.x(s1, 0f), lobe.y(s1, 0f), 0.006f, 0.006f, zz, -1, -1, s0, s1, 0f, 0f, 32, lobe.seed)
                }
            }
            if (lobe.half > 0.1f) {
                for (sb in floatArrayOf(0.2f, 0.42f, 0.62f)) {
                    val se = sb + 0.18f
                    for (side in SIDES) {
                        val w = side * lobe.width(se) * 0.92f
                        for (s in SIDES) {
                            Shapes.line(glow, lobe.seg, lobe.x(sb, 0f), lobe.y(sb, 0f), lobe.x(se, w), lobe.y(se, w), 0.005f, 0.004f, z + s * 0.005f, -1, -1, sb, se, 0f, 1f, 32, lobe.seed)
                        }
                    }
                }
            }
            if (index == 0) {
                for (s in SIDES) {
                    val zz = z + s * 0.004f
                    Shapes.diamond(glow, lobe.seg, lobe.x(0.66f, 0f), lobe.y(0.66f, 0f), zz, 0.045f, 0xFFFF8AD8.toInt(), 0.66f, 0f, 33, 0f)
                    Shapes.diamond(glow, lobe.seg, lobe.x(0.66f, 0f), lobe.y(0.66f, 0f), zz + s * 0.0005f, 0.022f, 0xFF8AF0FF.toInt(), 0.66f, 0f, 33, 0.5f)
                }
            }
        }

        val rnd = Random(61L)
        repeat(22) {
            val lobe = lobes[rnd.nextInt(2)]
            val s = 0.3f + 0.65f * rnd.nextFloat()
            val side = (rnd.nextFloat() - 0.5f) * 1.6f * lobe.width(s)
            sparks += Spark(lobe.seg, lobe.x(s, side), lobe.y(s, side), 0f, rnd.nextFloat())
        }
    }

    /** Ripples out from the root, linear in u so the veins ride the membrane. */
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        p.z += u * 0.03f * sin(t * 5.5f + seed * 3f)
    }

    private fun hue(u: Float, v: Float, seed: Float, t: Float) = Paint.hue(0.8f + 0.12f * sin(t * 0.5f + u * 2.4f) + v * 0.14f + seed * 0.1f)

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        30 -> Colors.rgb(color, Colors.hsv(hue(u, v, seed, t), 0.35f + 0.15f * v, 1f))
        31 -> Colors.alpha(Colors.hsv(hue(u, v, seed, t) + 0.05f, 0.5f, 1f), 0.5f + 0.2f * sin(t * 3f + u * 8f))
        32 -> Colors.alpha(Colors.hsv(hue(u, v, seed, t), 0.3f, 1f), 0.28f)
        else -> Colors.alpha(color, 0.5f + 0.4f * sin(t * 2.2f + seed * 3f))
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.35f + s.seed)
        val glow = sin(PI_F * life) * (0.6f + 0.4f * sin(t * 9f + s.seed * 50f))
        out.x = s.x + 0.04f * sin(t * 1.3f + s.seed * 19f) * life
        out.y = s.y - life * 0.7f
        out.z = s.z + 0.03f * sin(t * 1.7f + s.seed * 7f)
        out.size = 0.018f * glow
        out.color = Colors.alpha(Colors.hsv(Paint.hue(0.8f + s.seed * 0.3f), 0.45f, 1f), glow)
        return true
    }
}
