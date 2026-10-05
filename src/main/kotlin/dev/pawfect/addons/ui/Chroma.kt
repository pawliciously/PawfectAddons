package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.ToggleSetting
import dev.pawfect.addons.ui.Draw.gradient
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.ui.Draw.pill
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the Chroma menu shares with the watermark: the tabs, each with its own colour, and the
 * moving gradient strip along the top.
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

    /** The strip's colour at a point along it: the accent and a lighter shade drifting past, plus the sweep. */
    private fun stripColour(accent: Int, light: Int, at: Float, time: Float, sweep: Float): Int {
        val wave = 0.5f + 0.5f * sin((2.0 * PI * (at * 1.5f - time)).toFloat())
        var c = Theme.lerp(Theme.opaque(accent), Theme.opaque(light), wave * 0.85f)
        if (sweep < 1f) {
            val distance = abs(at - sweep)
            if (distance < SWEEP_WIDTH) c = Theme.lerp(c, Theme.opaque(0xFFFFFF), (1f - distance / SWEEP_WIDTH) * 0.8f)
        }
        return c
    }

    private fun stripTime(): Float =
        if (Theme.animationsEnabled) (System.currentTimeMillis() % STRIP_PERIOD_MS) / STRIP_PERIOD_MS.toFloat() else 0f

    /**
     * The strip along a panel's top edge: a band of the accent and a lighter shade that drifts
     * along, plus a bright sweep that runs across once whenever the tab changes ([sweep] goes
     * 0 to 1, or stays 1). With a [radius] it bends down into the rounded corners, drawn in
     * one-pixel slices there so it follows the curve.
     */
    fun drawStrip(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, accent: Int, sweep: Float = 1f, radius: Float = 0f) {
        if (width <= 0f) return
        val light = Theme.mix(accent, 0xFFFFFF, 0.55f)
        val time = stripTime()
        val r = radius.coerceIn(0f, width / 2f)
        fun drop(at: Float): Float {
            val d = min(at, width - at)
            if (d >= r) return 0f
            val k = r - d
            return r - sqrt((r * r - k * k).coerceAtLeast(0f))
        }
        val cuts = ArrayList<Float>()
        var at = 0f
        while (at < r) { cuts += at; at += 1f }
        val middle = width - 2f * r
        for (i in 0 until STRIP_SLICES) cuts += r + middle * i / STRIP_SLICES
        at = width - r
        while (at < width) { cuts += at; at += 1f }
        cuts += width
        for (i in 0 until cuts.size - 1) {
            val a = cuts[i]
            val b = cuts[i + 1]
            if (b <= a) continue
            val da = drop(a)
            val db = drop(b)
            val top = min(da, db)
            graphics.gradientHorizontal(x + a, y + top, b - a + 0.6f, height + abs(da - db), stripColour(accent, light, a / width, time, sweep), stripColour(accent, light, b / width, time, sweep))
        }
        if (r == 0f) graphics.pill(x, y + height, width, 0.5f, Theme.withAlpha(0x000000, 90))
    }

    /** The same moving gradient running down instead of across. */
    fun drawStripVertical(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, accent: Int) {
        if (height <= 0f) return
        val light = Theme.mix(accent, 0xFFFFFF, 0.55f)
        val time = stripTime()
        val slices = 10
        val slice = height / slices
        for (i in 0 until slices) {
            val a = i / slices.toFloat()
            val b = (i + 1) / slices.toFloat()
            graphics.gradient(x, y + i * slice, width, slice + 0.6f, stripColour(accent, light, a, time, 1f), stripColour(accent, light, b, time, 1f))
        }
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
