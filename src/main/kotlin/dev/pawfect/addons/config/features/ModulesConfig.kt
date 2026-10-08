package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ModulesConfig {

    /** Modules switched off, each with the toggles that were on so switching back restores them. */
    @Expose
    val off: MutableMap<String, MutableList<String>> = mutableMapOf()
}
