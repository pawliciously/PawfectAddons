package dev.pawfect.addons.config.core

object HudDefaults {

    const val RECIPE_TRACKER = "Recipe Tracker"
    const val SLAYER_HEALTH = "Slayer Health"
    const val STAT_HEALTH = "Health"
    const val STAT_DEFENSE = "Defense"
    const val STAT_MANA = "Mana"
    const val STAT_OVERFLOW = "Overflow"
    const val STAT_VITALITY = "Vitality"
    const val MEDIA = "Media"
    const val PACKET_LOG = "Packet Log"

    class Default(val x: Int, val y: Int, val scale: Float = Position.DEFAULT_SCALE)

    private val defaults = mapOf(
        RECIPE_TRACKER to Default(10, 60),
        SLAYER_HEALTH to Default(-10, 60),
        STAT_HEALTH to Default(10, 120),
        STAT_DEFENSE to Default(10, 132),
        STAT_MANA to Default(10, 144),
        STAT_OVERFLOW to Default(10, 156),
        STAT_VITALITY to Default(10, 168),
        MEDIA to Default(-10, -40),
        PACKET_LOG to Default(10, 190),
    )

    fun position(label: String): Position {
        val default = defaults[label] ?: Default(10, 10)
        return Position(default.x, default.y, default.scale)
    }

    fun defaultFor(label: String): Default? = defaults[label]

    fun reset(position: Position): Boolean {
        val default = defaults[position.label] ?: return false
        position.moveTo(default.x, default.y)
        position.scale = default.scale
        return true
    }

    fun resetAll(positions: List<Position>) {
        positions.forEach { reset(it) }
    }
}
