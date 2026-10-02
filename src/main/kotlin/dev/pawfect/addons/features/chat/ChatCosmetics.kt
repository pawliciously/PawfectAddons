package dev.pawfect.addons.features.chat

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.cosmetics.Cosmetics
import dev.pawfect.addons.features.cosmetics.ResolvedName
import dev.pawfect.addons.utils.McCompat
import net.minecraft.ChatFormatting
import net.minecraft.client.StringSplitter
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object ChatCosmetics {

    private val config get() = ConfigManager.features.chat

    private val CHAT_TAG = FontDescription.Resource(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "name_chat"))
    private val TAB_TAG = FontDescription.Resource(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "name_tab"))

    private const val TAB_FLOOR = 150f
    private const val PLAN_TTL_NANOS = 250_000_000L
    private const val IDLE_NANOS = 60_000_000_000L
    private const val WINDOW_NANOS = 1_000_000_000L

    private val WORD = Regex("[A-Za-z0-9_]{3,16}")

    private class Run(val style: Style, val text: String)

    private class Glyph(val style: Style, val codePoint: Int)

    private class Plan(val name: ResolvedName?, val before: List<Glyph>, val after: List<Glyph>, val extra: Float)

    private val NONE = Plan(null, emptyList(), emptyList(), 0f)
    private val SPACE = Glyph(Style.EMPTY, ' '.code)

    private val chatPlans = ConcurrentHashMap<String, Plan>()
    private val tabPlans = ConcurrentHashMap<String, Plan>()

    @Volatile
    private var plansAt = 0L

    @Volatile
    private var seenAt = 0L

    private val active: Boolean get() = System.nanoTime() - seenAt < IDLE_NANOS && Cosmetics.hasNames

    fun chat(message: Component): Component? {
        if (!config.chatCosmetics || !Cosmetics.hasNames) return null
        val marked = mark(message, CHAT_TAG, sender = true) ?: return null
        seenAt = System.nanoTime()
        return marked
    }

    private class TabRow(val marked: Component, val ign: String?, val width: Float)

    private val tabRows = IdentityHashMap<Component, TabRow>()
    private val rowWidths = ConcurrentHashMap<String, Float>()

    @Volatile
    private var widest = 0f
    private var windowWidest = 0f
    private var windowStart = 0L

    @JvmStatic
    fun tab(original: Component?): Component? {
        if (original == null || !config.tabCosmetics || !Cosmetics.hasNames) return original
        val row = synchronized(tabRows) {
            tabRows[original] ?: run {
                if (tabRows.size > 512) tabRows.clear()
                val width = McCompat.font.width(original).toFloat()
                val marked = mark(original, TAB_TAG, sender = false)
                val ign = marked?.let { firstMarked(it) }
                TabRow(marked ?: original, ign, width).also { tabRows[original] = it }
            }
        }
        val now = System.nanoTime()
        synchronized(this) {
            if (now - windowStart > WINDOW_NANOS) {
                widest = windowWidest
                windowWidest = 0f
                windowStart = now
            }
            if (row.width > windowWidest) windowWidest = row.width
        }
        if (row.ign != null) {
            rowWidths[row.ign] = row.width
            seenAt = now
        }
        return row.marked
    }

    @JvmStatic
    fun decorate(text: FormattedCharSequence): FormattedCharSequence {
        if (!active) return text
        return FormattedCharSequence { sink ->
            var current: String? = null
            var plan: Plan? = null
            var position = 0
            text.accept { index, style, codePoint ->
                val tag = style.font
                val ign = if (tag === CHAT_TAG || tag === TAB_TAG) style.insertion else null
                if (ign == null) {
                    current = null
                    return@accept sink.accept(index, style, codePoint)
                }
                if (ign != current || position >= ign.length) {
                    current = ign
                    position = 0
                    plan = plan(ign, tag === TAB_TAG)
                    plan?.before?.forEach { if (!sink.accept(index, it.style, it.codePoint)) return@accept false }
                }
                val chosen = plan
                var shown = style.withFont(FontDescription.DEFAULT)
                var glyph = codePoint
                val name = chosen?.name
                if (name != null) {
                    shown = shown.withColor(TextColor.fromRgb(Cosmetics.nameColor(name, position, ign.length)))
                    if (name.bold) shown = shown.withBold(true)
                    if (name.uppercase) glyph = Character.toUpperCase(glyph)
                }
                if (!sink.accept(index, shown, glyph)) return@accept false
                position++
                if (position == ign.length) {
                    chosen?.after?.forEach { if (!sink.accept(index, it.style, it.codePoint)) return@accept false }
                }
                true
            }
        }
    }

    @JvmStatic
    fun width(original: StringSplitter.WidthProvider, codePoint: Int, style: Style): Float {
        val tag = style.font
        if (tag !== CHAT_TAG && tag !== TAB_TAG) return original.getWidth(codePoint, style)
        var shown = style.withFont(FontDescription.DEFAULT)
        val ign = style.insertion ?: return original.getWidth(codePoint, shown)
        val plan = plan(ign, tag === TAB_TAG)
        var glyph = codePoint
        val name = plan?.name
        if (name != null) {
            if (name.bold) shown = shown.withBold(true)
            if (name.uppercase) glyph = Character.toUpperCase(glyph)
        }
        return original.getWidth(glyph, shown) + (plan?.extra ?: 0f) / ign.length
    }

    private fun plan(ign: String, tab: Boolean): Plan? {
        val now = System.nanoTime()
        if (now - plansAt > PLAN_TTL_NANOS) {
            chatPlans.clear()
            tabPlans.clear()
            plansAt = now
        }
        val cache = if (tab) tabPlans else chatPlans
        val plan = cache.getOrPut(ign) { build(ign, tab) ?: NONE }
        return plan.takeUnless { it === NONE }
    }

    private fun build(ign: String, tab: Boolean): Plan? {
        if (if (tab) !config.tabCosmetics else !config.chatCosmetics) return null
        val named = Cosmetics.byName(ign) ?: return null
        val look = Cosmetics.lookFor(named) ?: return null
        val font = McCompat.font
        val badgeWidths = look.badges.map { font.width(it.component).toFloat() }
        val emojiWidths = look.emoji.map { font.width(it).toFloat() }
        val space = font.width(" ").toFloat()

        var badges = look.badges.size
        var emoji = look.emoji.size
        fun extra(): Float =
            badgeWidths.take(badges).sum() + (if (badges > 0) space else 0f) +
                (if (emoji > 0) space else 0f) + emojiWidths.take(emoji).sum()

        if (tab) {
            val room = tabRoom(ign)
            while (extra() > room) {
                when {
                    emoji > 0 -> emoji--
                    badges > 0 -> badges--
                    else -> break
                }
            }
        }
        if (look.name == null && badges == 0 && emoji == 0) return null

        val before = look.badges.take(badges).flatMap { glyphs(it.component) } + if (badges > 0) listOf(SPACE) else emptyList()
        val after = (if (emoji > 0) listOf(SPACE) else emptyList()) + look.emoji.take(emoji).flatMap(::glyphs)
        return Plan(look.name, before, after, extra())
    }

    private fun tabRoom(ign: String): Float {
        val limit = maxOf(widest, windowWidest, TAB_FLOOR)
        return limit - (rowWidths[ign] ?: TAB_FLOOR)
    }

    private fun glyphs(component: Component): List<Glyph> {
        val out = ArrayList<Glyph>()
        component.visit(
            object : FormattedText.StyledContentConsumer<Unit> {
                override fun accept(style: Style, text: String): Optional<Unit> {
                    text.codePoints().forEach { out += Glyph(style, it) }
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        )
        return out
    }

    private fun runs(source: Component): List<Run> {
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
        return runs
    }

    private fun firstMarked(component: Component): String? {
        var found: String? = null
        component.visit(
            object : FormattedText.StyledContentConsumer<String> {
                override fun accept(style: Style, text: String): Optional<String> {
                    if (style.font === TAB_TAG || style.font === CHAT_TAG) style.insertion?.let { return Optional.of(it) }
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        ).ifPresent { found = it }
        return found
    }

    private fun mark(source: Component, tag: FontDescription, sender: Boolean): Component? {
        val runs = runs(source)
        if (runs.isEmpty()) return null

        val visible = StringBuilder()
        for (run in runs) {
            var i = 0
            while (i < run.text.length) {
                if (run.text[i] == '§' && i + 1 < run.text.length) {
                    i += 2
                    continue
                }
                visible.append(run.text[i])
                i++
            }
        }

        val range = target(visible.toString(), sender) ?: return null
        val ign = visible.substring(range.first, range.last + 1)
        if (Cosmetics.byName(ign) == null) return null
        return rebuild(runs, range, ign, tag)
    }

    private fun target(text: String, sender: Boolean): IntRange? {
        val colon = if (sender) text.indexOf(": ") else -1
        val end = if (colon >= 0) colon else text.length
        val arrow = text.indexOf(" > ")
        val start = if (arrow in 0 until minOf(end, 24)) arrow + 3 else 0

        var depth = 0
        var scanned = 0
        val words = ArrayList<IntRange>()
        for (match in WORD.findAll(text.substring(0, end), start)) {
            while (scanned < match.range.first) {
                when (text[scanned]) {
                    '[' -> depth++
                    ']' -> depth = (depth - 1).coerceAtLeast(0)
                }
                scanned++
            }
            if (depth == 0) words += match.range
        }
        if (words.isEmpty()) return null
        return if (colon >= 0) words.last() else words.first()
    }

    private fun rebuild(runs: List<Run>, range: IntRange, ign: String, tag: FontDescription): Component? {
        val out = Component.empty()
        var visibleIndex = 0
        for (run in runs) {
            val text = run.text
            var effective = run.style
            var segmentStyle = run.style
            var cut = 0
            val name = StringBuilder()
            var nameStyle = run.style
            var i = 0
            while (i < text.length) {
                if (name.isNotEmpty() && visibleIndex !in range) {
                    out.append(Component.literal(name.toString()).setStyle(nameStyle.withFont(tag).withInsertion(ign)))
                    name.setLength(0)
                    cut = i
                    segmentStyle = effective
                }
                val c = text[i]
                if (c == '§' && i + 1 < text.length) {
                    if (name.isNotEmpty()) return null
                    val format = ChatFormatting.getByCode(text[i + 1])
                    if (format != null) effective = if (format == ChatFormatting.RESET) run.style else effective.applyLegacyFormat(format)
                    i += 2
                    continue
                }
                if (visibleIndex in range) {
                    if (name.isEmpty()) {
                        if (i > cut) out.append(Component.literal(text.substring(cut, i)).setStyle(segmentStyle))
                        nameStyle = effective
                    }
                    name.append(c)
                }
                visibleIndex++
                i++
            }
            if (name.isNotEmpty()) {
                out.append(Component.literal(name.toString()).setStyle(nameStyle.withFont(tag).withInsertion(ign)))
                cut = text.length
            }
            if (cut < text.length) out.append(Component.literal(text.substring(cut)).setStyle(segmentStyle))
        }
        return out
    }
}
