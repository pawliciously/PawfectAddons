package dev.pawfect.addons.features.dev

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat

/**
 * Dev only: cleans the screen up for recording footage of the mod. Off for anyone but the dev,
 * whatever their config says.
 */
object RecordingMode {

    private const val DEV_NAME = "pawliciously"

    private val config get() = ConfigManager.features.dev

    /** Set while one of our own messages is being added to chat. */
    private val sending = ThreadLocal.withInitial { false }

    private fun isDev(): Boolean = runCatching { McCompat.mc.user.name.equals(DEV_NAME, ignoreCase = true) }.getOrDefault(false)

    fun active(): Boolean = config.recordingMode && isDev()

    /** Runs [send] as one of our messages, so chat lets it through while others are hidden. */
    fun <T> ours(send: () -> T): T {
        val before = sending.get()
        sending.set(true)
        try {
            return send()
        } finally {
            sending.set(before)
        }
    }

    /** Whether a message about to go into chat should be dropped: anything not from PawfectAddons. */
    @JvmStatic
    fun hidesChat(): Boolean = active() && config.recordingHideChat && !sending.get()
}
