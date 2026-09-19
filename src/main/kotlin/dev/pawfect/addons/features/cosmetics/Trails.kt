package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Trails {

    private const val MAX_POINTS = 64
    private const val MAX_PLAYERS = 64
    private const val SAMPLE_MS = 20L
    private const val IDLE_MS = 4_000L
    private const val MIN_STEP = 0.02
    private const val BREAK_STEP = 8.0
    private const val FADE_FLOOR = 0.6f
    private const val FADE_CEIL = 4.2f
    private const val VIEW_LIMIT = 96.0

    private val tracks = HashMap<UUID, Track>()

    private val config get() = ConfigManager.features.cosmetics

    private class Point(val x: Double, val y: Double, val z: Double, val at: Long, val strength: Float)

    private class Track {
        val points = ArrayDeque<Point>()
        var lastSample = 0L
        var lastSeen = 0L
    }

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    fun onTick() {
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        tracks.entries.removeIf { now - it.value.lastSeen > IDLE_MS }
    }

    fun clear() = tracks.clear()

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val mode = config.trails
        if (!config.enabled || mode == Visibility.NONE) return

        val client = McCompat.mc
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera.position()
        val self = client.player?.uuid
        val now = System.currentTimeMillis()
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val time = if (config.animate) (now % 600_000L) / 1000f else 0f

        val hideSelf = !config.firstPerson && client.options.cameraType.isFirstPerson

        val jobs = ArrayList<Pair<ResolvedTrail, Track>>()

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (hideSelf && player.uuid == self) continue
            if (player.isSpectator || player.isInvisible) continue

            val trail = Cosmetics.trailFor(player.uuid) ?: continue
            val position = headPosition(player, partial)
            if (position.distanceToSqr(camera) > VIEW_LIMIT * VIEW_LIMIT) continue

            val track = tracks.getOrPut(player.uuid) { Track() }
            track.lastSeen = now
            sample(track, position, now)
            trim(track, now, trail.length)

            if (track.points.size >= 2) jobs += trail to track
        }

        if (tracks.size > MAX_PLAYERS) {
            val stale = tracks.entries.sortedBy { it.value.lastSeen }.take(tracks.size - MAX_PLAYERS)
            stale.forEach { tracks.remove(it.key) }
        }

        if (jobs.isEmpty()) return

        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)

        jobs.groupBy { it.first.glow }.forEach { (glow, group) ->
            collector.submitCustomGeometry(poseStack, TrailRenderTypes.forGlow(glow)) { pose, consumer ->
                group.forEach { (trail, track) -> ribbon(pose, consumer, trail, track, camera, time) }
            }
        }

        poseStack.popPose()
    }

    private fun headPosition(player: AbstractClientPlayer, partial: Float): Vec3 {
        val base = player.getPosition(partial)
        return Vec3(base.x, base.y + player.bbHeight * 0.45, base.z)
    }

    private fun sample(track: Track, position: Vec3, now: Long) {
        val last = track.points.lastOrNull()

        if (last != null) {
            val step = sqrt(
                (position.x - last.x) * (position.x - last.x) +
                    (position.y - last.y) * (position.y - last.y) +
                    (position.z - last.z) * (position.z - last.z),
            )

            if (step > BREAK_STEP) {
                track.points.clear()
            } else {
                if (now - track.lastSample < SAMPLE_MS) return
                if (step < MIN_STEP) return

                val seconds = ((now - last.at).coerceAtLeast(1L)) / 1000.0
                val speed = (step / seconds).toFloat()
                val strength = ((speed - FADE_FLOOR) / (FADE_CEIL - FADE_FLOOR)).coerceIn(0f, 1f)

                track.points.addLast(Point(position.x, position.y, position.z, now, strength))
                track.lastSample = now
                while (track.points.size > MAX_POINTS) track.points.removeFirst()
                return
            }
        }

        track.points.addLast(Point(position.x, position.y, position.z, now, 0f))
        track.lastSample = now
    }

    private fun trim(track: Track, now: Long, length: Float) {
        val window = (length * 1000f).toLong()
        while (track.points.size > 2 && now - track.points.first().at > window) {
            track.points.removeFirst()
        }
        while (track.points.size > MAX_POINTS) track.points.removeFirst()
    }

    private fun ribbon(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        trail: ResolvedTrail,
        track: Track,
        camera: Vec3,
        time: Float,
    ) {
        val points = track.points.toList()
        if (points.size < 2) return

        val span = (points.size - 1).toFloat()
        val half = trail.width * 0.5f
        val side = Vector3f()
        val forward = Vector3f()
        val toCamera = Vector3f()

        var started = false
        var previousTop = Vector3f()
        var previousBottom = Vector3f()
        var previousColor = 0

        for (index in points.indices) {
            val point = points[index]
            val before = points[(index - 1).coerceAtLeast(0)]
            val after = points[(index + 1).coerceAtMost(points.size - 1)]

            forward.set(
                (after.x - before.x).toFloat(),
                (after.y - before.y).toFloat(),
                (after.z - before.z).toFloat(),
            )
            if (forward.lengthSquared() < 1.0e-8f) continue
            forward.normalize()

            toCamera.set(
                (camera.x - point.x).toFloat(),
                (camera.y - point.y).toFloat(),
                (camera.z - point.z).toFloat(),
            )
            if (toCamera.lengthSquared() < 1.0e-8f) continue
            toCamera.normalize()

            side.set(forward).cross(toCamera)
            if (side.lengthSquared() < 1.0e-8f) continue
            side.normalize().mul(half * taper(index / span))

            val center = Vector3f(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
            val top = Vector3f(center).add(side)
            val bottom = Vector3f(center).sub(side)
            val color = shade(trail, index / span, point.strength, time)

            if (started) {
                quad(pose, consumer, previousTop, previousBottom, bottom, top, previousColor, color)
            }

            started = true
            previousTop = top
            previousBottom = bottom
            previousColor = color
        }
    }

    private fun taper(position: Float): Float = position.coerceIn(0f, 1f).pow(0.7f)

    private fun shade(trail: ResolvedTrail, position: Float, strength: Float, time: Float): Int {
        val rgb = trail.palette.colorAt(position, time) and 0xFFFFFF
        val fade = position.coerceIn(0f, 1f).pow(1.4f) * strength
        val alpha = (fade * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or rgb
    }

    private fun quad(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        a: Vector3f,
        b: Vector3f,
        c: Vector3f,
        d: Vector3f,
        startColor: Int,
        endColor: Int,
    ) {
        consumer.addVertex(pose, a.x, a.y, a.z).setColor(startColor)
        consumer.addVertex(pose, b.x, b.y, b.z).setColor(startColor)
        consumer.addVertex(pose, c.x, c.y, c.z).setColor(endColor)
        consumer.addVertex(pose, d.x, d.y, d.z).setColor(endColor)
    }
}
