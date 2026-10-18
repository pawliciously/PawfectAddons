package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class DropCelebrationConfig {

    enum class Tier(private val label: String) {
        RARE("Rare"),
        VERY_RARE("Very Rare"),
        CRAZY_RARE("Crazy Rare"),
        INSANE("Insane");

        override fun toString(): String = label
    }

    enum class Style(private val label: String) {
        CELEBRATION("Celebration"),
        ITEM_GET("Item Get");

        override fun toString(): String = label
    }

    @Expose
    var enabled: Boolean = true

    @Expose
    var style: Style = Style.CELEBRATION

    @Expose
    var dungeonLoot: Boolean = true

    @Expose
    var dyes: Boolean = true

    @Expose
    var slayerDrops: Boolean = true

    @Expose
    var minTier: Tier = Tier.RARE

    @Expose
    var impactFrames: Boolean = true

    @Expose
    var shake: Boolean = true

    @Expose
    var worldEffects: Boolean = true

    @Expose
    var volume: Float = 0.8f

    @Expose
    var speed: Float = 1f
}
