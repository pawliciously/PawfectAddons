package dev.pawfect.addons.utils

object SessionTracker {

    private var startedAt = 0L

    fun tick() {
        if (startedAt == 0L && McCompat.player != null) startedAt = System.currentTimeMillis()
    }

    fun reset() {
        startedAt = 0L
    }

    val elapsedMillis: Long
        get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    fun formatted(): String {
        val totalMinutes = elapsedMillis / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
