package dev.pawfect.addons.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.ToggleSetting
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

object ModuleSwitch {

    private val ALWAYS_ON = setOf("theme", "dev")

    private val gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().serializeNulls().create()

    private val disabled get() = ConfigManager.features.modules.disabled

    private val copies: MutableMap<Any, Any> = Collections.synchronizedMap(IdentityHashMap())

    fun switchable(category: SettingCategory): Boolean =
        category.id !in ALWAYS_ON && category.groups.any { group -> group.settings.any { it is ToggleSetting } }

    @JvmStatic
    fun enabled(id: String): Boolean = runCatching { id !in disabled }.getOrDefault(true)

    fun set(category: SettingCategory, on: Boolean) {
        if (!switchable(category)) return
        val list = disabled
        if (on) list.remove(category.id) else if (category.id !in list) list.add(category.id)
        copies.clear()
    }

    @JvmStatic
    fun <T : Any> view(id: String, real: T): T {
        if (enabled(id)) return real
        @Suppress("UNCHECKED_CAST")
        return copies.getOrPut(real) { switchedOff(real) } as T
    }

    private fun <T : Any> switchedOff(real: T): T {
        val copy = runCatching { gson.fromJson(gson.toJson(real), real.javaClass) }.getOrNull() ?: return real
        var type: Class<*>? = real.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields
                .filter { it.type == java.lang.Boolean.TYPE && !Modifier.isStatic(it.modifiers) }
                .forEach { field -> runCatching { field.isAccessible = true; field.setBoolean(copy, false) } }
            type = type.superclass
        }
        return copy
    }
}
