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
import java.util.UUID
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object Ripples {

    private const val VIEW_LIMIT = 72.0
    private const val MAX_PLAYERS = 48
    private const val MAX_LIVE = 32
    private const val SEGMENTS = 32
    private const val GROUND_LIFT = 0.02
    private const val IDLE_MS = 8_000L
    private const val RISE = 0.18f

    private val fallers = HashMap<UUID, Faller>()
    private val live = ArrayDeque<Ring>()

    private val config get() = ConfigManager.features.cosmetics

    private class Faller {
        var lastY = Double.NaN
        var airborne = false
        var descent = 0.0
        var lastSeen = 0L
    }

    private class Ring(
        val center: Vec3,
        val bornAt: Long,
        val trail: ResolvedTrail,
        val ripple: ResolvedRipple,
        val power: Float,
    ) {
        val lifeMs: Long = (ripple.life * 1000f).toLong().coerceAtLeast(1L)
    }

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    fun onTick() {
        if (fallers.isEmpty()) return
        val now = System.currentTimeMillis()
        fallers.entries.removeIf { now - it.value.lastSeen > IDLE_MS }
    }

    fun clear() {
        fallers.clear()
        live.clear()
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val mode = config.trails
        if (!config.enabled || mode == Visibility.NONE) {
            if (live.isNotEmpty()) live.clear()
            return
        }

        val client = McCompat.mc
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera.position()
        val self = client.player?.uuid
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val now = System.currentTimeMillis()
        val time = if (config.animate) (now % 600_000L) / 1000f else 0f

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (player.isSpectator || player.isInvisible) continue

            val trail = Cosmetics.trailFor(player.uuid) ?: continue
            val ripple = trail.ripple ?: continue

            val position = player.getPosition(partial)
            if (position.distanceToSqr(camera) > VIEW_LIMIT * VIEW_LIMIT) continue

            val faller = fallers.getOrPut(player.uuid) { Faller() }
            faller.lastSeen = now
            track(faller, player.onGround(), position, ripple, trail, now)
        }

        if (fallers.size > MAX_PLAYERS) {
            val stale = fallers.entries.sortedBy { it.value.lastSeen }.take(fallers.size - MAX_PLAYERS)
            stale.forEach { fallers.remove(it.key) }
        }

        live.removeIf { now - it.bornAt > it.lifeMs }
        if (live.isEmpty()) return

        val rings = live.filter { it.center.distanceToSqr(camera) <= VIEW_LIMIT * VIEW_LIMIT }
        if (rings.isEmpty()) return

        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)

        rings.groupBy { it.trail.glow }.forEach { (glow, group) ->
            collector.submitCustomGeometry(poseStack, TrailRenderTypes.forGlow(glow)) { pose, consumer ->
                group.forEach { ring -> annulus(pose, consumer, ring, now, time) }
            }
        }

        poseStack.popPose()
    }

    private fun track(
        faller: Faller,
        onGround: Boolean,
        position: Vec3,
        ripple: ResolvedRipple,
        trail: ResolvedTrail,
        now: Long,
    ) {
        if (faller.lastY.isNaN()) {
            faller.lastY = position.y
            faller.airborne = !onGround
            return
        }

        if (!onGround) {
            val drop = faller.lastY - position.y
            if (drop > 0.0) faller.descent += drop
            faller.airborne = true
            faller.lastY = position.y
            return
        }

        if (faller.airborne) {
            val fell = faller.descent
            faller.airborne = false
            faller.descent = 0.0

            if (fell >= ripple.minFall) {
                val power = ((fell - ripple.minFall) / 6.0).coerceIn(0.0, 1.0).toFloat() * 0.3f + 0.7f
                spawn(Ring(Vec3(position.x, position.y + GROUND_LIFT, position.z), now, trail, ripple, power))
            }
        }

        faller.lastY = position.y
    }

    private fun spawn(ring: Ring) {
        live.addLast(ring)
        while (live.size > MAX_LIVE) live.removeFirst()
    }

    private fun annulus(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        ring: Ring,
        now: Long,
        time: Float,
    ) {
        val age = ((now - ring.bornAt).toFloat() / ring.lifeMs.toFloat()).coerceIn(0f, 1f)
        if (age >= 1f) return

        val eased = (age * ring.ripple.speed).coerceIn(0f, 1f).pow(0.55f)
        val outer = eased * ring.ripple.size * ring.power
        val inner = (outer - ring.ripple.thickness).coerceAtLeast(0f)
        if (outer <= 0.001f) return

        val fade = envelope(age) * ring.power
        val rgb = ring.trail.palette.colorAt(eased, time) and 0xFFFFFF
        val alpha = (fade * 255f).roundToInt().coerceIn(0, 255)
        if (alpha == 0) return

        val core = (alpha shl 24) or rgb
        val edge = ((alpha / 4) shl 24) or rgb
        val mid = (inner + outer) * 0.5f

        val cx = ring.center.x.toFloat()
        val cy = ring.center.y.toFloat()
        val cz = ring.center.z.toFloat()

        ribbon(pose, consumer, cx, cy, cz, inner, mid, edge, core)
        ribbon(pose, consumer, cx, cy, cz, mid, outer, core, edge)
    }

    private fun envelope(age: Float): Float {
        val rise = (age / RISE).coerceIn(0f, 1f)
        val fall = ((1f - age) / (1f - RISE)).coerceIn(0f, 1f)
        return rise * fall.pow(1.15f)
    }

    private fun ribbon(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        cx: Float,
        cy: Float,
        cz: Float,
        from: Float,
        to: Float,
        fromColor: Int,
        toColor: Int,
    ) {
        val step = (Math.PI * 2.0 / SEGMENTS).toFloat()
        val a = Vector3f()
        val b = Vector3f()
        val c = Vector3f()
        val d = Vector3f()

        for (segment in 0 until SEGMENTS) {
            val head = step * segment
            val tail = step * (segment + 1)

            val headX = cos(head)
            val headZ = sin(head)
            val tailX = cos(tail)
            val tailZ = sin(tail)

            a.set(cx + headX * from, cy, cz + headZ * from)
            b.set(cx + headX * to, cy, cz + headZ * to)
            c.set(cx + tailX * to, cy, cz + tailZ * to)
            d.set(cx + tailX * from, cy, cz + tailZ * from)

            consumer.addVertex(pose, a.x, a.y, a.z).setColor(fromColor)
            consumer.addVertex(pose, b.x, b.y, b.z).setColor(toColor)
            consumer.addVertex(pose, c.x, c.y, c.z).setColor(toColor)
            consumer.addVertex(pose, d.x, d.y, d.z).setColor(fromColor)
        }
    }
}
