package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class DropCelebrationConfig {

    /** How hard a drop is to get, which sets how hard its celebration hits. */
    enum class Tier(private val label: String) {
        RARE("Rare"),
        VERY_RARE("Very Rare"),
        CRAZY_RARE("Crazy Rare"),
        INSANE("Insane");

        override fun toString(): String = label
    }

    @Expose
    var enabled: Boolean = true

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
}
