package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.features.cosmetics.WorldFx.TAU
import dev.pawfect.addons.features.cosmetics.WorldFx.hash
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Little cut gems circling you on tilted orbits, each in a soft glow with a twinkle and a short
 * comet tail. The swarm lags a moment behind when you move and settles back round you.
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
        val swarms = jobs.map { Swarm(it, camera, time) }
        WorldFx.submit(
            poseStack, collector, shade,
            solids = { fx -> swarms.forEach { it.gems(fx) } },
            lights = { fx -> swarms.forEach { it.glow(fx) } },
        )
    }

    private fun density(requested: Int, distanceSqr: Double): Int {
        val near = VIEW_LIMIT * 0.35
        if (distanceSqr <= near * near) return requested
        val falloff = (near * near / distanceSqr).coerceIn(0.15, 1.0)
        return (requested * falloff).roundToInt()
    }

    internal class Job(val motes: ResolvedMotes, val anchor: Vec3, val follow: Vec3, val seed: Int, val count: Int)

    /** One player's motes, placed once per frame and shared by the solid and light passes. */
    internal class Swarm(job: Job, camera: Vec3, private val time: Float) {
        private val motes = job.motes
        private val count = job.count
        private val seed = job.seed
        private val drift = time * motes.drift

        // Camera relative, so the floats stay precise far from spawn.
        private val ax = (job.anchor.x - camera.x).toFloat()
        private val ay = (job.anchor.y - camera.y).toFloat()
        private val az = (job.anchor.z - camera.z).toFloat()
        private val lagX = (job.follow.x - job.anchor.x).toFloat()
        private val lagY = (job.follow.y - job.anchor.y).toFloat()
        private val lagZ = (job.follow.z - job.anchor.z).toFloat()

        private val px = FloatArray(count)
        private val py = FloatArray(count)
        private val pz = FloatArray(count)
        private val size = FloatArray(count)
        private val color = IntArray(count)
        private val shine = FloatArray(count)
        private val spin = Array(count) { Quaternionf() }
        private val tail = Array(count) { FloatArray(TAIL * 3) }
        private val at = Vector3f()

        init {
            for (i in 0 until count) {
                place(i, 0f, at)
                px[i] = at.x
                py[i] = at.y
                pz[i] = at.z
                for (k in 0 until TAIL) {
                    place(i, (k + 1) * 0.05f, at)
                    tail[i][k * 3] = at.x
                    tail[i][k * 3 + 1] = at.y
                    tail[i][k * 3 + 2] = at.z
                }
                val beat = 0.5f + hash(seed, i, 7) * 1.1f
                val tint = hash(seed, i, 8)
                val breath = 0.8f + 0.2f * sin(drift * (1.2f + hash(seed, i, 9)) + tint * TAU)
                size[i] = motes.size * (0.7f + 0.6f * hash(seed, i, 10)) * breath
                color[i] = motes.palette.colorAt(tint, time) and 0xFFFFFF
                shine[i] = max(0f, sin(drift * beat + tint * TAU)).pow(6f)
                val turn = drift * (0.6f + hash(seed, i, 11) * 1.4f)
                spin[i].rotationXYZ(hash(seed, i, 12) * TAU + turn, hash(seed, i, 13) * TAU + turn * 0.7f, hash(seed, i, 14) * TAU)
            }
        }

        /**
         * Where mote [i] is [back] seconds ago. Each has its own tilted orbit and bob, and hangs
         * back toward the swarm's lagging centre by its own amount.
         */
        private fun place(i: Int, back: Float, out: Vector3f) {
            val t = drift - back * motes.drift
            val ring = motes.radius * 0.5f * (0.35f + 0.65f * sqrt(hash(seed, i, 1)))
            val dir = if (hash(seed, i, 2) < 0.8f) 1f else -1f
            val rate = dir * (0.35f + hash(seed, i, 3) * 0.5f) / (0.4f + ring)
            val angle = hash(seed, i, 0) * TAU + t * rate
            val tilt = (hash(seed, i, 4) - 0.5f) * 1.1f
            val node = hash(seed, i, 5) * TAU
            val bob = sin(t * (0.5f + hash(seed, i, 6) * 0.6f) + i) * motes.height * 0.08f
            val level = (hash(seed, i, 15) - 0.5f) * motes.height * 0.6f

            // A flat circle, tipped by [tilt] about the line at [node].
            val cx = cos(angle) * ring
            val cz = sin(angle) * ring
            val nx = cos(node)
            val nz = sin(node)
            val along = cx * nx + cz * nz
            val across = -cx * nz + cz * nx
            val lift = across * sin(tilt)
            val flat = across * cos(tilt)
            val hang = 0.25f + 0.75f * hash(seed, i, 16)
            out.set(
                ax + along * nx - flat * nz + lagX * hang,
                ay + level + bob + lift + lagY * hang,
                az + along * nz + flat * nx + lagZ * hang,
            )
        }

        fun gems(fx: Fx) {
            for (i in 0 until count) {
                val r = size[i]
                fx.crystal(px[i], py[i], pz[i], spin[i], r * 0.8f, r * 1.7f, color[i] or 0xFF000000.toInt())
            }
        }

        fun glow(fx: Fx) {
            val additive = motes.glow
            for (i in 0 until count) {
                val r = size[i]
                val c = color[i] or 0xFF000000.toInt()
                val halo = fx.light(WorldFx.hot(c, 0.1f) and 0xFFFFFF or (((0.22f + 0.2f * shine[i]) * 255f).toInt() shl 24), additive)
                fx.soft(px[i], py[i], pz[i], r * 5f, halo)
                streak(fx, i, c, additive)
                if (shine[i] > 0.02f) {
                    fx.star(px[i], py[i], pz[i], r * (2.5f + 4f * shine[i]), fx.light(WorldFx.hot(c, 0.6f) and 0xFFFFFF or ((shine[i] * 230f).toInt() shl 24), additive))
                }
            }
        }

        /** The comet tail: a ribbon down the mote's own path, thinning and fading behind it. */
        private fun streak(fx: Fx, i: Int, color: Int, additive: Boolean) {
            var x0 = px[i]
            var y0 = py[i]
            var z0 = pz[i]
            var w0 = size[i] * 0.55f
            val head = fx.light(WorldFx.hot(color, 0.25f) and 0xFFFFFF or (0x90 shl 24), additive)
            var c0 = head
            val t = tail[i]
            for (k in 0 until TAIL) {
                val x1 = t[k * 3]
                val y1 = t[k * 3 + 1]
                val z1 = t[k * 3 + 2]
                val f = 1f - (k + 1f) / TAIL
                val w1 = size[i] * 0.55f * f
                val c1 = fx.dim(head, f * f)
                // Sideways across the path as seen from the camera.
                val dx = x1 - x0
                val dy = y1 - y0
                val dz = z1 - z0
                var sx = dy * z0 - dz * y0
                var sy = dz * x0 - dx * z0
                var sz = dx * y0 - dy * x0
                val len = sqrt(sx * sx + sy * sy + sz * sz)
                if (len > 1.0e-6f) {
                    sx /= len
                    sy /= len
                    sz /= len
                    fx.vertex(x0 + sx * w0, y0 + sy * w0, z0 + sz * w0, c0)
                    fx.vertex(x1 + sx * w1, y1 + sy * w1, z1 + sz * w1, c1)
                    fx.vertex(x1 - sx * w1, y1 - sy * w1, z1 - sz * w1, c1)
                    fx.vertex(x0 - sx * w0, y0 - sy * w0, z0 - sz * w0, c0)
                }
                x0 = x1
                y0 = y1
                z0 = z1
                w0 = w1
                c0 = c1
            }
        }
    }
}
