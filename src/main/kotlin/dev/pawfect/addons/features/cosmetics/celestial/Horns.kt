package dev.pawfect.addons.features.cosmetics.celestial

import net.minecraft.client.renderer.entity.state.AvatarRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private const val TAU = Motion.TAU
private const val PI_F = PI.toFloat()

/**
 * A mirrored pair of horns rooted on the head at [root] (model space, right side). Inside a horn
 * x points out from the head, y up and z back.
 */
abstract class HornStyle(id: String, private val root: Vector3f) : Piece(id, Anchor.HEAD, 2) {

    override fun matrices(state: AvatarRenderState, motion: Motion, t: Float, size: Float): Array<Matrix4f> = Motion.mirrored { side ->
        val m = Matrix4f().translate(side * root.x, root.y, root.z).scale(side * size, -size, size)
        Triple(m, m, m)
    }

    protected fun v(x: Float, y: Float, z: Float) = Vector3f(x, y, z)

    /** Gold bands around a horn between rings [from] and [to], with a glint running round them. */
    protected fun goldBand(fill: MeshBuilder, glow: MeshBuilder, f: Solid.Frames, from: Int, to: Int) {
        Solid.tube(fill, 0, f, 10, 0, 0f, from, to, shell = 1.12f, ambient = 0.5f, gloss = 1f) { _, _ -> 0xFFFFC85A.toInt() }
        Solid.tube(glow, 0, f, 10, 1, 0f, from, to, shell = 1.18f, light = false) { _, _ -> 0xFFFFE6A0.toInt() }
    }

    protected fun bandGlint(u: Float, t: Float): Float {
        val d = (frac(u + t * 0.35f) - 0.5f) * 6f
        return exp(-d * d)
    }

    /** A pulse climbing the horn from root to tip. */
    protected fun climb(v: Float, t: Float) = max(0f, sin((v * 1.4f - t * 0.55f) * TAU)).pow(4f)
}

object Horns {
    val ALL: List<HornStyle> = listOf(Ram, Demon, Elder, Oni, Crystal, VoidHorns)
}

/** Ivory ram horns curling round the ears, ridged all the way, bound in gold. */
object Ram : HornStyle("ram", Vector3f(0.22f, -0.42f, 0.02f)) {
    override val baseHue = 0.11f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val f = Solid.frames(56, { s -> 0.068f * (1f - s).pow(0.85f) + 0.01f }) { s ->
            val phi = s * 1.75f * PI_F
            val r = 0.15f * (1f - 0.45f * s)
            v(0.02f + 0.13f * s, r * sin(phi), 0.15f - r * cos(phi))
        }
        Solid.tube(fill, 0, f, 10, 0, 0f, gloss = 0.25f) { s, _ ->
            val base = Colors.lerp(0xFFE2D4B4.toInt(), 0xFF7A644A.toInt(), s.pow(1.2f))
            Colors.scale(base, 0.74f + 0.26f * abs(sin(s * PI_F * 22f)).pow(0.4f))
        }
        goldBand(fill, glow, f, 5, 7)
        goldBand(fill, glow, f, 11, 12)
        goldBand(fill, glow, f, 52, 56)
        val tip = f.p[55]
        sparks += Spark(0, tip.x, tip.y, tip.z, 0.2f)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int =
        if (kind == 1) Colors.alpha(color, 0.06f + 0.6f * bandGlint(u, t)) else color

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val tw = max(0f, sin(t * 1.3f)).pow(8f)
        out.x = s.x
        out.y = s.y
        out.z = s.z
        out.size = 0.03f * tw
        out.color = Colors.alpha(0xFFFFF0C0.toInt(), tw)
        return true
    }
}

/** Glossy black horns sweeping back, lava cracks pulsing up them to a burning tip. */
object Demon : HornStyle("demon", Vector3f(0.14f, -0.47f, -0.09f)) {
    override val baseHue = 0.06f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val path = Solid.bezier(v(0f, 0f, 0f), v(0.05f, 0.16f, 0f), v(0.17f, 0.3f, 0.12f), v(0.12f, 0.44f, 0.34f))
        val f = Solid.frames(40, { s -> 0.056f * (1f - s).pow(0.9f) + 0.003f }, path = path)
        Solid.tube(fill, 0, f, 10, 0, 0f, ambient = 0.4f, gloss = 0.8f) { s, _ -> Colors.lerp(0xFF32101A.toInt(), 0xFF0A0508.toInt(), s) }
        val rnd = Random(81L)
        repeat(5) { k ->
            val start = k / 5f * TAU + rnd.nextFloat()
            Solid.surfaceLine(glow, 0, f, 2, 34, { s -> start + 0.5f * sin(s * 9f + k * 2f) }, 0.009f, 20, k / 5f) { 0xFFFF6A1A.toInt() }
        }
        Solid.tube(glow, 0, f, 10, 21, 0f, 33, 40, shell = 1.18f, light = false) { _, _ -> 0xFFFF4010.toInt() }
        val tip = f.p[39]
        repeat(8) { sparks += Spark(0, tip.x, tip.y, tip.z, rnd.nextFloat()) }
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        20 -> Colors.alpha(color, 0.3f + 0.7f * climb(v + seed * 0.3f, t))
        21 -> Colors.alpha(color, ((v - 0.83f) / 0.17f).coerceIn(0f, 1f) * (0.4f + 0.3f * sin(t * 3f)))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.6f + s.seed)
        out.x = s.x + 0.03f * sin(s.seed * 40f + t * 2f) * life
        out.y = s.y + life * 0.28f
        out.z = s.z + 0.03f * cos(s.seed * 31f + t * 1.7f) * life
        out.size = 0.014f * (1f - life) + 0.002f
        out.color = Colors.alpha(Colors.lerp(0xFFFFB040.toInt(), 0xFFFF2000.toInt(), life), 0.9f * (1f - life))
        return true
    }
}

/** Pale antlers with glowing moss, little blossoms on every tine and fireflies drifting round them. */
object Elder : HornStyle("elder", Vector3f(0.12f, -0.49f, 0f)) {
    override val baseHue = 0.45f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val bark = { s: Float, _: Float -> Colors.lerp(0xFFEDE2CC.toInt(), 0xFFBFAE90.toInt(), s) }
        val mainPath = Solid.bezier(v(0f, 0f, 0f), v(0.05f, 0.12f, 0.03f), v(0.2f, 0.24f, 0.08f), v(0.3f, 0.42f, 0.02f))
        val main = Solid.frames(30, { s -> 0.028f - 0.018f * s }, path = mainPath)
        Solid.tube(fill, 0, main, 8, 0, 0f, gloss = 0.2f, color = bark)
        Solid.tube(glow, 0, main, 8, 30, 0.1f, 1, 12, shell = 1.15f, light = false) { _, _ -> 0xFF6CFFC8.toInt() }

        val tips = arrayListOf(main.p[30])
        val tines = listOf(0.12f to v(-0.02f, 0.06f, -0.12f), 0.3f to v(0f, 0.16f, -0.06f), 0.55f to v(0.02f, 0.17f, -0.03f), 0.75f to v(0.07f, 0.12f, 0.06f))
        for ((i, tine) in tines.withIndex()) {
            val (at, dir) = tine
            val start = mainPath(at)
            val bend = v(0f, 0.03f, 0f)
            val path = Solid.bezier(start, Vector3f(dir).mul(0.3f).add(start).add(bend), Vector3f(dir).mul(0.75f).add(start).add(bend), Vector3f(dir).add(start))
            val r0 = 0.026f - 0.018f * at
            val tf = Solid.frames(10, { s -> r0 * 0.7f * (1f - s) + 0.004f }, path = path)
            Solid.tube(fill, 0, tf, 6, 0, 0f, gloss = 0.2f, color = bark)
            if (i == 1) Solid.tube(glow, 0, tf, 6, 30, 0.6f, 0, 4, shell = 1.2f, light = false) { _, _ -> 0xFF6CFFC8.toInt() }
            tips += tf.p[10]
        }

        val rnd = Random(83L)
        for (tip in tips) {
            repeat(5) {
                val a = it / 5f * TAU
                Solid.glint(glow, 0, tip.x + cos(a) * 0.016f, tip.y + 0.012f, tip.z + sin(a) * 0.016f, 0.012f, 0xFFFFB8E8.toInt(), 0f, 0f, 31, rnd.nextFloat())
            }
            Solid.glint(glow, 0, tip.x, tip.y + 0.014f, tip.z, 0.009f, 0xFFFFF4B0.toInt(), 0f, 0f, 31, rnd.nextFloat())
        }
        repeat(14) {
            val p = mainPath(rnd.nextFloat())
            sparks += Spark(0, p.x, p.y + 0.04f, p.z, rnd.nextFloat())
        }
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        30 -> Colors.alpha(color, 0.1f + 0.2f * (0.5f + 0.5f * sin(u * TAU * 2f + v * 14f + t * 0.8f + seed * 9f)))
        31 -> Colors.alpha(color, 0.6f + 0.3f * sin(t * 2f + seed * 10f))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val blink = max(0f, sin(t * 2f + s.seed * 30f)).pow(2f)
        out.x = s.x + 0.06f * sin(t * 0.7f + s.seed * 20f)
        out.y = s.y + 0.04f * sin(t * 1.1f + s.seed * 13f)
        out.z = s.z + 0.06f * cos(t * 0.6f + s.seed * 7f)
        out.size = 0.012f * blink
        out.color = Colors.alpha(0xFFD8FF70.toInt(), blink)
        return true
    }
}

/** Short crimson oni horns fading to bone at the tip, gold rings at the base and heat round them. */
object Oni : HornStyle("oni", Vector3f(0.11f, -0.5f, -0.13f)) {
    override val baseHue = 0.97f

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val path = Solid.bezier(v(0f, 0f, 0f), v(0.01f, 0.1f, -0.03f), v(0.04f, 0.2f, -0.05f), v(0.08f, 0.28f, -0.01f))
        val f = Solid.frames(30, { s -> 0.046f * (1f - s).pow(0.75f) + 0.003f }, path = path)
        Solid.tube(fill, 0, f, 10, 0, 0f, ambient = 0.45f, gloss = 0.9f) { s, _ ->
            Colors.lerp(0xFFB8142A.toInt(), 0xFFF6ECD8.toInt(), ((s - 0.5f) / 0.35f).coerceIn(0f, 1f))
        }
        goldBand(fill, glow, f, 3, 4)
        goldBand(fill, glow, f, 6, 7)
        Solid.tube(glow, 0, f, 10, 40, 0f, shell = 1.4f, light = false) { _, _ -> 0xFFFF2040.toInt() }
        val rnd = Random(84L)
        repeat(8) { sparks += Spark(0, 0f, 0f, 0f, rnd.nextFloat()) }
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        1 -> Colors.alpha(color, 0.06f + 0.6f * bandGlint(u, t))
        40 -> Colors.alpha(color, 0.05f + 0.03f * sin(t * 1.8f))
        else -> color
    }

    /** Sparks spiral up around the horn. */
    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val life = frac(t * 0.45f + s.seed)
        val a = s.seed * TAU + life * 6f
        val r = 0.05f * (1f - life * 0.5f)
        out.x = 0.04f * life + cos(a) * r
        out.y = life * 0.36f
        out.z = -0.02f * life + sin(a) * r
        out.size = 0.01f * sin(PI_F * life) + 0.002f
        out.color = Colors.alpha(Colors.lerp(0xFFFFD060.toInt(), 0xFFFF2040.toInt(), life), 0.9f * sin(PI_F * life))
        return true
    }
}

/** Cut crystal horns: flat facets in shifting colour, lit edges and gems floating off them. */
object Crystal : HornStyle("crystal", Vector3f(0.13f, -0.48f, -0.07f)) {
    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val path = Solid.bezier(v(0f, 0f, 0f), v(0.03f, 0.15f, 0.02f), v(0.08f, 0.3f, 0.08f), v(0.1f, 0.46f, 0.2f))
        val f = Solid.frames(8, { s -> 0.052f * (1f - s) + 0.001f }, path = path)
        Solid.tube(fill, 0, f, 5, 50, 0f, flat = true, ambient = 0.5f, gloss = 0.8f) { _, _ -> 0xB4FFFFFF.toInt() }
        for (j in 0 until 5) {
            Solid.surfaceLine(glow, 0, f, 0, 8, { j / 5f * TAU }, 0.005f, 51, j / 5f, shell = 1.02f) { -1 }
        }
        Solid.tube(glow, 0, f, 5, 52, 0f, shell = 1.06f, light = false) { _, _ -> -1 }
        val rnd = Random(85L)
        for (at in floatArrayOf(0.4f, 0.7f, 0.95f)) {
            val p = path(at).add(0.06f, 0.02f, 0.03f * rnd.nextFloat())
            Solid.gem(fill, 0, p, 0.022f, 0.01f, 0xC0FFFFFF.toInt(), 53, rnd.nextFloat(), at)
        }
        val tip = path(1f)
        repeat(8) { sparks += Spark(0, tip.x + (rnd.nextFloat() - 0.5f) * 0.14f, tip.y + (rnd.nextFloat() - 0.6f) * 0.14f, tip.z + (rnd.nextFloat() - 0.5f) * 0.14f, rnd.nextFloat()) }
    }

    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        if (kind == 53) p.y += 0.012f * sin(t * 2f + seed * TAU)
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int {
        val h = Paint.hue(0.55f + v * 0.4f + t * 0.07f)
        return when (kind) {
            50, 53 -> Colors.rgb(color, Colors.hsv(h, 0.4f, (color and 255) / 255f))
            51 -> Colors.alpha(Colors.hsv(h + 0.1f, 0.5f, 1f), 0.55f)
            52 -> {
                val d = (frac(v - t * 0.4f) - 0.5f) * 6f
                Colors.alpha(Colors.hsv(h + 0.05f, 0.25f, 1f), 0.04f + 0.5f * exp(-d * d))
            }
            else -> color
        }
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val tw = max(0f, sin(t * 2.1f + s.seed * 50f)).pow(3f)
        out.x = s.x
        out.y = s.y
        out.z = s.z
        out.size = 0.014f * tw
        out.color = Colors.alpha(Colors.hsv(Paint.hue(0.55f + s.seed * 0.4f + t * 0.07f), 0.35f, 1f), 0.9f * tw)
        return true
    }
}

/** Twisted obsidian horns, violet veins spiralling up them to a tip that motes circle. */
object VoidHorns : HornStyle("void", Vector3f(0.13f, -0.48f, -0.04f)) {
    override val baseHue = 0.76f

    private const val TWIST = 4f * PI_F
    private const val VIOLET = 0xFFB048FF.toInt()

    override fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>) {
        val path = Solid.bezier(v(0f, 0f, 0f), v(0.04f, 0.18f, 0.05f), v(0.07f, 0.34f, 0.15f), v(0.03f, 0.5f, 0.3f))
        val f = Solid.frames(48, { s -> 0.05f * (1f - s).pow(0.9f) + 0.003f }, path = path)
        Solid.tube(fill, 0, f, 6, 0, 0f, twist = TWIST, flat = true, ambient = 0.35f, gloss = 0.9f) { s, _ ->
            Colors.lerp(0xFF221830.toInt(), 0xFF0C0814.toInt(), s)
        }
        for (k in 0 until 2) {
            val start = k * PI_F + 0.5f / 6f * TAU
            Solid.surfaceLine(glow, 0, f, 0, 44, { s -> start + TWIST * s }, 0.008f, 60, k * 0.5f) { VIOLET }
        }
        Solid.tube(glow, 0, f, 6, 61, 0f, 40, 48, shell = 1.2f, twist = TWIST, light = false) { _, _ -> VIOLET }
        val tip = path(1f)
        val rnd = Random(86L)
        repeat(10) { sparks += Spark(0, tip.x, tip.y, tip.z, rnd.nextFloat()) }
    }

    override fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = when (kind) {
        60 -> Colors.alpha(color, 0.2f + 0.8f * climb(v + seed * 0.5f, t))
        61 -> Colors.alpha(color, ((v - 0.83f) / 0.17f).coerceIn(0f, 1f) * (0.45f + 0.25f * sin(t * 2.5f)))
        else -> color
    }

    override fun spark(s: Spark, t: Float, out: SparkOut): Boolean {
        val a = s.seed * TAU + t * (1.2f + s.seed)
        val r = 0.05f + 0.02f * sin(t * 1.7f + s.seed * 20f)
        out.x = s.x + cos(a) * r
        out.y = s.y + 0.03f * sin(t * 1.3f + s.seed * 11f)
        out.z = s.z + sin(a) * r
        out.size = 0.01f + 0.004f * sin(t * 5f + s.seed * 30f)
        out.color = Colors.alpha(VIOLET, 0.85f)
        return true
    }
}
