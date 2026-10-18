package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.world.level.GameType

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
        var order = ""
    }

    private val TAB_LINE = Regex("""^\[\d+] (?:\[[^\]]+] |[^\w\[\s]+ )*(\w{1,16})(?: [^(]*)?\((\w+)(?: ([IVXLCDM]+))?\)$""")

    private val members = LinkedHashMap<String, Member>()
    private var level: Any? = null
    private var tick = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { update() } }
    }

    fun members(): Collection<Member> = members.values

    fun member(name: String): Member? = members[name.lowercase()]

    fun ordered(): List<Member> = members.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.order })

    private fun update() {
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
            val spectator = if (info.gameMode == GameType.SPECTATOR) "1" else "0"
            member.order = spectator + "\u0000" + (info.team?.name ?: "") + "\u0000" + info.profile.name
            if (status.equals("DEAD", ignoreCase = true)) {
                member.dead = true
            } else {
                member.dead = false
                DungeonClass.of(status)?.let { member.dungeonClass = it }
            }
        }
    }
}
