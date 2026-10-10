package dev.pawfect.addons.features.cosmetics.celestial

import net.minecraft.client.renderer.entity.state.AvatarRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private const val TAU = Motion.TAU
private const val PI_F = PI.toFloat()

/** How far round the halo radius sits from the head's centre line. */
private const val R = 0.21f

/**
 * A halo floating over the head, or standing behind it when [behind]. Segment 0 holds still,
 * segments 1 and 2 turn so rings and lights can drift against each other.
 */
abstract class HaloStyle(id: String, private val behind: Boolean = false) : Piece(id, Anchor.HEAD, 1) {

    override fun matrices(state: AvatarRenderState, motion: Motion, t: Float, size: Float): Array<Matrix4f> {
        val bob = 0.012f * sin(t * 1.6f)
        val base = if (behind) {
            Matrix4f().translate(0f, -0.27f - bob, 0.36f).scale(size, -size, size).rotateX(-90f * DEG)
        } else {
            Matrix4f().translate(0f, -0.68f - bob, 0.03f).scale(size, -size, size).rotateX(-12f * DEG)
        }
        return arrayOf(base, orbit(Matrix4f(base), t, 1), orbit(Matrix4f(base), t, 2))
    }

    protected open fun orbit(m: Matrix4f, t: Float, seg: Int): Matrix4f =
        if (seg == 1) m.rotateY(t * 0.35f) else m.rotateY(-t * 0.5f)

    protected fun ring(radius: Float, thickness: Float, rings: Int = 64): Solid.Frames =
        Solid.frames(rings, { thickness }, Vector3f(0f, 1f, 0f)) { s -> Vector3f(cos(s * TAU) * radius, 0f, sin(s * TAU) * radius) }

    /** A bright spot running round the ring, at [pos] from 0 to 1 around it. */
    protected fun sweep(pos: Float, speed: Float, t: Float, width: Float = 8f): Float {
        val d = (frac(pos - t * speed) - 0.5f) * width
        return exp(-d * d)
    }

    protected fun twinkle(seed: Float, t: Float, speed: Float = 3f) = max(0f, sin(t * speed + seed * 40f)).pow(4f)

    protected fun breathe(color: Int, t: Float, depth: Float = 0.2f, speed: Float = 2f) =
        Colors.alpha(color, (color ushr 24) / 255f * (1f - depth + depth * sin(t * speed)))
}

object Halos {
    val ALL: List<HaloStyle> = listOf(Gilded, Eclipse, Astral, Radiant, Ember, Facet)
}

private const val GOLD = 0xFFFFCF5A.toInt()
private const val GOLD_DEEP = 0xFFC8861E.toInt()
private const val GOLD_LIGHT = 0xFFFFE6A0.toInt()

/** Polished gold with a bright glint running round it, a soft bloom and jewels turning inside. */
object Gilded : HaloStyle("gilded") {
    override val baseHue = 0.11f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val ring = ring(R, 0.018f)
        Solid.tube(fill, 0, ring, 8, 0, 0f, ambient = 0.55f, gloss = 0.9f) { s, _ -> Colors.lerp(GOLD, GOLD_DEEP, 0.5f + 0.5f * sin(s * TAU * 3f)) }
        Solid.tube(glow, 0, ring, 8, 1, 0f, shell = 1.25f, light = false) { _, _ -> GOLD_LIGHT }
        Solid.band(glow, 0, R - 0.06f, R, 0x00FFC860, 0x50FFC860, 64, 2, 0f)
        Solid.band(glow, 0, R, R + 0.1f, 0x50FFC860, 0x00FFC860, 64, 2, 0f)
        Solid.band(glow, 2, R * 0.8f, R * 0.8f + 0.006f, 0xFFFFF0C0.toInt(), 0xFFFFF0C0.toInt(), 64, 3, 0f)
        repeat(8) {
            val a = it / 8f * TAU
            Solid.glint(glow, 1, cos(a) * R, 0f, sin(a) * R, 0.022f, GOLD_LIGHT, it / 8f, 0f, 4, it / 8f)
        }
        val rnd = Random(71L)
        repeat(10) {
            val a = rnd.nextFloat() * TAU
            sparks += Spark(1, cos(a) * R, 0f, sin(a) * R, rnd.nextFloat())
        }
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        1 -> Colors.alpha(color, 0.05f + 0.6f * sweep(v, 0.22f, t))
        2 -> breathe(color, t)
        3 -> Colors.alpha(color, 0.35f + 0.15f * sin(t * 3f + u * TAU * 4f))
        4 -> Colors.alpha(color, 0.15f + 0.85f * twinkle(seed, t))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.3f + s.seed)
        val glow = sin(PI_F * life)
        out.x = s.x
        out.y = s.y - life * 0.35f
        out.z = s.z
        out.size = 0.016f * glow
        out.color = Colors.alpha(GOLD_LIGHT, 0.85f * glow)
        return true
    }
}

/** A black ring eclipsing its own light: a white corona, flickering rays and a diamond flare. */
object Eclipse : HaloStyle("eclipse") {
    override val baseHue = 0.1f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val ring = ring(R, 0.02f)
        Solid.tube(fill, 0, ring, 8, 0, 0f, ambient = 0.6f, gloss = 0.5f) { _, _ -> 0xFF0C0A10.toInt() }
        Solid.band(glow, 0, R + 0.012f, R + 0.085f, 0xC8FFF2D8.toInt(), 0x00FFE0B0, 64, 5, 0f)
        Solid.band(glow, 0, R - 0.026f, R - 0.017f, 0xFFBFE8FF.toInt(), 0xFFBFE8FF.toInt(), 64, 6, 0f)
        val rnd = Random(72L)
        repeat(30) {
            val a = (it + rnd.nextFloat() * 0.6f) / 30f * TAU
            Solid.ray(glow, 1, a, R + 0.015f, R + 0.06f + 0.08f * rnd.nextFloat(), 0.006f, 0f, 0xFFFFF0D0.toInt(), 0x00FFD090, 7, rnd.nextFloat())
        }
        repeat(6) {
            val a = (it + 0.5f) / 6f * TAU
            Solid.ray(glow, 2, a, R + 0.02f, R + 0.22f, 0.003f, 0f, 0x90FFFFFF.toInt(), 0x00FFFFFF, 7, rnd.nextFloat())
        }
        sparks += Spark(2, R, 0f, 0f, 0f)
        for (a in floatArrayOf(0.09f, -0.12f, 0.2f)) sparks += Spark(2, cos(a * TAU) * R, 0f, sin(a * TAU) * R, 0.3f + a)
        repeat(10) { sparks += Spark(0, 0f, 0f, 0f, rnd.nextFloat()) }
    }

    /** Rays lengthen and shrink, linear in v so each stays one straight spike. */
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        if (kind != 7) return
        val f = 1f + v * 0.25f * sin(t * 2.5f + seed * 20f)
        p.x *= f
        p.z *= f
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        5 -> breathe(color, t, 0.25f, 1.4f)
        6 -> Colors.alpha(color, 0.45f + 0.35f * sweep(u, 0.15f, t, 5f))
        7 -> Colors.alpha(color, (color ushr 24) / 255f * (0.5f + 0.5f * sin(t * 4f + seed * 30f)))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        if (s.seg == 2) {
            val big = s.seed == 0f
            out.x = s.x
            out.y = 0f
            out.z = s.z
            out.size = if (big) 0.05f + 0.012f * sin(t * 3f) else 0.014f * (0.6f + 0.4f * sin(t * 5f + s.seed * 20f))
            out.color = if (big) 0xFFFFFFFF.toInt() else 0xC0E8F6FF.toInt()
            return true
        }
        val life = frac(t * 0.25f + s.seed)
        val a = s.seed * TAU * 7f
        val r = R + 0.02f + life * 0.18f
        out.x = cos(a) * r
        out.y = 0.01f * sin(t + s.seed * 9f)
        out.z = sin(a) * r
        out.size = 0.012f * (1f - life)
        out.color = Colors.alpha(0xFFFFF0D0.toInt(), 0.8f * (1f - life))
        return true
    }
}

/** Rings of starlight crossing like a gyroscope, stars strung on them and planets riding round. */
object Astral : HaloStyle("astral") {
    override val baseHue = 0.6f

    override fun orbit(m: Matrix4f, t: Float, seg: Int): Matrix4f =
        if (seg == 1) m.rotateZ(28f * DEG).rotateY(t * 0.7f) else m.rotateZ(-34f * DEG).rotateX(14f * DEG).rotateY(-t * 0.5f)

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val cyan = 0xFF7FE8FF.toInt()
        val violet = 0xFFB49CFF.toInt()
        Solid.band(glow, 0, R - 0.004f, R + 0.004f, cyan, cyan, 72, 10, 0f)
        Solid.band(glow, 0, R - 0.06f, R, 0x009B5CFF, 0x309B5CFF, 72, 12, 0f)
        Solid.band(glow, 0, R, R + 0.06f, 0x309B5CFF, 0x009B5CFF, 72, 12, 0f)
        Solid.band(glow, 1, R * 0.9f - 0.0025f, R * 0.9f + 0.0025f, violet, violet, 72, 10, 0.3f)
        Solid.band(glow, 2, R * 1.1f - 0.0025f, R * 1.1f + 0.0025f, cyan, cyan, 72, 10, 0.6f)
        val rnd = Random(73L)
        for ((seg, radius, count) in listOf(Triple(1, R * 0.9f, 9), Triple(2, R * 1.1f, 7), Triple(0, R, 6))) {
            repeat(count) {
                val a = (it + rnd.nextFloat() * 0.5f) / count * TAU
                Solid.glint(glow, seg, cos(a) * radius, 0f, sin(a) * radius, 0.008f + 0.01f * rnd.nextFloat(), Colors.lerp(-1, cyan, rnd.nextFloat()), 0f, 0f, 11, rnd.nextFloat())
            }
        }
        sparks += Spark(1, R * 0.9f, 0f, 0f, 0.1f)
        sparks += Spark(1, R * 0.9f, 0f, 0f, 0.6f)
        sparks += Spark(2, R * 1.1f, 0f, 0f, 0.35f)
        sparks += Spark(2, R * 1.1f, 0f, 0f, 0.85f)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        10 -> Colors.alpha(color, 0.45f + 0.2f * sin(t * 1.5f + u * TAU * 2f + seed * 6f))
        11 -> Colors.alpha(color, 0.25f + 0.75f * twinkle(seed, t, 2.4f))
        12 -> breathe(color, t, 0.35f, 0.9f)
        else -> color
    }

    /** Planets run round their ring faster than it turns. x holds the ring's radius. */
    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val a = s.seed * TAU + t * 1.4f
        out.x = cos(a) * s.x
        out.y = 0f
        out.z = sin(a) * s.x
        out.size = 0.02f + 0.004f * sin(t * 4f + s.seed * 30f)
        out.color = if (s.seg == 1) 0xFFE0D4FF.toInt() else 0xFFC8F6FF.toInt()
        return true
    }
}

/** A sunburst standing behind the head: two sets of rays turning against each other round a gold ring. */
object Radiant : HaloStyle("radiant", behind = true) {
    override val baseHue = 0.11f

    override fun orbit(m: Matrix4f, t: Float, seg: Int): Matrix4f =
        if (seg == 1) m.rotateY(t * 0.12f) else m.rotateY(-t * 0.2f)

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val rnd = Random(74L)
        repeat(32) {
            val a = it / 32f * TAU
            val long = it % 2 == 0
            Solid.ray(glow, 1, a, R * 0.6f, R + if (long) 0.24f else 0.13f, if (long) 0.022f else 0.014f, 0f, 0xB0FFD27A.toInt(), 0x00FFC040, 20, rnd.nextFloat())
        }
        repeat(16) {
            val a = (it + 0.5f) / 16f * TAU
            Solid.ray(glow, 2, a, R * 0.65f, R + 0.3f, 0.006f, 0f, 0x90FFF8E0.toInt(), 0x00FFF8E0, 21, rnd.nextFloat())
        }
        val ring = ring(R * 0.62f, 0.012f)
        Solid.tube(fill, 0, ring, 8, 0, 0f, ambient = 0.55f, gloss = 0.9f) { s, _ -> Colors.lerp(GOLD, GOLD_DEEP, 0.5f + 0.5f * sin(s * TAU * 4f)) }
        Solid.tube(glow, 0, ring, 8, 22, 0f, shell = 1.3f, light = false) { _, _ -> GOLD_LIGHT }
        Solid.band(glow, 0, R * 0.25f, R * 0.62f, 0x00FFE8A0, 0x40FFE8A0, 48, 23, 0f)
        Solid.band(glow, 0, R * 0.62f, R * 0.95f, 0x40FFE8A0, 0x00FFE8A0, 48, 23, 0f)
        repeat(14) { sparks += Spark(1, 0f, 0f, 0f, rnd.nextFloat()) }
    }

    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        if (kind != 20) return
        val f = 1f + v * 0.06f * sin(t * 2f + seed * 12f)
        p.x *= f
        p.z *= f
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        20 -> Colors.alpha(color, (color ushr 24) / 255f * (0.7f + 0.3f * sin(t * 2f + seed * 12f)))
        21 -> Colors.alpha(color, (color ushr 24) / 255f * (0.5f + 0.5f * sin(t * 1.3f + seed * 9f)))
        22 -> Colors.alpha(color, 0.05f + 0.55f * sweep(v, 0.18f, t))
        23 -> breathe(color, t, 0.3f, 1.2f)
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.3f + s.seed)
        val a = s.seed * TAU * 5f
        val r = R * 0.6f + life * 0.35f
        out.x = cos(a) * r
        out.y = 0f
        out.z = sin(a) * r
        out.size = 0.016f * sin(PI_F * life)
        out.color = Colors.alpha(GOLD_LIGHT, 0.9f * sin(PI_F * life))
        return true
    }
}

/** A crown of fire: a smouldering ring with hot spots crawling round it, flames dancing on top. */
object Ember : HaloStyle("ember") {
    override val baseHue = 0.06f

    override fun orbit(m: Matrix4f, t: Float, seg: Int): Matrix4f = if (seg == 1) m.rotateY(t * 0.15f) else m

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val ring = ring(R, 0.016f)
        Solid.tube(fill, 1, ring, 8, 0, 0f, ambient = 0.5f, gloss = 0.3f) { _, _ -> 0xFF3A1408.toInt() }
        Solid.tube(glow, 1, ring, 8, 30, 0f, shell = 1.3f, light = false) { _, _ -> 0xFFFF6A10.toInt() }
        val rnd = Random(75L)
        repeat(14) {
            val a = it / 14f * TAU
            val h = 0.13f + 0.07f * rnd.nextFloat()
            val seed = rnd.nextFloat()
            val x = cos(a) * R
            val z = sin(a) * R
            Solid.flame(glow, 1, x, 0.004f, z, h, 0.06f, a + TAU / 4f, 0xFFFFD060.toInt(), 0xFFFF2800.toInt(), 31, seed, it / 14f)
            Solid.flame(glow, 1, x, 0.004f, z, h * 0.55f, 0.03f, a + TAU / 4f, 0xFFFFF4C0.toInt(), 0xFFFFA020.toInt(), 32, seed, it / 14f)
        }
        repeat(18) {
            val a = rnd.nextFloat() * TAU
            sparks += Spark(1, cos(a) * R, 0f, sin(a) * R, rnd.nextFloat())
        }
    }

    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        if (kind != 31 && kind != 32) return
        p.y += v * 0.035f * sin(t * 9f + seed * 30f)
        p.x += v * 0.008f * sin(t * 6f + seed * 11f)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        30 -> Colors.alpha(color, (0.15f + 0.85f * max(0f, sin(v * TAU * 7f + t * 1.3f)).pow(4f)) * (0.75f + 0.25f * sin(t * 7f + v * 40f)))
        31 -> Colors.alpha(color, (1f - v * 0.85f) * (0.55f + 0.45f * sin(t * 11f + seed * 19f)))
        32 -> Colors.alpha(color, (1f - v) * 0.8f)
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.5f + s.seed)
        out.x = s.x + 0.03f * sin(s.seed * 40f + t * 2f) * life
        out.y = s.y + life * 0.4f
        out.z = s.z + 0.03f * cos(s.seed * 31f + t * 1.6f) * life
        out.size = 0.016f * (1f - life).pow(0.7f) + 0.003f
        out.color = Colors.alpha(Colors.lerp(0xFFFFE070.toInt(), 0xFFFF2800.toInt(), life), 0.95f * (1f - life))
        return true
    }
}

/** Cut gems orbiting in two rings, catching colour as they turn and bob. */
object Facet : HaloStyle("prism") {
    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        for ((seg, radius, scale) in listOf(Triple(1, R, 1f), Triple(2, R * 0.72f, 0.55f))) {
            repeat(9) {
                val a = it / 9f * TAU
                val c = Vector3f(cos(a) * radius, 0f, sin(a) * radius)
                val seed = it / 9f + seg * 0.37f
                Solid.gem(fill, seg, c, 0.065f * scale, 0.026f * scale, 0xC0FFFFFF.toInt(), 40, seed, it / 9f, turn = a)
                Solid.gem(glow, seg, c, 0.076f * scale, 0.032f * scale, -1, 41, seed, it / 9f, light = false, turn = a)
            }
        }
        Solid.band(glow, 0, R - 0.003f, R + 0.003f, -1, -1, 72, 42, 0f)
        val rnd = Random(76L)
        repeat(14) {
            val a = rnd.nextFloat() * TAU
            val r = R * (0.75f + 0.5f * rnd.nextFloat())
            sparks += Spark(0, cos(a) * r, (rnd.nextFloat() - 0.5f) * 0.08f, sin(a) * r, rnd.nextFloat())
        }
    }

    /** Each gem bobs as one piece; its seed is the same on all of its corners. */
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        if (kind == 40 || kind == 41) p.y += 0.014f * sin(t * 2f + seed * TAU)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int {
        val h = Paint.hue(u + t * 0.08f)
        return when (kind) {
            40 -> Colors.rgb(color, Colors.hsv(h, 0.4f, (color and 255) / 255f))
            41 -> Colors.alpha(Colors.hsv(h + 0.1f, 0.3f, 1f), 0.06f + 0.5f * max(0f, sin(t * 2.4f + u * TAU * 2f)).pow(6f))
            else -> Colors.alpha(Colors.hsv(h, 0.45f, 1f), 0.35f)
        }
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val tw = max(0f, sin(t * 1.9f + s.seed * 50f)).pow(3f)
        out.x = s.x
        out.y = s.y
        out.z = s.z
        out.size = 0.018f * tw
        out.color = Colors.alpha(Colors.hsv(Paint.hue(s.seed + t * 0.08f), 0.35f, 1f), 0.9f * tw)
        return true
    }
}
