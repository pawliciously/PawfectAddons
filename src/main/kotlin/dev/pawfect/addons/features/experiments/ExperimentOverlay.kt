package dev.pawfect.addons.features.experiments

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.stringCentered
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import kotlin.math.sin

object ExperimentPalette {
    const val NEXT = 0x4ADE80
    const val UPCOMING = 0x8B5CF6
    const val DUPLICATE = 0xFBBF24
    const val KNOWN = 0x60A5FA
}

object ExperimentOverlay {

    private val config get() = ConfigManager.features.experiments

    private val startedAt = System.currentTimeMillis()

    private fun seconds(): Float = (System.currentTimeMillis() - startedAt) / 1000f

    private fun alpha(rgb: Int, value: Int): Int = (value.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private fun slotOf(screen: AbstractContainerScreen<*>, index: Int): Slot? =
        screen.menu.slots.getOrNull(index)

    private class Mark(val slot: Int, val color: Int, val strength: Float, val orders: List<Int>)

    private class Cluster(
        val color: Int,
        val colorEnd: Int,
        val strength: Float,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val horizontal: Boolean,
        val orders: List<Int>,
    )

    private fun marks(): List<Mark> {
        val highlights = ExperimentManager.highlights()
        if (highlights.isEmpty()) return emptyList()
        return highlights.groupBy { it.slot }.map { (slot, list) ->
            val best = list.maxByOrNull { it.strength } ?: list.first()
            val orders = list.mapNotNull { h -> h.order.takeIf { it > 0 } }.distinct().sorted()
            Mark(slot, best.color, list.maxOf { it.strength }.coerceIn(0f, 1f), orders)
        }
    }

    private fun rects(screen: AbstractContainerScreen<*>, marks: List<Mark>): List<Cluster> {
        if (marks.isEmpty()) return emptyList()
        val result = ArrayList<Cluster>()

        for ((_, group) in marks.groupBy { it.color to it.orders }) {
            val bySlot = group.associateBy { it.slot }
            val used = HashSet<Int>()

            for (slot in group.map { it.slot }.sorted()) {
                if (slot in used) continue
                if (bySlot.containsKey(slot - 9)) continue

                val members = ArrayList<Mark>()
                var id = slot
                while (true) {
                    val mark = bySlot[id] ?: break
                    members.add(mark)
                    used.add(id)
                    id += 9
                }
                if (members.isEmpty()) continue

                val top = slotOf(screen, members.first().slot) ?: continue
                val bottom = slotOf(screen, members.last().slot) ?: continue

                result.add(
                    Cluster(
                        members.first().color,
                        members.last().color,
                        members.maxOf { it.strength },
                        top.x - 1f,
                        top.y - 1f,
                        18f,
                        bottom.y + 17f - (top.y - 1f),
                        false,
                        members.flatMap { it.orders }.distinct().sorted(),
                    ),
                )
            }
        }
        return result
    }

    @JvmStatic
    fun renderUnder(graphics: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>) {
        if (!ExperimentManager.active()) return
        val radius = config.radius.coerceIn(0f, 8f)
        val pulse = 0.72f + 0.28f * sin(seconds() * 3.2f)

        for (cluster in rects(screen, marks())) {
            if (cluster.strength >= 0.9f) {
                Shapes.shadow(
                    graphics,
                    cluster.x - 2f,
                    cluster.y - 2f,
                    cluster.width + 4f,
                    cluster.height + 4f,
                    radius + 2f,
                    7f,
                    alpha(cluster.color, (110 * pulse).toInt()),
                )
            }

            Shapes.rect(
                graphics,
                cluster.x,
                cluster.y,
                cluster.width,
                cluster.height,
                radius,
                alpha(cluster.color, (95 * cluster.strength).toInt()),
                alpha(cluster.colorEnd, (95 * cluster.strength).toInt()),
                0f,
                0,
                0f,
                0f,
                cluster.horizontal,
            )
        }
    }

    @JvmStatic
    fun renderOver(graphics: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>) {
        if (!ExperimentManager.active()) return
        val radius = config.radius.coerceIn(0f, 8f)
        val pulse = 0.72f + 0.28f * sin(seconds() * 3.2f)
        val solver = ExperimentManager.current
        val marks = marks()

        if (solver is SuperpairsSolver) {
            for (mark in marks) {
                val slot = slotOf(screen, mark.slot) ?: continue
                solver.remembered(mark.slot)?.let { graphics.item(it, slot.x, slot.y) }
            }
        }

        for (cluster in rects(screen, marks)) {
            val strong = cluster.strength >= 0.9f
            Shapes.outline(
                graphics,
                cluster.x,
                cluster.y,
                cluster.width,
                cluster.height,
                radius,
                alpha(cluster.color, if (strong) (235 * pulse).toInt() else (150 * cluster.strength).toInt()),
                if (strong) 1.6f else 1f,
            )
        }

        if (config.showOrder) {
            for (cluster in rects(screen, marks)) {
                if (cluster.orders.isEmpty()) continue
                val label = cluster.orders.joinToString(",")
                val badgeWidth = Draw.width(label) + 5f
                val badgeX = cluster.x + cluster.width - badgeWidth + 1f
                val badgeY = cluster.y - 3f
                Shapes.rect(
                    graphics,
                    badgeX,
                    badgeY,
                    badgeWidth,
                    9f,
                    4f,
                    alpha(cluster.color, (240 * cluster.strength.coerceAtLeast(0.55f)).toInt()),
                )
                graphics.stringCentered(
                    label,
                    badgeX + badgeWidth / 2f,
                    badgeY + 1f,
                    alpha(0x0B0D12, 255),
                )
            }
        }

        if (config.showPanel) renderPanel(graphics, screen)
    }

    private fun renderPanel(graphics: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>) {
        val solver = ExperimentManager.current ?: return
        val accent = Theme.palette.accent
        val maxed = solver.maxRounds > 0 && solver.round >= solver.maxRounds

        val lines = ArrayList<Pair<String, Int>>()
        lines.add(solver.status() to Theme.palette.text)
        if (solver.maxRounds > 0) {
            val roundText = "Round ${solver.round} / ${solver.maxRounds}"
            lines.add(roundText to if (maxed) ExperimentPalette.NEXT else Theme.palette.textDim)
        }
        if (maxed) lines.add("MAX REWARD, you can stop" to ExperimentPalette.DUPLICATE)

        val header = if (ExperimentManager.tier.isEmpty()) solver.displayName else "${solver.displayName} · ${ExperimentManager.tier}"
        var width = Draw.width(header) + 18f
        lines.forEach { width = maxOf(width, Draw.width(it.first) + 18f) }
        val height = 20f + lines.size * 11f

        val x = screen.menu.slots.maxOfOrNull { it.x }?.plus(24f) ?: 190f
        val y = 4f

        Shapes.shadow(graphics, x - 2f, y - 2f, width + 4f, height + 4f, 8f, 9f, alpha(0x000000, 120))
        Shapes.rect(
            graphics,
            x,
            y,
            width,
            height,
            7f,
            Theme.surface(Theme.palette.panel, 240),
            Theme.surface(Theme.palette.background, 240),
            1f,
            alpha(if (maxed) ExperimentPalette.NEXT else accent, 190),
        )

        graphics.stringCentered(header, x + width / 2f, y + 6f, alpha(accent, 255), shadow = true)

        var cursor = y + 19f
        for ((text, color) in lines) {
            graphics.stringCentered(text, x + width / 2f, cursor, alpha(color, 255))
            cursor += 11f
        }
    }
}
