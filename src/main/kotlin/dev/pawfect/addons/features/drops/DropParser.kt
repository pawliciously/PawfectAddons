package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier

/**
 * Reads your own rare drops out of chat. Only yours: teammates' chest loot comes as
 * "RARE REWARD! Name found a ... in their ... Chest!", which isn't indented and is skipped.
 */
object DropParser {

    enum class Source { DUNGEON, DYE, SLAYER }

    class Found(val name: String, val id: String?, val tier: Tier, val source: Source)

    /** Your chest's own loot: "    RARE REWARD! Fuming Potato Book". */
    private val rareReward = Regex("""^\s{3,}RARE REWARD! (.+)$""")

    /** A dye anyone finds is announced to the server: "WOW! [MVP+] Name found a Pearlescent Dye!". */
    private val dye = Regex("""^WOW! (?:\[[^\]]+] )?(\w+) found an? (.+ Dye)!$""")

    /** Boss and mob drops: "RARE DROP! (Judgement Core) (+250% ✯ Magic Find)". */
    private val rareDrop = Regex("""^(?:RARE|VERY RARE|CRAZY RARE|INSANE) DROP!\s+\(?(.+?)\)?(?:\s+\(\+[^)]*Magic Find\))?$""")

    /** A chest's loot listed one to a line, indented, for the ones Hypixel doesn't flag. */
    private val chestLine = Regex("""^\s{3,}(?!.*:)(\S.*)$""")

    fun parse(line: String, me: String): Found? {
        rareReward.matchEntire(line)?.let { match ->
            val name = match.groupValues[1].trim()
            val drop = DropTable.find(name)
            return Found(drop?.name ?: name, drop?.id, drop?.tier ?: Tier.RARE, Source.DUNGEON)
        }
        val trimmed = line.trim()
        dye.matchEntire(trimmed)?.let { match ->
            if (!match.groupValues[1].equals(me, ignoreCase = true)) return null
            val name = match.groupValues[2]
            return Found(name, DropTable.find(name)?.id, Tier.INSANE, Source.DYE)
        }
        rareDrop.matchEntire(trimmed)?.let { match ->
            val drop = DropTable.find(match.groupValues[1]) ?: return null
            return Found(drop.name, drop.id, drop.tier, Source.SLAYER)
        }
        chestLine.matchEntire(line)?.let { match ->
            val drop = DropTable.find(match.groupValues[1])?.takeIf { it.dungeon } ?: return null
            return Found(drop.name, drop.id, drop.tier, Source.DUNGEON)
        }
        return null
    }
}
