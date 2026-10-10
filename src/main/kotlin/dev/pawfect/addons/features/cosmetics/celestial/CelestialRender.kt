package dev.pawfect.addons.features.cosmetics.celestial

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.BlendFactor
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.features.cosmetics.Cosmetics
import dev.pawfect.addons.mixin.RenderTypeInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Bone lengths, resting angles in degrees and flap of a wing. Lift raises a segment, sweep swings
 * it back, tilt leans the whole wing back at the top.
 */
class Rig(
    val l0: Float,
    val l1: Float,
    val l2: Float,
    val sweep: Float,
    val lift: Float,
    val elbowSweep: Float,
    val elbowLift: Float,
    val wristSweep: Float,
    val wristLift: Float,
    val amp: Float,
    val period: Float,
    val lag: Float,
    val scale: Float,
    val tilt: Float = 12f,
)

enum class Anchor { BODY, HEAD }

/**
 * A cosmetic built once into meshes. Every vertex lives in one of three segments; [sides] copies
 * are drawn, each with its own three segment transforms, so mirrored pairs cost one mesh.
 */
abstract class Piece(val id: String, val anchor: Anchor, val sides: Int) {
    /** The hue this style is tuned around, or null when it cycles colours on its own. */
    open val baseHue: Float? = null

    private val built by lazy {
        val fill = MeshBuilder(false)
        val glow = MeshBuilder(true)
        val sparks = ArrayList<Spark>()
        build(fill, glow, sparks)
        Triple(fill.build(), glow.build(), sparks)
    }

    val fill: Mesh get() = built.first
    val glow: Mesh get() = built.second
    val sparks: List<Spark> get() = built.third

    protected abstract fun build(fill: MeshBuilder, glow: MeshBuilder, sparks: MutableList<Spark>)

    /** Segment transforms for the frame, relative to the anchor, [sides] times three. */
    abstract fun matrices(state: AvatarRenderState, motion: Motion, t: Float, size: Float): Array<Matrix4f>

    /** Moves a vertex for the frame. Must stay linear in v per part so layered copies stay together. */
    open fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {}

    open fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = color

    open fun spark(s: Spark, t: Float, out: SparkOut): Boolean = false
}

abstract class WingStyle(id: String, val rig: Rig) : Piece(id, Anchor.BODY, 2) {
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        p.z += v * 0.022f * sin(t * 2.6f + u * 6f + seed * 6.283f)
    }

    override fun matrices(state: AvatarRenderState, motion: Motion, t: Float, size: Float) =
        motion.matrices(rig, if (state.showCape) 0.2f else 0.15f, size)
}

object WingRenderType {
    /**
     * One premultiplied blend draws solid parts and additive glow in the same batch, so glow
     * never ends up underneath a feather when batches come out in a different order.
     */
    val type: RenderType by lazy {
        val pipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/celestial"))
            .withColorTargetState(ColorTargetState(BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(false)
            .build()
        RenderTypeInvoker.`pawfectaddons$create`(
            "pawfect_celestial",
            RenderSetup.builder(RenderPipelines.register(pipeline)).createRenderSetup(),
        )
    }
}

/** Eases the wings between idle, walking, crouching and gliding instead of snapping. */
class Motion {
    var seenAt = 0L
    private var last = 0L
    private var phase = 0f
    private var move = 0f
    private var fold = 0f
    private var glide = 0f

    fun update(state: AvatarRenderState, rig: Rig) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1.0e9f).coerceIn(0f, 0.1f)
        last = now
        val k = 1f - exp(-dt * 7f)
        move += (state.walkAnimationSpeed.coerceIn(0f, 1f) - move) * k
        fold += ((if (state.isCrouching || state.isVisuallySwimming) 1f else 0f) - fold) * k
        glide += ((if (state.isFallFlying) 1f else 0f) - glide) * k
        val speed = (1f + 0.6f * move) * (1f - 0.5f * glide)
        phase = (phase + dt * TAU / rig.period * speed) % TAU
    }

    /** Three segment transforms per wing, right wing first, relative to the body. */
    fun matrices(rig: Rig, back: Float, size: Float = 1f): Array<Matrix4f> {
        val amp = rig.amp * (1f + 0.5f * move) * (1f - 0.75f * glide) * (1f - 0.5f * fold)
        val f0 = sin(phase)
        val f1 = sin(phase - rig.lag)
        val f2 = sin(phase - rig.lag * 2f)
        val lift = mix(rig.lift + amp * f0, 6f + 2f * f0, glide) - 22f * fold
        val sweep = mix(rig.sweep - amp * 0.3f * f0 + 10f * move, 6f, glide) + 30f * fold
        val elbowLift = mix(rig.elbowLift + amp * 0.6f * f1, 0f, glide) - 14f * fold
        val wristLift = mix(rig.wristLift + amp * 0.8f * f2, -4f, glide) - 10f * fold
        val elbowSweep = rig.elbowSweep + 10f * fold
        val wristSweep = rig.wristSweep + 12f * fold

        return mirrored { side ->
            val m0 = Matrix4f()
                .translate(side * 0.09f, 0.22f, back)
                .scale(side * rig.scale * size, -rig.scale * size, rig.scale * size)
                .rotateY(-sweep * DEG)
                .rotateX(rig.tilt * DEG)
                .rotateZ(lift * DEG)
            val m1 = Matrix4f(m0).translate(rig.l0, 0f, 0f).rotateY(-elbowSweep * DEG).rotateZ(elbowLift * DEG)
            val m2 = Matrix4f(m1).translate(rig.l1, 0f, 0f).rotateY(-wristSweep * DEG).rotateZ(wristLift * DEG)
            Triple(m0, m1, m2)
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        const val TAU = (PI * 2.0).toFloat()

        /** Runs [segments] for the right side then the left, flattening into one array. */
        fun mirrored(segments: (side: Float) -> Triple<Matrix4f, Matrix4f, Matrix4f>): Array<Matrix4f> {
            val r = segments(1f)
            val l = segments(-1f)
            return arrayOf(r.first, r.second, r.third, l.first, l.second, l.third)
        }
    }
}

class CelestialLayer(parent: RenderLayerParent<AvatarRenderState, PlayerModel>) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {

    /** Each player flaps on their own; forgotten once they have not been drawn for a while. */
    private val motions = HashMap<Int, Motion>()
    private var prunedAt = 0L

    private fun motion(id: Int): Motion {
        val now = System.currentTimeMillis()
        if (now - prunedAt > 10_000L) {
            prunedAt = now
            motions.values.removeIf { now - it.seenAt > 30_000L }
        }
        return motions.getOrPut(id) { Motion() }.also { it.seenAt = now }
    }

    override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, yRot: Float, xRot: Float) {
        if (state.isInvisible || state.isSpectator) return
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return
        val uuid = if (state.id == self.id) self.uuid else mc.level?.getEntity(state.id)?.uuid ?: return
        val picks = Cosmetics.celestialFor(uuid) ?: return
        if (picks.isEmpty()) return

        val motion = motion(state.id)
        (picks.firstOrNull { it.piece is WingStyle }?.piece as WingStyle?)?.let { motion.update(state, it.rig) }
        val t = state.ageInTicks / 20f
        val block = (light shr 4) and 15
        val sky = (light shr 20) and 15
        val shade = 0.45f + 0.55f * max(block, sky) / 15f

        for (pick in picks) {
            val piece = pick.piece
            val paint = pick.paint
            val local = piece.matrices(state, motion, t, pick.size)
            poseStack.pushPose()
            (if (piece.anchor == Anchor.HEAD) parentModel.head else parentModel.body).translateAndRotate(poseStack)
            collector.submitCustomGeometry(poseStack, WingRenderType.type) { pose, consumer ->
                val mats = Array(local.size) { Matrix4f(pose.pose()).mul(local[it]) }
                Paint.active = paint
                draw(piece.fill, piece, mats, consumer, t, shade)
                draw(piece.glow, piece, mats, consumer, t, shade)
                sparks(piece, mats, consumer, t)
                Paint.active = null
            }
            poseStack.popPose()
        }
    }

    private val p = Vector3f()
    private val spark = SparkOut()

    private fun draw(mesh: Mesh, piece: Piece, mats: Array<Matrix4f>, consumer: VertexConsumer, t: Float, shade: Float) {
        for (i in 0 until mesh.size) {
            p.set(mesh.x[i], mesh.y[i], mesh.z[i])
            val kind = mesh.kind[i].toInt()
            piece.displace(kind, mesh.u[i], mesh.v[i], mesh.seed[i], t, p)
            mesh.px[i] = p.x
            mesh.py[i] = p.y
            mesh.pz[i] = p.z
            mesh.out[i] = Colors.out(piece.tint(kind, mesh.color[i], mesh.u[i], mesh.v[i], mesh.seed[i], t), mesh.glow, shade)
        }
        for (side in 0 until piece.sides) {
            for (i in 0 until mesh.size) {
                consumer.addVertex(mats[side * 3 + mesh.seg[i]], mesh.px[i], mesh.py[i], mesh.pz[i]).setColor(mesh.out[i])
            }
        }
    }

    /** Each spark is two crossed diamonds so it reads from the side as well as from behind. */
    private fun sparks(piece: Piece, mats: Array<Matrix4f>, consumer: VertexConsumer, t: Float) {
        for (side in 0 until piece.sides) {
            for (s in piece.sparks) {
                if (!piece.spark(s, t, spark) || spark.size <= 0f) continue
                val m = mats[side * 3 + s.seg]
                val c = Colors.out(spark.color, true, 1f)
                val r = spark.size
                consumer.addVertex(m, spark.x, spark.y + r, spark.z).setColor(c)
                consumer.addVertex(m, spark.x + r, spark.y, spark.z).setColor(c)
                consumer.addVertex(m, spark.x, spark.y - r, spark.z).setColor(c)
                consumer.addVertex(m, spark.x - r, spark.y, spark.z).setColor(c)
                consumer.addVertex(m, spark.x, spark.y + r, spark.z).setColor(c)
                consumer.addVertex(m, spark.x, spark.y, spark.z + r).setColor(c)
                consumer.addVertex(m, spark.x, spark.y - r, spark.z).setColor(c)
                consumer.addVertex(m, spark.x, spark.y, spark.z - r).setColor(c)
            }
        }
    }
}
