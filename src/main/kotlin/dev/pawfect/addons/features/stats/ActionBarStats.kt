package dev.pawfect.addons.features.stats

import net.minecraft.network.chat.Component

object ActionBarStats {

    const val ICON_HEALTH = '\uE010'
    const val ICON_DEFENSE = '\uE008'
    const val ICON_MANA = '\uE003'
    const val ICON_OVERFLOW = '\uE017'
    const val ICON_VITALITY = '\uE028'

    private val statRegex = Regex(
        "(?:\u00A7(.))?(\\d[\\d,]*)(?:/(\\d[\\d,]*))?\\s*([\\uE000-\\uF8FF])[ ]*",
    )

    class Stat(val current: Int, val max: Int?, val colorCode: Char?) {
        val percent: Double
            get() {
                val limit = max ?: return 100.0
                if (limit <= 0) return 100.0
                return current.toDouble() / limit * 100.0
            }
    }

    var health: Stat? = null
        private set

    var defense: Stat? = null
        private set

    var mana: Stat? = null
        private set

    var overflowMana: Stat? = null
        private set

    var vitality: Stat? = null
        private set

    private var segments: Map<Char, IntRange> = emptyMap()
    private var lastRaw: String = ""

    var lastSeen: Long = 0L
        private set

    val hasData: Boolean get() = System.currentTimeMillis() - lastSeen < STALE_MS

    fun onActionBar(message: Component) {
        val text = message.string
        if (!text.any { it in '\uE000'..'\uF8FF' }) return

        var foundHealth: Stat? = null
        var foundDefense: Stat? = null
        var foundMana: Stat? = null
        var foundOverflow: Stat? = null
        var foundVitality: Stat? = null
        val found = HashMap<Char, IntRange>()

        for (match in statRegex.findAll(text)) {
            val current = match.groupValues[2].replace(",", "").toIntOrNull() ?: continue
            val max = match.groupValues[3].takeIf { it.isNotEmpty() }?.replace(",", "")?.toIntOrNull()
            val color = match.groupValues[1].firstOrNull()
            val icon = match.groupValues[4].firstOrNull() ?: continue
            val stat = Stat(current, max, color)

            found[icon] = match.range
            when (icon) {
                ICON_HEALTH -> foundHealth = stat
                ICON_DEFENSE -> foundDefense = stat
                ICON_MANA -> foundMana = stat
                ICON_OVERFLOW -> foundOverflow = stat
                ICON_VITALITY -> foundVitality = stat
                else -> {}
            }
        }

        health = foundHealth
        defense = foundDefense
        mana = foundMana
        overflowMana = foundOverflow
        vitality = foundVitality
        segments = found
        lastRaw = text
        lastSeen = System.currentTimeMillis()
    }

    fun withoutIcons(message: Component, icons: Set<Char>): Component? {
        if (icons.isEmpty()) return null
        val text = message.string
        if (text != lastRaw || segments.isEmpty()) return null

        val ranges = icons.mapNotNull { segments[it] }.sortedByDescending { it.first }
        if (ranges.isEmpty()) return null

        val builder = StringBuilder(text)
        for (range in ranges) builder.delete(range.first, range.last + 1)

        val result = builder.toString()
        if (result.isBlank()) return Component.empty()
        return Component.literal(result)
    }

    fun reset() {
        health = null
        defense = null
        mana = null
        overflowMana = null
        vitality = null
        segments = emptyMap()
        lastRaw = ""
        lastSeen = 0L
    }

    private const val STALE_MS = 5_000L
}
