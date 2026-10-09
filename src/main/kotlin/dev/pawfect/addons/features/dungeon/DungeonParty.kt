package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Who's in your dungeon run and what class each of them plays, read from the tab list
 * ("[45] Steve (Mage XXVI)"). A player's line says "(DEAD)" once they die, so their class is
 * remembered from before.
 */
object DungeonParty {

    enum class DungeonClass(val label: String, val letter: String, val color: Int) {
        ARCHER("Archer", "A", 0xFFAA00),
        BERSERKER("Berserker", "B", 0xFF5555),
        MAGE("Mage", "M", 0x55FFFF),
        TANK("Tank", "T", 0x55AA55),
        HEALER("Healer", "H", 0xFF55FF);

        companion object {
            fun of(text: String): DungeonClass? = when (text.lowercase()) {
                "archer" -> ARCHER
                "berserk", "berserker" -> BERSERKER
                "mage" -> MAGE
                "tank" -> TANK
                "healer" -> HEALER
                else -> null
            }
        }
    }

    class Member(val name: String) {
        var dungeonClass: DungeonClass? = null
        var dead = false
    }

    private val TAB_LINE = Regex("""^\[\d+] (?:\[[^\]]+] |[^\w\[\s]+ )*(\w{1,16})(?: [^(]*)?\((\w+)(?: ([IVXLCDM]+))?\)$""")

    private val members = LinkedHashMap<String, Member>()
    /** The world the party was read in; a new run is a new world. */
    private var level: Any? = null
    private var tick = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { update() } }
    }

    /** Everyone in the run, you included, by name in lower case. */
    fun members(): Collection<Member> = members.values

    fun member(name: String): Member? = members[name.lowercase()]

    private fun update() {
        // Cleared per world, not when the scoreboard blinks, so nobody's class is forgotten mid-run.
        val current = McCompat.mc.level
        if (current !== level) {
            level = current
            members.clear()
        }
        if (!SkyBlockData.inDungeons || tick++ % 10 != 0) return
        val connection = McCompat.mc.connection ?: return
        for (info in connection.listedOnlinePlayers) {
            val text = info.tabListDisplayName?.string?.removeColor()?.trim() ?: continue
            val match = TAB_LINE.find(text) ?: continue
            val name = match.groupValues[1]
            val status = match.groupValues[2]
            if (status.equals("EMPTY", ignoreCase = true)) continue
            val member = members.getOrPut(name.lowercase()) { Member(name) }
            if (status.equals("DEAD", ignoreCase = true)) {
                member.dead = true
            } else {
                member.dead = false
                DungeonClass.of(status)?.let { member.dungeonClass = it }
            }
        }
    }
}
