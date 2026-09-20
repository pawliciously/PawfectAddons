package dev.pawfect.addons.ui

import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.concurrent.CopyOnWriteArrayList

object Notifications {

    private const val IN_MS = 300f
    private const val OUT_MS = 240f
    private const val GAP = 5f
    private const val MARGIN = 10f
    private const val MAX_VISIBLE = 6
    private const val BADGE_LOGO = 16f
    private const val DEFAULT_MS = 5_000L

    class Toast(
        val title: String,
        val body: String,
        val glyph: String,
        val accent: Int,
        val lifetime: Long,
    ) {
        val born: Long = System.currentTimeMillis()
    }

    private val active = CopyOnWriteArrayList<Toast>()

    fun push(
        title: String,
        body: String,
        glyph: String = Icons.SPARKLE,
        accent: Int = Theme.accent,
        lifetime: Long? = null,
    ) {
        active.add(Toast(title, body, glyph, accent, lifetime ?: DEFAULT_MS))
        while (active.size > MAX_VISIBLE) active.removeAt(0)
    }

    fun clear() {
        active.clear()
    }

    fun render() {
        if (active.isEmpty()) return
        if (McCompat.hideGui || McCompat.mc.screen != null) return
        if (!RenderContext.isActive) return
        val graphics = RenderContext.graphics
        UiScale.minWidth = 0f
        UiScale.minHeight = 0f
        UiScale.push(graphics)
        try {
            draw(graphics)
        } finally {
            UiScale.pop(graphics)
        }
    }

    fun renderScaled(graphics: GuiGraphicsExtractor) {
        if (active.isEmpty()) return
        draw(graphics)
    }

    private fun draw(graphics: GuiGraphicsExtractor) {
        val now = System.currentTimeMillis()
        active.removeAll { now - it.born > it.lifetime + OUT_MS }
        if (active.isEmpty()) return

        var top = MARGIN + 18f

        for (toast in active) {
            val age = (now - toast.born).toFloat()
            val enter = easeOut((age / IN_MS).coerceIn(0f, 1f))
            val leaving = age - toast.lifetime
            val exit = if (leaving <= 0f) 1f else 1f - (leaving / OUT_MS).coerceIn(0f, 1f)
            val alpha = enter * exit
            if (alpha <= 0.01f) continue
            val life = 1f - (age / toast.lifetime).coerceIn(0f, 1f)

            top += badge(graphics, toast, top, enter, alpha, life) + GAP
        }
    }

    private fun easeOut(t: Float): Float {
        val i = 1f - t
        return 1f - i * i * i
    }

    private fun fade(colour: Int, alpha: Float): Int {
        val base = (colour ushr 24) and 0xFF
        val scaled = (base * alpha).toInt().coerceIn(0, 255)
        return (scaled shl 24) or (colour and 0xFFFFFF)
    }

    private fun badge(
        graphics: GuiGraphicsExtractor,
        toast: Toast,
        top: Float,
        enter: Float,
        alpha: Float,
        life: Float,
    ): Float {
        val height = 36f
        val titleWidth = Draw.width(toast.title)
        val bodyWidth = Draw.width(toast.body)
        val textLeft = 12f + BADGE_LOGO + 9f
        val plateWidth = maxOf(titleWidth, bodyWidth) + textLeft + 12f
        val pop = 0.74f + 0.26f * enter + (if (enter < 1f) kotlin.math.sin(enter * Math.PI).toFloat() * 0.07f else 0f)

        val right = UiScale.width - MARGIN
        val plateX = right - plateWidth
        val centerY = top + height / 2f

        graphics.pose().pushMatrix()
        graphics.pose().translate(right, centerY)
        graphics.pose().scale(pop, pop)
        graphics.pose().translate(-right, -centerY)

        val plateTop = top + 1f
        val plateHeight = height - 4f
        graphics.dropShadow(plateX, top + 3f, plateWidth, plateHeight, 6f, 6f, fade(0x55000000, alpha), 1f)
        graphics.roundRect(plateX, plateTop, plateWidth, plateHeight, 6f, fade(Theme.surface(Theme.panel, 240), alpha))
        Brand.logo(graphics, plateX + 12f, centerY - BADGE_LOGO / 2f - 1.5f, BADGE_LOGO, alpha)
        graphics.string(Draw.truncate(toast.title, plateWidth - textLeft - 12f), plateX + textLeft, top + 6f, fade(Theme.opaque(Theme.text), alpha), bold = true)
        graphics.string(Draw.truncate(toast.body, plateWidth - textLeft - 12f), plateX + textLeft, top + 17f, fade(Theme.withAlpha(Theme.textDim, 200), alpha))

        val trackHeight = 2.5f
        val trackInset = 6f
        val trackWidth = (plateWidth - trackInset * 2f).coerceAtLeast(0f)
        val trackY = plateTop + plateHeight - trackHeight - 2.5f
        graphics.roundRect(plateX + trackInset, trackY, trackWidth, trackHeight, trackHeight / 2f, fade(Theme.withAlpha(toast.accent, 55), alpha))
        if (life > 0f) {
            graphics.roundRect(plateX + trackInset, trackY, trackWidth * life, trackHeight, trackHeight / 2f, fade(Theme.opaque(toast.accent), alpha))
        }

        graphics.pose().popMatrix()
        return height
    }
}
