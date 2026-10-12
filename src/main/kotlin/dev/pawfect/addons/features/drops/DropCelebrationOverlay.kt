package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier
import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.features.drops.DropCelebration.argb
import dev.pawfect.addons.features.drops.DropCelebration.smooth
import dev.pawfect.addons.ui.Draw.gradient
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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
 * - the wind up (rarer drops): the screen darkens, the heart thumps, and streaks of light are
 *   pulled into a point,
 * - the impact frames: hard black and white frames with speed lines, a spiked burst and, for the
 *   rarest, lightning, the item caught at a hard angle in each,
 * - the reveal: a flash and shockwaves as the 3D item spins in and settles, light rays turning
 *   behind it, sparks, confetti cannons, the rarity slammed in a letter at a time with a second
 *   impact as the last lands, then the item turning on show while fireworks go off,
 * - the outro: it spins up and shrinks into a point, and the light goes with it.
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
        val shakeX: Float,
        val shakeY: Float,
    ) {
        val f get() = show.feel
        val seed get() = show.seed
        val color = DropColors.of(show.tier)
        val glow = DropColors.glowOf(show.tier)

        /** One block of the item, in GUI units. */
        val unit get() = 16f * f.size

        /** Everything else scales with the item. */
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
        val shake = DropCelebration.shakeAmount(show, t) * 7f
        val sx = shake * DropCelebration.noise(t, 0)
        val sy = shake * DropCelebration.noise(t, 3)
        val frame = Frame(graphics, show, w, h, w / 2f, h * 0.42f, sqrt(w * w + h * h) / 2f + 24f, sx, sy)

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(sx, sy)
        val after = show.feel.aftershock && t >= show.aftershockAt && t < show.aftershockAt + DropCelebration.IMPACT_MS * 2
        when {
            t < show.impactAt -> windup(frame, t)
            t < show.revealAt -> impact(frame, ((t - show.impactAt) / DropCelebration.IMPACT_MS).toInt())
            // The second hit, as the last letter lands.
            after && DropCelebration.impactFrames() -> impact(frame, ((t - show.aftershockAt) / DropCelebration.IMPACT_MS).toInt() + 1)
            else -> reveal(frame, t - show.revealAt, t)
        }
        pose.popMatrix()
    }

    // ------------------------------------------------------------------ wind up

    private fun windup(fr: Frame, t: Float) {
        val g = fr.g
        val p = t / fr.f.windup
        val kick = DropCelebration.heartbeatKick(fr.show, t)
        full(fr, argb(0x000000, 0.55f * p))
        vignette(fr, 0x000000, 0.6f * p)
        // Each heartbeat flushes the edges and sends a ring out.
        if (kick > 0f) vignette(fr, fr.color, 0.45f * kick)
        for (beat in fr.f.heartbeats) {
            val q = (t - beat) / 500f
            if (q in 0f..1f) ring(g, fr.cx, fr.cy, 10f + q.pow(0.6f) * fr.reach * 0.8f, 1f + 3f * (1f - q), argb(fr.color, (1f - q) * 0.7f))
        }
        // Light dragged in toward the point it's about to burst from, faster as it builds.
        for (i in 0 until 40) {
            val angle = WorldFx.hash(fr.seed, i, 0) * WorldFx.TAU
            val phase = (p * p * (2.4f + WorldFx.hash(fr.seed, i, 1) * 1.6f) + WorldFx.hash(fr.seed, i, 2)) % 1f
            val from = fr.reach * (1f - phase)
            val length = 24f + 70f * WorldFx.hash(fr.seed, i, 3) * (0.4f + p)
            ray(g, fr.cx, fr.cy, angle, from, length, 1.2f + p, argb(fr.glow, 0.9f * p), argb(fr.color, 0f))
        }
        val closing = 8f + fr.reach * 0.6f * (1f - p * p)
        ring(g, fr.cx, fr.cy, closing, 1.5f + 3f * p, argb(fr.color, p))
        soft(g, fr.cx, fr.cy, (4f + 18f * p * p) * (1f + 0.5f * kick), argb(0xFFFFFF, p))
        soft(g, fr.cx, fr.cy, (16f + 40f * p) * (1f + 0.3f * kick), argb(fr.color, 0.5f * p))
    }

    // ------------------------------------------------------------------ impact frames

    private fun impact(fr: Frame, index: Int) {
        val g = fr.g
        val dark = index % 2 == 0
        val back = if (dark) 0xFF000000.toInt() else -1
        val ink = if (dark) 0xFFFFFF else 0x000000
        full(fr, back)
        val seed = fr.seed + index * 31
        for (i in 0 until 100) {
            val angle = WorldFx.hash(seed, i, 0) * WorldFx.TAU
            val from = fr.reach * (0.16f + WorldFx.hash(seed, i, 1) * 0.35f)
            val width = 0.8f + WorldFx.hash(seed, i, 2) * 3.6f
            ray(g, fr.cx, fr.cy, angle, from, fr.reach * 1.3f, width, argb(ink, 0f), argb(ink, 1f))
        }
        if (fr.f.lightning) lightning(fr, seed, argb(ink, 1f), 2.2f)
        val spikes = 13
        for (i in 0 until spikes) {
            val angle = i / spikes.toFloat() * WorldFx.TAU + WorldFx.hash(seed, i, 4) * 0.4f
            val length = (50f + WorldFx.hash(seed, i, 5) * 110f) * fr.size
            val width = (10f + WorldFx.hash(seed, i, 6) * 16f) * fr.size
            ray(g, fr.cx, fr.cy, angle, 0f, length, width, argb(ink, 1f), argb(ink, 1f))
        }
        Shapes.circle(g, fr.cx, fr.cy, 38f * fr.size, argb(ink, 1f))
        // Caught at a hard angle, different each frame.
        val spin = (WorldFx.hash(seed, 0, 7) - 0.5f) * 2.2f
        val tilt = (WorldFx.hash(seed, 0, 8) - 0.5f) * 0.9f
        val roll = if (dark) -0.22f else 0.18f
        item(fr, 0f, 0f, 1.35f, spin, tilt, roll)
    }

    // ------------------------------------------------------------------ reveal

    private fun reveal(fr: Frame, r: Float, t: Float) {
        val g = fr.g
        val f = fr.f
        val show = fr.show
        val appear = min(1f, r / 180f)
        // The outro: everything draws back into the item, and the item into a point.
        val o = ((t - show.outroAt) / DropCelebration.OUTRO_MS).coerceIn(0f, 1f)
        val out = 1f - smooth(o)

        full(fr, argb(0x000000, f.dim * appear * out))
        if (r < FLASH_MS) {
            val k = 1f - r / FLASH_MS
            full(fr, argb(if (DropCelebration.impactFrames()) fr.color else 0xFFFFFF, f.flash * k * k))
        }
        if (show.tier == Tier.INSANE) vignette(fr, fr.color, (0.28f + 0.12f * sin(r / 160f)) * appear * out)
        if (f.lightning && r < 320f && (r / 40f).toInt() % 2 == 0) lightning(fr, fr.seed + (r / 40f).toInt(), argb(fr.glow, 0.8f * (1f - r / 320f)), 1.6f)

        rays(fr, r, appear * out, out)
        val breathe = 1f + 0.06f * sin(r / 1000f * 3.2f)
        soft(g, fr.cx, fr.cy, 50f * fr.size * breathe * out, argb(fr.color, 0.55f * appear * out))
        soft(g, fr.cx, fr.cy, 22f * fr.size * out, argb(0xFFFFFF, 0.5f * appear * out))

        for (j in 0 until f.rings) {
            val q = (r - j * 130f) / 700f
            if (q < 0f || q > 1f) continue
            val radius = 12f + q.pow(0.55f) * fr.reach
            val tone = if (j % 2 == 0) 0xFFFFFF else fr.color
            ring(g, fr.cx, fr.cy, radius, 1f + 7f * (1f - q), argb(tone, (1f - q).pow(1.5f)))
        }
        if (f.aftershock) {
            val q = (t - show.aftershockAt) / 600f
            if (q in 0f..1f) ring(g, fr.cx, fr.cy, 14f + q.pow(0.5f) * fr.reach, 1f + 6f * (1f - q), argb(fr.glow, (1f - q).pow(1.5f)))
        }

        fireworks(fr, r, out)
        sparks(fr, r, out)
        for (wave in 0 until f.waves) confetti(fr, r - wave * 1500f, out, fr.seed + wave * 1009)
        orbit(fr, r, appear * out)

        // Spins in fast and settles, then turns slowly on show; spins away and shrinks at the end.
        val pop = elasticOut(min(1f, r / 800f))
        val zoom = (1.75f - 0.75f * pop) * (1f - o * o)
        val settle = 4f * PI.toFloat() * (1f - easeOutCubic(min(1f, r / 1400f)))
        val spin = settle + r / 1000f * 0.9f + o * o * 14f
        val tilt = 0.12f + 0.18f * sin(r / 1000f * 1.1f)
        val roll = sin(r / 1000f * 1.7f) * 0.06f
        val bob = sin(r / 1000f * 2.3f) * 3f * appear
        item(fr, 0f, bob, zoom, spin, tilt, roll)
        if (o > 0.75f) {
            val k = (o - 0.75f) / 0.25f
            soft(g, fr.cx, fr.cy, 6f + 30f * k, argb(0xFFFFFF, (1f - k) * 0.9f))
        }

        val labelY = fr.cy + fr.unit * 0.62f + 14f
        label(fr, r, out, labelY)
        name(fr, r, out, labelY + 9f * f.letterScale + 10f)
    }

    private fun rays(fr: Frame, r: Float, strength: Float, out: Float) {
        val f = fr.f
        if (f.rays <= 0 || strength <= 0f) return
        val layers = if (fr.show.tier == Tier.INSANE) 2 else 1
        val pulse = 0.85f + 0.15f * sin(r / 1000f * 3f)
        // They shoot out on the reveal and pull back in at the end.
        val extent = easeOutCubic(min(1f, r / 500f)) * out
        for (layer in 0 until layers) {
            val spin = r / 1000f * (if (layer == 0) 0.35f else -0.22f)
            val strong = if (layer == 0) 0.3f else 0.18f
            for (i in 0 until f.rays) {
                val angle = i / f.rays.toFloat() * WorldFx.TAU + spin + layer * 0.2f
                val a = strength * pulse * strong
                ray(fr.g, fr.cx, fr.cy, angle, 6f, fr.reach * 0.85f * extent, 34f * fr.size, argb(fr.color, a * 0.6f), argb(fr.color, 0f))
                ray(fr.g, fr.cx, fr.cy, angle, 6f, fr.reach * 0.7f * extent, 12f * fr.size, argb(fr.glow, a), argb(fr.glow, 0f))
            }
        }
    }

    /** Thrown out from the item, slowing in the air and falling as they fade. */
    private fun sparks(fr: Frame, r: Float, out: Float) {
        val seed = fr.seed
        val time = r / 1000f
        for (i in 0 until fr.f.sparks) {
            val life = 0.9f + WorldFx.hash(seed, i, 10) * 1.3f
            val tt = time - WorldFx.hash(seed, i, 11) * 0.08f
            if (tt < 0f || tt > life) continue
            val angle = WorldFx.hash(seed, i, 12) * WorldFx.TAU
            val speed = (140f + WorldFx.hash(seed, i, 13) * 420f) * (0.7f + 0.3f * fr.f.size)
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
            if (i % 4 == 0) glint(fr.g, x, y, size * 4f * fade, argb(0xFFFFFF, a))
        }
    }

    /** Fired up from the bottom corners, slowing at the top and fluttering down. */
    private fun confetti(fr: Frame, r: Float, out: Float, seed: Int) {
        if (r < 0f) return
        val time = r / 1000f
        val pose = fr.g.pose()
        for (i in 0 until fr.f.confetti) {
            val tt = time - WorldFx.hash(seed, i, 20) * 0.25f
            val life = 2.2f + WorldFx.hash(seed, i, 21) * 1.4f
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
            val tone = Color.HSBtoRGB(WorldFx.hash(seed, i, 24), 0.65f, 1f)
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

    /** Shot up from below, bursting into a ring of sparks that droop and twinkle out. */
    private fun fireworks(fr: Frame, r: Float, out: Float) {
        val seed = fr.seed
        for (k in 0 until fr.f.fireworks) {
            val t = r - DropCelebration.fireworkLaunch(fr.show, k)
            if (t < 0f) continue
            val side = if (k % 2 == 0) -1f else 1f
            val bx = fr.cx + side * fr.w * (0.2f + WorldFx.hash(seed, k, 43) * 0.22f)
            val by = fr.h * (0.12f + WorldFx.hash(seed, k, 44) * 0.24f)
            val sx = bx + (WorldFx.hash(seed, k, 45) - 0.5f) * 40f
            val sy = fr.h + 8f
            val tone = Color.HSBtoRGB(WorldFx.hash(seed, k, 46), 0.6f, 1f)
            if (t < DropCelebration.FIREWORK_RISE) {
                val q = easeOutCubic(t / DropCelebration.FIREWORK_RISE)
                val back = max(0f, q - 0.15f)
                val x = sx + (bx - sx) * q
                val y = sy + (by - sy) * q
                line(fr.g, sx + (bx - sx) * back, sy + (by - sy) * back, x, y, 1.4f, argb(tone, 0f), argb(0xFFFFFF, 0.9f * out))
                soft(fr.g, x, y, 3f, argb(0xFFFFFF, out))
                continue
            }
            val bt = (t - DropCelebration.FIREWORK_RISE) / 1000f
            if (bt > 1.6f) continue
            if (bt < 0.14f) soft(fr.g, bx, by, 6f + 28f * (1f - bt / 0.14f), argb(0xFFFFFF, 0.8f * (1f - bt / 0.14f) * out))
            for (i in 0 until 48) {
                val life = 1.1f + WorldFx.hash(seed, k * 97 + i, 47) * 0.5f
                if (bt > life) continue
                val angle = i / 48f * WorldFx.TAU + WorldFx.hash(seed, k * 97 + i, 48) * 0.2f
                val speed = 90f + WorldFx.hash(seed, k * 97 + i, 49) * 70f
                val travel = speed * (1f - exp(-2.2f * bt)) / 2.2f
                val x = bx + cos(angle) * travel
                val y = by + sin(angle) * travel + 40f * bt * bt
                val fade = 1f - bt / life
                val twinkle = if (bt > 0.6f) 0.5f + 0.5f * sin(bt * 40f + i) else 1f
                val a = fade * fade * out * twinkle
                soft(fr.g, x, y, 2.4f, argb(tone, a * 0.6f))
                fr.g.rect(x - 0.6f, y - 0.6f, 1.2f, 1.2f, argb(0xFFFFFF, a))
            }
        }
    }

    /** Glints circling the item, as if on a ring tilted toward you. */
    private fun orbit(fr: Frame, r: Float, strength: Float) {
        if (strength <= 0f) return
        val count = 6 + fr.show.tier.ordinal * 3
        val radius = fr.unit * 0.85f
        for (i in 0 until count) {
            val angle = i / count.toFloat() * WorldFx.TAU + r / 1000f * 1.3f
            val depth = sin(angle)
            val x = fr.cx + cos(angle) * radius
            val y = fr.cy + depth * radius * 0.28f - fr.unit * 0.05f
            val near = 0.55f + 0.45f * depth
            val tone = if (i % 2 == 0) fr.glow else 0xFFFFFF
            soft(fr.g, x, y, 3.5f * near * fr.size + 1f, argb(tone, strength * 0.5f * near))
            if (i % 3 == 0) glint(fr.g, x, y, 5f * near * fr.size, argb(0xFFFFFF, strength * near))
        }
    }

    /** Jagged bolts striking in from the edges to the item. */
    private fun lightning(fr: Frame, seed: Int, color: Int, width: Float) {
        for (b in 0 until 5) {
            val angle = b / 5f * WorldFx.TAU + WorldFx.hash(seed, b, 60) * 1.1f
            val startX = fr.cx + cos(angle) * fr.reach
            val startY = fr.cy + sin(angle) * fr.reach
            val normalX = -sin(angle)
            val normalY = cos(angle)
            var x = startX
            var y = startY
            val segments = 10
            for (s in 1..segments) {
                val k = s / segments.toFloat()
                val wander = if (s == segments) 0f else (WorldFx.hash(seed, b * 31 + s, 61) - 0.5f) * 60f * (1f - k * 0.7f)
                val nx = startX + (fr.cx - startX) * k * 0.92f + normalX * wander
                val ny = startY + (fr.cy - startY) * k * 0.92f + normalY * wander
                line(fr.g, x, y, nx, ny, width * 4f, argb(color, ((color ushr 24) / 255f) * 0.25f), argb(color, ((color ushr 24) / 255f) * 0.25f))
                line(fr.g, x, y, nx, ny, width, color, color)
                x = nx
                y = ny
            }
        }
    }

    // ------------------------------------------------------------------ text

    private fun label(fr: Frame, r: Float, out: Float, y: Float) {
        val text = DropColors.label(fr.show.tier)
        val font = McCompat.font
        val f = fr.f
        val widths = FloatArray(text.length) { font.width(bold(text[it])) * f.letterScale }
        val total = widths.sum()
        var x = fr.cx - total / 2f
        val split = fr.show.tier >= Tier.CRAZY_RARE
        // A shine passing over the letters every so often, once they've all landed.
        val landed = DropCelebration.LETTERS_START + text.length * f.letterGap + 200f
        val sweep = ((r - landed) % 1800f) / 650f
        val shineX = if (r > landed && sweep <= 1f) fr.cx - total / 2f - 20f + sweep * (total + 40f) else Float.NaN
        for (i in text.indices) {
            val start = DropCelebration.LETTERS_START + i * f.letterGap
            val q = (r - start) / 110f
            val width = widths[i]
            if (q > 0f && text[i] != ' ') {
                // Comes in huge and slams down, overshooting a touch.
                val land = backOut(min(1f, q))
                val scale = f.letterScale * (1f + 2.4f * (1f - land))
                val a = min(1f, q * 2.5f) * out
                var tone = if (fr.show.tier == Tier.INSANE) Color.HSBtoRGB((i * 0.07f + r / 1000f * 0.6f) % 1f, 0.75f, 1f) else fr.color
                val gx = x + width / 2f
                if (!shineX.isNaN()) {
                    val d = (gx - shineX) / 14f
                    tone = Theme.mix(tone, 0xFFFFFF, 0.85f * exp(-d * d))
                }
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
        val start = DropCelebration.LETTERS_START + DropColors.label(fr.show.tier).length * fr.f.letterGap + 80f
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
        val lineY = y + 9f * scale + 3f + rise
        fr.g.gradientHorizontal(fr.cx - line / 2f, lineY, line / 2f, 1f, argb(fr.color, 0f), argb(fr.color, a))
        fr.g.gradientHorizontal(fr.cx, lineY, line / 2f, 1f, argb(fr.color, a), argb(fr.color, 0f))
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

    /** The drop itself as a 3D model, centred where it sits on screen, shaken with everything else. */
    private fun item(fr: Frame, x: Float, y: Float, zoom: Float, spin: Float, tilt: Float, roll: Float) {
        // Drawn in its own box that ignores the shake translate, so the shake goes in as an offset.
        DropItemRenderer.draw(fr.g, fr.show.stack, fr.cx, fr.cy, fr.unit, ITEM_ROOM, x + fr.shakeX, y + fr.shakeY, zoom, spin, tilt, roll)
    }

    private fun full(fr: Frame, color: Int) {
        if ((color ushr 24) == 0) return
        fr.g.fill(-60, -60, fr.w.toInt() + 60, fr.h.toInt() + 60, color)
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

    private fun line(g: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, from: Int, to: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        ray(g, x0, y0, atan2(dy, dx), 0f, sqrt(dx * dx + dy * dy), width, from, to)
    }

    private fun glint(g: GuiGraphicsExtractor, x: Float, y: Float, arm: Float, color: Int) {
        if (arm <= 0.2f) return
        g.rect(x - arm, y - 0.4f, arm * 2f, 0.8f, color)
        g.rect(x - 0.4f, y - arm, 0.8f, arm * 2f, color)
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
        g.gradient(-60f, -60f, fr.w + 120f, bandH + 60f, edge, clear)
        g.gradient(-60f, fr.h - bandH, fr.w + 120f, bandH + 60f, clear, edge)
        g.gradientHorizontal(-60f, -60f, bandW + 60f, fr.h + 120f, edge, clear)
        g.gradientHorizontal(fr.w - bandW, -60f, bandW + 60f, fr.h + 120f, clear, edge)
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

    private fun easeOutCubic(x: Float): Float {
        val u = 1f - x.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /** How far off centre, in blocks, the item can go and still be in its box. */
    private const val ITEM_ROOM = 1.5f
    private const val FLASH_MS = 380f
    private const val DRAG = 2.4f
    private const val CONFETTI_DRAG = 1.4f
}
