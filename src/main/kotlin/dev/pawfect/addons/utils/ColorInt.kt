package dev.pawfect.addons.utils

object ColorInt {

    private const val OPAQUE = 0xFF000000.toInt()

    fun normalize(value: Int): Int = if (value ushr 24 == 0) value or OPAQUE else value

    fun rgb(value: Int): Int = value and 0xFFFFFF

    fun alphaByte(value: Int): Int = (normalize(value) ushr 24) and 0xFF

    fun alpha(value: Int): Float = alphaByte(value) / 255f

    fun pack(rgb: Int, alphaByte: Int): Int =
        ((alphaByte.coerceIn(1, 255)) shl 24) or (rgb and 0xFFFFFF)
}
