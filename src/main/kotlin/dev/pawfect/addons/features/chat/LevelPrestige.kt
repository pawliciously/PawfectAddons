package dev.pawfect.addons.features.chat

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.FormattedCharSequence
import java.util.IdentityHashMap
import java.util.Optional

object LevelPrestige {

    enum class Where { CHAT, TAB, NAMETAG }

    private val enabled get() = ConfigManager.features.chat.levelPrestige

    private const val DARK_RED = 0xAA0000
    private const val RED = 0xFF5555
    private const val GOLD = 0xFFAA00
    private const val YELLOW = 0xFFFF55
    private const val GREEN = 0x55FF55
    private const val AQUA = 0x55FFFF
    private const val LIGHT_PURPLE = 0xFF55FF

    private class Symbol(val realColor: Int, val glyph: String, val color: Int)

    private val SYMBOLS = mapOf(
        0xAA0001 to Symbol(DARK_RED, "✧", DARK_RED),
        0xAA0002 to Symbol(DARK_RED, "✦", DARK_RED),
        0xFF5556 to Symbol(RED, "✯", GOLD),
        0xFFAA01 to Symbol(GOLD, "✪", YELLOW),
        0x55FF56 to Symbol(GREEN, "❂", AQUA),
    )

    private class Tier(val digits: IntArray, val marker: Int, val open: Int? = null, val close: Int? = null)

    private fun tierOf(level: Int): Tier = when {
        level >= 680 -> Tier(intArrayOf(GOLD, YELLOW, GREEN), 0x55FF56, RED, LIGHT_PURPLE)
        level >= 640 -> Tier(intArrayOf(GOLD), 0xFFAA01)
        level >= 600 -> Tier(intArrayOf(RED), 0xFF5556)
        level >= 560 -> Tier(intArrayOf(DARK_RED), 0xAA0002)
        else -> Tier(intArrayOf(DARK_RED), 0xAA0001)
    }

    private val LEVEL = Regex("""\[(\d{3})]""")

    private class Run(val style: Style, val text: String)

    private class Mark(val level: Int, val position: Int, val length: Int)

    @JvmStatic
    fun restyle(source: Component, where: Where): Component? = if (enabled) styleLevels(source, where) else null

    @JvmStatic
    fun styleLevels(source: Component, @Suppress("UNUSED_PARAMETER") where: Where): Component? {
        val runs = ArrayList<Run>()
        source.visit(
            object : FormattedText.StyledContentConsumer<Unit> {
                override fun accept(style: Style, text: String): Optional<Unit> {
                    runs += Run(style, text)
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        )
        if (runs.isEmpty()) return null

        val visible = StringBuilder()
        val colours = ArrayList<Int?>()
        for (run in runs) {
            var legacy: Int? = null
            var i = 0
            while (i < run.text.length) {
                val c = run.text[i]
                if (c == '§' && i + 1 < run.text.length) {
                    val format = ChatFormatting.getByCode(run.text[i + 1])
                    if (format == ChatFormatting.RESET) legacy = null else format?.color?.let { legacy = it }
                    i += 2
                    continue
                }
                visible.append(c)
                colours += legacy ?: run.style.color?.value
                i++
            }
        }

        val marks = HashMap<Int, Mark>()
        for (match in LEVEL.findAll(visible)) {
            val level = match.groupValues[1].toInt()
            if (level < 520) continue
            val digits = match.range.first + 1..match.range.last - 1
            if (digits.any { colours[it] != DARK_RED }) continue
            val length = match.range.last - match.range.first + 1
            for (index in match.range) marks[index] = Mark(level, index - match.range.first, length)
        }
        if (marks.isEmpty()) return null
        seenAt = System.nanoTime()

        val out = Component.empty()
        var visibleIndex = 0
        for (run in runs) {
            val buffer = StringBuilder()
            val codes = StringBuilder()
            var i = 0
            while (i < run.text.length) {
                val c = run.text[i]
                if (c == '§' && i + 1 < run.text.length) {
                    codes.append(c).append(run.text[i + 1])
                    buffer.append(c).append(run.text[i + 1])
                    i += 2
                    continue
                }
                val mark = marks[visibleIndex]
                if (mark == null) {
                    buffer.append(c)
                } else {
                    if (buffer.isNotEmpty()) out.append(Component.literal(buffer.toString()).setStyle(run.style))
                    buffer.setLength(0)
                    val colour = colourFor(mark) ?: colours[visibleIndex]
                    val style = if (colour == null) run.style else run.style.withColor(TextColor.fromRgb(colour))
                    out.append(Component.literal(c.toString()).setStyle(style))
                    buffer.append(codes)
                }
                visibleIndex++
                i++
            }
            if (buffer.isNotEmpty() && buffer.toString() != codes.toString()) {
                out.append(Component.literal(buffer.toString()).setStyle(run.style))
            }
        }
        return out
    }

    private fun colourFor(mark: Mark): Int? {
        val tier = tierOf(mark.level)
        val last = mark.length - 1
        return when (mark.position) {
            0 -> tier.open
            last -> tier.close
            last - 1 -> tier.marker
            else -> tier.digits[(mark.position - 1) % tier.digits.size]
        }
    }

    private val tabCache = IdentityHashMap<Component, Component>()
    private val UNCHANGED: Component = Component.empty()

    @JvmStatic
    fun tab(original: Component?): Component? {
        if (original == null || !enabled) return original
        synchronized(tabCache) {
            tabCache[original]?.let { return if (it === UNCHANGED) original else it }
            if (tabCache.size > 512) tabCache.clear()
            val styled = styleLevels(original, Where.TAB)
            tabCache[original] = styled ?: UNCHANGED
            return styled ?: original
        }
    }

    @Volatile
    private var seenAt = 0L

    private val active: Boolean get() = System.nanoTime() - seenAt < IDLE_NANOS

    @JvmStatic
    fun decorate(text: FormattedCharSequence): FormattedCharSequence {
        if (!active) return text
        return FormattedCharSequence { sink ->
            text.accept { index, style, codePoint ->
                val symbol = style.color?.value?.let(SYMBOLS::get)
                    ?: return@accept sink.accept(index, style, codePoint)
                sink.accept(index, style.withColor(TextColor.fromRgb(symbol.realColor)), codePoint) &&
                    sink.accept(index, style.withColor(TextColor.fromRgb(symbol.color)), symbol.glyph.codePointAt(0))
            }
        }
    }

    @JvmStatic
    fun extraWidth(text: FormattedCharSequence): Int {
        if (!active) return 0
        var extra = 0
        text.accept { _, style, _ ->
            style.color?.value?.let(SYMBOLS::get)?.let { extra += glyphWidth(it.glyph) }
            true
        }
        return extra
    }

    @JvmStatic
    fun extraWidth(text: FormattedText): Int {
        if (!active) return 0
        var extra = 0
        text.visit(
            object : FormattedText.StyledContentConsumer<Unit> {
                override fun accept(style: Style, piece: String): Optional<Unit> {
                    style.color?.value?.let(SYMBOLS::get)?.let { extra += glyphWidth(it.glyph) * piece.codePointCount(0, piece.length) }
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        )
        return extra
    }

    private val widths = HashMap<String, Int>()

    private fun glyphWidth(glyph: String): Int = synchronized(widths) {
        widths.getOrPut(glyph) { McCompat.font.width(glyph) }
    }

    private const val IDLE_NANOS = 30_000_000_000L

    fun previewLines(): List<Component> = listOf(480, 520, 560, 600, 640, 680).map { level ->
        val raw = "§8[§4$level§8] §b[MVP§c+§b] pawliciously§f: level $level preview"
        val line: MutableComponent = Component.literal(raw)
        styleLevels(line, Where.CHAT) ?: line
    }
}
