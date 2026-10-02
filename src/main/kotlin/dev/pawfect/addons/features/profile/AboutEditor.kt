package dev.pawfect.addons.features.profile

import dev.pawfect.addons.features.chat.Emojis
import dev.pawfect.addons.ui.UiFont
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import kotlin.math.abs

object AboutText {

    const val LINES = 4
    const val MAX_CHARS = 120

    private val cache = HashMap<String, Component>()
    private var cachedFace: FontDescription? = null

    fun render(line: String): Component {
        val face = UiFont.style().font
        if (face != cachedFace) {
            cache.clear()
            cachedFace = face
        }
        cache[line]?.let { return it }
        if (cache.size > 256) cache.clear()
        val built = build(line)
        cache[line] = built
        return built
    }

    fun width(line: String): Float = McCompat.font.width(render(line)).toFloat()

    fun measure(text: String): Float = McCompat.font.width(build(text)).toFloat()

    private fun build(text: String): Component = Emojis.apply(Component.literal(text).withStyle(UiFont.style()))

    fun allowed(character: Char): Boolean {
        val code = character.code
        return code >= 0x20 &&
            code !in 0x7F..0x9F &&
            code != 0xA7 &&
            code !in 0x200B..0x200F &&
            code !in 0x2028..0x202E &&
            code !in 0x2060..0x206F &&
            code !in 0xE000..0xF8FF &&
            code != 0xFEFF &&
            !character.isSurrogate()
    }
}

class AboutEditor(initial: List<String>, private val maxWidth: Float) {

    val lines: MutableList<String> = MutableList(AboutText.LINES) { initial.getOrElse(it) { "" } }

    var row = initial.indexOfLast { it.isNotEmpty() }.coerceAtLeast(0)
        private set

    var caret = lines[row].length
        private set

    var pick = 0
        private set

    private var dismissed = false

    private val original = clean(lines)

    val result: List<String> get() = clean(lines)

    val changed: Boolean get() = result != original

    val empty: Boolean get() = lines.all { it.isBlank() }

    fun overflowing(): Int = lines.indexOfFirst { AboutText.width(it.trim()) > maxWidth }

    fun caretX(): Float = AboutText.measure(lines[row].substring(0, caret))

    fun suggestions(): List<String> {
        if (dismissed) return emptyList()
        val colon = codeStart(lines[row], caret)
        if (colon < 0 || caret - colon - 1 < 2) return emptyList()
        return Emojis.ranked(lines[row].substring(colon + 1, caret), SUGGESTIONS)
    }

    fun dismiss(): Boolean {
        if (suggestions().isEmpty()) return false
        dismissed = true
        return true
    }

    fun hover(index: Int) {
        pick = index
    }

    fun accept(index: Int = pick) {
        val code = suggestions().getOrNull(index) ?: return
        val colon = codeStart(lines[row], caret)
        if (colon < 0) return
        val line = lines[row]
        val next = line.substring(0, colon) + ":" + code + ":" + line.substring(caret)
        if (next.length > AboutText.MAX_CHARS) return
        lines[row] = next
        caret = colon + code.length + 2
        edited()
    }

    fun type(text: String) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
        for (character in normalized) {
            if (character == '\n') {
                newline()
                continue
            }
            if (!AboutText.allowed(character)) continue
            val line = lines[row]
            val next = line.substring(0, caret) + character + line.substring(caret)
            if (!acceptable(next, caret + 1)) continue
            lines[row] = next
            caret++
            edited()
        }
    }

    fun key(event: KeyEvent) {
        val control = event.hasControlDown()
        val options = suggestions()
        when (event.key()) {
            KEY_TAB -> if (options.isNotEmpty()) accept()
            KEY_ENTER, KEY_KP_ENTER -> if (options.isNotEmpty()) accept() else newline()
            KEY_UP -> if (options.isNotEmpty()) pick = (pick - 1).mod(options.size) else moveRow(-1)
            KEY_DOWN -> if (options.isNotEmpty()) pick = (pick + 1).mod(options.size) else moveRow(1)
            KEY_LEFT -> left(control)
            KEY_RIGHT -> right(control)
            KEY_HOME -> moveTo(0)
            KEY_END -> moveTo(lines[row].length)
            KEY_BACKSPACE -> backspace(control)
            KEY_DELETE -> delete(control)
            else -> if (event.isPaste) type(runCatching { McCompat.mc.keyboardHandler.clipboard }.getOrNull().orEmpty())
        }
    }

    fun place(target: Int, x: Float) {
        row = target.coerceIn(0, AboutText.LINES - 1)
        val line = lines[row]
        val spans = Emojis.spans(line)
        var best = 0
        var bestDistance = Float.MAX_VALUE
        var index = 0
        while (true) {
            val distance = abs(AboutText.measure(line.substring(0, index)) - x)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
            if (index >= line.length) break
            index = spans.firstOrNull { it.first == index }?.let { it.last + 1 } ?: (index + 1)
        }
        caret = best
        pick = 0
    }

    private fun acceptable(next: String, at: Int): Boolean {
        if (next.length > AboutText.MAX_CHARS) return false
        if (AboutText.width(next) <= maxWidth) return true
        return codeStart(next, at) >= 0
    }

    private fun codeStart(line: String, at: Int): Int {
        var start = at
        while (start > 0 && Emojis.isWordChar(line[start - 1])) start--
        if (start == 0 || line[start - 1] != ':') return -1
        val colon = start - 1
        if (colon > 0 && !line[colon - 1].isWhitespace() && Emojis.spans(line).none { it.last == colon - 1 }) return -1
        if (Emojis.spans(line).any { colon in it }) return -1
        return colon
    }

    private fun newline() {
        if (row >= AboutText.LINES - 1) return
        if (lines.last().isEmpty()) {
            val line = lines[row]
            lines.removeAt(lines.size - 1)
            lines.add(row + 1, line.substring(caret))
            lines[row] = line.substring(0, caret)
        }
        row++
        caret = 0
        edited()
    }

    private fun backspace(word: Boolean) {
        if (caret == 0) {
            if (row == 0) return
            val previous = lines[row - 1]
            val joined = previous + lines[row]
            if (joined.length <= AboutText.MAX_CHARS && AboutText.width(joined) <= maxWidth) {
                lines[row - 1] = joined
                lines.removeAt(row)
                lines.add("")
            }
            row--
            caret = previous.length
            edited()
            return
        }
        val start = if (word) wordLeft(caret) else stepLeft(caret)
        lines[row] = lines[row].removeRange(start, caret)
        caret = start
        edited()
    }

    private fun delete(word: Boolean) {
        val line = lines[row]
        if (caret >= line.length) {
            if (row >= AboutText.LINES - 1) return
            val joined = line + lines[row + 1]
            if (joined.length > AboutText.MAX_CHARS || AboutText.width(joined) > maxWidth) return
            lines[row] = joined
            lines.removeAt(row + 1)
            lines.add("")
            edited()
            return
        }
        val end = if (word) wordRight(caret) else stepRight(caret)
        lines[row] = line.removeRange(caret, end)
        edited()
    }

    private fun left(word: Boolean) {
        when {
            caret > 0 -> moveTo(if (word) wordLeft(caret) else stepLeft(caret))
            row > 0 -> {
                row--
                moveTo(lines[row].length)
            }
        }
    }

    private fun right(word: Boolean) {
        when {
            caret < lines[row].length -> moveTo(if (word) wordRight(caret) else stepRight(caret))
            row < AboutText.LINES - 1 -> {
                row++
                moveTo(0)
            }
        }
    }

    private fun moveRow(delta: Int) {
        val target = (row + delta).coerceIn(0, AboutText.LINES - 1)
        if (target == row) return
        place(target, caretX())
    }

    private fun moveTo(position: Int) {
        caret = position.coerceIn(0, lines[row].length)
        pick = 0
    }

    private fun stepLeft(position: Int): Int =
        Emojis.spans(lines[row]).firstOrNull { it.last == position - 1 }?.first ?: (position - 1)

    private fun stepRight(position: Int): Int =
        Emojis.spans(lines[row]).firstOrNull { it.first == position }?.let { it.last + 1 } ?: (position + 1)

    private fun wordLeft(position: Int): Int {
        val line = lines[row]
        var index = position
        while (index > 0 && line[index - 1] == ' ') index--
        while (index > 0 && line[index - 1] != ' ') index = stepLeft(index)
        return index
    }

    private fun wordRight(position: Int): Int {
        val line = lines[row]
        var index = position
        while (index < line.length && line[index] != ' ') index = stepRight(index)
        while (index < line.length && line[index] == ' ') index++
        return index
    }

    private fun edited() {
        dismissed = false
        pick = 0
        Emojis.spans(lines[row]).firstOrNull { caret > it.first && caret <= it.last }?.let { caret = it.last + 1 }
    }

    private fun clean(source: List<String>): List<String> =
        source.map { it.trim().replace(Regex(" {2,}"), " ") }.dropLastWhile { it.isEmpty() }

    private companion object {
        const val SUGGESTIONS = 6
        const val KEY_TAB = 258
        const val KEY_ENTER = 257
        const val KEY_KP_ENTER = 335
        const val KEY_BACKSPACE = 259
        const val KEY_DELETE = 261
        const val KEY_RIGHT = 262
        const val KEY_LEFT = 263
        const val KEY_DOWN = 264
        const val KEY_UP = 265
        const val KEY_HOME = 268
        const val KEY_END = 269
    }
}
