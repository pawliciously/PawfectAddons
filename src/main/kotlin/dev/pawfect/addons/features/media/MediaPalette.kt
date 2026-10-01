package dev.pawfect.addons.features.media

import com.mojang.blaze3d.platform.NativeImage
import java.awt.Color
import kotlin.math.abs
import kotlin.math.min

object MediaPalette {

    private const val HUE_BINS = 24
    private const val NEUTRAL_BINS = 3
    private const val MAX_SAMPLES = 6000

    val IDLE: IntArray = intArrayOf(0x2B2F3E, 0x363A4E, 0x23262F, 0x3B3652)

    fun extract(image: NativeImage): IntArray {
        val count = DoubleArray(HUE_BINS + NEUTRAL_BINS)
        val red = DoubleArray(count.size)
        val green = DoubleArray(count.size)
        val blue = DoubleArray(count.size)
        val saturation = DoubleArray(count.size)
        val value = DoubleArray(count.size)
        val hsv = FloatArray(3)

        val pixels = image.width * image.height
        val stride = maxOf(1, pixels / MAX_SAMPLES)
        var index = 0
        while (index < pixels) {
            val pixel = image.getPixel(index % image.width, index / image.width)
            index += stride
            if ((pixel ushr 24) and 0xFF < 128) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            Color.RGBtoHSB(r, g, b, hsv)
            val bin = if (hsv[1] < 0.16f || hsv[2] < 0.10f) {
                HUE_BINS + min(NEUTRAL_BINS - 1, (hsv[2] * NEUTRAL_BINS).toInt())
            } else {
                (hsv[0] * HUE_BINS).toInt() % HUE_BINS
            }
            count[bin] += 1.0
            red[bin] += r.toDouble()
            green[bin] += g.toDouble()
            blue[bin] += b.toDouble()
            saturation[bin] += hsv[1].toDouble()
            value[bin] += hsv[2].toDouble()
        }

        val score = DoubleArray(count.size) { bin ->
            val n = count[bin]
            if (n == 0.0) return@DoubleArray 0.0
            if (bin >= HUE_BINS) return@DoubleArray n * 0.22
            n * (0.3 + saturation[bin] / n) * (0.35 + value[bin] / n)
        }

        val chosen = ArrayList<Int>(4)
        for (bin in score.indices.sortedByDescending { score[it] }) {
            if (score[bin] <= 0.0 || chosen.size == 4) break
            val clashes = chosen.any { other ->
                if (bin >= HUE_BINS || other >= HUE_BINS) bin >= HUE_BINS && other >= HUE_BINS
                else hueGap(bin, other) < 2
            }
            if (!clashes) chosen += bin
        }
        if (chosen.isEmpty()) return IDLE.copyOf()

        val colours = chosen.map { bin ->
            val n = count[bin]
            pack((red[bin] / n).toInt(), (green[bin] / n).toInt(), (blue[bin] / n).toInt())
        }.toMutableList()
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
