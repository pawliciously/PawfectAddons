package dev.pawfect.addons.features.cosmetics.celestial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** One vertex of a wing, in the space of one of the three wing segments. */
class Vtx(
    val seg: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val color: Int,
    val u: Float,
    val v: Float,
    val kind: Int,
    val seed: Float,
)

/** A wing's quads, flattened into arrays once so a frame only reads them. */
class Mesh(verts: List<Vtx>, val glow: Boolean) {
    val size = verts.size
    val seg = ByteArray(size) { verts[it].seg.toByte() }
    val x = FloatArray(size) { verts[it].x }
    val y = FloatArray(size) { verts[it].y }
    val z = FloatArray(size) { verts[it].z }
    val color = IntArray(size) { verts[it].color }
    val u = FloatArray(size) { verts[it].u }
    val v = FloatArray(size) { verts[it].v }
    val kind = ByteArray(size) { verts[it].kind.toByte() }
    val seed = FloatArray(size) { verts[it].seed }

    // Per frame scratch, filled once and drawn for both wings.
    val px = FloatArray(size)
    val py = FloatArray(size)
    val pz = FloatArray(size)
    val out = IntArray(size)
}

class MeshBuilder(private val glow: Boolean) {
    private val verts = ArrayList<Vtx>()

    fun quad(a: Vtx, b: Vtx, c: Vtx, d: Vtx) {
        verts += a
        verts += b
        verts += c
        verts += d
    }

    fun build() = Mesh(verts, glow)
}

/** A loose bit of light drawn as a small star, moved by its style every frame. */
class Spark(val seg: Int, val x: Float, val y: Float, val z: Float, val seed: Float)

class SparkOut {
    var x = 0f
    var y = 0f
    var z = 0f
    var size = 0f
    var color = 0
}

/** A feather or blade rooted on a segment, pointing at an angle in that segment's plane. */
class Feather(
    val row: Int,
    val seg: Int,
    val x: Float,
    val y: Float,
    angle: Float,
    val length: Float,
    val width: Float,
    val u: Float,
    val seed: Float,
) {
    private val dx = cos(angle * DEG)
    private val dy = sin(angle * DEG)

    fun px(along: Float, side: Float) = x + dx * length * along - dy * side
    fun py(along: Float, side: Float) = y + dy * length * along + dx * side
}

const val DEG = (PI / 180.0).toFloat()
val SIDES = floatArrayOf(1f, -1f)

object Shapes {
    /** Pairs of (position along, width) describing an outline from root to tip. */
    val FEATHER = floatArrayOf(0f, 0.22f, 0.45f, 1f, 0.82f, 0.78f, 1f, 0f)
    val SHARD = floatArrayOf(0f, 0.12f, 0.2f, 1f, 0.76f, 1f, 1f, 0f)
    val FLAME = floatArrayOf(0f, 0.3f, 0.35f, 1f, 0.72f, 0.55f, 1f, 0f)

    fun vtx(f: Feather, along: Float, side: Float, z: Float, color: Int, kind: Int) =
        Vtx(f.seg, f.px(along, side), f.py(along, side), z, color, f.u, along, kind, f.seed)

    /** Fills a feather's outline. [from] and [to] stretch it along the feather, for flames past the tip. */
    fun blade(
        b: MeshBuilder, f: Feather, profile: FloatArray, z: Float, c0: Int, c1: Int, kind: Int,
        from: Float = 0f, to: Float = 1f, widthMul: Float = 1f,
    ) {
        for (i in 0 until profile.size / 2 - 1) {
            val p0 = profile[i * 2]
            val p1 = profile[i * 2 + 2]
            val w0 = profile[i * 2 + 1] * f.width * 0.5f * widthMul
            val w1 = profile[i * 2 + 3] * f.width * 0.5f * widthMul
            val a0 = from + (to - from) * p0
            val a1 = from + (to - from) * p1
            val k0 = Colors.lerp(c0, c1, p0)
            val k1 = Colors.lerp(c0, c1, p1)
            b.quad(vtx(f, a0, w0, z, k0, kind), vtx(f, a1, w1, z, k1, kind), vtx(f, a1, -w1, z, k1, kind), vtx(f, a0, -w0, z, k0, kind))
        }
    }

    /** Thin lines tracing both edges of a feather's outline. */
    fun outline(b: MeshBuilder, f: Feather, profile: FloatArray, w: Float, z: Float, color: Int, kind: Int) {
        for (s in SIDES) {
            for (i in 0 until profile.size / 2 - 1) {
                val a0 = profile[i * 2]
                val a1 = profile[i * 2 + 2]
                val s0 = s * profile[i * 2 + 1] * f.width * 0.5f
                val s1 = s * profile[i * 2 + 3] * f.width * 0.5f
                line(b, f.seg, f.px(a0, s0), f.py(a0, s0), f.px(a1, s1), f.py(a1, s1), w, w, z, color, color, f.u, f.u, a0, a1, kind, f.seed)
            }
        }
    }

    /** The shaft down the middle of a feather, broken where the outline bends so it bends with it. */
    fun spine(b: MeshBuilder, f: Feather, profile: FloatArray, w: Float, z: Float, c0: Int, c1: Int, kind: Int, end: Float) {
        var last = 0f
        for (i in 1 until profile.size / 2) {
            val a = minOf(profile[i * 2], end)
            if (a <= last) break
            line(b, f.seg, f.px(last, 0f), f.py(last, 0f), f.px(a, 0f), f.py(a, 0f), w, w * 0.5f, z, Colors.lerp(c0, c1, last), Colors.lerp(c0, c1, a), f.u, f.u, last, a, kind, f.seed)
            last = a
        }
    }

    fun line(
        b: MeshBuilder, seg: Int, x0: Float, y0: Float, x1: Float, y1: Float, w0: Float, w1: Float, z: Float,
        c0: Int, c1: Int, u0: Float, u1: Float, v0: Float, v1: Float, kind: Int, seed: Float,
    ) {
        val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(1.0e-5f)
        val nx = -(y1 - y0) / len * 0.5f
        val ny = (x1 - x0) / len * 0.5f
        b.quad(
            Vtx(seg, x0 + nx * w0, y0 + ny * w0, z, c0, u0, v0, kind, seed),
            Vtx(seg, x1 + nx * w1, y1 + ny * w1, z, c1, u1, v1, kind, seed),
            Vtx(seg, x1 - nx * w1, y1 - ny * w1, z, c1, u1, v1, kind, seed),
            Vtx(seg, x0 - nx * w0, y0 - ny * w0, z, c0, u0, v0, kind, seed),
        )
    }

    fun diamond(b: MeshBuilder, seg: Int, x: Float, y: Float, z: Float, r: Float, color: Int, u: Float, v: Float, kind: Int, seed: Float) {
        b.quad(
            Vtx(seg, x, y + r, z, color, u, v, kind, seed),
            Vtx(seg, x + r, y, z, color, u, v, kind, seed),
            Vtx(seg, x, y - r, z, color, u, v, kind, seed),
            Vtx(seg, x - r, y, z, color, u, v, kind, seed),
        )
    }
}

/** Where the feathers of a bird-like wing sit, worked out from the wing's resting pose. */
object Layout {
    private val Z = floatArrayOf(0f, 0.005f, 0.009f, 0.014f, 0.019f)

    /** How far each row sits off the wing so rows never fight over the same depth. */
    fun z(row: Int) = Z[row]

    fun restAngles(rig: Rig) = floatArrayOf(rig.lift, rig.lift + rig.elbowLift, rig.lift + rig.elbowLift + rig.wristLift)

    /** Rows: 0 primaries, 1 secondaries, 2 tertials, 3 coverts, 4 small coverts on the leading edge. */
    fun feathered(rig: Rig, seed: Long, len: Float = 1f, width: Float = 1f): List<Feather> {
        val rnd = java.util.Random(seed)
        val rest = restAngles(rig)
        val lens = floatArrayOf(rig.l0, rig.l1, rig.l2)
        val start = floatArrayOf(0f, rig.l0, rig.l0 + rig.l1)
        val span = rig.l0 + rig.l1 + rig.l2
        val out = ArrayList<Feather>()

        fun add(row: Int, seg: Int, at: Float, y: Float, angle: Float, length: Float, w: Float) {
            val x = lens[seg] * at
            out += Feather(
                row, seg, x, y,
                angle - rest[seg] + (rnd.nextFloat() - 0.5f) * 4f,
                length * len * (0.96f + rnd.nextFloat() * 0.08f),
                w * width,
                (start[seg] + x) / span,
                rnd.nextFloat(),
            )
        }

        fun fan(f: Float) = -86f + 80f * f.pow(1.3f)

        repeat(9) { val f = it / 8f; add(0, 2, 0.06f + 0.94f * f, 0f, fan(f), 0.5f + 0.3f * f, 0.13f) }
        repeat(8) { val f = it / 7f; add(1, 1, 0.03f + 0.95f * f, 0f, -92f + 4f * f, 0.46f + 0.1f * f, 0.14f) }
        repeat(6) { val f = it / 5f; add(2, 0, 0.12f + 0.88f * f, 0f, -100f + 6f * f, 0.34f + 0.12f * f, 0.13f) }

        repeat(5) { val f = it / 4f; add(3, 0, 0.1f + 0.9f * f, 0.03f, -96f, 0.2f, 0.11f) }
        repeat(7) { val f = it / 6f; add(3, 1, 0.02f + 0.96f * f, 0.03f, -91f, 0.22f, 0.11f) }
        repeat(7) { val f = it / 6f; add(3, 2, 0.03f + 0.92f * f, 0.03f, fan(f), 0.2f + 0.06f * f, 0.1f) }

        repeat(6) { val f = it / 5f; add(4, 0, 0.05f + 0.95f * f, 0.055f, -95f, 0.11f, 0.085f) }
        repeat(8) { val f = it / 7f; add(4, 1, 0.02f + 0.96f * f, 0.055f, -90f, 0.11f, 0.085f) }
        repeat(7) { val f = it / 6f; add(4, 2, 0.02f + 0.9f * f, 0.055f, fan(f) * 0.9f, 0.1f, 0.08f) }
        return out
    }
}

object Colors {
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val ca = (a ushr shift) and 255
            val cb = (b ushr shift) and 255
            out = out or (((ca + (cb - ca) * k).toInt() and 255) shl shift)
        }
        return out
    }

    fun alpha(color: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (color and 0xFFFFFF)

    fun rgb(color: Int, rgb: Int): Int = (color and 0xFF000000.toInt()) or (rgb and 0xFFFFFF)

    fun scale(color: Int, k: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 255) * k).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun hsv(h: Float, s: Float, v: Float): Int {
        val hue = (h - kotlin.math.floor(h)) * 6f
        val i = hue.toInt()
        val f = hue - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255f).toInt() shl 16) or ((g * 255f).toInt() shl 8) or (b * 255f).toInt()
    }

    /**
     * Premultiplies for the one blend the wings use. Solid parts keep their alpha and darken in
     * low light; glowing parts add their light on top and cover nothing.
     */
    fun out(raw: Int, glow: Boolean, shade: Float): Int {
        val color = Paint.active?.let { (raw and 0xFF000000.toInt()) or it.apply(raw) } ?: raw
        val a = color ushr 24
        val k = if (glow) a / 255f else a / 255f * shade
        val r = (((color shr 16) and 255) * k).toInt()
        val g = (((color shr 8) and 255) * k).toInt()
        val b = ((color and 255) * k).toInt()
        return ((if (glow) 1 else a) shl 24) or (r shl 16) or (g shl 8) or b
    }
}

fun frac(x: Float) = x - kotlin.math.floor(x)
