package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ModulesConfig {

    @Expose
    val disabled: MutableList<String> = mutableListOf()
}
