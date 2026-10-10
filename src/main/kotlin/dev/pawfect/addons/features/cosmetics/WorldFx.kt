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

/**
 * Motes, trails and ripples drawn in the celestial look: lit crystal solids first, then soft light
 * over them. Everything is placed relative to the camera, so the pose stack is the view alone.
 */
object WorldFx {

    const val TAU = 6.2831855f

    /** Premultiplied like the wings, so solids and light share one blend. Depth runs far = 0 on 26.2. */
    private fun pipeline(name: String, writeDepth: Boolean): RenderPipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/$name"))
        .withColorTargetState(ColorTargetState(BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
        .withCull(false)
        .build()

    private fun type(name: String, writeDepth: Boolean): RenderType =
        RenderTypeInvoker.`pawfectaddons$create`("pawfect_$name", RenderSetup.builder(RenderPipelines.register(pipeline(name, writeDepth))).createRenderSetup())

    private val solid: RenderType by lazy { type("fx_solid", true) }

    /** Light writes no depth, so overlapping glow adds up instead of cutting holes in itself. */
    private val light: RenderType by lazy { type("fx_light", false) }

    /** Draws [solids] then [lights] after them, so light always lands on top of the crystals it surrounds. */
    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, shade: Float, solids: (Fx) -> Unit, lights: (Fx) -> Unit) {
        collector.submitCustomGeometry(poseStack, solid) { pose, consumer -> solids(Fx(pose, consumer, shade)) }
        collector.order(1).submitCustomGeometry(poseStack, light) { pose, consumer -> lights(Fx(pose, consumer, shade)) }
    }

    /** How lit a spot is, so crystals darken at night and underground like the blocks around them. */
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

    /** [color] pushed toward white, for the hot middle of a light. */
    fun hot(color: Int, k: Float): Int = Colors.lerp(color or 0xFF000000.toInt(), -1, k)
}

/** One batch of vertices. Colours go in straight ARGB and come out premultiplied. */
class Fx(private val pose: PoseStack.Pose, private val consumer: VertexConsumer, private val shade: Float) {

    private val right = Vector3f()
    private val up = Vector3f()
    private val a = Vector3f()
    private val b = Vector3f()
    private val c = Vector3f()
    private val n = Vector3f()
    private val ring = Array(CRYSTAL_SIDES) { Vector3f() }

    /** A light colour: [additive] adds it to what's behind, otherwise it covers like tinted glass. */
    fun light(argb: Int, additive: Boolean): Int = Colors.out(argb, additive, shade)

    fun solid(argb: Int): Int = Colors.out(argb, false, shade)

    /** Scales a premultiplied colour, alpha too, so it fades the same whichever blend it uses. */
    fun dim(color: Int, k: Float): Int {
        fun ch(shift: Int) = (((color ushr shift) and 255) * k).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun vertex(x: Float, y: Float, z: Float, color: Int) {
        consumer.addVertex(pose, x, y, z).setColor(color)
    }

    fun vertex(p: Vector3f, color: Int) = vertex(p.x, p.y, p.z, color)

    /** Sets the billboard axes for a point, facing the camera at the origin. False when it can't be faced. */
    fun face(x: Float, y: Float, z: Float): Boolean {
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1.0e-4f) return false
        right.set(-z, 0f, x)
        if (right.lengthSquared() < 1.0e-8f) right.set(1f, 0f, 0f)
        right.normalize()
        up.set(right).cross(-x / len, -y / len, -z / len).normalize()
        return true
    }

    /** A round soft light, bright in the middle and gone at the edge. Takes a premultiplied colour. */
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

    /** A four pointed glint with a long upright ray, like the wings' sparks seen up close. */
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

    /**
     * A cut gem: two six sided pyramids base to base, turned by [rot], each face lit flat by the
     * same light as the halos.
     */
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
                // Alternate faces a touch lighter so the cut reads even side on.
                val tone = if (j % 2 == 0) argb else Colors.lerp(argb, -1, 0.12f)
                val col = solid(Solid.lit(tone or 0xFF000000.toInt(), n, 0.42f, gloss))
                vertex(x + p0.x, y + p0.y, z + p0.z, col)
                vertex(x + p1.x, y + p1.y, z + p1.z, col)
                vertex(x + c.x, y + c.y, z + c.z, col)
                vertex(x + c.x, y + c.y, z + c.z, col)
            }
        }
    }

    /** A flat ring on the ground from [r0] to [r1], fading between [c0] and [c1] (premultiplied). */
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

    /** An upright ring of light at radius [r], from [y0] to [y1], fading between [c0] and [c1]. */
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

    private companion object {
        const val CRYSTAL_SIDES = 6

        /** Direction and length of each arm of a star, the upright one longest. */
        val ARMS = listOf(Triple(0f, 1f, 1f), Triple(0f, -1f, 0.75f), Triple(1f, 0f, 0.55f), Triple(-1f, 0f, 0.55f))
    }
}
