package dev.pawfect.addons.config

import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.ToggleSetting

/**
 * The click GUI's module switch. Switching a module off notes which of its toggles were on and
 * turns them all off, so none of it runs; switching it on turns those back on. The few features
 * driven by something other than a toggle, like the hand chams overlay, also check [enabled].
 */
object ModuleSwitch {

    /** Categories that hold settings rather than a feature, so they have no switch. */
    private val ALWAYS_ON = setOf("theme", "dev")

    private val off get() = ConfigManager.features.modules.off

    fun switchable(category: SettingCategory): Boolean =
        category.id !in ALWAYS_ON && toggles(category).isNotEmpty()

    @JvmStatic
    fun enabled(id: String): Boolean = runCatching { id !in off }.getOrDefault(true)

    fun set(category: SettingCategory, on: Boolean) {
        if (!switchable(category) || on == enabled(category.id)) return
        val toggles = toggles(category)
        if (on) {
            val wanted = off.remove(category.id).orEmpty().toSet()
            toggles.forEach { (key, toggle) -> if (key in wanted && !toggle.value) toggle.value = true }
        } else {
            off[category.id] = toggles.filter { it.second.value }.map { it.first }.toMutableList()
            toggles.forEach { (_, toggle) -> if (toggle.value) toggle.value = false }
        }
    }

    /**
     * A toggle turned on by hand while its module is off switches the module back on, bringing
     * back the rest of what was on before.
     */
    fun wake(category: SettingCategory) {
        if (enabled(category.id)) return
        if (toggles(category).any { it.second.value }) set(category, true)
    }

    private fun toggles(category: SettingCategory): List<Pair<String, ToggleSetting>> =
        category.groups.flatMap { group ->
            group.settings.filterIsInstance<ToggleSetting>().map { "${group.title}/${it.name}" to it }
        }
}
