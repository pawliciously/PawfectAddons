package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class SecretWaypointsConfig {

    @Expose
    var enabled: Boolean = false

    @Expose
    var showNames: Boolean = true

    @Expose
    var throughWalls: Boolean = true

    @Expose
    var lineWidth: Float = 1.5f

    @Expose
    var opacity: Float = 1f

    @Expose
    var hideClaimed: Boolean = true

    @Expose
    var playSound: Boolean = true

    @Expose
    var soundId: String = "minecraft:entity.experience_orb.pickup"

    @Expose
    var soundVolume: Float = 0.6f

    @Expose
    var soundPitch: Float = 1f

    @Expose
    val categories: MutableMap<String, Style> = LinkedHashMap()

    fun style(category: String): Style = categories.getOrPut(category) { Style(defaultColor(category)) }

    class Style(
        @field:Expose var color: Int = 0x55FFFF,
        @field:Expose var enabled: Boolean = true,
        @field:Expose var lineWidth: Float = 0f,
    )

    companion object {
        val CATEGORIES = listOf(
            "entrance", "superboom", "chest", "item", "bat", "wither", "key",
            "lever", "fairysoul", "stonk", "aotv", "pearl", "prince", "default",
        )

        fun label(category: String): String = when (category) {
            "key" -> "Redstone Key"
            "fairysoul" -> "Fairy Soul"
            "aotv" -> "AOTV"
            else -> category.replaceFirstChar { it.uppercase() }
        }

        fun defaultColor(category: String): Int = when (category) {
            "entrance" -> 0x00FF00
            "superboom" -> 0xFF0000
            "chest" -> 0x02D5FA
            "item" -> 0x0240FA
            "bat" -> 0x8E4200
            "wither" -> 0x1E1E1E
            "key" -> 0xC81E1E
            "lever" -> 0xFAD902
            "fairysoul" -> 0xFF55FF
            "stonk" -> 0x9234EB
            "aotv" -> 0xFC6203
            "pearl" -> 0x39757D
            "prince" -> 0x85150D
            else -> 0xBEFFFC
        }
    }
}
