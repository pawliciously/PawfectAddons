package dev.pawfect.addons.features

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.recipetracker.RecipeTrackerOverlay
import dev.pawfect.addons.features.visual.LavaChanger
import dev.pawfect.addons.features.visual.handchams.HandChams

object FeatureRegistry {

    class Entry(
        val id: String,
        val displayName: String,
        val isEnabled: () -> Boolean,
        val setEnabled: (Boolean) -> Unit,
    )

    private val config get() = ConfigManager.features

    val entries: List<Entry> by lazy {
        listOf(
            Entry(
                id = "recipetracker",
                displayName = "Recipe Tracker",
                isEnabled = { config.recipeTracker.enabled },
                setEnabled = {
                    config.recipeTracker.enabled = it
                    RecipeTrackerOverlay.invalidate()
                },
            ),
            Entry(
                id = "slayerhealth",
                displayName = "Slayer Health",
                isEnabled = { config.slayers.enabled },
                setEnabled = { config.slayers.enabled = it },
            ),
            Entry(
                id = "lavachanger",
                displayName = "Lava Changer",
                isEnabled = { config.visuals.lavaChanger },
                setEnabled = {
                    config.visuals.lavaChanger = it
                    LavaChanger.invalidate()
                },
            ),
            Entry(
                id = "handchams",
                displayName = "Hand Chams",
                isEnabled = { config.handChams.anyActive },
                setEnabled = {
                    if (it) config.handChams.tintEnabled = true else config.handChams.disableAll()
                    HandChams.invalidate()
                },
            ),
            Entry(
                id = "playerchams",
                displayName = "Player Chams",
                isEnabled = { config.playerChams.enabled },
                setEnabled = { config.playerChams.enabled = it },
            ),
            Entry(
                id = "hidetooltips",
                displayName = "Hide Tooltips",
                isEnabled = { config.visuals.hideTooltips },
                setEnabled = { config.visuals.hideTooltips = it },
            ),
            Entry(
                id = "bathighlight",
                displayName = "Dungeon Bat Highlight",
                isEnabled = { config.visuals.batHighlighter },
                setEnabled = { config.visuals.batHighlighter = it },
            ),
        )
    }

    fun find(query: String): Entry? {
        val key = normalize(query)
        return entries.firstOrNull { normalize(it.id) == key || normalize(it.displayName) == key }
    }

    fun names(): List<String> = entries.map { it.displayName }.sorted()

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}
