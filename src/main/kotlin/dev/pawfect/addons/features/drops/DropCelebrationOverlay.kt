package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier
import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.features.drops.DropCelebration.argb
import dev.pawfect.addons.features.drops.DropCelebration.smooth
import dev.pawfect.addons.ui.Draw.gradient
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a drop's celebration over everything, the chest you opened included:
 *
 * - the wind up (rarer drops): the screen darkens and streaks of light are pulled into a point,
 * - the impact frames: hard black and white frames with speed lines bursting out round the item,
 * - the reveal: a flash, shockwaves, light rays turning behind the item as it pops in, sparks
 *   flying, confetti cannons from the corners, and the rarity slammed in one letter at a time.
 */
object DropCelebrationOverlay {

    /** From the HUD, when no screen's open; a screen draws it itself over its own contents. */
    fun renderHud() {
        if (McCompat.screen != null) return
        draw(RenderContext.graphics)
    }

    fun renderOverScreen(graphics: GuiGraphicsExtractor) {
        if (McCompat.screen == null) return
        draw(graphics)
    }

    private class Frame(
        val g: GuiGraphicsExtractor,
        val show: DropCelebration.Show,
        val w: Float,
        val h: Float,
        val cx: Float,
        val cy: Float,
        val reach: Float,
    ) {
        val f get() = show.feel
        val color = DropColors.of(show.tier)
        val glow = DropColors.glowOf(show.tier)
        val size get() = f.size / 4f
    }

    private fun draw(graphics: GuiGraphicsExtractor) {
        val show = DropCelebration.current() ?: return
        DropCelebration.pump(show)
        val t = show.elapsed()
        if (t >= show.feel.length) return
        val window = McCompat.mc.window
        val w = window.guiScaledWidth.toFloat()
        val h = window.guiScaledHeight.toFloat()
        val frame = Frame(graphics, show, w, h, w / 2f, h * 0.42f, sqrt(w * w + h * h) / 2f + 24f)

        val shake = DropCelebration.shakeAmount(show, t) * 7f
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(shake * DropCelebration.noise(t, 0), shake * DropCelebration.noise(t, 3))
        when {
            t < show.impactAt -> windup(frame, t / show.feel.windup)
            t < show.revealAt -> impact(frame, ((t - show.impactAt) / DropCelebration.IMPACT_MS).toInt())
            else -> {
                val out = 1f - smooth((t - (show.feel.length - OUTRO)) / OUTRO)
                reveal(frame, t - show.revealAt, out)
            }
        }
        pose.popMatrix()
    }

    // ------------------------------------------------------------------ wind up

    private fun windup(fr: Frame, p: Float) {
        val g = fr.g
        g.fill(-40, -40, fr.w.toInt() + 40, fr.h.toInt() + 40, argb(0x000000, 0.5f * p))
        vignette(fr, 0x000000, 0.6f * p)
        // Light dragged in toward the point it's about to burst from.
        for (i in 0 until 30) {
            val angle = WorldFx.hash(fr.show.seed, i, 0) * WorldFx.TAU
            val phase = (p * (1.6f + WorldFx.hash(fr.show.seed, i, 1)) + WorldFx.hash(fr.show.seed, i, 2)) % 1f
            val from = fr.reach * (1f - phase)
            val length = 30f + 60f * WorldFx.hash(fr.show.seed, i, 3)
            ray(g, fr.cx, fr.cy, angle, from, length, 1.5f, argb(fr.glow, 0.9f * p), argb(fr.color, 0f))
        }
        val closing = 8f + fr.reach * 0.6f * (1f - p * p)
        ring(g, fr.cx, fr.cy, closing, 1.5f + 3f * p, argb(fr.color, p))
        soft(g, fr.cx, fr.cy, 4f + 16f * p * p, argb(0xFFFFFF, p))
    }

    // ------------------------------------------------------------------ impact frames

    private fun impact(fr: Frame, index: Int) {
        val g = fr.g
        val dark = index % 2 == 0
        val back = if (dark) 0xFF000000.toInt() else -1
        val ink = if (dark) 0xFFFFFF else 0x000000
        g.fill(-40, -40, fr.w.toInt() + 40, fr.h.toInt() + 40, back)
        val seed = fr.show.seed + index * 31
        for (i in 0 until 90) {
            val angle = WorldFx.hash(seed, i, 0) * WorldFx.TAU
            val from = fr.reach * (0.16f + WorldFx.hash(seed, i, 1) * 0.35f)
            val width = 0.8f + WorldFx.hash(seed, i, 2) * 3.4f
            ray(g, fr.cx, fr.cy, angle, from, fr.reach * 1.3f, width, argb(ink, 0f), argb(ink, 1f))
        }
        val spikes = 12
        for (i in 0 until spikes) {
            val angle = i / spikes.toFloat() * WorldFx.TAU + WorldFx.hash(seed, i, 4) * 0.4f
            val length = (50f + WorldFx.hash(seed, i, 5) * 100f) * fr.size
            val width = (10f + WorldFx.hash(seed, i, 6) * 16f) * fr.size
            ray(g, fr.cx, fr.cy, angle, 0f, length, width, argb(ink, 1f), argb(ink, 1f))
        }
        Shapes.circle(g, fr.cx, fr.cy, 36f * fr.size, argb(ink, 1f))
        item(g, fr.show.stack, fr.cx, fr.cy, fr.f.size * 1.35f, if (dark) -0.14f else 0.1f)
    }

    // ------------------------------------------------------------------ reveal

    private fun reveal(fr: Frame, r: Float, out: Float) {
        val g = fr.g
        val f = fr.f
        val appear = min(1f, r / 180f)
        val full = { color: Int -> g.fill(-40, -40, fr.w.toInt() + 40, fr.h.toInt() + 40, color) }
        full(argb(0x000000, f.dim * appear * out))
        if (r < FLASH_MS) {
            val k = 1f - r / FLASH_MS
            full(argb(if (DropCelebration.impactFrames()) fr.color else 0xFFFFFF, f.flash * k * k))
        }
        if (fr.show.tier == Tier.INSANE) vignette(fr, fr.color, (0.28f + 0.12f * sin(r / 160f)) * appear * out)

        rays(fr, r, appear * out)
        val breathe = 1f + 0.06f * sin(r / 1000f * 3.2f)
        soft(g, fr.cx, fr.cy, 48f * fr.size * breathe, argb(fr.color, 0.55f * appear * out))
        soft(g, fr.cx, fr.cy, 22f * fr.size, argb(0xFFFFFF, 0.5f * appear * out))

        for (j in 0 until f.rings) {
            val q = (r - j * 130f) / 700f
            if (q < 0f || q > 1f) continue
            val radius = 12f + q.pow(0.55f) * fr.reach
            val tone = if (j % 2 == 0) 0xFFFFFF else fr.color
            ring(g, fr.cx, fr.cy, radius, 1f + 7f * (1f - q), argb(tone, (1f - q).pow(1.5f)))
        }

        sparks(fr, r, out)
        confetti(fr, r, out)

        val pop = elasticOut(min(1f, r / 750f))
        val scale = f.size * (1.75f - 0.75f * pop) * sqrt(out)
        val bob = sin(r / 1000f * 2.3f) * 3f * appear
        val tilt = sin(r / 1000f * 1.7f) * 0.06f
        item(g, fr.show.stack, fr.cx, fr.cy + bob, scale, tilt)

        val labelY = fr.cy + 9f * f.size + 14f
        label(fr, r, out, labelY)
        name(fr, r, out, labelY + 9f * f.letterScale + 10f)
    }

    private fun rays(fr: Frame, r: Float, strength: Float) {
        val f = fr.f
        if (f.rays <= 0 || strength <= 0f) return
        val layers = if (fr.show.tier == Tier.INSANE) 2 else 1
        val pulse = 0.85f + 0.15f * sin(r / 1000f * 3f)
        for (layer in 0 until layers) {
            val spin = r / 1000f * (if (layer == 0) 0.35f else -0.22f)
            val strong = if (layer == 0) 0.3f else 0.18f
            for (i in 0 until f.rays) {
                val angle = i / f.rays.toFloat() * WorldFx.TAU + spin + layer * 0.2f
                val a = strength * pulse * strong
                ray(fr.g, fr.cx, fr.cy, angle, 6f, fr.reach * 0.85f, 34f * fr.size, argb(fr.color, a * 0.6f), argb(fr.color, 0f))
                ray(fr.g, fr.cx, fr.cy, angle, 6f, fr.reach * 0.7f, 12f * fr.size, argb(fr.glow, a), argb(fr.glow, 0f))
            }
        }
    }

    /** Thrown out from the item, slowing in the air and falling as they fade. */
    private fun sparks(fr: Frame, r: Float, out: Float) {
        val seed = fr.show.seed
        val time = r / 1000f
        for (i in 0 until fr.f.sparks) {
            val life = 0.9f + WorldFx.hash(seed, i, 10) * 1.1f
            val tt = time - WorldFx.hash(seed, i, 11) * 0.08f
            if (tt < 0f || tt > life) continue
            val angle = WorldFx.hash(seed, i, 12) * WorldFx.TAU
            val speed = (140f + WorldFx.hash(seed, i, 13) * 380f) * (0.7f + 0.3f * fr.f.size)
            val travel = speed * (1f - exp(-DRAG * tt)) / DRAG
            val x = fr.cx + cos(angle) * travel
            val y = fr.cy + sin(angle) * travel + 90f * tt * tt
            val fade = 1f - tt / life
            val a = fade * fade * out
            val size = 1.2f + WorldFx.hash(seed, i, 14) * 2.2f
            val tone = when (i % 3) {
                0 -> 0xFFFFFF
                1 -> fr.color
                else -> fr.glow
            }
            soft(fr.g, x, y, size * 2.8f, argb(tone, a * 0.55f))
            soft(fr.g, x, y, size * 0.9f, argb(0xFFFFFF, a))
            // Every fourth one glints.
            if (i % 4 == 0) {
                val arm = size * 4f * fade
                fr.g.rect(x - arm, y - 0.4f, arm * 2f, 0.8f, argb(0xFFFFFF, a))
                fr.g.rect(x - 0.4f, y - arm, 0.8f, arm * 2f, argb(0xFFFFFF, a))
            }
        }
    }

    /** Fired up from the bottom corners, slowing at the top and fluttering down. */
    private fun confetti(fr: Frame, r: Float, out: Float) {
        val seed = fr.show.seed
        val time = r / 1000f
        val pose = fr.g.pose()
        for (i in 0 until fr.f.confetti) {
            val tt = time - WorldFx.hash(seed, i, 20) * 0.25f
            val life = 2.2f + WorldFx.hash(seed, i, 21) * 1.2f
            if (tt < 0f || tt > life) continue
            val fromLeft = i % 2 == 0
            val lift = Math.toRadians(58.0 + WorldFx.hash(seed, i, 22) * 26.0).toFloat()
            val speed = fr.h * (1.4f + WorldFx.hash(seed, i, 23) * 0.8f)
            val dirX = cos(lift) * (if (fromLeft) 1f else -1f)
            val dirY = -sin(lift)
            val carried = (1f - exp(-CONFETTI_DRAG * tt)) / CONFETTI_DRAG
            var x = (if (fromLeft) fr.w * 0.04f else fr.w * 0.96f) + dirX * speed * carried
            val y = fr.h + 10f + dirY * speed * carried + 0.5f * fr.h * 0.9f * tt * tt
            x += sin(tt * 6f + i) * 6f * min(1f, tt)
            val a = out * min(1f, (life - tt) / 0.4f)
            val hue = WorldFx.hash(seed, i, 24)
            val tone = Color.HSBtoRGB(hue, 0.65f, 1f)
            val spin = tt * (4f + WorldFx.hash(seed, i, 25) * 6f) * (if (i % 3 == 0) -1f else 1f)
            pose.pushMatrix()
            pose.translate(x, y)
            pose.rotate(spin)
            // Turning over as it falls.
            pose.scale(1f, max(0.15f, abs(cos(tt * 8f + i))))
            fr.g.rect(-1.5f, -3f, 3f, 6f, argb(tone, a))
            pose.popMatrix()
        }
    }

    // ------------------------------------------------------------------ text

    private fun label(fr: Frame, r: Float, out: Float, y: Float) {
        val text = DropColors.label(fr.show.tier)
        val font = McCompat.font
        val f = fr.f
        val widths = FloatArray(text.length) { font.width(bold(text[it])) * f.letterScale }
        var x = fr.cx - widths.sum() / 2f
        val split = fr.show.tier >= Tier.CRAZY_RARE
        for (i in text.indices) {
            val start = 120f + i * f.letterGap
            val q = (r - start) / 110f
            val width = widths[i]
            if (q > 0f && text[i] != ' ') {
                // Comes in huge and slams down, overshooting a touch.
                val land = backOut(min(1f, q))
                val scale = f.letterScale * (1f + 2.4f * (1f - land))
                val a = min(1f, q * 2.5f) * out
                val tone = if (fr.show.tier == Tier.INSANE) Color.HSBtoRGB((i * 0.07f + r / 1000f * 0.6f) % 1f, 0.75f, 1f) else fr.color
                val gx = x + width / 2f
                if (split) {
                    val spread = 1.2f + 2.5f * (1f - min(1f, (r - start) / 400f))
                    glyph(fr.g, text[i], gx - spread, y, scale, argb(0xFF3355, a * 0.55f))
                    glyph(fr.g, text[i], gx + spread, y, scale, argb(0x33E5FF, a * 0.55f))
                }
                glyph(fr.g, text[i], gx, y, scale, argb(tone, a))
            }
            x += width
        }
    }

    private fun name(fr: Frame, r: Float, out: Float, y: Float) {
        val start = 120f + DropColors.label(fr.show.tier).length * fr.f.letterGap + 80f
        val q = min(1f, (r - start) / 300f)
        if (q <= 0f) return
        val a = smooth(q) * out
        if (a < 0.05f) return
        val font = McCompat.font
        val text = Component.literal(fr.show.name)
        val scale = 1.6f
        val width = font.width(text) * scale
        val rise = (1f - smooth(q)) * 6f
        val pose = fr.g.pose()
        pose.pushMatrix()
        pose.translate(fr.cx - width / 2f, y + rise)
        pose.scale(scale, scale)
        fr.g.text(font, text, 0, 0, argb(0xFFFFFF, a), true)
        pose.popMatrix()
        // A line opening out underneath.
        val line = width * smooth(q) + 12f
        fr.g.gradientHorizontal(fr.cx - line / 2f, y + 9f * scale + 3f + rise, line / 2f, 1f, argb(fr.color, 0f), argb(fr.color, a))
        fr.g.gradientHorizontal(fr.cx, y + 9f * scale + 3f + rise, line / 2f, 1f, argb(fr.color, a), argb(fr.color, 0f))
    }

    private fun bold(c: Char): Component = Component.literal(c.toString()).withStyle(ChatFormatting.BOLD)

    private fun glyph(g: GuiGraphicsExtractor, c: Char, x: Float, y: Float, scale: Float, color: Int) {
        if ((color ushr 24) < 6) return
        val font = McCompat.font
        val text = bold(c)
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(scale, scale)
        g.text(font, text, -font.width(text) / 2, -4, color, true)
        pose.popMatrix()
    }

    // ------------------------------------------------------------------ shapes

    private fun item(g: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, scale: Float, rotation: Float) {
        if (scale <= 0.02f) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.rotate(rotation)
        pose.scale(scale, scale)
        g.item(stack, -8, -8)
        pose.popMatrix()
    }

    /** A bar out from a point at an angle, fading from [inner] at its near end to [outer] at its far one. */
    private fun ray(g: GuiGraphicsExtractor, cx: Float, cy: Float, angle: Float, from: Float, length: Float, width: Float, inner: Int, outer: Int) {
        if (length <= 0f || width <= 0f) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(cx, cy)
        pose.rotate(angle)
        g.gradientHorizontal(from, -width / 2f, length, width, inner, outer)
        pose.popMatrix()
    }

    private fun soft(g: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, color: Int) {
        if (radius <= 0f || (color ushr 24) == 0) return
        Shapes.rect(g, x - radius, y - radius, radius * 2f, radius * 2f, radius, color, color, softness = radius * 0.9f)
    }

    private fun ring(g: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, width: Float, color: Int) {
        if (radius <= 0f || (color ushr 24) == 0) return
        Shapes.rect(g, x - radius, y - radius, radius * 2f, radius * 2f, radius, 0, 0, width, color, softness = 1f)
    }

    private fun vignette(fr: Frame, rgb: Int, strength: Float) {
        if (strength <= 0f) return
        val g = fr.g
        val edge = argb(rgb, strength)
        val clear = argb(rgb, 0f)
        val bandH = fr.h * 0.32f
        val bandW = fr.w * 0.22f
        g.gradient(-40f, -40f, fr.w + 80f, bandH + 40f, edge, clear)
        g.gradient(-40f, fr.h - bandH, fr.w + 80f, bandH + 40f, clear, edge)
        g.gradientHorizontal(-40f, -40f, bandW + 40f, fr.h + 80f, edge, clear)
        g.gradientHorizontal(fr.w - bandW, -40f, bandW + 40f, fr.h + 80f, clear, edge)
    }

    private fun elasticOut(x: Float): Float = when {
        x <= 0f -> 0f
        x >= 1f -> 1f
        else -> (2.0.pow(-10.0 * x) * sin((x * 10.0 - 0.75) * (2.0 * PI / 3.0)) + 1.0).toFloat()
    }

    private fun backOut(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = x - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private const val OUTRO = 450f
    private const val FLASH_MS = 380f
    private const val DRAG = 2.4f
    private const val CONFETTI_DRAG = 1.4f
}
