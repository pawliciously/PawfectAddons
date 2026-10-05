package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.ToggleSetting
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.ui.Draw.pill
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.sin

/**
 * What the Chroma menu shares with its folded watermark: the tabs, each with its own colour,
 * and the moving gradient strip along the top.
 */
object Chroma {

    class TabDef(val id: String, val title: String, val icon: String, val hue: Int, val categories: List<String>)

    const val FAVOURITES = "fav"

    val TABS = listOf(
        TabDef(FAVOURITES, "Favourites", Icons.SPARKLE, 0xFFC83D, emptyList()),
        TabDef("dungeon", "Dungeons", Icons.SHIELD, 0xFF5470, listOf("dungeon")),
        TabDef("slayers", "Slayers", Icons.FIRE, 0xFF9A3C, listOf("slayers")),
        TabDef("visuals", "Visuals", Icons.EYE, 0x39D0FF, listOf("visuals", "skybox")),
        TabDef("chams", "Chams", Icons.CHAMELEON, 0xD65CFF, listOf("chams")),
        TabDef("interface", "Interface", Icons.TAG, 0x6F8CFF, listOf("chat", "scoreboard", "inventory", "media")),
        TabDef("sounds", "Sounds", Icons.MUSIC, 0x2EE6C8, listOf("hitsounds")),
        TabDef("misc", "Misc", Icons.PACKAGE, 0x4BE08A, listOf("recipetracker", "experiments")),
        TabDef("social", "Social", Icons.USER, 0xFF6FB5, listOf("cosmetics", "discord")),
        TabDef("config", "Config", Icons.PALETTE, 0xB9B9C8, listOf("theme")),
        TabDef("dev", "Dev", Icons.GEAR, 0x9D4EDD, listOf("dev")),
    )

    fun hueFor(categoryId: String): Int = TABS.firstOrNull { categoryId in it.categories }?.hue ?: 0xB9B9C8

    fun pinKey(category: SettingCategory, groupTitle: String, setting: Setting<*>): String =
        "${category.id}/$groupTitle/${setting.name}"

    val pins: MutableList<String> get() = ConfigManager.features.theme.chromaPins

    /**
     * The strip: a band of the accent and a lighter shade of it that drifts along, plus a bright
     * sweep that runs across once whenever the tab changes ([sweep] goes 0 to 1, or stays 1).
     */
    fun drawStrip(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, accent: Int, sweep: Float = 1f) {
        if (width <= 0f) return
        val light = Theme.mix(accent, 0xFFFFFF, 0.55f)
        val time = if (Theme.animationsEnabled) (System.currentTimeMillis() % STRIP_PERIOD_MS) / STRIP_PERIOD_MS.toFloat() else 0f
        fun colour(at: Float): Int {
            val wave = 0.5f + 0.5f * sin((2.0 * PI * (at * 1.5f - time)).toFloat())
            var c = Theme.lerp(Theme.opaque(accent), Theme.opaque(light), wave * 0.85f)
            if (sweep < 1f) {
                val distance = kotlin.math.abs(at - sweep)
                if (distance < SWEEP_WIDTH) c = Theme.lerp(c, Theme.opaque(0xFFFFFF), (1f - distance / SWEEP_WIDTH) * 0.8f)
            }
            return c
        }
        val slice = width / STRIP_SLICES
        for (i in 0 until STRIP_SLICES) {
            val a = i / STRIP_SLICES.toFloat()
            val b = (i + 1) / STRIP_SLICES.toFloat()
            graphics.gradientHorizontal(x + i * slice, y, slice + 0.6f, height, colour(a), colour(b))
        }
        graphics.pill(x, y + height, width, 0.5f, Theme.withAlpha(0x000000, 90))
    }

    fun pinnedToggles(categories: List<SettingCategory>): List<Pair<SettingCategory, ToggleSetting>> {
        val wanted = pins.toSet()
        return categories.flatMap { category ->
            category.groups.flatMap { group ->
                group.settings.filterIsInstance<ToggleSetting>()
                    .filter { pinKey(category, group.title, it) in wanted }
                    .map { category to it }
            }
        }
    }

    private const val STRIP_PERIOD_MS = 2600L
    private const val STRIP_SLICES = 36
    private const val SWEEP_WIDTH = 0.18f
}
