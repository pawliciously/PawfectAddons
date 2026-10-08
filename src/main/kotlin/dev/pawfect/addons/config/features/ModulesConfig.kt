package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ModulesConfig {

    /** Modules switched off in the click GUI. Their settings are left exactly as they are. */
    @Expose
    val disabled: MutableList<String> = mutableListOf()
}
