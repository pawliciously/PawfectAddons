package dev.pawfect.addons.features.rng

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.RngMeterEntry
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.ceil

object RngMeterOverlay {

    private val config get() = ModuleSwitch.view("rngmeter", ConfigManager.features.rngMeter)

    fun render() {
        if (!config.enabled || !RenderContext.isActive) return
        val editing = GuiEditManager.isEditorOpen()
        val meter = RngMeter.activeMeter() ?: if (editing) PREVIEW_METER else return
        val entry = RngMeter.entry(meter) ?: if (editing) preview() else null

        val position = config.position
        val scale = position.effectiveScale
        val scaledWidth = (WIDTH * scale).toInt()
        val scaledHeight = (HEIGHT * scale).toInt()
        val x = position.getAbsX(scaledWidth)
        val y = position.getAbsY(scaledHeight)

        RenderContext.pushPop {
            RenderContext.translate(x.toFloat(), y.toFloat())
            if (scale != 1f) RenderContext.scale(scale)
            draw(RenderContext.graphics, meter, entry)
        }
        GuiEditManager.register(position, HudDefaults.RNG_METER, scaledWidth, scaledHeight)
    }

    private fun draw(graphics: GuiGraphicsExtractor, meter: String, entry: RngMeterEntry?) {
        if (config.background) {
            graphics.dropShadow(0f, 0f, WIDTH, HEIGHT, RADIUS, 10f, Theme.withAlpha(0x000000, 140), 2f)
            graphics.roundPanel(0f, 0f, WIDTH, HEIGHT, RADIUS, Theme.surface(Theme.background, 240), Theme.opaque(Theme.border))
        }
        val shadow = !config.background

        val item = entry?.item
        graphics.item(item?.let { NeuRepo.itemStack(it) } ?: ItemStack(Items.PAINTING), PAD.toInt(), PAD.toInt())

        val textX = PAD + ICON + 6f
        val right = WIDTH - PAD
        val goal = entry?.goal?.takeIf { it > 0 && item != null }
        val fraction = if (entry != null && goal != null) (entry.stored.toDouble() / goal).coerceIn(0.0, 1.0) else null

        graphics.string(label(meter), textX, PAD, Theme.opaque(Theme.text), shadow, bold = true)
        if (fraction != null) {
            val percent = if (fraction >= 1.0) "FULL" else "%.1f%%".format(fraction * 100)
            graphics.stringRight(percent, right, PAD, Theme.opaque(if (fraction >= 1.0) FULL else Theme.accent), shadow)
        }

        val second = when {
            entry == null -> "Open its RNG Meter menu" to Theme.opaque(Theme.textDim)
            item == null -> "No drop selected" to Theme.opaque(Theme.textDim)
            else -> NeuRepo.displayName(item).let { it.removeColor().trim() to Theme.opaque(rarity(it)) }
        }
        graphics.string(Draw.truncate(second.first, right - textX), textX, PAD + 10f, second.second, shadow)

        val barY = PAD + ICON + 5f
        val barWidth = right - PAD
        graphics.roundRect(PAD, barY, barWidth, BAR, BAR / 2f, Theme.withAlpha(Theme.border, 200))
        if (fraction != null && fraction > 0.0) {
            val fill = (barWidth * fraction).toFloat().coerceAtLeast(BAR)
            graphics.roundRect(PAD, barY, fill, BAR, BAR / 2f, Theme.opaque(if (fraction >= 1.0) FULL else Theme.accent))
        }

        val bottomY = barY + BAR + 4f
        val dim = Theme.opaque(Theme.textDim)
        if (entry == null) {
            graphics.string("to start tracking it", PAD, bottomY, dim, shadow)
            return
        }
        val stored = (if (entry.exact) "" else "~") + "%,d".format(entry.stored)
        graphics.string(if (goal != null) "$stored / ${"%,d".format(goal)}" else "$stored stored", PAD, bottomY, dim, shadow)
        left(meter, entry, goal)?.let { graphics.stringRight(it, right, bottomY, dim, shadow) }
    }

    private fun left(meter: String, entry: RngMeterEntry, goal: Long?): String? {
        if (!config.showLeft || goal == null) return null
        val gain = entry.lastGain?.takeIf { it > 0 } ?: return null
        val missing = goal - entry.stored
        if (missing <= 0) return null
        val count = ceil(missing.toDouble() / gain).toLong()
        val noun = if (meter in RngMeter.SLAYER_BOSSES) (if (count == 1L) "boss" else "bosses") else (if (count == 1L) "run" else "runs")
        return "~%,d %s".format(count, noun)
    }

    private fun label(meter: String): String = when {
        meter.length == 2 && meter[0] == 'F' -> "Floor ${meter[1]}"
        meter.length == 2 && meter[0] == 'M' -> "Master Mode ${meter[1]}"
        else -> meter
    }

    private fun rarity(name: String): Int {
        val code = if (name.length >= 2 && name[0] == '§') name[1] else return Theme.text
        return ChatFormatting.getByCode(code)?.let { TextColor.fromLegacyFormat(it) }?.value ?: Theme.text
    }

    private fun preview() = RngMeterEntry().apply {
        item = "NECRON_HANDLE"
        stored = 27_315
        goal = NeuRepo.rngDrops(PREVIEW_METER)[item] ?: 100_000
        lastGain = 305
        exact = true
    }

    private const val PREVIEW_METER = "F7"
    private const val WIDTH = 164f
    private const val PAD = 7f
    private const val ICON = 16f
    private const val BAR = 4f
    private const val HEIGHT = PAD + ICON + 5f + BAR + 4f + Draw.LINE_HEIGHT + PAD
    private const val RADIUS = 5f
    private const val FULL = 0x7EE08A
}
