package dev.pawfect.addons.features.visual

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A CS style inspect for whatever you're holding: it comes up and turns to face you, flips over
 * to show the other side, then twirls the rest of the way round and drops back into your hand.
 * All keyframes in code, smoothed through so it never stops dead between them. Swinging, using
 * the item or switching off it eases it straight back.
 */
object HandInspect {

    private val config get() = ModuleSwitch.view("visuals", ConfigManager.features.handInspect)

    /** One pose: where the hand sits (blocks) and how the item's turned (degrees), mirrored for a left hand. */
    private class Key(val t: Float, vararg val v: Float)

    private val KEYS = arrayOf(
        //   time   x       y      z      roll  pitch  spin
        Key(0.00f, 0f, 0f, 0f, 0f, 0f, 0f),
        Key(0.16f, -0.14f, 0.07f, 0.08f, 22f, 8f, 38f),
        Key(0.40f, -0.16f, 0.08f, 0.09f, 26f, 14f, 50f),
        Key(0.56f, -0.12f, 0.12f, 0.06f, 32f, 6f, 210f),
        Key(0.62f, -0.12f, 0.11f, 0.06f, 28f, 10f, 225f),
        Key(0.80f, -0.13f, 0.10f, 0.07f, 24f, 16f, 232f),
        Key(1.00f, 0f, 0f, 0f, 0f, 0f, 360f),
    )
    private const val CHANNELS = 6
    private const val SPIN = 5

    /** Each key's slope per channel, so the curve flows through the keys instead of stopping at each. */
    private val slopes: Array<FloatArray> = Array(KEYS.size) { i ->
        FloatArray(CHANNELS) { c ->
            if (i == 0 || i == KEYS.size - 1) 0f
            else (KEYS[i + 1].v[c] - KEYS[i - 1].v[c]) / (KEYS[i + 1].t - KEYS[i - 1].t)
        }
    }

    private var startedAt = -1L
    private var stoppedAt = -1L
    private var slot = -1
    private val pose = FloatArray(CHANNELS)
    private val frozen = FloatArray(CHANNELS)

    fun start() {
        if (!config.enabled) return
        val client = McCompat.mc
        val player = client.player ?: return
        if (!client.options.cameraType.isFirstPerson || player.mainHandItem.isEmpty || player.isUsingItem) return
        // Mashing the key doesn't restart it halfway through.
        if (playing()) return
        startedAt = System.nanoTime()
        stoppedAt = -1L
        slot = player.inventory.selectedSlot
    }

    private fun playing(): Boolean = startedAt >= 0 && stoppedAt < 0 && progress(System.nanoTime()) < 1f

    private fun progress(now: Long): Float {
        val seconds = (now - startedAt) / 1_000_000_000f
        return seconds * config.speed.coerceIn(0.25f, 4f) / DURATION
    }

    @JvmStatic
    fun apply(stack: PoseStack, player: AbstractClientPlayer, hand: InteractionHand, swing: Float) {
        if (startedAt < 0 || hand != InteractionHand.MAIN_HAND) return
        val now = System.nanoTime()
        if (stoppedAt < 0) {
            val p = progress(now)
            if (p >= 1f || !config.enabled) {
                startedAt = -1L
                return
            }
            evaluate(p)
            if (swing > 0f || player.isUsingItem || player.inventory.selectedSlot != slot || player.mainHandItem.isEmpty) {
                // Let go from wherever it is, spun the short way home.
                stoppedAt = now
                pose.copyInto(frozen)
                frozen[SPIN] -= 360f * (frozen[SPIN] / 360f).roundToInt()
            }
        }
        if (stoppedAt >= 0) {
            val k = 1f - (now - stoppedAt) / 1_000_000f / LET_GO_MS
            if (k <= 0f) {
                startedAt = -1L
                stoppedAt = -1L
                return
            }
            val ease = k * k * (3f - 2f * k)
            for (c in 0 until CHANNELS) pose[c] = frozen[c] * ease
        }
        val side = if (player.mainArm == HumanoidArm.RIGHT) 1f else -1f
        stack.translate(pose[0] * side, pose[1], pose[2])
        stack.mulPose(Axis.ZP.rotationDegrees(pose[3] * side))
        stack.mulPose(Axis.XP.rotationDegrees(pose[4]))
        stack.mulPose(Axis.YP.rotationDegrees(pose[5] * side))
    }

    private fun evaluate(p: Float) {
        var i = 0
        while (i < KEYS.size - 2 && p >= KEYS[i + 1].t) i++
        val a = KEYS[i]
        val b = KEYS[i + 1]
        val h = b.t - a.t
        val u = ((p - a.t) / h).coerceIn(0f, 1f)
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2 * u3 - 3 * u2 + 1
        val h10 = u3 - 2 * u2 + u
        val h01 = -2 * u3 + 3 * u2
        val h11 = u3 - u2
        // A slow sway on top while it's up, so it never looks frozen in place.
        val up = sin(p * PI.toFloat())
        for (c in 0 until CHANNELS) {
            pose[c] = h00 * a.v[c] + h10 * h * slopes[i][c] + h01 * b.v[c] + h11 * h * slopes[i + 1][c]
        }
        pose[1] += sin(p * 9f) * 0.006f * up
        pose[3] += sin(p * 7f + 1f) * 1.5f * up
    }

    private const val DURATION = 2.4f
    private const val LET_GO_MS = 160f
}
