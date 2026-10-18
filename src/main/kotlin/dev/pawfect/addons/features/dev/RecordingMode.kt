package dev.pawfect.addons.features.dev

import dev.pawfect.addons.config.ConfigManager

object RecordingMode {

    private val config get() = ConfigManager.features.dev

    private val sending = ThreadLocal.withInitial { false }

    fun active(): Boolean = config.recordingMode && DevAccount.isDev()

    fun <T> ours(send: () -> T): T {
        val before = sending.get()
        sending.set(true)
        try {
            return send()
        } finally {
            sending.set(before)
        }
    }

    @JvmStatic
    fun hidesChat(): Boolean = active() && config.recordingHideChat && !sending.get()
}
