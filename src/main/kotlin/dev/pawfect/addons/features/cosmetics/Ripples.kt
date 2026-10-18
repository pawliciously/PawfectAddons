package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import java.util.UUID

object Ripples {

    private const val VIEW_LIMIT = 72.0
    private const val MAX_PLAYERS = 48
    private const val MAX_LIVE = 32
    private const val GROUND_LIFT = 0.02
    private const val IDLE_MS = 8_000L

    private val fallers = HashMap<UUID, Faller>()
    private val live = ArrayDeque<Ring>()
    private var born = 0

    private val config get() = ModuleSwitch.view("cosmetics", ConfigManager.features.cosmetics)

    private class Faller {
        var lastY = Double.NaN
        var airborne = false
        var descent = 0.0
        var lastSeen = 0L
    }

    internal class Ring(
        val center: Vec3,
        val bornAt: Long,
        val trail: ResolvedTrail,
        val ripple: ResolvedRipple,
        val power: Float,
        val seed: Int,
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
        val camera = client.gameRenderer.mainCamera().position()
        val self = client.player?.uuid
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val now = System.currentTimeMillis()
        val time = if (config.animate) (now % 600_000L) / 1000f else 0f

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (player.isSpectator || player.isInvisible) continue

            val trail = Cosmetics.trailFor(player.uuid) ?: continue
            val ripple = trail.ripple ?: continue
            if (ripple.legacy) continue

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

        val rings = live.filter { it.center.distanceToSqr(camera) <= VIEW_LIMIT * VIEW_LIMIT }.map { Bloom.of(it, camera, now, time) }
        if (rings.isEmpty()) return

        WorldFx.submit(
            poseStack, collector, WorldFx.shade(level, rings.first().ring.center.add(0.0, 0.5, 0.0)),
            solids = { fx -> rings.forEach { it.solids(fx) } },
            lights = { fx -> rings.forEach { it.lights(fx) } },
        )
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
                spawn(Ring(Vec3(position.x, position.y + GROUND_LIFT, position.z), now, trail, ripple, power, born++))
            }
        }

        faller.lastY = position.y
    }

    private fun spawn(ring: Ring) {
        live.addLast(ring)
        while (live.size > MAX_LIVE) live.removeFirst()
    }
}
