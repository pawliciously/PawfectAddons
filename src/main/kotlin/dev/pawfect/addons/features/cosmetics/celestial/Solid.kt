package dev.pawfect.addons.features.cosmetics.celestial

import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object Solid {
    private val LIGHT = Vector3f(0.35f, 0.75f, -0.55f).normalize()
    private const val TAU = Motion.TAU

    fun lit(color: Int, n: Vector3f, ambient: Float = 0.5f, gloss: Float = 0f): Int {
        val d = max(0f, n.dot(LIGHT))
        val k = ambient + (1f - ambient) * d
        val spec = if (gloss > 0f) gloss * d.pow(14f) * 255f else 0f
        fun ch(shift: Int) = (((color shr shift) and 255) * k + spec).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    class Frames(val p: Array<Vector3f>, val n: Array<Vector3f>, val b: Array<Vector3f>, val r: FloatArray) {
        val size get() = p.size

        fun s(i: Int) = i / (size - 1f)

        fun normal(i: Int, a: Float, out: Vector3f = Vector3f()): Vector3f =
            out.set(n[i]).mul(cos(a)).add(b[i].x * sin(a), b[i].y * sin(a), b[i].z * sin(a))

        fun point(i: Int, a: Float, scale: Float = 1f, out: Vector3f = Vector3f()): Vector3f =
            normal(i, a, out).mul(r[i] * scale).add(p[i])

        fun across(i: Int, a: Float, out: Vector3f = Vector3f()): Vector3f =
            out.set(n[i]).mul(-sin(a)).add(b[i].x * cos(a), b[i].y * cos(a), b[i].z * cos(a))
    }

    fun bezier(a: Vector3f, b: Vector3f, c: Vector3f, d: Vector3f): (Float) -> Vector3f = { s ->
        val q = 1f - s
        Vector3f(a).mul(q * q * q)
            .add(Vector3f(b).mul(3f * q * q * s))
            .add(Vector3f(c).mul(3f * q * s * s))
            .add(Vector3f(d).mul(s * s * s))
    }

    fun frames(rings: Int, radius: (Float) -> Float, up: Vector3f? = null, path: (Float) -> Vector3f): Frames {
        val p = Array(rings + 1) { path(it / rings.toFloat()) }
        val t = Array(rings + 1) {
            val a = p[(it - 1).coerceAtLeast(0)]
            val c = p[(it + 1).coerceAtMost(rings)]
            Vector3f(c).sub(a).normalize()
        }
        val n = arrayOfNulls<Vector3f>(rings + 1)
        for (i in 0..rings) {
            val seed = up ?: if (i > 0) n[i - 1]!! else if (kotlin.math.abs(t[0].y) < 0.9f) Vector3f(0f, 1f, 0f) else Vector3f(1f, 0f, 0f)
            val d = Vector3f(t[i]).mul(seed.dot(t[i]))
            n[i] = Vector3f(seed).sub(d).normalize()
        }
        @Suppress("UNCHECKED_CAST")
        val ns = n as Array<Vector3f>
        val b = Array(rings + 1) { Vector3f(t[it]).cross(ns[it]).normalize() }
        return Frames(p, ns, b, FloatArray(rings + 1) { radius(it / rings.toFloat()) })
    }

    fun tube(
        b: MeshBuilder, seg: Int, f: Frames, sides: Int, kind: Int, seed: Float,
        from: Int = 0, to: Int = f.size - 1, shell: Float = 1f, twist: Float = 0f,
        light: Boolean = true, flat: Boolean = false, ambient: Float = 0.5f, gloss: Float = 0f,
        color: (s: Float, a: Float) -> Int,
    ) {
        val n = Vector3f()
        for (i in from until to) {
            for (j in 0 until sides) {
                val ring = intArrayOf(i, i + 1, i + 1, i)
                val step = intArrayOf(j, j, j + 1, j + 1)
                val verts = Array(4) { k ->
                    val s = f.s(ring[k])
                    val a = step[k] / sides.toFloat() * TAU + twist * s
                    val pos = f.point(ring[k], a, shell)
                    var c = color(s, step[k] / sides.toFloat())
                    if (light) {
                        if (flat) {
                            val mid = f.s(i) + 0.5f / (f.size - 1)
                            f.normal(i, (j + 0.5f) / sides * TAU + twist * mid, n)
                        } else {
                            f.normal(ring[k], a, n)
                        }
                        c = lit(c, n, ambient, gloss)
                    }
                    Vtx(seg, pos.x, pos.y, pos.z, c, step[k] / sides.toFloat(), s, kind, seed)
                }
                b.quad(verts[0], verts[1], verts[2], verts[3])
            }
        }
    }

    fun surfaceLine(
        b: MeshBuilder, seg: Int, f: Frames, from: Int, to: Int, angle: (Float) -> Float, width: Float,
        kind: Int, seed: Float, shell: Float = 1.03f, color: (Float) -> Int,
    ) {
        val pa = Vector3f()
        val pb = Vector3f()
        val wa = Vector3f()
        val wb = Vector3f()
        for (i in from until to) {
            val s0 = f.s(i)
            val s1 = f.s(i + 1)
            val a0 = angle(s0)
            val a1 = angle(s1)
            f.point(i, a0, shell, pa)
            f.point(i + 1, a1, shell, pb)
            f.across(i, a0, wa).mul(width * 0.5f)
            f.across(i + 1, a1, wb).mul(width * 0.5f)
            val u0 = frac(a0 / TAU)
            val u1 = frac(a1 / TAU)
            b.quad(
                Vtx(seg, pa.x + wa.x, pa.y + wa.y, pa.z + wa.z, color(s0), u0, s0, kind, seed),
                Vtx(seg, pb.x + wb.x, pb.y + wb.y, pb.z + wb.z, color(s1), u1, s1, kind, seed),
                Vtx(seg, pb.x - wb.x, pb.y - wb.y, pb.z - wb.z, color(s1), u1, s1, kind, seed),
                Vtx(seg, pa.x - wa.x, pa.y - wa.y, pa.z - wa.z, color(s0), u0, s0, kind, seed),
            )
        }
    }

    fun band(b: MeshBuilder, seg: Int, r0: Float, r1: Float, c0: Int, c1: Int, steps: Int, kind: Int, seed: Float, y: Float = 0f) {
        for (i in 0 until steps) {
            val a0 = i / steps.toFloat() * TAU
            val a1 = (i + 1) / steps.toFloat() * TAU
            val u0 = i / steps.toFloat()
            val u1 = (i + 1) / steps.toFloat()
            b.quad(
                Vtx(seg, cos(a0) * r0, y, sin(a0) * r0, c0, u0, 0f, kind, seed),
                Vtx(seg, cos(a0) * r1, y, sin(a0) * r1, c1, u0, 1f, kind, seed),
                Vtx(seg, cos(a1) * r1, y, sin(a1) * r1, c1, u1, 1f, kind, seed),
                Vtx(seg, cos(a1) * r0, y, sin(a1) * r0, c0, u1, 0f, kind, seed),
            )
        }
    }

    fun ray(b: MeshBuilder, seg: Int, a: Float, r0: Float, r1: Float, w0: Float, w1: Float, c0: Int, c1: Int, kind: Int, seed: Float, y: Float = 0f) {
        val dx = cos(a)
        val dz = sin(a)
        val u = frac(a / TAU)
        b.quad(
            Vtx(seg, dx * r0 - dz * w0, y, dz * r0 + dx * w0, c0, u, 0f, kind, seed),
            Vtx(seg, dx * r1 - dz * w1, y, dz * r1 + dx * w1, c1, u, 1f, kind, seed),
            Vtx(seg, dx * r1 + dz * w1, y, dz * r1 - dx * w1, c1, u, 1f, kind, seed),
            Vtx(seg, dx * r0 + dz * w0, y, dz * r0 - dx * w0, c0, u, 0f, kind, seed),
        )
    }

    fun gem(b: MeshBuilder, seg: Int, c: Vector3f, height: Float, width: Float, color: Int, kind: Int, seed: Float, u: Float, light: Boolean = true, turn: Float = 0f) {
        val top = Vector3f(c.x, c.y + height, c.z)
        val bottom = Vector3f(c.x, c.y - height * 0.8f, c.z)
        val ring = Array(4) {
            val a = it / 4f * TAU + turn
            Vector3f(c.x + cos(a) * width, c.y, c.z + sin(a) * width)
        }
        val n = Vector3f()
        for (k in 0 until 4) {
            val a = ring[k]
            val d = ring[(k + 1) % 4]
            for ((tip, v) in listOf(top to 1f, bottom to 0f)) {
                n.set(a).sub(tip).cross(Vector3f(d).sub(tip)).normalize()
                if (n.dot(a.x - c.x + d.x - c.x, tip.y - c.y, a.z - c.z + d.z - c.z) < 0f) n.negate()
                val k0 = if (light) lit(color, n, 0.55f, 0.6f) else color
                b.quad(
                    Vtx(seg, a.x, a.y, a.z, k0, u, 0.5f, kind, seed),
                    Vtx(seg, d.x, d.y, d.z, k0, u, 0.5f, kind, seed),
                    Vtx(seg, tip.x, tip.y, tip.z, k0, u, v, kind, seed),
                    Vtx(seg, tip.x, tip.y, tip.z, k0, u, v, kind, seed),
                )
            }
        }
    }

    fun glint(b: MeshBuilder, seg: Int, x: Float, y: Float, z: Float, r: Float, color: Int, u: Float, v: Float, kind: Int, seed: Float) {
        b.quad(Vtx(seg, x, y + r, z, color, u, v, kind, seed), Vtx(seg, x + r, y, z, color, u, v, kind, seed), Vtx(seg, x, y - r, z, color, u, v, kind, seed), Vtx(seg, x - r, y, z, color, u, v, kind, seed))
        b.quad(Vtx(seg, x, y + r, z, color, u, v, kind, seed), Vtx(seg, x, y, z + r, color, u, v, kind, seed), Vtx(seg, x, y - r, z, color, u, v, kind, seed), Vtx(seg, x, y, z - r, color, u, v, kind, seed))
        b.quad(Vtx(seg, x + r, y, z, color, u, v, kind, seed), Vtx(seg, x, y, z + r, color, u, v, kind, seed), Vtx(seg, x - r, y, z, color, u, v, kind, seed), Vtx(seg, x, y, z - r, color, u, v, kind, seed))
    }

    fun flame(b: MeshBuilder, seg: Int, x: Float, y: Float, z: Float, height: Float, width: Float, facing: Float, c0: Int, c1: Int, kind: Int, seed: Float, u: Float) {
        val profile = Shapes.FLAME
        for (a in floatArrayOf(facing, facing + TAU / 4f)) {
            val wx = cos(a)
            val wz = sin(a)
            for (i in 0 until profile.size / 2 - 1) {
                val p0 = profile[i * 2]
                val p1 = profile[i * 2 + 2]
                val w0 = profile[i * 2 + 1] * width * 0.5f
                val w1 = profile[i * 2 + 3] * width * 0.5f
                val k0 = Colors.lerp(c0, c1, p0)
                val k1 = Colors.lerp(c0, c1, p1)
                b.quad(
                    Vtx(seg, x + wx * w0, y + height * p0, z + wz * w0, k0, u, p0, kind, seed),
                    Vtx(seg, x + wx * w1, y + height * p1, z + wz * w1, k1, u, p1, kind, seed),
                    Vtx(seg, x - wx * w1, y + height * p1, z - wz * w1, k1, u, p1, kind, seed),
                    Vtx(seg, x - wx * w0, y + height * p0, z - wz * w0, k0, u, p0, kind, seed),
                )
            }
        }
    }
}
