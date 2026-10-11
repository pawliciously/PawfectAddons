package dev.pawfect.addons.features.dev

import dev.pawfect.addons.config.ConfigManager

/**
 * Dev only: cleans the screen up for recording footage of the mod. Off for anyone but the dev,
 * whatever their config says.
 */
object RecordingMode {

    private val config get() = ConfigManager.features.dev

    /** Set while one of our own messages is being added to chat. */
    private val sending = ThreadLocal.withInitial { false }

    fun active(): Boolean = config.recordingMode && DevAccount.isDev()

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
