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
    val wrap: Wrap? = Wrap.FEATHERED,
    val fold: Fold = Fold.TUCKED,
)

class Fold(
    val sweep: Float,
    val lift: Float,
    val elbowSweep: Float,
    val elbowLift: Float,
    val wristSweep: Float,
    val wristLift: Float,
    val tilt: Float,
    val back: Float,
) {
    companion object {
        val TUCKED = Fold(-10f, -22f, 10f, -14f, 12f, -10f, -12f, 0.06f)
    }
}

class Wrap(
    val rootX: Float,
    val rootZ: Float,
    val sweep: Float,
    val lift: Float,
    val tilt: Float,
    val elbowSweep: Float,
    val elbowLift: Float,
    val wristSweep: Float,
    val wristLift: Float,
) {
    companion object {
        val FEATHERED = Wrap(0.46f, 0.04f, -10f, 10f, 0f, -90f, 8f, -50f, 10f)
    }
}

enum class Anchor { BODY, HEAD }

abstract class Piece(val id: String, val anchor: Anchor, val sides: Int) {
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

    abstract fun matrices(state: AvatarRenderState, motion: Motion, t: Float, size: Float): Array<Matrix4f>

    open fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {}

    open fun tint(kind: Int, color: Int, u: Float, v: Float, seed: Float, t: Float): Int = color

    open fun spark(s: Spark, t: Float, out: SparkOut): Boolean = false
}

abstract class WingStyle(id: String, val rig: Rig) : Piece(id, Anchor.BODY, 2) {
    override fun displace(kind: Int, u: Float, v: Float, seed: Float, t: Float, p: Vector3f) {
        p.z += v * 0.022f * sin(t * 2.6f + u * 6f + seed * 6.283f)
    }

    override fun matrices(state: AvatarRenderState, motion: Motion, t: Float, size: Float) =
        motion.matrices(rig, if (state.showCape) BACK_CAPE else BACK, size)

    companion object {
        const val BACK = 0.21f
        const val BACK_CAPE = 0.25f
    }
}

object WingRenderType {
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

class Motion {
    var seenAt = 0L
    private var last = 0L
    private var phase = 0f
    private var move = 0f
    private var fold = 0f
    private var glide = 0f
    private var wrap = 0f
    private var still = 0f

    fun force(move: Float, fold: Float, glide: Float, wrap: Float, phase: Float) {
        this.move = move
        this.fold = fold
        this.glide = glide
        this.wrap = wrap
        this.phase = phase
    }

    fun update(state: AvatarRenderState, rig: Rig) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1.0e9f).coerceIn(0f, 0.1f)
        last = now
        val k = 1f - exp(-dt * 7f)
        move += (state.walkAnimationSpeed.coerceIn(0f, 1f) - move) * k
        fold += ((if (state.isCrouching || state.isVisuallySwimming) 1f else 0f) - fold) * k
        glide += ((if (state.isFallFlying) 1f else 0f) - glide) * k
        val resting = state.isCrouching && !state.isFallFlying && state.walkAnimationSpeed < 0.05f && state.attackTime == 0f && !state.isUsingItem
        still = if (resting) still + dt else 0f
        val wrapping = still > WRAP_AFTER
        wrap += ((if (wrapping) 1f else 0f) - wrap) * (1f - exp(-dt * if (wrapping) 4f else 9f))
        val speed = (1f + 0.6f * move) * (1f - 0.5f * glide)
        phase = (phase + dt * TAU / rig.period * speed) % TAU
    }

    fun matrices(rig: Rig, back: Float, size: Float = 1f): Array<Matrix4f> {
        val pose = rig.wrap
        val reach = ((size - 0.4f) / 0.5f).coerceIn(0f, 1f)
        val e = if (pose == null) 0f else wrap * wrap * (3f - 2f * wrap) * reach
        val amp = rig.amp * (1f + 0.5f * move) * (1f - 0.75f * glide) * (1f - 0.5f * fold) * (1f - 0.85f * e)
        val f0 = sin(phase)
        val f1 = sin(phase - rig.lag)
        val f2 = sin(phase - rig.lag * 2f)
        val tuck = rig.fold
        var lift = mix(rig.lift + amp * f0, 6f + 2f * f0, glide) + tuck.lift * fold
        var sweep = mix(rig.sweep - amp * 0.3f * f0 + 10f * move, 6f, glide) + tuck.sweep * fold
        var elbowLift = mix(rig.elbowLift + amp * 0.6f * f1, 0f, glide) + tuck.elbowLift * fold
        var wristLift = mix(rig.wristLift + amp * 0.8f * f2, -4f, glide) + tuck.wristLift * fold
        var elbowSweep = rig.elbowSweep + tuck.elbowSweep * fold
        var wristSweep = rig.wristSweep + tuck.wristSweep * fold
        var tilt = rig.tilt + tuck.tilt * fold
        var rootX = 0.09f
        val off = back + 0.06f * (size - 1f).coerceAtLeast(0f)
        var rootZ = off + tuck.back * fold * size
        if (pose != null && e > 0f) {
            lift = mix(lift, pose.lift + amp * f0, e)
            sweep = mix(sweep, pose.sweep, e)
            elbowLift = mix(elbowLift, pose.elbowLift + amp * 0.6f * f1, e)
            wristLift = mix(wristLift, pose.wristLift + amp * 0.8f * f2, e)
            elbowSweep = mix(elbowSweep, pose.elbowSweep, e)
            wristSweep = mix(wristSweep, pose.wristSweep, e)
            tilt = mix(tilt, pose.tilt, e)
            rootX = mix(rootX, pose.rootX, e)
            rootZ = mix(rootZ, off + pose.rootZ, e)
        }

        return mirrored { side ->
            val m0 = Matrix4f()
                .translate(side * rootX, 0.22f, rootZ)
                .scale(side * rig.scale * size, -rig.scale * size, rig.scale * size)
                .rotateY(-sweep * DEG)
                .rotateX(tilt * DEG)
                .rotateZ(lift * DEG)
            val m1 = Matrix4f(m0).translate(rig.l0, 0f, 0f).rotateY(-elbowSweep * DEG).rotateZ(elbowLift * DEG)
            val m2 = Matrix4f(m1).translate(rig.l1, 0f, 0f).rotateY(-wristSweep * DEG).rotateZ(wristLift * DEG)
            Triple(m0, m1, m2)
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        const val TAU = (PI * 2.0).toFloat()

        const val WRAP_AFTER = 0.8f

        fun mirrored(segments: (side: Float) -> Triple<Matrix4f, Matrix4f, Matrix4f>): Array<Matrix4f> {
            val r = segments(1f)
            val l = segments(-1f)
            return arrayOf(r.first, r.second, r.third, l.first, l.second, l.third)
        }
    }
}

class CelestialLayer(parent: RenderLayerParent<AvatarRenderState, PlayerModel>) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {

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
        val body = if (picks.any { it.piece is WingStyle }) Collider.of(parentModel) else null

        for (pick in picks) {
            val piece = pick.piece
            val paint = pick.paint
            val local = piece.matrices(state, motion, t, pick.size)
            poseStack.pushPose()
            (if (piece.anchor == Anchor.HEAD) parentModel.head else parentModel.body).translateAndRotate(poseStack)
            collector.submitCustomGeometry(poseStack, WingRenderType.type) { pose, consumer ->
                val mats = Array(local.size) { Matrix4f(pose.pose()).mul(local[it]) }
                val collider = if (piece is WingStyle) body else null
                Paint.active = paint
                draw(piece.fill, piece, mats, consumer, t, shade, collider, local, pose.pose())
                draw(piece.glow, piece, mats, consumer, t, shade, collider, local, pose.pose())
                sparks(piece, mats, consumer, t)
                Paint.active = null
            }
            poseStack.popPose()
        }
    }

    private val p = Vector3f()
    private val q = Vector3f()
    private val spark = SparkOut()

    private fun draw(mesh: Mesh, piece: Piece, mats: Array<Matrix4f>, consumer: VertexConsumer, t: Float, shade: Float, collider: Collider?, local: Array<Matrix4f>, pose: Matrix4f) {
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
                if (collider == null) {
                    consumer.addVertex(mats[side * 3 + mesh.seg[i]], mesh.px[i], mesh.py[i], mesh.pz[i]).setColor(mesh.out[i])
                } else {
                    local[side * 3 + mesh.seg[i]].transformPosition(mesh.px[i], mesh.py[i], mesh.pz[i], q)
                    collider.push(q)
                    consumer.addVertex(pose, q.x, q.y, q.z).setColor(mesh.out[i])
                }
            }
        }
    }

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
