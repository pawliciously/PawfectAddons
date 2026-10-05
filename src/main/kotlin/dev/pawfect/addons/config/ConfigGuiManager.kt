package dev.pawfect.addons.config

import dev.pawfect.addons.config.settings.PawfectSettings
import dev.pawfect.addons.config.settings.SettingSection
import dev.pawfect.addons.ui.ChromaConfigScreen
import dev.pawfect.addons.ui.PawfectConfigScreen
import dev.pawfect.addons.utils.McCompat

object ConfigGuiManager {

    private var sections: List<SettingSection> = emptyList()

    fun build() {
        sections = PawfectSettings.buildSections()
    }

    fun open(tab: String? = null) {
        build()
        McCompat.screen = if (ConfigManager.features.theme.classicMenu) PawfectConfigScreen(sections, tab) else ChromaConfigScreen(sections, tab)
    }

    fun reopen() {
        val screen = McCompat.screen
        if (screen is PawfectConfigScreen || screen is ChromaConfigScreen) open()
    }
}
