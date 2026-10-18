package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import java.util.UUID
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
    private val showcase = HashMap<UUID, Track>()

    private val config get() = ModuleSwitch.view("cosmetics", ConfigManager.features.cosmetics)

    internal class Point(val x: Double, val y: Double, val z: Double, val at: Long, val strength: Float, val dist: Float, val seed: Int)

    internal class Track {
        val points = ArrayDeque<Point>()
        var lastSample = 0L
        var lastSeen = 0L
        var count = 0
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

    fun clear() {
        tracks.clear()
        showcase.clear()
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val mode = config.trails
        if (!config.enabled || mode == Visibility.NONE) return

        val client = McCompat.mc
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val self = client.player?.uuid
        val now = System.currentTimeMillis()
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)

        val hideSelf = !config.firstPerson && client.options.cameraType.isFirstPerson

        val jobs = ArrayList<Pair<ResolvedTrail, Track>>()

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (hideSelf && player.uuid == self) continue
            if (player.isSpectator || player.isInvisible) continue

            val trail = Cosmetics.trailFor(player.uuid) ?: continue
            if (trail.legacy) continue
            val position = waist(player, partial)
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
        submit(poseStack, collector, jobs, camera, now, WorldFx.shade(level, camera))
    }

    fun submitShowcase(poseStack: PoseStack, collector: SubmitNodeCollector, uuid: UUID, feet: Vec3, height: Double, camera: Vec3) {
        if (!Cosmetics.showsOffWorld(config.trails, uuid)) return
        val trail = Cosmetics.trailFor(uuid) ?: return
        if (trail.legacy) return LegacyTrails.submitShowcase(poseStack, collector, uuid, feet, height, camera)
        val now = System.currentTimeMillis()
        val track = showcase.getOrPut(uuid) { Track() }
        sample(track, Vec3(feet.x, feet.y + height * 0.45, feet.z), now)
        trim(track, now, trail.length)
        if (track.points.size >= 2) submit(poseStack, collector, listOf(trail to track), camera, now, 0.9f)
    }

    private fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, jobs: List<Pair<ResolvedTrail, Track>>, camera: Vec3, now: Long, shade: Float) {
        val time = if (config.animate) (now % 600_000L) / 1000f else 0f
        val ribbons = jobs.mapNotNull { (trail, track) -> Ribbon.of(trail, track, camera, now, time) }
        if (ribbons.isEmpty()) return
        WorldFx.submit(poseStack, collector, shade, solids = { fx -> ribbons.forEach { it.solids(fx) } }, lights = { fx -> ribbons.forEach { it.lights(fx) } })
    }

    private fun waist(player: AbstractClientPlayer, partial: Float): Vec3 {
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

                track.points.addLast(Point(position.x, position.y, position.z, now, strength, last.dist + step.toFloat(), track.count++))
                track.lastSample = now
                while (track.points.size > MAX_POINTS) track.points.removeFirst()
                return
            }
        }

        track.points.addLast(Point(position.x, position.y, position.z, now, 0f, 0f, track.count++))
        track.lastSample = now
    }

    private fun trim(track: Track, now: Long, length: Float) {
        val window = (length * 1000f).toLong()
        while (track.points.size > 2 && now - track.points.first().at > window) {
            track.points.removeFirst()
        }
        while (track.points.size > MAX_POINTS) track.points.removeFirst()
    }
}
