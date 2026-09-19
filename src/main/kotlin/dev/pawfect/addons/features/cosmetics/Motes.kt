package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Motes {

    private const val VIEW_LIMIT = 64.0
    private const val MAX_PLAYERS = 24
    // FIXME still havent confirmed these actually render in game
    private const val BUDGET = 480
    private const val TAU = 6.2831855f

    private val config get() = ConfigManager.features.cosmetics

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val mode = config.motes
        if (!config.enabled || mode == Visibility.NONE) return

        val client = McCompat.mc
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera.position()
        val self = client.player?.uuid
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val now = System.currentTimeMillis()
        val time = if (config.animate) (now % 3_600_000L) / 1000f else 0f

        val hideSelf = !config.firstPerson && client.options.cameraType.isFirstPerson

        val jobs = ArrayList<Job>()
        var budget = BUDGET

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (hideSelf && player.uuid == self) continue
            if (player.isSpectator || player.isInvisible) continue

            val motes = Cosmetics.motesFor(player.uuid) ?: continue

            val base = player.getPosition(partial)
            val anchor = Vec3(base.x, base.y + player.bbHeight * 0.4, base.z)
            val distanceSqr = anchor.distanceToSqr(camera)
            if (distanceSqr > VIEW_LIMIT * VIEW_LIMIT) continue

            val count = density(motes.density, distanceSqr)
            if (count <= 0) continue
            if (budget <= 0) break

            val allowed = count.coerceAtMost(budget)
            budget -= allowed
            jobs += Job(motes, anchor, player.uuid.hashCode(), allowed)

            if (jobs.size >= MAX_PLAYERS) break
        }

        if (jobs.isEmpty()) return

        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)

        jobs.groupBy { it.motes.glow }.forEach { (glow, group) ->
            collector.submitCustomGeometry(poseStack, TrailRenderTypes.forGlow(glow)) { pose, consumer ->
                group.forEach { job -> swarm(pose, consumer, job, camera, time) }
            }
        }

        poseStack.popPose()
    }

    private fun density(requested: Int, distanceSqr: Double): Int {
        val near = VIEW_LIMIT * 0.35
        if (distanceSqr <= near * near) return requested
        val falloff = (near * near / distanceSqr).coerceIn(0.15, 1.0)
        return (requested * falloff).roundToInt()
    }

    private class Job(val motes: ResolvedMotes, val anchor: Vec3, val seed: Int, val count: Int)

    private fun swarm(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        job: Job,
        camera: Vec3,
        time: Float,
    ) {
        val motes = job.motes
        val right = Vector3f()
        val up = Vector3f()
        val forward = Vector3f()
        val center = Vector3f()

        val seed = job.seed
        val sway = motes.radius * 0.28f
        val lift = motes.height * 0.18f

        for (index in 0 until job.count) {
            val angle = hash(seed, index, 0) * TAU
            val spot = sqrt(hash(seed, index, 1)) * motes.radius * 0.72f
            val tall = (hash(seed, index, 2) - 0.5f) * motes.height * 0.64f
            val rate = 0.12f + hash(seed, index, 3) * 0.28f
            val px = hash(seed, index, 4)
            val py = hash(seed, index, 5)
            val pz = hash(seed, index, 6)
            val beat = 0.5f + hash(seed, index, 7) * 1.1f
            val tint = hash(seed, index, 8)

            val drift = time * motes.drift
            val own = drift * rate

            val x = cos(angle) * spot + flow(own, px) * sway
            val z = sin(angle) * spot + flow(own * 1.3247f, pz) * sway
            val y = tall + flow(own * 0.7549f, py) * lift

            center.set(
                (job.anchor.x + x).toFloat(),
                (job.anchor.y + y).toFloat(),
                (job.anchor.z + z).toFloat(),
            )

            forward.set(
                (camera.x - center.x).toFloat(),
                (camera.y - center.y).toFloat(),
                (camera.z - center.z).toFloat(),
            )
            if (forward.lengthSquared() < 1.0e-8f) continue
            forward.normalize()

            right.set(forward).cross(0f, 1f, 0f)
            if (right.lengthSquared() < 1.0e-8f) continue
            right.normalize()
            up.set(right).cross(forward).normalize()

            val breath = 0.72f + 0.28f * sin(own * 2.3f + px * TAU)
            val half = motes.size * breath
            right.mul(half)
            up.mul(half)

            val pulse = abs(sin(drift * beat * 0.6f + tint * TAU))
            val color = shade(motes, tint, 0.4f + 0.6f * pulse, time)
            if ((color ushr 24) == 0) continue

            quad(pose, consumer, center, right, up, color)
        }
    }

    private fun flow(t: Float, phase: Float): Float {
        val a = sin(t + phase * TAU)
        val b = sin(t * 0.61803f + phase * 17.3f + 1.7f)
        return a * 0.64f + b * 0.36f
    }

    private fun shade(motes: ResolvedMotes, tint: Float, fade: Float, time: Float): Int {
        val rgb = motes.palette.colorAt(tint, time) and 0xFFFFFF
        val alpha = (fade.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or rgb
    }

    private fun hash(seed: Int, index: Int, channel: Int): Float {
        var value = seed * 374761393 + index * 668265263 + channel * 1013904223
        value = (value xor (value shr 13)) * 1274126177
        value = value xor (value shr 16)
        return ((value ushr 8) and 0xFFFF) / 65535f
    }

    private fun quad(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        center: Vector3f,
        right: Vector3f,
        up: Vector3f,
        color: Int,
    ) {
        val ax = center.x - right.x - up.x
        val ay = center.y - right.y - up.y
        val az = center.z - right.z - up.z

        val bx = center.x - right.x + up.x
        val by = center.y - right.y + up.y
        val bz = center.z - right.z + up.z

        val cx = center.x + right.x + up.x
        val cy = center.y + right.y + up.y
        val cz = center.z + right.z + up.z

        val dx = center.x + right.x - up.x
        val dy = center.y + right.y - up.y
        val dz = center.z + right.z - up.z

        consumer.addVertex(pose, ax, ay, az).setColor(color)
        consumer.addVertex(pose, bx, by, bz).setColor(color)
        consumer.addVertex(pose, cx, cy, cz).setColor(color)
        consumer.addVertex(pose, dx, dy, dz).setColor(color)
    }
}
