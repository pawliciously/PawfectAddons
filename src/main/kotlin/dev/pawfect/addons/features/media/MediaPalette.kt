package dev.pawfect.addons.features.media

import com.mojang.blaze3d.platform.NativeImage
import java.awt.Color
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

object MediaPalette {

    private const val HUE_BINS = 36
    private const val NEUTRAL_BINS = 3
    private const val MAX_SAMPLES = 6000

    /** How far either side of a hue's peak still counts as the same colour, in bins. */
    private const val FAMILY_REACH = 2

    val IDLE: IntArray = intArrayOf(0x2B2F3E, 0x363A4E, 0x23262F, 0x3B3652)

    /** A run of neighbouring bins that reads as one colour: a peak and the hues around it. */
    private class Family(val neutral: Boolean) {
        var count = 0.0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var saturation = 0.0
        var value = 0.0
        var peak = 0

        fun add(other: Bin) {
            count += other.count
            red += other.red
            green += other.green
            blue += other.blue
            saturation += other.saturation
            value += other.value
        }

        // Mostly how much of the cover it takes up, with a mild lean towards vivid colour so a
        // large drab area still wins over a small bright one, but not over an equal one.
        val score: Double
            get() {
                if (count == 0.0) return 0.0
                if (neutral) return count * 0.55
                return count * (0.7 + 0.3 * saturation / count) * (0.75 + 0.25 * value / count)
            }

        fun colour(): Int = pack((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private class Bin {
        var count = 0.0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var saturation = 0.0
        var value = 0.0
    }

    fun extract(image: NativeImage): IntArray {
        val bins = Array(HUE_BINS + NEUTRAL_BINS) { Bin() }
        val hsv = FloatArray(3)

        // An even grid over the whole cover, so no part of it is skipped.
        val step = maxOf(1, sqrt(image.width.toDouble() * image.height / MAX_SAMPLES).toInt())
        for (y in 0 until image.height step step) {
            for (x in 0 until image.width step step) {
                val pixel = image.getPixel(x, y)
                if ((pixel ushr 24) and 0xFF < 128) continue
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                Color.RGBtoHSB(r, g, b, hsv)
                val index = if (hsv[1] < 0.18f || hsv[2] < 0.12f) {
                    HUE_BINS + min(NEUTRAL_BINS - 1, (hsv[2] * NEUTRAL_BINS).toInt())
                } else {
                    (hsv[0] * HUE_BINS).toInt() % HUE_BINS
                }
                val bin = bins[index]
                bin.count += 1.0
                bin.red += r
                bin.green += g
                bin.blue += b
                bin.saturation += hsv[1]
                bin.value += hsv[2]
            }
        }

        val families = ArrayList<Family>()
        // Each strongest remaining hue claims its neighbours, so one colour spread across a few
        // bins counts as one colour instead of several smaller ones.
        val taken = BooleanArray(HUE_BINS)
        val smoothed = DoubleArray(HUE_BINS) { i ->
            bins[i].count + 0.5 * (bins[(i + HUE_BINS - 1) % HUE_BINS].count + bins[(i + 1) % HUE_BINS].count)
        }
        for (peak in (0 until HUE_BINS).sortedByDescending { smoothed[it] }) {
            if (taken[peak] || bins[peak].count == 0.0) continue
            val family = Family(neutral = false)
            family.peak = peak
            for (offset in -FAMILY_REACH..FAMILY_REACH) {
                val i = (peak + offset + HUE_BINS) % HUE_BINS
                if (taken[i]) continue
                taken[i] = true
                family.add(bins[i])
            }
            families += family
        }
        for (n in 0 until NEUTRAL_BINS) {
            val bin = bins[HUE_BINS + n]
            if (bin.count == 0.0) continue
            families += Family(neutral = true).also { it.add(bin) }
        }

        val chosen = ArrayList<Family>(4)
        for (family in families.sortedByDescending { it.score }) {
            if (chosen.size == 4) break
            // One grey at most, and hues far enough apart to read as different colours.
            val clashes = chosen.any { other ->
                if (family.neutral || other.neutral) family.neutral && other.neutral
                else hueGap(family.peak, other.peak) <= FAMILY_REACH * 2
            }
            if (!clashes) chosen += family
        }
        if (chosen.isEmpty()) return IDLE.copyOf()

        val colours = chosen.map { it.colour() }.toMutableList()
        var shift = 0
        while (colours.size < 4) {
            shift++
            colours += variant(colours[0], if (shift % 2 == 0) 0.05f * shift else -0.05f * shift, if (shift % 2 == 0) 0.8f else 1.15f)
        }
        return colours.map(::tone).toIntArray()
    }

    fun fromSeed(seed: String): IntArray {
        val hue = (abs(seed.hashCode()) % 360) / 360f
        return intArrayOf(
            hsv(hue, 0.58f, 0.70f),
            hsv(hue + 0.08f, 0.62f, 0.55f),
            hsv(hue - 0.07f, 0.50f, 0.62f),
            hsv(hue + 0.16f, 0.45f, 0.48f),
        ).map(::tone).toIntArray()
    }

    fun light(rgb: Int): Int {
        val hsv = Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
        return hsv(hsv[0], min(hsv[1], 0.38f), 1f)
    }

    fun mix(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun tone(rgb: Int): Int {
        val hsv = Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
        val saturation = if (hsv[1] < 0.16f) hsv[1] else (hsv[1] * 1.2f).coerceAtMost(0.9f)
        val value = 0.20f + hsv[2] * 0.40f
        return hsv(hsv[0], saturation, value)
    }

    private fun variant(rgb: Int, hueShift: Float, valueScale: Float): Int {
        val hsv = Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
        return hsv(hsv[0] + hueShift, hsv[1], (hsv[2] * valueScale).coerceIn(0f, 1f))
    }

    private fun hueGap(a: Int, b: Int): Int {
        val gap = abs(a - b)
        return min(gap, HUE_BINS - gap)
    }

    private fun hsv(hue: Float, saturation: Float, value: Float): Int =
        Color.HSBtoRGB(hue - kotlin.math.floor(hue), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)) and 0xFFFFFF

    private fun pack(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
