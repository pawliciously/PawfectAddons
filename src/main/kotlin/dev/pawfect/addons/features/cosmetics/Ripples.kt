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
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A landing that blooms: a crown of crystal shards breaks out of the ground and sinks back, two
 * rings of light run outward with a low glowing wall on the leading one, and sparks fly up and
 * fall. Harder landings bloom bigger.
 */
object Ripples {

    private const val VIEW_LIMIT = 72.0
    private const val MAX_PLAYERS = 48
    private const val MAX_LIVE = 32
    private const val GROUND_LIFT = 0.02
    private const val IDLE_MS = 8_000L
    private const val RISE = 0.12f
    private const val SHARDS = 14
    private const val SPARKS = 22
    private const val GRAVITY = 7f

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

        val rings = live.filter { it.center.distanceToSqr(camera) <= VIEW_LIMIT * VIEW_LIMIT }.map { Bloom(it, camera, now, time) }
        if (rings.isEmpty()) return

        WorldFx.submit(
            poseStack, collector, WorldFx.shade(level, rings.first().ring.center.add(0.0, 0.5, 0.0)),
            solids = { fx -> rings.forEach { it.shards(fx) } },
            lights = { fx -> rings.forEach { it.light(fx) } },
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

    /** One landing worked out for this frame. */
    internal class Bloom(val ring: Ring, camera: Vec3, now: Long, private val time: Float) {
        private val ripple = ring.ripple
        private val palette = ring.trail.palette
        private val power = ring.power
        private val seed = ring.seed * 7919 + 17
        private val age = ((now - ring.bornAt).toFloat() / ring.lifeMs).coerceIn(0f, 1f)
        private val seconds = (now - ring.bornAt) / 1000f

        /** How far out the front has got, easing off as it spreads. */
        private val front = (age * ripple.speed).coerceIn(0f, 1f).pow(0.55f) * ripple.size * power
        private val fade = envelope(age) * power
        private val scale = sqrt(ripple.size / 1.6f) * power

        private val cx = (ring.center.x - camera.x).toFloat()
        private val cy = (ring.center.y - camera.y).toFloat()
        private val cz = (ring.center.z - camera.z).toFloat()
        private val spin = Quaternionf()

        /** Shards burst up to full height early, hold, then sink back into the ground and shrink. */
        fun shards(fx: Fx) {
            if (front <= 0.01f) return
            for (i in 0 until SHARDS) {
                val delay = hash(seed, i, 1) * 0.12f
                val local = ((age - delay) / (1f - delay)).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val pop = sin(PI.toFloat() * (local / 0.35f).coerceAtMost(1f) * 0.5f)
                val sink = if (local < 0.55f) 1f else (1f - (local - 0.55f) / 0.45f).coerceAtLeast(0f)
                val grow = pop * sink
                if (grow <= 0.01f) continue
                val a = (i + hash(seed, i, 2) * 0.6f) / SHARDS * TAU
                val r = front * (0.78f + hash(seed, i, 3) * 0.12f)
                val tall = (0.14f + hash(seed, i, 4) * 0.12f) * scale
                val wide = tall * (0.32f + hash(seed, i, 5) * 0.1f)
                val lean = 0.35f + hash(seed, i, 6) * 0.25f
                // Lean outward from the centre, with a slight twist so no two catch the light alike.
                spin.identity().rotateY(-a).rotateZ(-lean).rotateY(hash(seed, i, 7) * TAU)
                val base = palette.colorAt(i / SHARDS.toFloat(), time) or 0xFF000000.toInt()
                fx.crystal(
                    cx + cos(a) * r, cy - tall * 0.9f + tall * 1.25f * grow, cz + sin(a) * r,
                    spin, wide * grow, tall * grow, base, 0.9f,
                )
            }
        }

        fun light(fx: Fx) {
            if (fade <= 0.003f) return
            val additive = ring.trail.glow
            val y = cy
            val thick = ripple.thickness * power
            val c = palette.colorAt(age, time) or 0xFF000000.toInt()
            val rim = fx.light(WorldFx.hot(c, 0.3f) and 0xFFFFFF or ((fade * 255f).toInt().coerceIn(0, 255) shl 24), additive)
            val body = fx.light(c and 0xFFFFFF or ((fade * 0.45f * 255f).toInt().coerceIn(0, 255) shl 24), additive)

            // The leading ring: soft inside, a hot rim, a quick falloff outside.
            fx.band(cx, y, cz, (front - thick * 2.2f).coerceAtLeast(0f), front - thick * 0.25f, 0, body)
            fx.band(cx, y, cz, front - thick * 0.25f, front, body, rim)
            fx.band(cx, y + 0.002f, cz, front, front + thick * 0.6f, rim, 0)

            // A second, slower ring following it in.
            val inner = front * 0.6f
            val trailRing = fx.dim(body, 0.7f)
            fx.band(cx, y + 0.001f, cz, (inner - thick).coerceAtLeast(0f), inner, 0, trailRing)
            fx.band(cx, y + 0.001f, cz, inner, inner + thick * 0.4f, trailRing, 0)

            // The glowing wall on the leading ring, lower as it spreads.
            val wall = (0.42f * scale) * (1f - age).pow(0.8f)
            fx.wall(cx, cz, front, y, y + wall, fx.dim(rim, 0.55f), 0)

            // The flash of the impact.
            val flash = (1f - age / 0.25f).coerceIn(0f, 1f)
            if (flash > 0f) {
                val hot = fx.light(WorldFx.hot(c, 0.6f) and 0xFFFFFF or ((flash * power * 200f).toInt() shl 24), additive)
                fx.soft(cx, y + 0.15f, cz, (0.5f + 0.4f * age) * scale, hot, 14)
                fx.band(cx, y + 0.003f, cz, 0f, 0.9f * scale, hot, 0)
            }

            sparks(fx, c, additive)
        }

        /** Thrown out and up from the impact, falling back as they go out. */
        private fun sparks(fx: Fx, c: Int, additive: Boolean) {
            val reach = ripple.size * power * ripple.speed
            for (i in 0 until SPARKS) {
                val a = hash(seed, i, 20) * TAU
                val out = reach * (0.55f + hash(seed, i, 21) * 0.6f)
                val upward = (2.2f + hash(seed, i, 22) * 2.2f) * scale
                val t = seconds * (0.8f + hash(seed, i, 23) * 0.4f)
                val h = upward * t - 0.5f * GRAVITY * t * t
                if (h < -0.05f) continue
                val d = out * (1f - (1f - (t / ripple.life).coerceIn(0f, 1f)).pow(2f))
                val glow = (1f - age) * (0.5f + 0.5f * sin(time * 9f + i * 1.7f).let { it * it })
                val base = palette.colorAt(hash(seed, i, 24), time) or 0xFF000000.toInt()
                val col = fx.light(WorldFx.hot(base, 0.5f) and 0xFFFFFF or ((glow * power * 255f).toInt().coerceIn(0, 255) shl 24), additive)
                fx.star(cx + cos(a) * d, cy + h.coerceAtLeast(0f), cz + sin(a) * d, (0.06f + 0.05f * hash(seed, i, 25)) * scale, col)
            }
        }

        private fun envelope(age: Float): Float {
            val rise = (age / RISE).coerceIn(0f, 1f)
            val fall = ((1f - age) / (1f - RISE)).coerceIn(0f, 1f)
            return rise * fall.pow(1.15f)
        }
    }
}
