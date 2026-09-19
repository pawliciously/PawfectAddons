package dev.pawfect.addons.features.stats

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.Position
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.stats.ActionBarStats.Stat
import dev.pawfect.addons.utils.NumberUtil.addSeparators
import dev.pawfect.addons.utils.renderables.TextRenderable

object StatsOverlay {

    private val config get() = ConfigManager.features.stats

    fun render() {
        if (!SkyBlockData.onSkyBlock || !ActionBarStats.hasData) return

        draw(config.healthEnabled, config.healthPosition, "Health", "§c", ActionBarStats.health)
        draw(config.defenseEnabled, config.defensePosition, "Defense", "§a", ActionBarStats.defense)
        draw(config.manaEnabled, config.manaPosition, "Mana", "§b", ActionBarStats.mana)
        draw(config.overflowEnabled, config.overflowPosition, "Overflow", "§3", ActionBarStats.overflowMana)
        draw(config.vitalityEnabled, config.vitalityPosition, "Vitality", "§4", ActionBarStats.vitality)
    }

    private fun draw(enabled: Boolean, position: Position, label: String, color: String, stat: Stat?) {
        if (!enabled) return
        val value = stat ?: return

        val shown = if (config.useNativeColors && value.colorCode != null) "§${value.colorCode}" else color

        val text = StringBuilder()
        if (config.showLabels) text.append("§7").append(label).append(' ')
        text.append(shown).append(value.current.addSeparators())
        if (config.showMax && value.max != null) {
            text.append("§8/").append(shown).append(value.max.addSeparators())
        }

        position.render(TextRenderable(text.toString()), label)
    }
}
