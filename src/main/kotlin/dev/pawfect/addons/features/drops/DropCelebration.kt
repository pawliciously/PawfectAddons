package dev.pawfect.addons.features.drops

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.features.drops.DropParser.Source
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SoundCue
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Celebrates your rare drops, harder the rarer they are. A drop winds up (for the rare ones),
 * lands with impact frames, then the item sits in the middle of the screen in a burst of light,
 * sparks and confetti with its rarity slammed in under it. One plays at a time; any more queue up.
 */
object DropCelebration {

    private val config get() = ModuleSwitch.view("dropfx", ConfigManager.features.dropCelebration)

    /** How a tier plays out. Times are in milliseconds. */
    class Feel(
        val windup: Float,
        val impacts: Int,
        val length: Float,
        val size: Float,
        val rays: Int,
        val sparks: Int,
        val confetti: Int,
        val rings: Int,
        val shake: Float,
        val flash: Float,
        val dim: Float,
        val letterScale: Float,
        val letterGap: Float,
    )

    class Show(val stack: ItemStack, val name: String, val tier: Tier, val feel: Feel, val seed: Int) {
        var startedAt = 0L
        var nextCue = 0
        val cues = DropCelebration.cues(tier, feel)

        fun elapsed(now: Long = System.nanoTime()): Float = (now - startedAt) / 1_000_000f

        /** When the item lands and the reveal starts, after the wind up and impact frames. */
        val impactAt: Float get() = feel.windup
        val revealAt: Float get() = feel.windup + feel.impacts * IMPACT_MS
    }

    class Cue(val at: Float, val id: String, val volume: Float, val pitch: Float)

    private val queue = ArrayDeque<Show>()
    private var playing: Show? = null
    private val recent = HashMap<String, Long>()
    private val random = Random(System.nanoTime())

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { current()?.let(::pump) } }
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { drawWorld(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    // ------------------------------------------------------------------ triggers

    fun onChat(message: Component) {
        if (!config.enabled) return
        val found = DropParser.parse(message.string.removeColor(), McCompat.mc.user.name) ?: return
        val wanted = when (found.source) {
            Source.DUNGEON -> config.dungeonLoot
            Source.DYE -> config.dyes
            Source.SLAYER -> config.slayerDrops
        }
        if (!wanted || found.tier < config.minTier) return
        // A chest can list the same drop twice, flagged and plain.
        val now = System.currentTimeMillis()
        val key = found.name.lowercase()
        if (now - (recent[key] ?: 0L) < DEDUPE_MS) return
        recent[key] = now
        celebrate(found.name, found.id, found.tier)
    }

    fun celebrate(name: String, id: String?, tier: Tier) {
        val repoId = id?.takeIf { NeuRepo.item(it) != null } ?: NeuRepo.resolve(name)?.internalName
        val stack = repoId?.let { NeuRepo.itemStack(it) }?.takeIf { !it.isEmpty } ?: ItemStack(Items.NETHER_STAR)
        val display = repoId?.let { NeuRepo.displayName(it) }?.takeIf { it.removeColor().isNotBlank() } ?: "§f$name"
        if (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast(Show(stack, display, tier, feel(tier), random.nextInt()))
    }

    // ------------------------------------------------------------------ dev tests

    fun testTier(tier: Tier) {
        val drop = DropTable.sample(tier)
        celebrate(drop.name, drop.id, tier)
    }

    fun testAll() = Tier.entries.forEach(::testTier)

    fun testItem(query: String) {
        val drop = DropTable.find(query) ?: DropTable.findId(query)
        if (drop != null) {
            celebrate(drop.name, drop.id, drop.tier)
            ChatUtils.chat("§7Playing §f${drop.name} §7as §f${drop.tier}§7.")
            return
        }
        val item = NeuRepo.resolve(query)
        if (item == null) {
            ChatUtils.chat("§cNo item called §f$query§c.")
            return
        }
        // Dyes count as insane wherever they're from.
        val tier = if (item.internalName.endsWith("_DYE")) Tier.INSANE else Tier.RARE
        celebrate(item.displayName.removeColor(), item.internalName, tier)
        ChatUtils.chat("§7Not in the drop table, so playing §f${item.displayName.removeColor()} §7as §f$tier§7.")
    }

    /** Runs a line through the chat parser as if it came from Hypixel. A leading ~ stands for the indent. */
    fun testChat(line: String) {
        val me = McCompat.mc.user.name
        val text = line.replace("{me}", me).replaceFirst(Regex("^~\\s*"), "    ")
        val found = DropParser.parse(text, me)
        if (found == null) {
            ChatUtils.chat("§cNo drop in §f\"$text\"§c.")
            return
        }
        ChatUtils.chat("§7Read §f${found.name} §7(${found.source.name.lowercase()}, §f${found.tier}§7).")
        celebrate(found.name, found.id, found.tier)
    }

    // ------------------------------------------------------------------ playing

    /** The one playing now, starting the next in line when it's done. */
    fun current(): Show? {
        val now = System.nanoTime()
        var show = playing
        if (show != null && show.elapsed(now) >= show.feel.length) {
            playing = null
            show = null
        }
        if (show == null) {
            show = queue.removeFirstOrNull() ?: return null
            show.startedAt = now
            playing = show
        }
        return show
    }

    /** Plays every sound that's come due, from the HUD's frames or the tick, whichever's first. */
    fun pump(show: Show) {
        val t = show.elapsed()
        val volume = config.volume.coerceIn(0f, 1f)
        while (show.nextCue < show.cues.size && show.cues[show.nextCue].at <= t) {
            val cue = show.cues[show.nextCue++]
            if (volume > 0f) SoundCue.play(cue.id, cue.volume * volume, cue.pitch)
        }
    }

    fun impactFrames(): Boolean = config.impactFrames

    /** How hard everything shakes right now: building through the wind up, hardest on impact, then settling. */
    fun shakeAmount(show: Show, t: Float): Float {
        val f = show.feel
        return when {
            t < show.impactAt -> f.shake * 0.25f * (t / f.windup.coerceAtLeast(1f))
            t < show.revealAt + 40f -> f.shake * 1.4f
            else -> f.shake * exp(-(t - show.revealAt) / 260f)
        }
    }

    fun noise(t: Float, channel: Int): Float =
        sin(t * 0.091f + channel * 1.7f) * 0.6f + sin(t * 0.173f + channel * 4.1f) * 0.4f

    /** Shakes the camera, called just before vanilla's own hurt shake. */
    @JvmStatic
    fun shake(pose: PoseStack) {
        if (!config.shake) return
        val show = playing ?: return
        val t = show.elapsed()
        if (t >= show.feel.length) return
        val amount = shakeAmount(show, t) * 2.2f
        if (amount < 0.01f) return
        pose.mulPose(Axis.ZP.rotationDegrees(amount * noise(t, 7)))
        pose.mulPose(Axis.XP.rotationDegrees(amount * 0.6f * noise(t, 11)))
    }

    // ------------------------------------------------------------------ world

    /** For the rarest: rings rippling out across the ground round you and lights spiralling up past you. */
    private fun drawWorld(poseStack: PoseStack, collector: net.minecraft.client.renderer.SubmitNodeCollector) {
        if (!config.worldEffects) return
        val show = playing ?: return
        if (show.tier < Tier.CRAZY_RARE) return
        val r = show.elapsed() - show.revealAt
        if (r < 0f || r > show.feel.length) return
        val client = McCompat.mc
        val player = client.player ?: return
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val eye = client.gameRenderer.mainCamera().position()
        val feet = player.getPosition(partial)
        val x = (feet.x - eye.x).toFloat()
        val y = (feet.y - eye.y).toFloat()
        val z = (feet.z - eye.z).toFloat()
        val left = show.feel.length - show.revealAt
        val out = 1f - smooth((r - (left - 600f)) / 600f)
        val color = DropColors.of(show.tier)
        val glow = DropColors.glowOf(show.tier)
        WorldFx.submit(poseStack, collector, 1f, solids = {}, lights = { fx ->
            for (j in 0 until 3) {
                val q = ((r + j * 420f) % 1260f) / 1260f
                val radius = 0.4f + q * 7f
                val a = (1f - q) * (1f - q) * out
                fx.band(x, y + 0.05f, z, radius, radius + 0.35f, fx.light(argb(color, a * 0.7f), true), fx.light(argb(glow, 0f), true))
            }
            val count = if (show.tier == Tier.INSANE) 28 else 16
            for (i in 0 until count) {
                val phase = ((r / 2600f) + WorldFx.hash(show.seed, i, 0)) % 1f
                val angle = WorldFx.hash(show.seed, i, 1) * WorldFx.TAU + r / 700f
                val spread = 1.1f + WorldFx.hash(show.seed, i, 2) * 0.9f
                val px = x + cos(angle) * spread
                val py = y + phase * 9f
                val pz = z + sin(angle) * spread
                val a = min(phase * 6f, 1f) * (1f - phase) * out
                val c = if (i % 3 == 0) 0xFFFFFF else if (i % 3 == 1) color else glow
                fx.soft(px, py, pz, 0.16f, fx.light(argb(c, a * 0.5f), true), 8)
                fx.soft(px, py, pz, 0.05f, fx.light(argb(WorldFx.hot(c, 0.7f), a), true), 6)
            }
        })
    }

    // ------------------------------------------------------------------ tiers

    private fun feel(tier: Tier): Feel {
        val frames = config.impactFrames
        return when (tier) {
            Tier.RARE -> Feel(0f, if (frames) 1 else 0, 2600f, 3.2f, 0, 34, 0, 1, 0.3f, 0.35f, 0.25f, 1.7f, 34f)
            Tier.VERY_RARE -> Feel(0f, if (frames) 2 else 0, 3300f, 4f, 8, 60, 40, 2, 0.55f, 0.5f, 0.35f, 2.1f, 32f)
            Tier.CRAZY_RARE -> Feel(380f, if (frames) 3 else 0, 4200f, 4.8f, 12, 90, 80, 2, 0.95f, 0.65f, 0.45f, 2.5f, 30f)
            Tier.INSANE -> Feel(760f, if (frames) 4 else 0, 5600f, 5.6f, 16, 140, 130, 3, 1.5f, 0.8f, 0.55f, 3f, 26f)
        }
    }

    private fun cues(tier: Tier, feel: Feel): List<Cue> {
        val list = ArrayList<Cue>()
        val hit = feel.windup
        if (feel.windup > 0f) {
            // The build: a low hum and notes climbing to the hit.
            list += Cue(0f, "minecraft:block.beacon.power_select", 0.6f, 0.5f)
            val steps = (feel.windup / 55f).toInt()
            for (i in 0 until steps) {
                val k = i / steps.toFloat()
                list += Cue(i * 55f, "minecraft:block.note_block.pling", 0.3f + 0.5f * k, 0.5f + 1.5f * k)
            }
        }
        when (tier) {
            Tier.RARE -> {
                list += Cue(hit, "minecraft:block.note_block.bell", 0.8f, 1.2f)
                list += Cue(hit, "minecraft:entity.experience_orb.pickup", 0.7f, 0.8f)
                list += Cue(hit + 120f, "minecraft:block.amethyst_block.chime", 1f, 1.2f)
                list += Cue(hit + 260f, "minecraft:block.note_block.pling", 0.6f, 2f)
            }
            Tier.VERY_RARE -> {
                list += Cue(hit, "minecraft:item.trident.thunder", 0.45f, 1.4f)
                list += Cue(hit, "minecraft:block.note_block.bell", 0.9f, 1f)
                list += Cue(hit + 150f, "minecraft:ui.toast.challenge_complete", 0.7f, 1.2f)
                list += Cue(hit + 320f, "minecraft:entity.firework_rocket.twinkle", 0.6f, 1f)
            }
            Tier.CRAZY_RARE -> {
                list += Cue(hit, "minecraft:entity.generic.explode", 0.6f, 0.6f)
                list += Cue(hit, "minecraft:item.totem.use", 0.7f, 1f)
                list += Cue(hit + 160f, "minecraft:ui.toast.challenge_complete", 0.9f, 1f)
                list += Cue(hit + 260f, "minecraft:entity.firework_rocket.large_blast", 0.8f, 0.9f)
                list += Cue(hit + 520f, "minecraft:entity.firework_rocket.twinkle", 0.8f, 1.1f)
                list += Cue(hit + 900f, "minecraft:entity.firework_rocket.twinkle_far", 0.7f, 1.3f)
            }
            Tier.INSANE -> {
                list += Cue(hit, "minecraft:block.end_portal.spawn", 0.8f, 1f)
                list += Cue(hit, "minecraft:entity.warden.sonic_boom", 0.7f, 0.8f)
                list += Cue(hit, "minecraft:entity.lightning_bolt.thunder", 0.35f, 1.6f)
                list += Cue(hit, "minecraft:item.totem.use", 0.8f, 0.9f)
                list += Cue(hit + 220f, "minecraft:ui.toast.challenge_complete", 1f, 1f)
                list += Cue(hit + 320f, "minecraft:entity.firework_rocket.large_blast", 0.9f, 1f)
                list += Cue(hit + 520f, "minecraft:entity.firework_rocket.large_blast_far", 0.8f, 0.8f)
                list += Cue(hit + 700f, "minecraft:entity.firework_rocket.twinkle", 0.9f, 1f)
                list += Cue(hit + 950f, "minecraft:entity.firework_rocket.twinkle", 0.8f, 1.2f)
                list += Cue(hit + 1250f, "minecraft:entity.player.levelup", 0.7f, 1.5f)
                list += Cue(hit + 1600f, "minecraft:entity.firework_rocket.twinkle_far", 0.8f, 1.4f)
            }
        }
        return list.sortedBy { it.at }
    }

    fun argb(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    const val IMPACT_MS = 55f
    private const val DEDUPE_MS = 5_000L
    private const val MAX_QUEUE = 6
}
