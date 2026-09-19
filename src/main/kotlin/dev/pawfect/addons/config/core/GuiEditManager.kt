package dev.pawfect.addons.config.core

import dev.pawfect.addons.utils.McCompat

object GuiEditManager {

    private const val VISIBLE_TIMEOUT_MS = 15_000L

    private data class Entry(
        val position: Position,
        var width: Int,
        var height: Int,
        var lastSeen: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun register(position: Position, label: String, width: Int, height: Int) {
        position.label = label
        val existing = entries[label]
        if (existing == null) {
            entries[label] = Entry(position, width, height, System.currentTimeMillis())
        } else {
            existing.width = width
            existing.height = height
            existing.lastSeen = System.currentTimeMillis()
        }
    }

    fun activeEntries(): List<Triple<Position, Int, Int>> {
        val cutoff = System.currentTimeMillis() - VISIBLE_TIMEOUT_MS
        entries.values.removeIf { it.lastSeen < cutoff }
        return entries.values.map { Triple(it.position, it.width, it.height) }
    }

    fun isEditorOpen(): Boolean = McCompat.screen is GuiPositionEditorScreen

    fun openEditor() {
        McCompat.screen = GuiPositionEditorScreen()
    }
}
