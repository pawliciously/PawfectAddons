package dev.pawfect.addons.features.dev

import dev.pawfect.addons.utils.McCompat

object DevAccount {

    private const val DEV_NAME = "pawliciously"

    fun isDev(): Boolean = runCatching { McCompat.mc.user.name.equals(DEV_NAME, ignoreCase = true) }.getOrDefault(false)
}
