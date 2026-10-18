package dev.pawfect.addons.features.drops

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.features.drops.DropCelebration.argb
import dev.pawfect.addons.features.drops.DropCelebration.smooth
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.SoundCue
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.CameraType
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object ItemGet {

    private class Moment(val stack: ItemStack, val name: String, val color: Int) {
        var clock = 0f
        var lastNanos = 0L
        var nextCue = 0
        var typed = 0

        fun elapsed(): Float {
            val now = System.nanoTime()
            if (lastNanos != 0L) clock += (now - lastNanos) / 1_000_000f
            lastNanos = now
            return clock
        }
    }

    private class Cue(val at: Float, val id: String, val volume: Float, val pitch: Float)

    private var moment: Moment? = null
    private var savedCamera: CameraType? = null

    private val pending = ArrayDeque<Pair<String, String?>>()

    fun queue(name: String, id: String?) {
        if (pending.size < 4) pending.addLast(name to id)
    }

    private val cues: List<Cue> = buildList {
        fun note(n: Int) = 2f.pow((n - 12) / 12f)
        add(Cue(150f, "minecraft:entity.player.attack.sweep", 0.4f, 1.5f))
        val climb = intArrayOf(6, 10, 13, 18)
        climb.forEachIndexed { i, n -> add(Cue(JINGLE + i * 120f, "minecraft:block.note_block.harp", 0.9f, note(n))) }
        add(Cue(JINGLE + 360f, "minecraft:block.note_block.bell", 0.7f, note(18)))
        for (n in intArrayOf(13, 17, 20)) add(Cue(JINGLE + 600f, "minecraft:block.note_block.harp", 0.8f, note(n)))
        add(Cue(JINGLE + 600f, "minecraft:block.note_block.bell", 0.8f, note(24)))
        add(Cue(JINGLE + 600f, "minecraft:block.note_block.flute", 0.6f, note(20)))
        add(Cue(JINGLE + 760f, "minecraft:block.amethyst_block.chime", 1f, 1.5f))
    }.sortedBy { it.at }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { tick() } }
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { drawWorld(context.poseStack(), context.submitNodeCollector()) }
        }
    }

    fun start(name: String, id: String?) {
        val client = McCompat.mc
        if (client.player == null) return
        val repoId = id?.takeIf { NeuRepo.item(it) != null } ?: NeuRepo.resolve(name)?.internalName
        val stack = repoId?.let { NeuRepo.itemStack(it) }?.takeIf { !it.isEmpty } ?: ItemStack(Items.NETHER_STAR)
        val display = repoId?.let { NeuRepo.displayName(it) }?.takeIf { it.removeColor().isNotBlank() } ?: "§f$name"
        val tier = DropTable.find(name)?.tier
        val color = tier?.let { DropColors.of(it) } ?: 0xFFE9A8
        if (moment == null) savedCamera = client.options.cameraType
        client.options.setCameraType(CameraType.THIRD_PERSON_FRONT)
        moment = Moment(stack, display, color)
    }

    private fun stop() {
        moment = null
        savedCamera?.let { McCompat.mc.options.setCameraType(it) }
        savedCamera = null
    }

    private fun tick() {
        if (moment == null && pending.isNotEmpty() && McCompat.screen == null && McCompat.mc.player != null) {
            val (name, id) = pending.removeFirst()
            start(name, id)
        }
        val m = moment ?: return
        if (McCompat.mc.player == null || McCompat.mc.level == null) {
            stop()
            pending.clear()
            return
        }
        val t = m.elapsed()
        val volume = DropCelebration.volume()
        while (m.nextCue < cues.size && cues[m.nextCue].at <= t) {
            val cue = cues[m.nextCue++]
            if (volume > 0f) SoundCue.play(cue.id, cue.volume * volume, cue.pitch)
        }
        val typed = typedChars(t)
        if (typed / 2 > m.typed / 2 && typed < plainLength(m) && volume > 0f) SoundCue.play("minecraft:block.note_block.hat", 0.2f * volume, 1.8f)
        m.typed = typed
        if (t >= END) stop()
    }

    private fun raised(t: Float): Float = smooth(t / 350f) * (1f - smooth((t - LOWER) / 300f))

    @JvmStatic
    fun pose(entityId: Int, rightArm: ModelPart, leftArm: ModelPart) {
        val m = moment ?: return
        val player = McCompat.mc.player ?: return
        if (entityId != player.id || McCompat.mc.options.cameraType.isFirstPerson) return
        val k = raised(m.clock)
        if (k <= 0f) return
        rightArm.xRot += (ARM_UP - rightArm.xRot) * k
        rightArm.yRot += (0f - rightArm.yRot) * k
        rightArm.zRot += (-ARM_IN - rightArm.zRot) * k
        leftArm.xRot += (ARM_UP - leftArm.xRot) * k
        leftArm.yRot += (0f - leftArm.yRot) * k
        leftArm.zRot += (ARM_IN - leftArm.zRot) * k
    }

    private fun drawWorld(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val m = moment ?: return
        val client = McCompat.mc
        val player = client.player ?: return
        val t = m.clock
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val camera = client.gameRenderer.mainCamera()
        val eye = camera.position()
        val feet = player.getPosition(partial)
        val lift = DropDraw.backOut(((t - 150f) / 500f).coerceIn(0f, 1f)) * (1f - smooth((t - LOWER) / 300f))
        val height = HANDS + (OVERHEAD - HANDS) * lift
        val x = (feet.x - eye.x).toFloat()
        val y = (feet.y - eye.y).toFloat() + height
        val z = (feet.z - eye.z).toFloat()

        val item = ItemStackRenderState()
        client.itemModelResolver.updateForTopItem(item, m.stack, ItemDisplayContext.FIXED, client.level, player, 0)
        poseStack.pushPose()
        poseStack.translate(x, y, z)
        poseStack.mulPose(Axis.YP.rotation(t / 1000f * 1.2f))
        poseStack.scale(0.8f, 0.8f, 0.8f)
        item.submit(poseStack, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()

        val burst = smooth((t - JINGLE) / 250f) * (1f - smooth((t - LOWER) / 300f))
        if (burst <= 0f) return
        val left = camera.leftVector()
        val up = camera.upVector()
        val color = m.color
        WorldFx.submit(poseStack, collector, 1f, solids = {}, lights = { fx ->
            fx.soft(x, y, z, 1.1f * burst, fx.light(argb(color, 0.35f * burst), true), 16)
            fx.soft(x, y, z, 0.45f, fx.light(argb(0xFFFFFF, 0.4f * burst), true), 12)
            fx.halo(x, y, z, 0.7f * burst, 0.85f * burst, fx.light(argb(0xFFFFFF, 0.5f * burst), true), 24)
            val rays = 14
            for (i in 0 until rays) {
                val angle = i / rays.toFloat() * WorldFx.TAU + t / 1000f * 0.5f
                val length = (1.4f + 0.8f * WorldFx.hash(7, i, 0)) * burst
                val dx = left.x() * cos(angle) + up.x() * sin(angle)
                val dy = left.y() * cos(angle) + up.y() * sin(angle)
                val dz = left.z() * cos(angle) + up.z() * sin(angle)
                fx.line(
                    x + dx * 0.2f, y + dy * 0.2f, z + dz * 0.2f,
                    x + dx * length, y + dy * length, z + dz * length,
                    0.07f, 0f, fx.light(argb(color, 0.5f * burst), true), fx.light(argb(color, 0f), true),
                )
            }
            for (i in 0 until 10) {
                val phase = ((t / 1800f) + WorldFx.hash(11, i, 0)) % 1f
                val angle = WorldFx.hash(11, i, 1) * WorldFx.TAU
                val px = x + cos(angle) * 0.7f
                val py = y - 0.4f + phase * 1.4f
                val pz = z + sin(angle) * 0.7f
                val a = min(phase * 5f, 1f) * (1f - phase) * burst
                fx.star(px, py, pz, 0.08f, fx.light(argb(0xFFFFFF, a), true))
            }
        })
    }

    private fun line(m: Moment): String = "§fYou got ${m.name}§f!"

    private fun plainLength(m: Moment): Int = line(m).removeColor().length

    private fun typedChars(t: Float): Int = ((t - TEXT) / 35f).toInt().coerceAtLeast(0)

    private fun typed(text: String, count: Int): String {
        val out = StringBuilder()
        var shown = 0
        var i = 0
        while (i < text.length && shown < count) {
            if (text[i] == '§' && i + 1 < text.length) {
                out.append(text, i, i + 2)
                i += 2
                continue
            }
            out.append(text[i])
            shown++
            i++
        }
        return out.toString()
    }

    fun renderHud() {
        val m = moment ?: return
        val t = m.clock
        if (t < TEXT - 200f) return
        val g = RenderContext.graphics
        val window = McCompat.mc.window
        val w = window.guiScaledWidth.toFloat()
        val h = window.guiScaledHeight.toFloat()
        val appear = smooth((t - TEXT + 200f) / 200f) * (1f - smooth((t - LOWER) / 300f))
        if (appear <= 0.02f) return
        val font = McCompat.font
        val full = line(m)
        val width = (font.width(Component.literal(full)) * 1.3f + 28f).coerceAtLeast(160f)
        val height = 30f
        val x = (w - width) / 2f
        val y = h - 74f + (1f - appear) * 16f
        g.roundPanel(x, y, width, height, 5f, argb(0x0B0B12, 0.88f * appear), argb(m.color, appear))
        val text = Component.literal(typed(full, typedChars(t)))
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x + 14f, y + (height - 9f * 1.3f) / 2f + 1f)
        pose.scale(1.3f, 1.3f)
        g.text(font, text, 0, 0, argb(0xFFFFFF, appear), true)
        pose.popMatrix()
        if (typedChars(t) >= plainLength(m) && (t / 400f).toInt() % 2 == 0) {
            g.text(font, "▼", (x + width - 14f).toInt(), (y + height - 11f).toInt(), argb(Theme.mix(m.color, 0xFFFFFF, 0.4f), appear), false)
        }
    }

    private const val JINGLE = 550f
    private const val TEXT = 900f
    private const val LOWER = 4300f
    private const val END = 4700f
    private const val HANDS = 1.55f
    private const val OVERHEAD = 2.45f

    private const val ARM_UP = -2.95f
    private const val ARM_IN = 0.28f
}
