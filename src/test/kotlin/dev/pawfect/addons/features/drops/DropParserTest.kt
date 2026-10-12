package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier
import dev.pawfect.addons.features.drops.DropParser.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DropParserTest {

    private val me = "pawliciously"

    @Test
    fun ownChestLoot() {
        // As it comes in chat, from the logs.
        val found = assertNotNull(DropParser.parse("    RARE REWARD! Fuming Potato Book", me))
        assertEquals("Fuming Potato Book", found.name)
        assertEquals(Tier.RARE, found.tier)
        assertEquals(Source.DUNGEON, found.source)
        assertEquals(Tier.VERY_RARE, DropParser.parse("    RARE REWARD! Recombobulator 3000", me)?.tier)
        assertEquals(Tier.INSANE, DropParser.parse("    RARE REWARD! Necron's Handle", me)?.tier)
    }

    @Test
    fun unlistedRareRewardStillCounts() {
        assertEquals(Tier.RARE, DropParser.parse("    RARE REWARD! Spirit Bone", me)?.tier)
    }

    @Test
    fun teammatesLootIsSkipped() {
        assertNull(DropParser.parse("RARE REWARD! FlameOfWar found a Recombobulator 3000 in their Bedrock Chest!", me))
    }

    @Test
    fun dyes() {
        val found = assertNotNull(DropParser.parse("WOW! [MVP+] pawliciously found a Pearlescent Dye!", me))
        assertEquals("Pearlescent Dye", found.name)
        assertEquals(Tier.INSANE, found.tier)
        assertEquals(Source.DYE, found.source)
        assertNull(DropParser.parse("WOW! [MVP+] xVertraeumter found a Pearlescent Dye!", me))
        assertNotNull(DropParser.parse("WOW! pawliciously found a Necron Dye!", me))
    }

    @Test
    fun slayerDrops() {
        val found = assertNotNull(DropParser.parse("RARE DROP! (Judgement Core) (+250% ✯ Magic Find)", me))
        assertEquals("Judgement Core", found.name)
        assertEquals(Source.SLAYER, found.source)
        assertNotNull(DropParser.parse("CRAZY RARE DROP! (Warden Heart)", me))
        assertNull(DropParser.parse("CRAZY RARE DROP! You dropped 120x Enchanted Brown Mushroom!", me))
    }

    @Test
    fun plainChestLines() {
        assertEquals(Tier.INSANE, DropParser.parse("   Necron Dye", me)?.tier)
        assertNull(DropParser.parse("   Judgement Core", me))
        assertNull(DropParser.parse("   Team Score: 302 (S+)", me))
    }
}
