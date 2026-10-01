package dev.pawfect.addons.features.chat

import dev.pawfect.addons.config.ConfigManager
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.FormattedCharSequence
import java.util.IdentityHashMap
import java.util.Optional
import kotlin.math.exp

/**
 * Prestige styling for SkyBlock levels past 480, where Hypixel stops adding colours and
 * everything is dark red. Each 40 levels adds something on top of that red:
 *
 *  520  bronze brackets
 *  560  silver brackets
 *  600  platinum brackets, obsidian digits (near-black with a red shadow)
 *  640  platinum brackets, a highlight sweeping across the digits
 *
 * It's purely how text is drawn on this client: nothing is sent, and the visible letters
 * never change, so mods reading chat or the tab list see exactly what they did before. Only
 * a "[NNN]" whose digits Hypixel coloured dark red counts, which rules out pet levels,
 * counts and anything else in brackets.
 */
object LevelPrestige {

    enum class Where { CHAT, TAB, NAMETAG }

    private val enabled get() = ConfigManager.features.chat.levelPrestige

    private const val DARK_RED = 0xAA0000
    private const val BRONZE = 0xCD7F32
    private const val SILVER = 0xC8D0DC
    private const val PLATINUM = 0xB9F2FF
    private const val OBSIDIAN = 0x1A0F12
    private const val OBSIDIAN_SHADOW = 0xFFAA0000.toInt()

    /**
     * Digits that shine carry this colour. It's one step off dark red, so it looks right even
     * if nothing animates it; [animate] swaps it for the moving highlight while drawing.
     */
    private const val SHINE_MARKER = 0xAA0001

    private val LEVEL = Regex("""\[(\d{3})]""")

    private class Run(val style: Style, val text: String)

    /** The level in [source] restyled, or null when there's nothing to change. */
    @JvmStatic
    fun restyle(source: Component, where: Where): Component? = if (enabled) styleLevels(source, where) else null

    /** [restyle] without the setting check, for the dev preview. */
    @JvmStatic
    fun styleLevels(source: Component, where: Where): Component? {
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

        // Hypixel often writes § codes inside the text instead of styles, so the colour a
        // character is drawn in is its run's colour, overridden by any code before it.
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

        // Visible index to the tier of the level it belongs to, and where in it.
        val marks = HashMap<Int, Mark>()
        for (match in LEVEL.findAll(visible)) {
            val level = match.groupValues[1].toInt()
            if (level < 520) continue
            val digits = match.range.first + 1..match.range.last - 1
            if (digits.any { colours[it] != DARK_RED }) continue
            for (index in match.range) marks[index] = Mark(level, index in digits)
        }
        if (marks.isEmpty()) return null
        if (marks.values.any { it.level >= 640 }) shineSeenAt = System.nanoTime()

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
                    out.append(Component.literal(c.toString()).setStyle(styleFor(run.style, mark, where)))
                    // Codes seen so far in this run still apply to what follows.
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

    private class Mark(val level: Int, val digit: Boolean)

    private fun styleFor(base: Style, mark: Mark, where: Where): Style {
        val level = mark.level
        if (!mark.digit) {
            val metal = when {
                level >= 600 -> PLATINUM
                level >= 560 -> SILVER
                else -> BRONZE
            }
            return base.withColor(TextColor.fromRgb(metal))
        }
        return when {
            level >= 640 -> base.withColor(TextColor.fromRgb(SHINE_MARKER))
            // Nametags sit on a dark backing and have no shadow, so obsidian would vanish there.
            level >= 600 && where != Where.NAMETAG ->
                base.withColor(TextColor.fromRgb(OBSIDIAN)).withShadowColor(OBSIDIAN_SHADOW)
            else -> base.withColor(TextColor.fromRgb(DARK_RED))
        }
    }

    // Tab entries are read many times a second by several mods; restyle each one once.

    private val tabCache = IdentityHashMap<Component, Component>()
    private val UNCHANGED: Component = Component.empty()

    @JvmStatic
    fun tab(original: Component?): Component? {
        if (original == null || !enabled) return original
        synchronized(tabCache) {
            tabCache[original]?.let { return if (it === UNCHANGED) original else it }
            if (tabCache.size > 512) tabCache.clear()
            val styled = restyle(original, Where.TAB)
            tabCache[original] = styled ?: UNCHANGED
            return styled ?: original
        }
    }

    // The shine: drawn text with marker-coloured digits gets a highlight sweeping across them.

    @Volatile
    private var shineSeenAt = 0L
    private val startedAt = System.nanoTime()

    /** Only wrap text while a 640+ level was seen recently; otherwise drawing is untouched. */
    @JvmStatic
    fun animate(text: FormattedCharSequence): FormattedCharSequence {
        if (System.nanoTime() - shineSeenAt > SHINE_IDLE_NANOS) return text
        if (!enabled) return text
        return FormattedCharSequence { sink ->
            var first = -1
            text.accept { index, style, codePoint ->
                val colour = style.color?.value
                if (colour != SHINE_MARKER) return@accept sink.accept(index, style, codePoint)
                if (first < 0) first = index
                sink.accept(index, style.withColor(TextColor.fromRgb(shineAt(index - first))), codePoint)
            }
        }
    }

    /** Dark red with a white band gliding left to right every couple of seconds. */
    private fun shineAt(position: Int): Int {
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val sweep = (seconds % 2.4) / 2.4 * 7.0 - 2.0
        val distance = position - sweep
        val glow = exp(-distance * distance / 0.9)
        fun channel(from: Int, to: Int) = (from + (to - from) * glow).toInt().coerceIn(0, 255)
        return (channel(0xAA, 0xFF) shl 16) or (channel(0x00, 0xE8) shl 8) or channel(0x00, 0xE0)
    }

    private const val SHINE_IDLE_NANOS = 30_000_000_000L

    /** For the dev preview: one sample line per tier, as Hypixel formats them. */
    fun previewLines(): List<Component> = listOf(480, 520, 560, 600, 640).map { level ->
        val raw = "§8[§4$level§8] §b[MVP§c+§b] pawliciously§f: level $level preview"
        val line: MutableComponent = Component.literal(raw)
        styleLevels(line, Where.CHAT) ?: line
    }
}
