package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.BlendFactor
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.features.cosmetics.celestial.Colors
import dev.pawfect.addons.features.cosmetics.celestial.Solid
import dev.pawfect.addons.mixin.RenderTypeInvoker
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object WorldFx {

    const val TAU = 6.2831855f

    private fun pipeline(name: String, writeDepth: Boolean): RenderPipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/$name"))
        .withColorTargetState(ColorTargetState(BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
        .withCull(false)
        .build()

    private fun type(name: String, writeDepth: Boolean): RenderType =
        RenderTypeInvoker.`pawfectaddons$create`("pawfect_$name", RenderSetup.builder(RenderPipelines.register(pipeline(name, writeDepth))).createRenderSetup())

    private val solid: RenderType by lazy { type("fx_solid", true) }

    private val light: RenderType by lazy { type("fx_light", false) }

    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, shade: Float, solids: (Fx) -> Unit, lights: (Fx) -> Unit) {
        collector.submitCustomGeometry(poseStack, solid) { pose, consumer -> solids(Fx(pose, consumer, shade)) }
        collector.order(1).submitCustomGeometry(poseStack, light) { pose, consumer -> lights(Fx(pose, consumer, shade)) }
    }

    fun shade(level: ClientLevel?, at: Vec3): Float {
        if (level == null) return 0.9f
        val light = level.getMaxLocalRawBrightness(BlockPos.containing(at))
        return 0.4f + 0.6f * light / 15f
    }

    fun hash(seed: Int, index: Int, channel: Int): Float {
        var value = seed * 374761393 + index * 668265263 + channel * 1013904223
        value = (value xor (value shr 13)) * 1274126177
        value = value xor (value shr 16)
        return ((value ushr 8) and 0xFFFF) / 65535f
    }

    fun hot(color: Int, k: Float): Int = Colors.lerp(color or 0xFF000000.toInt(), -1, k)
}

class Fx(private val pose: PoseStack.Pose, private val consumer: VertexConsumer, private val shade: Float) {

    private val right = Vector3f()
    private val up = Vector3f()
    private val a = Vector3f()
    private val b = Vector3f()
    private val c = Vector3f()
    private val n = Vector3f()
    private val ring = Array(CRYSTAL_SIDES) { Vector3f() }

    fun light(argb: Int, additive: Boolean): Int = Colors.out(argb, additive, shade)

    fun solid(argb: Int): Int = Colors.out(argb, false, shade)

    fun dim(color: Int, k: Float): Int {
        fun ch(shift: Int) = (((color ushr shift) and 255) * k).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun vertex(x: Float, y: Float, z: Float, color: Int) {
        consumer.addVertex(pose, x, y, z).setColor(color)
    }

    fun vertex(p: Vector3f, color: Int) = vertex(p.x, p.y, p.z, color)

    fun face(x: Float, y: Float, z: Float): Boolean {
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1.0e-4f) return false
        right.set(-z, 0f, x)
        if (right.lengthSquared() < 1.0e-8f) right.set(1f, 0f, 0f)
        right.normalize()
        up.set(right).cross(-x / len, -y / len, -z / len).normalize()
        return true
    }

    fun soft(x: Float, y: Float, z: Float, r: Float, color: Int, segments: Int = 10) {
        if (r <= 0f || !face(x, y, z)) return
        for (i in 0 until segments) {
            val a0 = i / segments.toFloat() * WorldFx.TAU
            val a1 = (i + 1) / segments.toFloat() * WorldFx.TAU
            vertex(x, y, z, color)
            vertex(x + (right.x * cos(a0) + up.x * sin(a0)) * r, y + (right.y * cos(a0) + up.y * sin(a0)) * r, z + (right.z * cos(a0) + up.z * sin(a0)) * r, 0)
            vertex(x + (right.x * cos(a1) + up.x * sin(a1)) * r, y + (right.y * cos(a1) + up.y * sin(a1)) * r, z + (right.z * cos(a1) + up.z * sin(a1)) * r, 0)
            vertex(x, y, z, color)
        }
    }

    fun star(x: Float, y: Float, z: Float, r: Float, color: Int) {
        if (r <= 0f || !face(x, y, z)) return
        val w = r * 0.16f
        val half = dim(color, 0.45f)
        for ((dx, dy, long) in ARMS) {
            val len = r * long
            val ax = right.x * dx + up.x * dy
            val ay = right.y * dx + up.y * dy
            val az = right.z * dx + up.z * dy
            val px = right.x * -dy + up.x * dx
            val py = right.y * -dy + up.y * dx
            val pz = right.z * -dy + up.z * dx
            vertex(x, y, z, color)
            vertex(x + px * w, y + py * w, z + pz * w, half)
            vertex(x + ax * len, y + ay * len, z + az * len, 0)
            vertex(x - px * w, y - py * w, z - pz * w, half)
        }
        soft(x, y, z, r * 0.4f, color, 6)
    }

    fun crystal(x: Float, y: Float, z: Float, rot: Quaternionf, radius: Float, height: Float, argb: Int, gloss: Float = 0.8f) {
        if (radius <= 0f) return
        for (j in 0 until CRYSTAL_SIDES) {
            val ang = j / CRYSTAL_SIDES.toFloat() * WorldFx.TAU
            rot.transform(ring[j].set(cos(ang) * radius, 0f, sin(ang) * radius))
        }
        for (tip in floatArrayOf(height, -height * 0.75f)) {
            rot.transform(c.set(0f, tip, 0f))
            for (j in 0 until CRYSTAL_SIDES) {
                val p0 = ring[j]
                val p1 = ring[(j + 1) % CRYSTAL_SIDES]
                a.set(p1).sub(p0)
                b.set(c).sub(p0)
                n.set(a).cross(b).normalize()
                if (tip < 0f) n.negate()
                val tone = if (j % 2 == 0) argb else Colors.lerp(argb, -1, 0.12f)
                val col = solid(Solid.lit(tone or 0xFF000000.toInt(), n, 0.42f, gloss))
                vertex(x + p0.x, y + p0.y, z + p0.z, col)
                vertex(x + p1.x, y + p1.y, z + p1.z, col)
                vertex(x + c.x, y + c.y, z + c.z, col)
                vertex(x + c.x, y + c.y, z + c.z, col)
            }
        }
    }

    fun band(x: Float, y: Float, z: Float, r0: Float, r1: Float, c0: Int, c1: Int, segments: Int = 48) {
        if (r1 <= r0) return
        for (i in 0 until segments) {
            val a0 = i / segments.toFloat() * WorldFx.TAU
            val a1 = (i + 1) / segments.toFloat() * WorldFx.TAU
            val x0 = cos(a0)
            val z0 = sin(a0)
            val x1 = cos(a1)
            val z1 = sin(a1)
            vertex(x + x0 * r0, y, z + z0 * r0, c0)
            vertex(x + x0 * r1, y, z + z0 * r1, c1)
            vertex(x + x1 * r1, y, z + z1 * r1, c1)
            vertex(x + x1 * r0, y, z + z1 * r0, c0)
        }
    }

    fun wall(x: Float, z: Float, r: Float, y0: Float, y1: Float, c0: Int, c1: Int, segments: Int = 48) {
        if (r <= 0f || y1 <= y0) return
        for (i in 0 until segments) {
            val a0 = i / segments.toFloat() * WorldFx.TAU
            val a1 = (i + 1) / segments.toFloat() * WorldFx.TAU
            val x0 = x + cos(a0) * r
            val z0 = z + sin(a0) * r
            val x1 = x + cos(a1) * r
            val z1 = z + sin(a1) * r
            vertex(x0, y0, z0, c0)
            vertex(x0, y1, z0, c1)
            vertex(x1, y1, z1, c1)
            vertex(x1, y0, z1, c0)
        }
    }

    fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, w0: Float, w1: Float, c0: Int, c1: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        n.set(dy * z0 - dz * y0, dz * x0 - dx * z0, dx * y0 - dy * x0)
        if (n.lengthSquared() < 1.0e-12f) return
        n.normalize()
        vertex(x0 + n.x * w0, y0 + n.y * w0, z0 + n.z * w0, c0)
        vertex(x1 + n.x * w1, y1 + n.y * w1, z1 + n.z * w1, c1)
        vertex(x1 - n.x * w1, y1 - n.y * w1, z1 - n.z * w1, c1)
        vertex(x0 - n.x * w0, y0 - n.y * w0, z0 - n.z * w0, c0)
    }

    fun halo(x: Float, y: Float, z: Float, r0: Float, r1: Float, color: Int, segments: Int = 16) {
        if (r1 <= r0 || !face(x, y, z)) return
        val mid = (r0 + r1) * 0.5f
        for (i in 0 until segments) {
            val a0 = i / segments.toFloat() * WorldFx.TAU
            val a1 = (i + 1) / segments.toFloat() * WorldFx.TAU
            val ox0 = right.x * cos(a0) + up.x * sin(a0)
            val oy0 = right.y * cos(a0) + up.y * sin(a0)
            val oz0 = right.z * cos(a0) + up.z * sin(a0)
            val ox1 = right.x * cos(a1) + up.x * sin(a1)
            val oy1 = right.y * cos(a1) + up.y * sin(a1)
            val oz1 = right.z * cos(a1) + up.z * sin(a1)
            for (half in 0..1) {
                val ra = if (half == 0) r0 else mid
                val rb = if (half == 0) mid else r1
                val ca = if (half == 0) 0 else color
                val cb = if (half == 0) color else 0
                vertex(x + ox0 * ra, y + oy0 * ra, z + oz0 * ra, ca)
                vertex(x + ox0 * rb, y + oy0 * rb, z + oz0 * rb, cb)
                vertex(x + ox1 * rb, y + oy1 * rb, z + oz1 * rb, cb)
                vertex(x + ox1 * ra, y + oy1 * ra, z + oz1 * ra, ca)
            }
        }
    }

    fun flame(x: Float, y: Float, z: Float, w: Float, h: Float, sway: Float, root: Int, tip: Int) {
        if (h <= 0f || w <= 0f) return
        var sx = -z
        var sz = x
        val len = sqrt(sx * sx + sz * sz)
        if (len < 1.0e-5f) {
            sx = 1f
            sz = 0f
        } else {
            sx /= len
            sz /= len
        }
        for (i in 0 until FLAME.size / 2 - 1) {
            val f0 = FLAME[i * 2]
            val f1 = FLAME[i * 2 + 2]
            val w0 = FLAME[i * 2 + 1] * w
            val w1 = FLAME[i * 2 + 3] * w
            val b0 = sway * f0 * f0
            val b1 = sway * f1 * f1
            val c0 = Colors.lerp(root, tip, f0)
            val c1 = Colors.lerp(root, tip, f1)
            vertex(x + sx * (b0 - w0), y + h * f0, z + sz * (b0 - w0), c0)
            vertex(x + sx * (b1 - w1), y + h * f1, z + sz * (b1 - w1), c1)
            vertex(x + sx * (b1 + w1), y + h * f1, z + sz * (b1 + w1), c1)
            vertex(x + sx * (b0 + w0), y + h * f0, z + sz * (b0 + w0), c0)
        }
    }

    fun leaf(
        x: Float, y: Float, z: Float, rot: Quaternionf, length: Float, width: Float, profile: FloatArray,
        argb: Int, tipArgb: Int = argb, rib: Boolean = false,
    ) {
        if (length <= 0f) return
        rot.transform(n.set(0f, 1f, 0f))
        if (n.y < 0f) n.negate()
        for (i in 0 until profile.size / 2 - 1) {
            val f0 = profile[i * 2]
            val f1 = profile[i * 2 + 2]
            val w0 = profile[i * 2 + 1] * width * 0.5f
            val w1 = profile[i * 2 + 3] * width * 0.5f
            val c0 = solid(Solid.lit(Colors.lerp(argb, tipArgb, f0) or 0xFF000000.toInt(), n, 0.55f, 0.5f))
            val c1 = solid(Solid.lit(Colors.lerp(argb, tipArgb, f1) or 0xFF000000.toInt(), n, 0.55f, 0.5f))
            corner(x, y, z, rot, f0 * length, w0, c0)
            corner(x, y, z, rot, f1 * length, w1, c1)
            corner(x, y, z, rot, f1 * length, -w1, c1)
            corner(x, y, z, rot, f0 * length, -w0, c0)
        }
        if (rib) {
            val c = solid(Solid.lit(Colors.scale(argb or 0xFF000000.toInt(), 0.7f), n, 0.5f, 0f))
            val w = width * 0.04f
            corner(x, y, z, rot, 0f, w, c, 0.002f)
            corner(x, y, z, rot, length * 0.95f, w * 0.3f, c, 0.002f)
            corner(x, y, z, rot, length * 0.95f, -w * 0.3f, c, 0.002f)
            corner(x, y, z, rot, 0f, -w, c, 0.002f)
        }
    }

    private fun corner(x: Float, y: Float, z: Float, rot: Quaternionf, along: Float, side: Float, color: Int, lift: Float = 0f) {
        rot.transform(a.set(along, lift, side))
        vertex(x + a.x, y + a.y, z + a.z, color)
    }

    fun orb(x: Float, y: Float, z: Float, r: Float, argb: Int, gloss: Float = 0.9f) {
        if (r <= 0f) return
        val rows = 4
        val cols = 7
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                for (k in 0..3) {
                    val ri = i + if (k == 1 || k == 2) 1 else 0
                    val cj = j + if (k >= 2) 1 else 0
                    val lat = (ri / rows.toFloat() - 0.5f) * PI_F
                    val lon = cj / cols.toFloat() * WorldFx.TAU
                    n.set(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon))
                    vertex(x + n.x * r, y + n.y * r, z + n.z * r, solid(Solid.lit(argb or 0xFF000000.toInt(), n, 0.35f, gloss)))
                }
            }
        }
    }

    fun torus(x: Float, y: Float, z: Float, radius: Float, thick: Float, argb: Int, alpha: Float, segments: Int = 40) {
        if (radius <= 0f || alpha <= 0.003f) return
        val sides = 6
        val a8 = (alpha * 255f).toInt().coerceIn(0, 255) shl 24
        for (i in 0 until segments) {
            for (j in 0 until sides) {
                for (k in 0..3) {
                    val si = i + if (k >= 2) 1 else 0
                    val sj = j + if (k == 1 || k == 2) 1 else 0
                    val u = si / segments.toFloat() * WorldFx.TAU
                    val v = sj / sides.toFloat() * WorldFx.TAU
                    n.set(cos(v) * cos(u), sin(v), cos(v) * sin(u))
                    val lit = Solid.lit(argb or 0xFF000000.toInt(), n, 0.5f, 0.9f)
                    val col = Colors.out(lit and 0xFFFFFF or a8, false, shade)
                    vertex(x + cos(u) * (radius + cos(v) * thick), y + sin(v) * thick, z + sin(u) * (radius + cos(v) * thick), col)
                }
            }
        }
    }

    private companion object {
        const val CRYSTAL_SIDES = 6
        const val PI_F = 3.1415927f

        val FLAME = floatArrayOf(0f, 0.45f, 0.12f, 1f, 0.35f, 0.85f, 0.6f, 0.5f, 0.85f, 0.2f, 1f, 0f)

        val ARMS = listOf(Triple(0f, 1f, 1f), Triple(0f, -1f, 0.75f), Triple(1f, 0f, 0.55f), Triple(-1f, 0f, 0.55f))
    }
}
