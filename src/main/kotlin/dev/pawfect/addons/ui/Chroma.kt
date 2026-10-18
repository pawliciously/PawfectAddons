package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.sin

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

    private fun flow(at: Float): Int {
        val wave = 0.5f + 0.5f * sin((2.0 * PI * (at - time())).toFloat())
        return Theme.lerp(Theme.opaque(Theme.accent), Theme.opaque(light()), wave)
    }

    fun drawEdge(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, radius: Float, thickness: Float) {
        Shapes.rimTop(graphics, x, y, width, height, radius, thickness, flow(0f), flow(0.5f))
    }

    fun drawGlow(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float, radius: Float) {
        val color = flow(0.25f)
        Shapes.shadow(graphics, x, y, size, size, radius, GLOW_SPREAD, Theme.withAlpha(color, 70))
        Shapes.shadow(graphics, x + GLOW_INSET, y + GLOW_INSET, size - GLOW_INSET * 2f, size - GLOW_INSET * 2f, radius, GLOW_CORE, Theme.withAlpha(color, 60))
    }

    fun onGlow(): Int = Theme.opaque(Theme.mix(Theme.accent, 0xFFFFFF, 0.35f))

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
    private const val GLOW_SPREAD = 6f
    private const val GLOW_INSET = 5f
    private const val GLOW_CORE = 4f
}
