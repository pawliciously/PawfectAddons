package dev.pawfect.addons.features.dev

import dev.pawfect.addons.utils.McCompat

/** Whether you're playing on the dev account, for features only the dev gets. */
object DevAccount {

    private const val DEV_NAME = "pawliciously"

    fun isDev(): Boolean = runCatching { McCompat.mc.user.name.equals(DEV_NAME, ignoreCase = true) }.getOrDefault(false)
}
