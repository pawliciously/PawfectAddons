package dev.pawfect.addons.utils

import java.util.Locale

object NumberUtil {

    private val SUFFIXES = listOf("" to 1L, "k" to 1_000L, "m" to 1_000_000L, "b" to 1_000_000_000L)

    fun Int.addSeparators(): String = String.format(Locale.US, "%,d", this)
    fun Long.addSeparators(): String = String.format(Locale.US, "%,d", this)

    fun Long.shortFormat(): String {
        if (this < 0) return "-" + (-this).shortFormat()
        if (this < 1_000) return this.toString()
        val (suffix, divisor) = SUFFIXES.last { this >= it.second }
        val value = this.toDouble() / divisor
        return if (value >= 100) "${value.toLong()}$suffix"
        else String.format(Locale.US, "%.1f%s", value, suffix)
    }

    fun Int.shortFormat(): String = this.toLong().shortFormat()
    fun Double.shortFormat(): String = this.toLong().shortFormat()

    fun String.parseHypixelNumber(): Long? {
        val cleaned = trim().replace(",", "").replace("_", "")
        if (cleaned.isEmpty()) return null

        val multiplier = when (cleaned.last().lowercaseChar()) {
            'k' -> 1_000L
            'm' -> 1_000_000L
            'b' -> 1_000_000_000L
            else -> null
        }
        val numberPart = if (multiplier != null) cleaned.dropLast(1) else cleaned
        val parsed = numberPart.toDoubleOrNull() ?: return null
        return (parsed * (multiplier ?: 1L)).toLong()
    }

    fun String.parseHypixelInt(): Int? = parseHypixelNumber()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt()
}
