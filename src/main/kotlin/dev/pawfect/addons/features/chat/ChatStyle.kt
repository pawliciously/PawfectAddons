package dev.pawfect.addons.features.chat

import dev.pawfect.addons.chat.ChatGraphicsHolder
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Notifications
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.chat.GuiMessage
import org.joml.Matrix3x2f
import org.joml.Vector2f
import kotlin.math.max
import kotlin.math.min

/**
 * Chat's look and right-click copy. Only backgrounds are replaced: vanilla's black strip
 * behind every line becomes one rounded panel per block of lines, and the scrollbar becomes
 * an accent pill. Everything else (text, wrapping, clicks, hover, chat size) stays vanilla.
 *
 * The positions recorded for the panel double as the hit boxes for copying a message.
 */
object ChatStyle {

    private val config get() = ConfigManager.features.chat

    private class Row(val line: GuiMessage.Line, val left: Int, val top: Int, val right: Int, val bottom: Int, val alpha: Float)

    private val rows = ArrayList<Row>()
    private var recording: Any? = null
    private var focused = false

    // The last frame the chat was open: its rows, and the transform from chat space to the screen.
    private var openRows: List<Row> = emptyList()
    private var openPose: Matrix3x2f? = null
    private var openAt = 0L

    private val styled: Boolean get() = config.styled

    @JvmStatic
    fun beginLines(access: Any, foreground: Boolean) {
        rows.clear()
        // The clickable-text pass also runs this method, with nothing to draw into; skip it.
        recording = access.takeIf { it is ChatGraphicsHolder }
        focused = foreground
    }

    /** One line's background. True when the styled panel replaces vanilla's strip. */
    @JvmStatic
    fun line(access: Any, baseY: Int, lineHeight: Int, width: Int, line: GuiMessage.Line, index: Int, alpha: Float): Boolean {
        if (access !== recording) return false
        val bottom = baseY - index * lineHeight
        rows += Row(line, -4, bottom - lineHeight, width + 8, bottom, alpha)
        return styled
    }

    @JvmStatic
    fun endLines(access: Any) {
        if (access !== recording) return
        recording = null
        val graphics = (access as ChatGraphicsHolder).`pawfectaddons$graphics`()
        if (focused) {
            openRows = rows.toList()
            openPose = Matrix3x2f(graphics.pose())
            openAt = System.currentTimeMillis()
        }
        if (styled && rows.isNotEmpty()) drawPanels(graphics)
        rows.clear()
    }

    /** The other fills chat makes: the scrollbar, its highlight, and the pending-messages line. */
    @JvmStatic
    fun fill(graphics: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int): Boolean {
        if (!styled) return false
        val rgb = color and 0xFFFFFF
        val alpha = ((color ushr 24) and 0xFF) / 255f
        val left = min(x0, x1).toFloat()
        val top = min(y0, y1).toFloat()
        val width = (max(x0, x1) - min(x0, x1)).toFloat()
        val height = (max(y0, y1) - min(y0, y1)).toFloat()
        when (rgb) {
            // Vanilla draws a 1 pixel highlight down the scrollbar; the pill doesn't need it.
            HIGHLIGHT -> Unit
            // The "messages pending" line under chat.
            0 -> panel(graphics, left, top, width, height, alpha * config.opacity.coerceIn(0f, 1f), alpha * config.opacity.coerceIn(0f, 1f))
            else -> {
                val bar = if (rgb == NEW_MESSAGES) Theme.accent else Theme.mix(Theme.accent, 0xFFFFFF, 0.25f)
                Shapes.rect(graphics, left, top, width, height, width / 2f, argb(bar, alpha), argb(bar, alpha))
            }
        }
        return true
    }

    private fun drawPanels(graphics: GuiGraphicsExtractor) {
        // Rows arrive bottom first. Touching rows form one block; a gap starts a new one.
        val sorted = rows.sortedBy { it.top }
        var start = 0
        for (i in 1..sorted.size) {
            val breaks = i == sorted.size || sorted[i].top > sorted[i - 1].bottom || sorted[i].left != sorted[start].left
            if (!breaks) continue
            drawBlock(graphics, sorted.subList(start, i))
            start = i
        }
    }

    /**
     * One block of lines. Each line keeps its own fade, the way vanilla fades lines one at a
     * time, but they're all slices of the same rounded panel, so the edges and outline stay
     * continuous. A line's top edge sits just out of sight above it while the line above is
     * solid and slides into place as that line fades, so corners and outline never pop in.
     */
    private fun drawBlock(graphics: GuiGraphicsExtractor, block: List<Row>) {
        val opacity = config.opacity.coerceIn(0f, 1f)
        val radius = config.radius.coerceIn(0f, 8f)
        val tuck = radius + 2f
        val rgb = if (config.customColor) config.color else Theme.palette.background
        val left = block[0].left.toFloat()
        val width = (block[0].right - block[0].left).toFloat()

        for ((index, row) in block.withIndex()) {
            val alpha = row.alpha * opacity
            if (alpha <= 0.003f) continue
            val above = if (index == 0) 0f else block[index - 1].alpha.coerceIn(0f, 1f)
            val edgeTop = row.top - tuck * above
            val edgeBottom = if (index == block.lastIndex) row.bottom.toFloat() else row.bottom + tuck
            val border = if (config.outline) argb(Theme.accent, alpha * 0.45f) else 0

            Shapes.pushScissor(graphics, left - 1f, row.top.toFloat(), width + 2f, (row.bottom - row.top).toFloat())
            Shapes.rect(
                graphics,
                left,
                edgeTop,
                width,
                edgeBottom - edgeTop,
                radius,
                argb(rgb, alpha),
                argb(rgb, alpha),
                if (config.outline) 1f else 0f,
                border,
            )
            Shapes.popScissor(graphics)
        }
    }

    /** A rounded panel that fades from [topAlpha] to [bottomAlpha], the way old lines fade first. */
    private fun panel(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, topAlpha: Float, bottomAlpha: Float) {
        if (width <= 0f || height <= 0f || max(topAlpha, bottomAlpha) <= 0.003f) return
        val rgb = if (config.customColor) config.color else Theme.palette.background
        val radius = config.radius.coerceIn(0f, 8f)
        val border = if (config.outline) argb(Theme.accent, min(topAlpha, bottomAlpha) * 0.45f) else 0
        Shapes.rect(
            graphics,
            x,
            y,
            width,
            height,
            radius,
            argb(rgb, topAlpha),
            argb(rgb, bottomAlpha),
            if (config.outline) 1f else 0f,
            border,
        )
    }

    /**
     * Right click on a message while chat is open: copy its plain text. Shift + right click is
     * left alone so other mods' menus (Chat Patches has one) still open.
     */
    fun copyAt(mouseX: Double, mouseY: Double): Boolean {
        if (!config.rightClickCopy) return false
        val pose = openPose ?: return false
        if (System.currentTimeMillis() - openAt > STALE_MS) return false

        val local = Vector2f(mouseX.toFloat(), mouseY.toFloat())
        Matrix3x2f(pose).invert().transformPosition(local)
        val row = openRows.firstOrNull { local.x >= it.left && local.x < it.right && local.y >= it.top && local.y < it.bottom }
            ?: return false

        val text = row.line.parent().content().string.removeColor().trim()
        if (text.isEmpty()) return false
        McCompat.mc.keyboardHandler.clipboard = text
        Notifications.push("Chat", "Message copied", Icons.TAG, lifetime = COPIED_MS)
        return true
    }

    private fun argb(rgb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

    private const val HIGHLIGHT = 0xCCCCCC

    /** Vanilla's scrollbar turns this colour when new messages arrive while scrolled up. */
    private const val NEW_MESSAGES = 0xCC3333

    private const val STALE_MS = 500L
    private const val COPIED_MS = 1_500L
}
