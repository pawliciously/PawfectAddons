package dev.pawfect.addons.features.cosmetics.celestial

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A picked colour for one cosmetic. Matches paintFor on the website: near-grey drains the colour
 * for a silver look, anything else turns the style's base hue onto the pick and dark picks darken
 * it. Styles with no base hue cycle colours themselves and get narrowed to the picked hue instead.
 */
class Paint private constructor(private val matrix: FloatArray?, val hue: Float?) {

    fun apply(rgb: Int): Int {
        val m = matrix ?: return rgb
        val r = (rgb shr 16) and 255
        val g = (rgb shr 8) and 255
        val b = rgb and 255
        fun ch(i: Int) = (m[i] * r + m[i + 1] * g + m[i + 2] * b).toInt().coerceIn(0, 255)
        return (ch(0) shl 16) or (ch(3) shl 8) or ch(6)
    }

    companion object {
        private val cache = HashMap<String, Paint?>()

        /** The paint the frame is drawing with, set per cosmetic before it draws. */
        var active: Paint? = null

        /** Hue for styles that cycle colours: theirs, or swaying around the picked one. */
        fun hue(h: Float): Float {
            val target = active?.hue ?: return h
            return target + 0.06f * sin(h * Motion.TAU)
        }

        fun of(piece: Piece, hex: String?): Paint? {
            if (hex == null) return null
            return cache.getOrPut("${piece.baseHue}|$hex") { build(piece.baseHue, hex) }
        }

        private fun build(baseHue: Float?, hex: String): Paint? {
            val rgb = hex.removePrefix("#").toIntOrNull(16) ?: return null
            val r = ((rgb shr 16) and 255) / 255f
            val g = ((rgb shr 8) and 255) / 255f
            val b = (rgb and 255) / 255f
            val hi = max(r, max(g, b))
            val d = hi - min(r, min(g, b))
            val s = if (hi == 0f) 0f else d / hi
            var h = 0f
            if (d > 0f) {
                h = when (hi) {
                    r -> ((g - b) / d + 6f) % 6f
                    g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                } / 6f
            }
            val dim = 0.4f + 0.6f * hi
            return when {
                s < 0.12f -> Paint(scaled(saturate(0.08f), dim), null)
                baseHue == null -> Paint(if (dim < 0.999f) scaled(saturate(1f), dim) else null, h)
                else -> Paint(scaled(hueRotate(h - baseHue), dim), null)
            }
        }

        private fun scaled(m: FloatArray, k: Float) = FloatArray(9) { m[it] * k }

        private fun hueRotate(turn: Float): FloatArray {
            val c = cos(turn * Motion.TAU)
            val s = sin(turn * Motion.TAU)
            return floatArrayOf(
                0.213f + 0.787f * c - 0.213f * s, 0.715f - 0.715f * c - 0.715f * s, 0.072f - 0.072f * c + 0.928f * s,
                0.213f - 0.213f * c + 0.143f * s, 0.715f + 0.285f * c + 0.14f * s, 0.072f - 0.072f * c - 0.283f * s,
                0.213f - 0.213f * c - 0.787f * s, 0.715f - 0.715f * c + 0.715f * s, 0.072f + 0.928f * c + 0.072f * s,
            )
        }

        private fun saturate(s: Float) = floatArrayOf(
            0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
            0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
            0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s,
        )
    }
}
