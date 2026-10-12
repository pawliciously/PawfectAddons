package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier

/** Each tier's colour, and the lighter one its light glows with. */
object DropColors {

    fun of(tier: Tier): Int = when (tier) {
        Tier.RARE -> 0x4FA8FF
        Tier.VERY_RARE -> 0xB45CFF
        Tier.CRAZY_RARE -> 0xFFA62B
        Tier.INSANE -> 0xFF2E63
    }

    fun glowOf(tier: Tier): Int = when (tier) {
        Tier.RARE -> 0xBFE2FF
        Tier.VERY_RARE -> 0xE6C6FF
        Tier.CRAZY_RARE -> 0xFFE7A8
        Tier.INSANE -> 0xFFD1A3
    }

    fun label(tier: Tier): String = when (tier) {
        Tier.RARE -> "RARE DROP!"
        Tier.VERY_RARE -> "VERY RARE DROP!"
        Tier.CRAZY_RARE -> "CRAZY RARE DROP!"
        Tier.INSANE -> "INSANE DROP!"
    }
}
