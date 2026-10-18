package dev.pawfect.addons.features.drops

import dev.pawfect.addons.config.features.DropCelebrationConfig.Tier

object DropParser {

    enum class Source { DUNGEON, DYE, SLAYER }

    class Found(val name: String, val id: String?, val tier: Tier, val source: Source)

    private val rareReward = Regex("""^\s{3,}RARE REWARD! (.+)$""")

    private val dye = Regex("""^WOW! (?:\[[^\]]+] )?(\w+) found an? (.+ Dye)!$""")

    private val rareDrop = Regex("""^(?:RARE|VERY RARE|CRAZY RARE|INSANE) DROP!\s+\(?(.+?)\)?(?:\s+\(\+[^)]*Magic Find\))?$""")

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
