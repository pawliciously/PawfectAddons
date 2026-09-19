package dev.pawfect.addons.config.settings

import dev.pawfect.addons.utils.ColorInt
import dev.pawfect.addons.config.core.Position
import kotlin.reflect.KMutableProperty0

sealed class Setting<T>(
    val name: String,
    val description: String,
) {

    var visibility: () -> Boolean = { true }
        private set

    var onChange: ((T) -> Unit)? = null
        private set

    abstract var value: T

    fun showIf(predicate: () -> Boolean): Setting<T> {
        visibility = predicate
        return this
    }

    fun onChange(listener: (T) -> Unit): Setting<T> {
        onChange = listener
        return this
    }

    protected fun notifyChanged(newValue: T) {
        onChange?.invoke(newValue)
    }

    val visible: Boolean get() = visibility()
}

abstract class BoundSetting<T>(
    name: String,
    description: String,
    private val property: KMutableProperty0<T>,
) : Setting<T>(name, description) {

    override var value: T
        get() = property.get()
        set(newValue) {
            property.set(newValue)
            notifyChanged(newValue)
        }
}

class ToggleSetting(
    name: String,
    description: String,
    property: KMutableProperty0<Boolean>,
) : BoundSetting<Boolean>(name, description, property)

class IntSliderSetting(
    name: String,
    description: String,
    property: KMutableProperty0<Int>,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val suffix: String = "",
) : BoundSetting<Int>(name, description, property) {

    val percent: Float
        get() = if (max == min) 0f else (value - min).toFloat() / (max - min).toFloat()

    fun fromPercent(fraction: Float): Int {
        val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
        val snapped = Math.round(raw / step) * step
        return snapped.coerceIn(min, max)
    }

    fun display(): String = "$value$suffix"
}

class FloatSliderSetting(
    name: String,
    description: String,
    property: KMutableProperty0<Float>,
    val min: Float,
    val max: Float,
    val step: Float = 0.1f,
    val suffix: String = "",
) : BoundSetting<Float>(name, description, property) {

    val percent: Float
        get() = if (max == min) 0f else (value - min) / (max - min)

    fun fromPercent(fraction: Float): Float {
        val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
        val snapped = Math.round(raw / step) * step
        return snapped.coerceIn(min, max)
    }

    fun display(): String = String.format("%.1f%s", value, suffix)
}

class DropdownSetting<E : Enum<E>>(
    name: String,
    description: String,
    property: KMutableProperty0<E>,
    val options: List<E>,
) : BoundSetting<E>(name, description, property) {

    var expanded: Boolean = false

    fun label(option: E): String = option.toString()

    fun selectIndex(index: Int) {
        options.getOrNull(index)?.let { value = it }
    }

    val selectedIndex: Int get() = options.indexOf(value).coerceAtLeast(0)
}

class TextSetting(
    name: String,
    description: String,
    property: KMutableProperty0<String>,
    val placeholder: String = "",
) : BoundSetting<String>(name, description, property)

class ColorSetting(
    name: String,
    description: String,
    property: KMutableProperty0<Int>,
    val supportsAlpha: Boolean = true,
) : BoundSetting<Int>(name, description, property) {

    val rgb: Int get() = ColorInt.rgb(value)

    val alphaByte: Int get() = if (supportsAlpha) ColorInt.alphaByte(value) else 255

    fun apply(rgb: Int, alphaByte: Int) {
        value = if (supportsAlpha) ColorInt.pack(rgb, alphaByte) else ColorInt.rgb(rgb)
    }
}

class GuideSetting(
    name: String,
    description: String,
    val lines: () -> List<String>,
    val label: () -> String? = { null },
    val action: () -> Unit = {},
) : Setting<Unit>(name, description) {

    override var value: Unit
        get() = Unit
        set(_) {}
}

class ButtonSetting(
    name: String,
    description: String,
    val label: String,
    val action: () -> Unit,
) : Setting<Unit>(name, description) {

    override var value: Unit
        get() = Unit
        set(_) {}
}

class PositionSetting(
    name: String,
    description: String,
    val position: Position,
    val label: String = "Move",
    val action: () -> Unit,
) : Setting<Unit>(name, description) {

    override var value: Unit
        get() = Unit
        set(_) {}
}

class SettingGroup(val title: String, val settings: List<Setting<*>>, val section: String? = null) {
    val visibleSettings: List<Setting<*>> get() = settings.filter { it.visible }
    val visible: Boolean get() = visibleSettings.isNotEmpty()
}

class SettingCategory(
    val id: String,
    val title: String,
    val icon: String,
    val updated: String,
    val groups: List<SettingGroup>,
) {
    var dividerAbove: Boolean = false
        private set

    fun divided(): SettingCategory {
        dividerAbove = true
        return this
    }

    val visibleGroups: List<SettingGroup> get() = groups.filter { it.visible }
}

class SettingSection(
    val id: String,
    val title: String,
    val icon: String,
    val categories: List<SettingCategory>,
)

fun group(title: String, vararg settings: Setting<*>) = SettingGroup(title, settings.toList())

fun sectionGroup(section: String, title: String, vararg settings: Setting<*>) =
    SettingGroup(title, settings.toList(), section)

fun category(id: String, title: String, icon: String, updated: String, vararg groups: SettingGroup) =
    SettingCategory(id, title, icon, updated, groups.toList())

fun dividedCategory(id: String, title: String, icon: String, updated: String, vararg groups: SettingGroup) =
    SettingCategory(id, title, icon, updated, groups.toList()).divided()

fun section(id: String, title: String, icon: String, vararg categories: SettingCategory) =
    SettingSection(id, title, icon, categories.toList())

class WaypointListSetting(
    name: String,
    description: String,
) : Setting<Unit>(name, description) {

    override var value: Unit
        get() = Unit
        set(_) {}
}

class SoundListSetting(
    name: String,
    description: String,
    property: KMutableProperty0<String>,
    val extras: () -> List<String> = { emptyList() },
    val preview: () -> Unit,
) : BoundSetting<String>(name, description, property)

class PacketLogSetting(
    name: String,
    description: String,
) : Setting<Unit>(name, description) {

    override var value: Unit
        get() = Unit
        set(_) {}
}
