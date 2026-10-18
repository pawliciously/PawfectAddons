package dev.pawfect.addons.features.drops

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.DropCelebrationConfig.Style
import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.features.drops.DropParser.Source
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SoundCue
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

object DropCelebration {

    private val config get() = ModuleSwitch.view("dropfx", ConfigManager.features.dropCelebration)

    class Feel(
        val windup: Float,
        val heartbeats: FloatArray,
        val impacts: Int,
        val length: Float,
        val size: Float,
        val rays: Int,
        val sparks: Int,
        val confetti: Int,
        val waves: Int,
        val fireworks: Int,
        val rings: Int,
        val shake: Float,
        val flash: Float,
        val dim: Float,
        val letterScale: Float,
        val letterGap: Float,
        val aftershock: Boolean,
        val lightning: Boolean,
        val fanfare: Boolean,
    )

    class Show(
        val stack: ItemStack,
        val name: String,
        val tier: Tier,
        val feel: Feel,
        val seed: Int,
        val signature: Signature,
        val extra: Int,
        val color: Int,
        val glow: Int,
    ) {
        var clock = 0f
        private var lastNanos = 0L
        var nextCue = 0
        val cues = DropCelebration.cues(this)

        fun elapsed(now: Long = System.nanoTime()): Float {
            if (lastNanos != 0L && !DropCelebration.paused) clock += (now - lastNanos) / 1_000_000f * DropCelebration.speed()
            lastNanos = now
            return clock
        }

        fun seek(ms: Float) {
            clock = ms.coerceIn(0f, feel.length - 1f)
            nextCue = cues.indexOfFirst { it.at >= clock }.let { if (it < 0) cues.size else it }
        }

        val impactAt: Float get() = feel.windup

        val revealAt: Float get() = feel.windup + feel.impacts * IMPACT_MS

        val aftershockAt: Float get() = revealAt + LETTERS_START + DropColors.label(tier).length * feel.letterGap + 110f

        val outroAt: Float get() = feel.length - OUTRO_MS
    }

    class Cue(val at: Float, val id: String, val volume: Float, val pitch: Float)

    private val queue = ArrayDeque<Show>()
    private var playing: Show? = null
    private val recent = HashMap<String, Long>()
    private val random = Random(System.nanoTime())

    fun register() {
        PictureInPictureRendererRegistry.register { DropItemRenderer() }
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { current()?.let(::pump) } }
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { drawWorld(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    fun speed(): Float = devSpeed ?: config.speed.coerceIn(0.25f, 3f)

    var devSpeed: Float? = null
    var paused = false
    private var last: Triple<String, String?, Tier>? = null
    private var lastSignature: Signature? = null
    private var lastExtra = 0

    fun onChat(message: Component) {
        if (!config.enabled) return
        val found = DropParser.parse(message.string.removeColor(), McCompat.mc.user.name) ?: return
        val wanted = when (found.source) {
            Source.DUNGEON -> config.dungeonLoot
            Source.DYE -> config.dyes
            Source.SLAYER -> config.slayerDrops
        }
        if (!wanted || found.tier < config.minTier) return
        val now = System.currentTimeMillis()
        val key = found.name.lowercase()
        if (now - (recent[key] ?: 0L) < DEDUPE_MS) return
        recent[key] = now
        when (config.style) {
            Style.CELEBRATION -> celebrate(found.name, found.id, found.tier)
            Style.ITEM_GET -> ItemGet.queue(found.name, found.id)
        }
    }

    fun volume(): Float = config.volume.coerceIn(0f, 1f)

    fun celebrate(name: String, id: String?, tier: Tier, signature: Signature? = null, extra: Int? = null) {
        val repoId = id?.takeIf { NeuRepo.item(it) != null } ?: NeuRepo.resolve(name)?.internalName
        val stack = repoId?.let { NeuRepo.itemStack(it) }?.takeIf { !it.isEmpty } ?: ItemStack(Items.NETHER_STAR)
        val display = repoId?.let { NeuRepo.displayName(it) }?.takeIf { it.removeColor().isNotBlank() } ?: "§f$name"
        val sig = signature ?: DropSignatures.of(name, repoId ?: id)
        val count = extra ?: DropSignatures.starCount(repoId ?: id)
        val color = if (sig == Signature.DYE) DropSignatures.dyeColor(name, stack) else DropColors.of(tier)
        val glow = if (sig == Signature.DYE) Theme.mix(color, 0xFFFFFF, 0.5f) and 0xFFFFFF else DropColors.glowOf(tier)
        last = Triple(name, id, tier)
        lastSignature = signature
        lastExtra = count
        if (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast(Show(stack, display, tier, feel(tier), random.nextInt(), sig, count, color, glow))
    }

    fun skip() {
        playing = null
        queue.clear()
        paused = false
    }

    fun replay() {
        val (name, id, tier) = last ?: return ChatUtils.chat("§cNothing's played yet.")
        skip()
        celebrate(name, id, tier, lastSignature, lastExtra)
    }

    fun togglePause() {
        paused = !paused
        ChatUtils.chat(if (paused) "§7Paused at §f${playing?.clock?.toInt() ?: 0}ms§7." else "§7Playing.")
    }

    fun seek(ms: Float) {
        val show = playing ?: current() ?: return ChatUtils.chat("§cNothing's playing.")
        show.seek(ms)
        paused = true
        ChatUtils.chat("§7Paused at §f${show.clock.toInt()}ms §7(${phase(show, show.clock)}).")
    }

    fun setSpeed(speed: Float?) {
        devSpeed = speed
        ChatUtils.chat(if (speed == null) "§7Back to the setting's speed." else "§7Playing at §f${speed}x§7 until you reset it.")
    }

    fun info() {
        val show = playing ?: return ChatUtils.chat("§cNothing's playing.")
        ChatUtils.chat("§f${show.name.removeColor()} §7as §f${show.tier}§7, signature §f${show.signature.id}§7${if (show.signature == Signature.STARS) " (${show.extra})" else ""}.")
        ChatUtils.chat("§7Now §f${show.clock.toInt()}ms §7(${phase(show, show.clock)}) of §f${show.feel.length.toInt()}ms§7.")
        ChatUtils.chat("§7Lands §f${show.impactAt.toInt()} §7· reveal §f${show.revealAt.toInt()} §7· second hit §f${if (show.feel.aftershock) show.aftershockAt.toInt() else "-"} §7· outro §f${show.outroAt.toInt()}")
    }

    private fun phase(show: Show, t: Float): String = when {
        t < show.impactAt -> "wind up"
        t < show.revealAt -> "impact frames"
        t < show.outroAt -> "reveal"
        else -> "outro"
    }

    fun testSignature(signature: Signature, tier: Tier?) {
        val (name, id) = when (signature) {
            Signature.NONE -> "Spirit Bone" to "SPIRIT_BONE"
            Signature.DYE -> "Necron Dye" to "NECRON_DYE"
            Signature.GIANT -> "Giant's Sword" to "GIANTS_SWORD"
            Signature.RECOMB -> "Recombobulator 3000" to "RECOMBOBULATOR_3000"
            Signature.STARS -> "Fifth Master Star" to "FIFTH_MASTER_STAR"
            Signature.NECRON -> "Necron's Handle" to "NECRON_HANDLE"
        }
        val playAs = tier ?: DropTable.find(name)?.tier ?: if (signature == Signature.DYE) Tier.INSANE else Tier.RARE
        celebrate(name, id, playAs, signature)
        ChatUtils.chat("§7Playing §f${signature.id} §7on §f$name §7as §f$playAs§7.")
    }

    fun testDye(name: String) {
        val full = if (name.trim().endsWith(" Dye", ignoreCase = true)) name.trim() else name.trim() + " Dye"
        val id = NeuRepo.resolve(full)?.internalName
        celebrate(full, id, Tier.INSANE, Signature.DYE)
        val stack = id?.let { NeuRepo.itemStack(it) } ?: ItemStack(Items.NETHER_STAR)
        ChatUtils.chat("§7Playing §f$full §7in §f#%06X§7.".format(DropSignatures.dyeColor(full, stack)))
    }

    fun testStars(count: Int) {
        val names = arrayOf("First", "Second", "Third", "Fourth", "Fifth")
        val name = names[count - 1] + " Master Star"
        val drop = DropTable.find(name) ?: return
        celebrate(drop.name, drop.id, drop.tier, Signature.STARS, count)
    }

    fun list() {
        ChatUtils.chat("§7Drops and their signatures:")
        for (name in DropTable.names) {
            val drop = DropTable.find(name) ?: continue
            val sig = DropSignatures.of(drop.name, drop.id)
            ChatUtils.chat("  §f${drop.name} §7${drop.tier}${if (sig != Signature.NONE) " §d${sig.id}" else ""}")
        }
        ChatUtils.chat("  §7Any dye: §fInsane §ddye")
    }

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
        val tier = if (item.internalName.endsWith("_DYE")) Tier.INSANE else Tier.RARE
        celebrate(item.displayName.removeColor(), item.internalName, tier)
        ChatUtils.chat("§7Not in the drop table, so playing §f${item.displayName.removeColor()} §7as §f$tier§7.")
    }

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

    fun current(): Show? {
        val now = System.nanoTime()
        var show = playing
        if (show != null && show.elapsed(now) >= show.feel.length) {
            playing = null
            show = null
        }
        if (show == null) {
            show = queue.removeFirstOrNull() ?: return null
            show.elapsed(now)
            playing = show
        }
        return show
    }

    fun pump(show: Show) {
        val t = show.elapsed()
        val volume = config.volume.coerceIn(0f, 1f)
        while (show.nextCue < show.cues.size && show.cues[show.nextCue].at <= t) {
            val cue = show.cues[show.nextCue++]
            if (volume > 0f) SoundCue.play(cue.id, cue.volume * volume, cue.pitch)
        }
    }

    fun impactFrames(): Boolean = config.impactFrames

    fun shakeAmount(show: Show, t: Float): Float {
        val f = show.feel
        var amount = when {
            t < show.impactAt -> f.shake * 0.25f * (t / f.windup.coerceAtLeast(1f)) + heartbeatKick(show, t) * f.shake * 0.5f
            t < show.revealAt + 40f -> f.shake * 1.4f
            else -> f.shake * exp(-(t - show.revealAt) / 260f)
        }
        if (f.aftershock && t >= show.aftershockAt) amount += f.shake * exp(-(t - show.aftershockAt) / 200f)
        return amount
    }

    fun heartbeatKick(show: Show, t: Float): Float {
        var kick = 0f
        for (beat in show.feel.heartbeats) {
            val since = t - beat
            if (since in 0f..400f) kick = max(kick, exp(-since / 90f))
        }
        return kick
    }

    fun fireworkLaunch(show: Show, k: Int): Float {
        val n = show.feel.fireworks
        val span = (show.outroAt - show.revealAt - 1800f).coerceAtLeast(600f)
        return 700f + k * span / max(1, n) + WorldFx.hash(show.seed, k, 40) * 220f
    }

    fun noise(t: Float, channel: Int): Float =
        sin(t * 0.091f + channel * 1.7f) * 0.6f + sin(t * 0.173f + channel * 4.1f) * 0.4f

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

    private fun drawWorld(poseStack: PoseStack, collector: SubmitNodeCollector) {
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
        val out = 1f - smooth((r - (left - 800f)) / 800f)
        val color = show.color
        val glow = show.glow
        WorldFx.submit(poseStack, collector, 1f, solids = {}, lights = { fx ->
            for (j in 0 until 3) {
                val q = ((r + j * 420f) % 1260f) / 1260f
                val radius = 0.4f + q * 7f
                val a = (1f - q) * (1f - q) * out
                fx.band(x, y + 0.05f, z, radius, radius + 0.35f, fx.light(argb(color, a * 0.7f), true), fx.light(argb(glow, 0f), true))
            }
            val count = if (show.tier == Tier.INSANE) 32 else 18
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

    private fun feel(tier: Tier): Feel {
        val frames = config.impactFrames
        return when (tier) {
            Tier.RARE -> Feel(
                0f, floatArrayOf(), if (frames) 1 else 0, 3800f, 3.4f,
                6, 44, 0, 0, 1, 1, 0.3f, 0.35f, 0.3f, 1.8f, 34f,
                aftershock = false, lightning = false, fanfare = false,
            )
            Tier.VERY_RARE -> Feel(
                520f, floatArrayOf(0f), if (frames) 2 else 0, 5400f, 4.2f,
                8, 70, 50, 1, 2, 2, 0.6f, 0.5f, 0.4f, 2.2f, 32f,
                aftershock = false, lightning = false, fanfare = true,
            )
            Tier.CRAZY_RARE -> Feel(
                1150f, floatArrayOf(0f, 560f), if (frames) 3 else 0, 7400f, 5f,
                12, 100, 80, 2, 4, 2, 1f, 0.65f, 0.5f, 2.6f, 30f,
                aftershock = true, lightning = false, fanfare = true,
            )
            Tier.INSANE -> Feel(
                1800f, floatArrayOf(0f, 620f, 1080f, 1420f, 1650f), if (frames) 5 else 0, 9800f, 5.8f,
                16, 150, 110, 3, 8, 3, 1.6f, 0.8f, 0.6f, 3f, 26f,
                aftershock = true, lightning = true, fanfare = true,
            )
        }
    }

    private fun cues(show: Show): List<Cue> {
        val f = show.feel
        val list = ArrayList<Cue>()
        val hit = f.windup
        val reveal = show.revealAt
        if (f.windup > 0f) {
            list += Cue(0f, "minecraft:block.beacon.power_select", 0.6f, 0.5f)
            for (beat in f.heartbeats) list += Cue(beat, "minecraft:entity.warden.heartbeat", 1f, 1f)
            val steps = (f.windup / 55f).toInt()
            for (i in 0 until steps) {
                val k = i / steps.toFloat()
                list += Cue(i * 55f, "minecraft:block.note_block.pling", 0.25f + 0.55f * k, 0.5f + 1.5f * k)
            }
        }
        when (show.tier) {
            Tier.RARE -> {
                list += Cue(hit, "minecraft:block.note_block.bell", 0.8f, 1.2f)
                list += Cue(hit, "minecraft:entity.experience_orb.pickup", 0.7f, 0.8f)
                list += Cue(hit + 120f, "minecraft:block.amethyst_block.chime", 1f, 1.2f)
                list += Cue(hit + 260f, "minecraft:block.note_block.pling", 0.6f, 2f)
            }
            Tier.VERY_RARE -> {
                list += Cue(hit, "minecraft:item.trident.thunder", 0.45f, 1.4f)
                list += Cue(hit, "minecraft:item.mace.smash_ground", 0.8f, 1f)
                list += Cue(hit, "minecraft:block.note_block.bell", 0.9f, 1f)
                list += Cue(reveal + 150f, "minecraft:ui.toast.challenge_complete", 0.7f, 1.2f)
            }
            Tier.CRAZY_RARE -> {
                list += Cue(hit, "minecraft:item.mace.smash_ground_heavy", 1f, 0.9f)
                list += Cue(hit, "minecraft:entity.generic.explode", 0.6f, 0.6f)
                list += Cue(hit, "minecraft:item.totem.use", 0.7f, 1f)
                list += Cue(reveal + 160f, "minecraft:ui.toast.challenge_complete", 0.9f, 1f)
            }
            Tier.INSANE -> {
                list += Cue(hit, "minecraft:item.mace.smash_ground_heavy", 1f, 0.8f)
                list += Cue(hit, "minecraft:block.end_portal.spawn", 0.8f, 1f)
                list += Cue(hit, "minecraft:entity.warden.sonic_boom", 0.7f, 0.8f)
                list += Cue(hit, "minecraft:entity.lightning_bolt.thunder", 0.35f, 1.6f)
                list += Cue(hit, "minecraft:item.totem.use", 0.8f, 0.9f)
                list += Cue(reveal + 220f, "minecraft:ui.toast.challenge_complete", 1f, 1f)
                list += Cue(reveal + 1400f, "minecraft:entity.player.levelup", 0.7f, 1.5f)
            }
        }
        list += DropSignatures.cues(show)
        if (f.fanfare && !show.signature.ownMusic) {
            val notes = intArrayOf(12, 16, 19, 24)
            val start = reveal + LETTERS_START + 40f
            val step = (DropColors.label(show.tier).length * f.letterGap) / notes.size
            notes.forEachIndexed { i, n ->
                val pitch = 2f.pow((n - 12) / 12f)
                list += Cue(start + i * step, "minecraft:block.note_block.chime", 0.8f, pitch)
                list += Cue(start + i * step, "minecraft:block.note_block.bell", 0.5f, pitch)
            }
        }
        if (f.aftershock) {
            list += Cue(show.aftershockAt, "minecraft:item.mace.smash_ground_heavy", 0.9f, 1.2f)
            list += Cue(show.aftershockAt, "minecraft:entity.generic.explode", 0.4f, 1.3f)
        }
        for (k in 0 until f.fireworks) {
            val launch = reveal + fireworkLaunch(show, k)
            list += Cue(launch, "minecraft:entity.firework_rocket.launch", 0.6f, 1f)
            val burst = launch + FIREWORK_RISE
            list += Cue(burst, if (k % 2 == 0) "minecraft:entity.firework_rocket.large_blast" else "minecraft:entity.firework_rocket.blast", 0.8f, 0.9f + WorldFx.hash(show.seed, k, 41) * 0.3f)
            list += Cue(burst + 550f, "minecraft:entity.firework_rocket.twinkle", 0.6f, 1f + WorldFx.hash(show.seed, k, 42) * 0.3f)
        }
        list += Cue(show.outroAt + OUTRO_MS - 160f, "minecraft:entity.experience_orb.pickup", 0.8f, 1.6f)
        list += Cue(show.outroAt + OUTRO_MS - 160f, "minecraft:block.amethyst_block.chime", 0.9f, 1.5f)
        return list.sortedBy { it.at }
    }

    fun argb(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    const val IMPACT_MS = 55f
    const val LETTERS_START = 120f
    const val OUTRO_MS = 700f
    const val FIREWORK_RISE = 380f
    private const val DEDUPE_MS = 5_000L
    private const val MAX_QUEUE = 6
}
