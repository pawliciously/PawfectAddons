package dev.pawfect.addons.utils

object StringUtil {

    private val COLOR_CODE = Regex("\u00A7[0-9a-fk-orA-FK-OR]")

    fun String.removeColor(): String = COLOR_CODE.replace(this, "")

    fun String.normalizeItemName(): String = removeColor()
        .filter { it.code < 0x2000 || it.isLetterOrDigit() }
        .replace(Regex("\\s+"), " ")
        .trim()
        .uppercase()

    fun String.internalNameToDisplay(): String = split("_", "-")
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
}
