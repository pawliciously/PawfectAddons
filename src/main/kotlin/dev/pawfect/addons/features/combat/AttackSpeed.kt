package dev.pawfect.addons.features.combat

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

object AttackSpeed {

    private const val REFRESH_TICKS = 10
    private const val BASE_TICKS = 10.0

    private val LINE = Regex(
        """(?i)(?:bonus\s+)?(?:attack\s+speed|atk\s*spd)\s*[:\s]+[^\d+\-]{0,8}([+\-]?\d+(?:\.\d+)?)""",
    )

    private var bonus = 0f
    private var known = false
    private var lastTick = Int.MIN_VALUE
    private var tick = 0

    fun reset() {
        bonus = 0f
        known = false
        lastTick = Int.MIN_VALUE
        tick = 0
    }

    fun onTick() {
        tick++
        if (tick - lastTick < REFRESH_TICKS) return
        lastTick = tick
        val read = readTab()
        if (read != null) {
            bonus = read
            known = true
        }
    }

    fun meleeDelay(): Int {
        if (!SkyBlockData.onSkyBlock) return 1
        return meleeTicks(if (known) bonus else 0f)
    }

    fun arrowDelay(): Int {
        if (!SkyBlockData.onSkyBlock) return 1
        return shortbowTicks(if (known) bonus else 0f)
    }

    fun meleeTicks(value: Float): Int =
        max(1, (BASE_TICKS / (1.0 + clamp(value) / 100.0)).roundToInt())

    fun shortbowTicks(value: Float): Int =
        max(1, ceil(BASE_TICKS / (1.0 + clamp(value) / 100.0)).toInt())

    private fun clamp(value: Float): Float = value.coerceIn(-99f, 150f)

    private fun readTab(): Float? {
        val connection = McCompat.mc.player?.connection ?: return null
        for (info in connection.listedOnlinePlayers) {
            val raw = info.tabListDisplayName ?: continue
            val match = LINE.find(raw.string.removeColor()) ?: continue
            val parsed = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: continue
            return parsed
        }
        return null
    }
}
