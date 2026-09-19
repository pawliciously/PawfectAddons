package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.SlayersConfig.SlayerDisplay
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.renderables.ColumnRenderable
import dev.pawfect.addons.utils.renderables.Renderable
import dev.pawfect.addons.utils.renderables.TextRenderable

object SlayerOverlay {

    private val config get() = ConfigManager.features.slayers

    fun render() {
        if (!config.enabled) return
        if (config.display == SlayerDisplay.WORLD) return
        if (!SkyBlockData.onSkyBlock) return

        val boss = SlayerManager.boss ?: return
        if (!boss.alive) return

        val lines: List<Renderable> = SlayerFormat.lines(boss).map { TextRenderable(it) }
        config.position.render(ColumnRenderable(lines, spacing = 1), "Slayer Health")
    }
}
