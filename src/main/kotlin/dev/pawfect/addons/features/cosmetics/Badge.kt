package dev.pawfect.addons.features.cosmetics

import dev.pawfect.addons.PawfectAddons
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

enum class Badge(
    val id: String,
    val label: String,
    val glyph: String,
    val color: Int,
) {
    DEVELOPER("developer", "Developer", "\uE900", 0xFFC94D),
    FOUNDER("og", "Founder", "\uE901", 0x8A6CFF),
    SUPPORTER("supporter", "Supporter", "\uE902", 0x4FD98A),
    CONTRIBUTOR("contributor", "Contributor", "\uE903", 0x56B8FF),
    BUG_HUNTER("bug_hunter", "Bug Hunter", "\uE904", 0xFF6B5C),
    BETA("beta", "Beta Tester", "\uE905", 0xFF6FD8),
    ;

    val component: Component by lazy { Component.literal(glyph).setStyle(STYLE.withColor(color)) }

    companion object {

        private val STYLE: Style = Style.EMPTY
            .withFont(FontDescription.Resource(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "badge")))

        private val BY_ID: Map<String, Badge> = buildMap {
            Badge.entries.forEach { put(it.id, it) }
            put("founder", FOUNDER)
            put("bughunter", BUG_HUNTER)
            put("beta_tester", BETA)
        }

        fun of(id: String?): Badge? = BY_ID[id?.trim()?.lowercase()]

        fun ordered(ids: Collection<String>?): List<Badge> {
            if (ids.isNullOrEmpty()) return emptyList()
            val owned = ids.mapNotNullTo(HashSet()) { of(it) }
            return entries.filter { it in owned }
        }
    }
}
