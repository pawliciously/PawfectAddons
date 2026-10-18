package dev.pawfect.addons.features.drops

import dev.pawfect.addons.features.drops.DropCelebration.argb
import dev.pawfect.addons.ui.Draw.gradient
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.utils.McCompat
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DropFrame(
    val g: GuiGraphicsExtractor,
    val show: DropCelebration.Show,
    val w: Float,
    val h: Float,
    val cx: Float,
    val cy: Float,
    val reach: Float,
    val shakeX: Float,
    val shakeY: Float,
) {
    val f get() = show.feel
    val seed get() = show.seed
    val color get() = show.color
    val glow get() = show.glow

    val unit get() = 16f * f.size

    val size get() = f.size / 4f
}

object DropDraw {

    fun full(fr: DropFrame, color: Int) {
        if ((color ushr 24) == 0) return
        fr.g.fill(-60, -60, fr.w.toInt() + 60, fr.h.toInt() + 60, color)
    }

    fun ray(g: GuiGraphicsExtractor, cx: Float, cy: Float, angle: Float, from: Float, length: Float, width: Float, inner: Int, outer: Int) {
        if (length <= 0f || width <= 0f) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(cx, cy)
        pose.rotate(angle)
        g.gradientHorizontal(from, -width / 2f, length, width, inner, outer)
        pose.popMatrix()
    }

    fun line(g: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, from: Int, to: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        ray(g, x0, y0, atan2(dy, dx), 0f, sqrt(dx * dx + dy * dy), width, from, to)
    }

    fun glint(g: GuiGraphicsExtractor, x: Float, y: Float, arm: Float, color: Int) {
        if (arm <= 0.2f) return
        g.rect(x - arm, y - 0.4f, arm * 2f, 0.8f, color)
        g.rect(x - 0.4f, y - arm, 0.8f, arm * 2f, color)
    }

    fun soft(g: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, color: Int) {
        if (radius <= 0f || (color ushr 24) == 0) return
        Shapes.rect(g, x - radius, y - radius, radius * 2f, radius * 2f, radius, color, color, softness = radius * 0.9f)
    }

    fun blob(g: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, color: Int) {
        if (radius <= 0f || (color ushr 24) == 0) return
        Shapes.rect(g, x - radius, y - radius, radius * 2f, radius * 2f, radius, color, color, softness = 0.8f)
    }

    fun ring(g: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, width: Float, color: Int) {
        if (radius <= 0f || (color ushr 24) == 0) return
        Shapes.rect(g, x - radius, y - radius, radius * 2f, radius * 2f, radius, 0, 0, width, color, softness = 1f)
    }

    fun vignette(fr: DropFrame, rgb: Int, strength: Float) {
        if (strength <= 0f) return
        val g = fr.g
        val edge = argb(rgb, strength)
        val clear = argb(rgb, 0f)
        val bandH = fr.h * 0.32f
        val bandW = fr.w * 0.22f
        g.gradient(-60f, -60f, fr.w + 120f, bandH + 60f, edge, clear)
        g.gradient(-60f, fr.h - bandH, fr.w + 120f, bandH + 60f, clear, edge)
        g.gradientHorizontal(-60f, -60f, bandW + 60f, fr.h + 120f, edge, clear)
        g.gradientHorizontal(fr.w - bandW, -60f, bandW + 60f, fr.h + 120f, clear, edge)
    }

    fun bold(text: String): Component = Component.literal(text).withStyle(ChatFormatting.BOLD)

    fun text(g: GuiGraphicsExtractor, text: String, x: Float, y: Float, scale: Float, color: Int, shadow: Boolean = true) {
        if ((color ushr 24) < 6 || scale <= 0f) return
        val font = McCompat.font
        val component = bold(text)
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(scale, scale)
        g.text(font, component, -font.width(component) / 2, -4, color, shadow)
        pose.popMatrix()
    }

    fun elasticOut(x: Float): Float = when {
        x <= 0f -> 0f
        x >= 1f -> 1f
        else -> (2.0.pow(-10.0 * x) * sin((x * 10.0 - 0.75) * (2.0 * PI / 3.0)) + 1.0).toFloat()
    }

    fun backOut(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = x - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    fun easeOutCubic(x: Float): Float {
        val u = 1f - x.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    fun easeInCubic(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * t
    }
}
