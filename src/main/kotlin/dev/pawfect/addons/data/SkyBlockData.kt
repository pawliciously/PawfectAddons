package dev.pawfect.addons.data

import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.world.scores.DisplaySlot

object SkyBlockData {

    private val profileRegex = Regex("Profile: (?<profile>[A-Za-z]+)")

    var currentProfile: String = UNKNOWN_PROFILE
        private set

    var sidebarLines: List<String> = emptyList()
        private set

    val playerUuid: String
        get() = McCompat.player?.uuid?.toString() ?: UNKNOWN_PLAYER

    val onSkyBlock: Boolean
        get() {
            val level = McCompat.mc.level ?: return false
            val objective = level.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false
            return objective.displayName.string.removeColor().contains("SKYBLOCK", ignoreCase = true)
        }

    val inDungeons: Boolean
        get() = sidebarLines.any { it.contains("The Catacombs") || it.contains("Dungeon Cleared") }

    fun refreshSidebar() {
        val level = McCompat.mc.level
        if (level == null) {
            sidebarLines = emptyList()
            return
        }

        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (objective == null) {
            sidebarLines = emptyList()
            return
        }

        val lines = ArrayList<String>()
        for (holder in scoreboard.trackedPlayers) {
            if (!scoreboard.listPlayerScores(holder).containsKey(objective)) continue
            val team = scoreboard.getPlayersTeam(holder.scoreboardName) ?: continue
            val raw = team.playerPrefix.string + team.playerSuffix.string
            if (raw.isBlank()) continue
            lines.add(raw.removeColor())
        }
        lines.reverse()
        lines.add(0, objective.displayName.string.removeColor())
        sidebarLines = lines
    }

    fun refreshProfile() {
        if (!onSkyBlock) return
        val connection = McCompat.mc.connection ?: return

        for (info in connection.onlinePlayers) {
            val text = info.tabListDisplayName?.string?.removeColor() ?: continue
            val match = profileRegex.find(text) ?: continue
            val profile = match.groups["profile"]?.value ?: continue
            if (profile != currentProfile) currentProfile = profile
            return
        }
    }

    const val UNKNOWN_PROFILE = "unknown"
    const val UNKNOWN_PLAYER = "unknown"
}
