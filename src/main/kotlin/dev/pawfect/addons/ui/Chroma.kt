package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.sin

/**
 * What the Chroma menu shares with the watermark: its tabs, and the moving gradient along the
 * top edge, in the theme's accent.
 */
object Chroma {

    class TabDef(val id: String, val title: String, val icon: String, val categories: List<String>)

    const val FAVOURITES = "fav"

    val TABS = listOf(
        TabDef(FAVOURITES, "Favourites", Icons.SPARKLE, emptyList()),
        TabDef("dungeon", "Dungeons", Icons.SHIELD, listOf("dungeon")),
        TabDef("slayers", "Slayers", Icons.FIRE, listOf("slayers")),
        TabDef("visuals", "Visuals", Icons.EYE, listOf("visuals", "skybox")),
        TabDef("chams", "Chams", Icons.CHAMELEON, listOf("chams")),
        TabDef("interface", "Interface", Icons.TAG, listOf("chat", "scoreboard", "inventory", "media")),
        TabDef("sounds", "Sounds", Icons.MUSIC, listOf("hitsounds")),
        TabDef("misc", "Misc", Icons.PACKAGE, listOf("recipetracker", "experiments")),
        TabDef("social", "Social", Icons.USER, listOf("cosmetics", "discord")),
        TabDef("config", "Config", Icons.PALETTE, listOf("theme")),
        TabDef("dev", "Dev", Icons.GEAR, listOf("dev")),
    )

    fun pinKey(category: SettingCategory, groupTitle: String, setting: Setting<*>): String =
        "${category.id}/$groupTitle/${setting.name}"

    val pins: MutableList<String> get() = ConfigManager.features.theme.chromaPins

    private fun time(): Float =
        if (Theme.animationsEnabled) (System.currentTimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat() else 0f

    private fun light(): Int = Theme.mix(Theme.accent, 0xFFFFFF, 0.5f)

    /** The accent and a lighter shade of it, flowing past at [at] along a line, 0 to 1. */
    private fun flow(at: Float): Int {
        val wave = 0.5f + 0.5f * sin((2.0 * PI * (at - time())).toFloat())
        return Theme.lerp(Theme.opaque(Theme.accent), Theme.opaque(light()), wave)
    }

    /**
     * The gradient edge: a rim along the top of a panel, drawn over it with the panel's own
     * bounds. It is [thickness] deep in the middle and follows the rounded corners down to
     * nothing. The two ends trade colours slowly.
     */
    fun drawEdge(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, radius: Float, thickness: Float) {
        Shapes.rimTop(graphics, x, y, width, height, radius, thickness, flow(0f), flow(0.5f))
    }

    /** A square filled with the same flowing gradient, top to bottom, for the selected tab. */
    fun drawTile(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float, radius: Float) {
        Shapes.gradient(graphics, x, y, size, size, radius, flow(0.5f), flow(0f))
    }

    /** An icon colour that reads on [drawTile]: dark on a light accent, white on a dark one. */
    fun onTile(): Int {
        val rgb = Theme.mix(Theme.accent, 0xFFFFFF, 0.25f)
        val luma = 0.299f * (rgb shr 16 and 0xFF) + 0.587f * (rgb shr 8 and 0xFF) + 0.114f * (rgb and 0xFF)
        return if (luma > 170f) Theme.opaque(Theme.mix(Theme.background, 0x000000, 0.3f)) else 0xFFFFFFFF.toInt()
    }

    /** Text with the same flowing gradient running through it, one letter at a time. */
    fun drawGradientText(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, bold: Boolean = true): Float {
        var cursor = x
        val total = width(text, bold).coerceAtLeast(1f)
        text.forEach { ch ->
            val glyph = ch.toString()
            graphics.string(glyph, cursor, y, flow((cursor - x) / total * 0.8f), bold = bold)
            cursor += width(glyph, bold)
        }
        return cursor - x
    }

    fun width(text: String, bold: Boolean): Float = McCompat.font.width(UiFont.component(text, bold)).toFloat()

    private const val PERIOD_MS = 3200L
}
