package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier

object DropTable {

    class Drop(val name: String, val id: String, val tier: Tier, val dungeon: Boolean)

    private val drops = listOf(
        Drop("Necron's Handle", "NECRON_HANDLE", Tier.INSANE, true),
        Drop("Shiny Necron's Handle", "NECRON_HANDLE", Tier.INSANE, true),
        Drop("Necron Dye", "NECRON_DYE", Tier.INSANE, true),
        Drop("Dark Claymore", "DARK_CLAYMORE", Tier.INSANE, true),
        Drop("Fifth Master Star", "FIFTH_MASTER_STAR", Tier.INSANE, true),
        Drop("Master Skull - Tier 5", "MASTER_SKULL_TIER_5", Tier.INSANE, true),
        Drop("Giant's Sword", "GIANTS_SWORD", Tier.CRAZY_RARE, true),
        Drop("Implosion", "IMPLOSION_SCROLL", Tier.CRAZY_RARE, true),
        Drop("Shadow Warp", "SHADOW_WARP_SCROLL", Tier.CRAZY_RARE, true),
        Drop("Wither Shield", "WITHER_SHIELD_SCROLL", Tier.CRAZY_RARE, true),
        Drop("Fourth Master Star", "FOURTH_MASTER_STAR", Tier.CRAZY_RARE, true),
        Drop("Third Master Star", "THIRD_MASTER_STAR", Tier.CRAZY_RARE, true),
        Drop("Recombobulator 3000", "RECOMBOBULATOR_3000", Tier.VERY_RARE, true),
        Drop("Spirit Mask", "SPIRIT_MASK", Tier.VERY_RARE, true),
        Drop("Shadow Fury", "SHADOW_FURY", Tier.VERY_RARE, true),
        Drop("Second Master Star", "SECOND_MASTER_STAR", Tier.VERY_RARE, true),
        Drop("First Master Star", "FIRST_MASTER_STAR", Tier.VERY_RARE, true),
        Drop("Fuming Potato Book", "FUMING_POTATO_BOOK", Tier.RARE, true),

        Drop("Judgement Core", "JUDGEMENT_CORE", Tier.INSANE, false),
        Drop("Warden Heart", "WARDEN_HEART", Tier.INSANE, false),
        Drop("Overflux Capacitor", "OVERFLUX_CAPACITOR", Tier.INSANE, false),
        Drop("Enchanted Book (Chimera I)", "ULTIMATE_CHIMERA;1", Tier.INSANE, false),
        Drop("Scythe Blade", "SCYTHE_BLADE", Tier.CRAZY_RARE, false),
        Drop("Shredded Sinew", "SHARD_OF_THE_SHREDDED", Tier.CRAZY_RARE, false),
        Drop("High Class Archfiend Dice", "HIGH_CLASS_ARCHFIEND_DICE", Tier.CRAZY_RARE, false),
        Drop("End Stone Idol", "END_STONE_IDOL", Tier.CRAZY_RARE, false),
        Drop("Primordial Eye", "PRIMORDIAL_EYE", Tier.CRAZY_RARE, false),
        Drop("Minos Relic", "MINOS_RELIC", Tier.CRAZY_RARE, false),
        Drop("Manti-core", "MANTI_CORE", Tier.CRAZY_RARE, false),
        Drop("Fateful Stinger", "FATEFUL_STINGER", Tier.CRAZY_RARE, false),
        Drop("Radioactive Vial", "RADIOACTIVE_VIAL", Tier.CRAZY_RARE, false),
        Drop("Tiki Mask", "TIKI_MASK", Tier.CRAZY_RARE, false),
        Drop("Titanoboa Shed", "TITANOBOA_SHED", Tier.CRAZY_RARE, false),
        Drop("Severed Hand", "SEVERED_HAND", Tier.VERY_RARE, false),
        Drop("Shriveled Wasp", "SHRIVELED_WASP", Tier.VERY_RARE, false),
        Drop("Digested Mosquito", "DIGESTED_MOSQUITO", Tier.VERY_RARE, false),
        Drop("Ensnared Snail", "ENSNARED_SNAIL", Tier.VERY_RARE, false),
        Drop("Shimmering Wool", "SHIMMERING_WOOL", Tier.VERY_RARE, false),
        Drop("Pocket-sized Igloo", "POCKET_SIZED_IGLOO", Tier.VERY_RARE, false),
        Drop("Prince's Crown Jewel", "PRINCES_CROWN_JEWEL", Tier.VERY_RARE, false),
    )

    private val byName = drops.associateBy { it.name.lowercase() }
    private val byId = drops.associateBy { it.id }

    val names: List<String> get() = drops.map { it.name }

    fun find(name: String): Drop? = byName[name.trim().lowercase()]

    fun findId(id: String): Drop? = byId[id.trim().uppercase()]

    fun sample(tier: Tier): Drop = when (tier) {
        Tier.RARE -> byName.getValue("fuming potato book")
        Tier.VERY_RARE -> byName.getValue("recombobulator 3000")
        Tier.CRAZY_RARE -> byName.getValue("giant's sword")
        Tier.INSANE -> byName.getValue("necron's handle")
    }
}
