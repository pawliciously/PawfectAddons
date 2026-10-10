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
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The motes swarming round a player, in the theme they picked (see [Swarm]). The swarm lags a
 * moment behind when you move and settles back round you. Legacy ones are [LegacyMotes].
 */
object Motes {

    private const val VIEW_LIMIT = 64.0
    private const val MAX_PLAYERS = 24
    private const val BUDGET = 480
    private const val TAIL = 6
    private const val IDLE_MS = 10_000L

    private val config get() = ModuleSwitch.view("cosmetics", ConfigManager.features.cosmetics)

    /** Where each swarm's centre has got to, chasing its player. */
    private class Follow(var x: Double, var y: Double, var z: Double, var at: Long)

    private val follows = HashMap<UUID, Follow>()

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
        val camera = client.gameRenderer.mainCamera().position()
        val self = client.player?.uuid
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val now = System.currentTimeMillis()
        val time = clock(now)

        val hideSelf = !config.firstPerson && client.options.cameraType.isFirstPerson

        val jobs = ArrayList<Job>()
        var budget = BUDGET

        for (player in level.players()) {
            if (mode == Visibility.SELF && player.uuid != self) continue
            if (hideSelf && player.uuid == self) continue
            if (player.isSpectator || player.isInvisible) continue

            val motes = Cosmetics.motesFor(player.uuid) ?: continue
            if (motes.legacy) continue

            val base = player.getPosition(partial)
            val anchor = Vec3(base.x, base.y + player.bbHeight * 0.4, base.z)
            val distanceSqr = anchor.distanceToSqr(camera)
            if (distanceSqr > VIEW_LIMIT * VIEW_LIMIT) continue

            val count = density(motes.density, distanceSqr)
            if (count <= 0) continue
            if (budget <= 0) break

            val allowed = count.coerceAtMost(budget)
            budget -= allowed
            jobs += Job(motes, anchor, follow(player.uuid, anchor, now), player.uuid.hashCode(), allowed)

            if (jobs.size >= MAX_PLAYERS) break
        }
        follows.entries.removeIf { now - it.value.at > IDLE_MS }

        if (jobs.isEmpty()) return
        submit(poseStack, collector, jobs, camera, time, WorldFx.shade(level, jobs.first().anchor))
    }

    /** Draws the motes of someone walking the menu scene, who has no entity in any world. */
    fun submitShowcase(poseStack: PoseStack, collector: SubmitNodeCollector, uuid: UUID, feet: Vec3, height: Double, camera: Vec3) {
        if (!Cosmetics.showsOffWorld(config.motes, uuid)) return
        val motes = Cosmetics.motesFor(uuid) ?: return
        if (motes.legacy) return LegacyMotes.submitShowcase(poseStack, collector, uuid, feet, height, camera)
        val anchor = Vec3(feet.x, feet.y + height * 0.4, feet.z)
        val distanceSqr = anchor.distanceToSqr(camera)
        if (distanceSqr > VIEW_LIMIT * VIEW_LIMIT) return
        val count = density(motes.density, distanceSqr)
        if (count <= 0) return
        val now = System.currentTimeMillis()
        val job = Job(motes, anchor, follow(uuid, anchor, now), uuid.hashCode(), count)
        submit(poseStack, collector, listOf(job), camera, clock(now), 0.9f)
    }

    private fun clock(now: Long) = if (config.animate) (now % 3_600_000L) / 1000f else 0f

    /** Eases the swarm centre toward [anchor], snapping when the player jumps a long way. */
    private fun follow(uuid: UUID, anchor: Vec3, now: Long): Vec3 {
        val f = follows.getOrPut(uuid) { Follow(anchor.x, anchor.y, anchor.z, now) }
        val dt = ((now - f.at).coerceIn(0L, 100L)) / 1000.0
        f.at = now
        val dx = anchor.x - f.x
        val dy = anchor.y - f.y
        val dz = anchor.z - f.z
        if (dx * dx + dy * dy + dz * dz > 36.0) {
            f.x = anchor.x
            f.y = anchor.y
            f.z = anchor.z
        } else {
            val k = 1.0 - exp(-dt * 2.6)
            f.x += dx * k
            f.y += dy * k
            f.z += dz * k
        }
        return Vec3(f.x, f.y, f.z)
    }

    private fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, jobs: List<Job>, camera: Vec3, time: Float, shade: Float) {
        val swarms = jobs.map { Swarm.of(it, camera, time) }
        WorldFx.submit(
            poseStack, collector, shade,
            solids = { fx -> swarms.forEach { it.solids(fx) } },
            lights = { fx -> swarms.forEach { it.lights(fx) } },
        )
    }

    private fun density(requested: Int, distanceSqr: Double): Int {
        val near = VIEW_LIMIT * 0.35
        if (distanceSqr <= near * near) return requested
        val falloff = (near * near / distanceSqr).coerceIn(0.15, 1.0)
        return (requested * falloff).roundToInt()
    }

    internal class Job(val motes: ResolvedMotes, val anchor: Vec3, val follow: Vec3, val seed: Int, val count: Int)
}
