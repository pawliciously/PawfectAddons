package dev.pawfect.addons.ui

import dev.pawfect.addons.config.settings.ButtonSetting
import dev.pawfect.addons.config.settings.ColorSetting
import dev.pawfect.addons.config.settings.DropdownSetting
import dev.pawfect.addons.config.settings.FloatSliderSetting
import dev.pawfect.addons.config.settings.IntSliderSetting
import dev.pawfect.addons.config.settings.PositionSetting
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SoundListSetting
import dev.pawfect.addons.config.settings.TextSetting
import dev.pawfect.addons.config.settings.ToggleSetting
import dev.pawfect.addons.features.combat.HitsoundLibrary
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.icon
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.roundOutline
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import java.awt.Color as AwtColor
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The click GUI's compact settings: a dim label with its control on the right, sized for a
 * narrow panel. Lists that need room (waypoints, presets, the packet log, guides) keep the
 * widgets the classic menu uses.
 */
object ClickWidgets {

    const val ROW = 13f
    const val LABEL_ROW = 11f
    const val FIELD = 13f
    const val FIELD_RADIUS = 3f

    fun create(setting: Setting<*>): Widget? = when (setting) {
        is ToggleSetting -> ClickToggle(setting)
        is IntSliderSetting -> ClickSlider(setting, { setting.percent }, { setting.value = setting.fromPercent(it) }, { setting.display() })
        is FloatSliderSetting -> ClickSlider(setting, { setting.percent }, { setting.value = setting.fromPercent(it) }, { setting.display() })
        is DropdownSetting<*> -> ClickChoice(setting)
        is SoundListSetting -> ClickSound(setting)
        is ColorSetting -> ClickColor(setting)
        is TextSetting -> ClickText(setting)
        is ButtonSetting -> ClickAction(setting, setting.label, setting.action)
        is PositionSetting -> ClickAction(setting, setting.label, setting.action)
        else -> WidgetFactory.create(setting)
    }

    // ------------------------------------------------------------------ shared drawing

    fun label(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, maxWidth: Float, color: Int = Theme.opaque(Theme.textDim)) {
        graphics.string(Draw.truncate(text, maxWidth), x, y, color)
    }

    /** Text that reads on the accent: dark on a light accent, white on a dark one. */
    fun onAccent(): Int {
        val rgb = Theme.accent
        val luma = 0.299f * (rgb shr 16 and 0xFF) + 0.587f * (rgb shr 8 and 0xFF) + 0.114f * (rgb and 0xFF)
        return if (luma > 150f) 0xFF0B0B0B.toInt() else 0xFFFFFFFF.toInt()
    }

    fun field(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, active: Boolean) {
        graphics.roundPanel(
            x, y, width, height, FIELD_RADIUS,
            Theme.surface(Theme.background, 245),
            if (active) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )
    }

    /** A straight stroke between two points, as a rotated pill. */
    fun line(graphics: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        val length = hypot(x2 - x1, y2 - y1)
        if (length <= 0f) return
        graphics.pose().pushMatrix()
        graphics.pose().translate(x1, y1)
        graphics.pose().rotate(atan2(y2 - y1, x2 - x1))
        Shapes.pill(graphics, -thickness / 2f, -thickness / 2f, length + thickness, thickness, color)
        graphics.pose().popMatrix()
    }

    fun check(graphics: GuiGraphicsExtractor, centerX: Float, centerY: Float, color: Int) {
        line(graphics, centerX - 2.6f, centerY + 0.1f, centerX - 0.8f, centerY + 1.9f, 1.2f, color)
        line(graphics, centerX - 0.8f, centerY + 1.9f, centerX + 2.8f, centerY - 1.9f, 1.2f, color)
    }

    fun caretWidth(): Float = UiFont.iconWidth(Icons.CARET_DOWN)

    fun textY(top: Float, height: Float): Float = top + (height - Draw.LINE_HEIGHT) / 2f
}

// -------------------------------------------------------------------------- toggle

class ClickToggle(private val toggle: ToggleSetting) : Widget(toggle) {

    private val onAnim = Anim(140L, if (toggle.value) 1f else 0f)

    override val height: Float get() = ClickWidgets.ROW

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val on = onAnim.update(if (toggle.value) 1f else 0f)
        val trackX = x + width - TRACK_W
        val trackY = y + (height - TRACK_H) / 2f
        ClickWidgets.label(graphics, setting.name, x, ClickWidgets.textY(y, height), width - TRACK_W - 5f)
        graphics.pill(trackX, trackY, TRACK_W, TRACK_H, Theme.lerp(Theme.withAlpha(Theme.text, 42), Theme.opaque(Theme.accent), on))
        val radius = TRACK_H / 2f - 1.3f
        val knobX = trackX + TRACK_H / 2f + (TRACK_W - TRACK_H) * on
        graphics.circle(knobX, trackY + TRACK_H / 2f, radius, Theme.lerp(Theme.opaque(Theme.text), ClickWidgets.onAccent(), on))
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || !Draw.inside(mouseX, mouseY, x, y, width, height)) return false
        toggle.value = !toggle.value
        UiSound.click()
        return true
    }

    companion object {
        private const val TRACK_W = 16f
        private const val TRACK_H = 9f
    }
}

// -------------------------------------------------------------------------- slider

class ClickSlider(
    setting: Setting<*>,
    private val percent: () -> Float,
    private val apply: (Float) -> Unit,
    private val display: () -> String,
) : Widget(setting) {

    private var dragging = false
    private val fillAnim = Anim(160L, percent())

    override val height: Float get() = HEIGHT

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val value = display()
        val valueWidth = Draw.width(value)
        ClickWidgets.label(graphics, setting.name, x, y + 1f, width - valueWidth - 6f)
        graphics.stringRight(value, x + width, y + 1f, Theme.opaque(Theme.text))

        val fill = if (dragging) percent().also { fillAnim.set(it) } else fillAnim.update(percent())
        val trackY = y + TRACK_TOP
        graphics.pill(x, trackY, width, TRACK_H, Theme.withAlpha(Theme.text, 36))
        if (fill > 0f) graphics.pill(x, trackY, width * fill, TRACK_H, Theme.opaque(Theme.accent))
        graphics.circle(x + width * fill, trackY + TRACK_H / 2f, 3.2f, Theme.opaque(Theme.text))
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || !Draw.inside(mouseX, mouseY, x - 2f, y, width + 4f, height)) return false
        dragging = true
        applyFrom(mouseX)
        UiSound.click()
        return true
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (dragging) applyFrom(mouseX)
    }

    override fun mouseReleased(button: Int) {
        dragging = false
    }

    private fun applyFrom(mouseX: Float) {
        if (width <= 0f) return
        apply(((mouseX - x) / width).coerceIn(0f, 1f))
    }

    companion object {
        private const val HEIGHT = 20f
        private const val TRACK_TOP = 14f
        private const val TRACK_H = 3f
    }
}

// -------------------------------------------------------------------------- dropdown

/** A choice: the value in a box on the right, with its options opening underneath. */
class ClickChoice(private val dropdown: DropdownSetting<*>) : Widget(dropdown) {

    private var open = false
    private val hover = HashMap<Int, Anim>()

    private fun boxWidth(): Float {
        val widest = dropdown.options.maxOfOrNull { Draw.width(it.toString()) } ?: 0f
        return (widest + ClickWidgets.caretWidth() + 14f).coerceAtMost(width * 0.6f)
    }

    /** Short labels sit beside the box; long ones go above it. */
    private val stacked: Boolean get() = Draw.width(setting.name) + boxWidth() + 6f > width

    private fun boxY(): Float = if (stacked) y + ClickWidgets.LABEL_ROW else y

    private fun boxX(): Float = if (stacked) x else x + width - boxWidth()

    private fun boxW(): Float = if (stacked) width else boxWidth()

    private fun listY(): Float = boxY() + ClickWidgets.FIELD + 3f

    override val height: Float
        get() {
            val top = if (stacked) ClickWidgets.LABEL_ROW + ClickWidgets.FIELD else ClickWidgets.ROW
            return if (open) top + 3f + dropdown.options.size * OPTION + 4f else top
        }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val bx = boxX()
        val by = boxY()
        val bw = boxW()
        val labelY = if (stacked) y + 1f else ClickWidgets.textY(y, ClickWidgets.ROW)
        ClickWidgets.label(graphics, setting.name, x, labelY, if (stacked) width else width - bw - 6f)

        ClickWidgets.field(graphics, bx, by, bw, ClickWidgets.FIELD, open)
        val caret = ClickWidgets.caretWidth()
        val current = dropdown.options[dropdown.selectedIndex].toString()
        graphics.string(Draw.truncate(current, bw - caret - 12f), bx + 5f, ClickWidgets.textY(by, ClickWidgets.FIELD), Theme.opaque(Theme.accent))
        graphics.icon(Icons.CARET_DOWN, bx + bw - caret - 4f, ClickWidgets.textY(by, ClickWidgets.FIELD), Theme.opaque(Theme.textDim))

        if (!open) return
        val ly = listY()
        val listHeight = dropdown.options.size * OPTION + 4f
        graphics.roundPanel(x, ly, width, listHeight, 4f, Theme.surface(Theme.background, 250), Theme.opaque(Theme.border))
        dropdown.options.forEachIndexed { index, option ->
            val oy = ly + 2f + index * OPTION
            val selected = index == dropdown.selectedIndex
            val hovered = Draw.inside(mouseX, mouseY, x + 2f, oy, width - 4f, OPTION)
            val h = hover.getOrPut(index) { Anim(120L) }.update(if (hovered) 1f else 0f)
            when {
                selected -> graphics.roundRect(x + 2f, oy, width - 4f, OPTION, 3f, Theme.withAlpha(Theme.accent, 32))
                h > 0.01f -> graphics.roundRect(x + 2f, oy, width - 4f, OPTION, 3f, Theme.withAlpha(Theme.text, (16 * h).toInt()))
            }
            val color = when {
                selected -> Theme.opaque(Theme.accent)
                else -> Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), h)
            }
            graphics.string(Draw.truncate(option.toString(), width - 20f), x + 6f, ClickWidgets.textY(oy, OPTION), color)
            if (selected) ClickWidgets.check(graphics, x + width - 9f, oy + OPTION / 2f, Theme.opaque(Theme.accent))
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        if (Draw.inside(mouseX, mouseY, boxX(), boxY(), boxW(), ClickWidgets.FIELD) ||
            (!stacked && Draw.inside(mouseX, mouseY, x, y, width, ClickWidgets.ROW))
        ) {
            open = !open
            UiSound.click()
            return true
        }
        if (!open) return false
        val ly = listY()
        for (index in dropdown.options.indices) {
            if (Draw.inside(mouseX, mouseY, x, ly + 2f + index * OPTION, width, OPTION)) {
                dropdown.selectIndex(index)
                open = false
                UiSound.click()
                return true
            }
        }
        return false
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (open && keyCode == KEY_ESCAPE) { open = false; return true }
        return false
    }

    override fun loseFocus() {
        open = false
    }

    companion object {
        private const val OPTION = 12f
        private const val KEY_ESCAPE = 256
    }
}

// -------------------------------------------------------------------------- sound list

/**
 * A sound: the chosen one in a box, opening into a searchable list of your own files and every
 * game sound. The list scrolls with the wheel, by dragging its thumb, or by clicking its track.
 * Clicking a sound picks it and plays it.
 */
class ClickSound(private val sound: SoundListSetting) : Widget(sound) {

    private class Row(val id: String?, val group: String?, val count: Int)

    private var open = false
    private var query = ""
    private var scroll = 0f
    private var dragging = false
    private var grab = 0f
    private var playing: String? = null
    private var playingUntil = 0L
    private var folderUntil = 0L

    private var all: List<Pair<String, List<String>>> = emptyList()
    private var cachedQuery: String? = null
    private var rows: List<Row> = emptyList()
    private val hover = HashMap<String, Anim>()

    private fun refresh() {
        val files = runCatching { sound.extras() }.getOrDefault(emptyList()).distinct()
        val known = files.toHashSet()
        val registered = BuiltInRegistries.SOUND_EVENT.keySet().map { it.toString() }.filter { it !in known }.sorted()
        val (game, mods) = registered.partition { it.startsWith("minecraft:") }
        all = listOf(
            "Your files" to files.sortedBy { label(it).lowercase() },
            "Minecraft" to game,
            "Other mods" to mods,
        ).filter { it.second.isNotEmpty() }
        cachedQuery = null
    }

    private fun matches(): List<Row> {
        if (cachedQuery == query) return rows
        val needle = query.trim().lowercase()
        val built = ArrayList<Row>()
        for ((group, ids) in all) {
            val hits = if (needle.isEmpty()) ids else ids.filter { it.contains(needle) || label(it).lowercase().contains(needle) }
            if (hits.isEmpty()) continue
            built += Row(null, group, hits.size)
            hits.forEach { built += Row(it, null, 0) }
        }
        rows = built
        cachedQuery = query
        return rows
    }

    private fun soundCount(): Int = matches().count { it.id != null }

    private fun total(): Int = all.sumOf { it.second.size }

    private fun boxY(): Float = y + ClickWidgets.LABEL_ROW
    private fun searchY(): Float = boxY() + ClickWidgets.FIELD + 4f
    private fun listY(): Float = searchY() + ClickWidgets.FIELD + 3f
    private fun footY(): Float = listY() + LIST_H + 3f

    override val height: Float
        get() = if (open) footY() - y + FOOT else ClickWidgets.LABEL_ROW + ClickWidgets.FIELD

    private fun folderWidth(): Float = Draw.width(FOLDER) + 10f

    private fun maxScroll(): Float = (matches().size * ENTRY - (LIST_H - 2f)).coerceAtLeast(0f)

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        ClickWidgets.label(graphics, setting.name, x, y + 1f, width)

        val by = boxY()
        ClickWidgets.field(graphics, x, by, width, ClickWidgets.FIELD, open)
        val caret = ClickWidgets.caretWidth()
        graphics.string(Draw.truncate(label(sound.value), width - caret - 12f), x + 5f, ClickWidgets.textY(by, ClickWidgets.FIELD), Theme.opaque(Theme.accent))
        graphics.icon(Icons.CARET_DOWN, x + width - caret - 4f, ClickWidgets.textY(by, ClickWidgets.FIELD), Theme.opaque(Theme.textDim))
        if (!open) return

        drawSearch(graphics, mouseX, mouseY)
        drawList(graphics, mouseX, mouseY)

        val now = System.currentTimeMillis()
        val note = if (now < folderUntil) "Opened the hitsounds folder" else {
            val n = soundCount()
            if (n == 1) "1 sound" else "$n sounds"
        }
        val fy = footY()
        graphics.string(Draw.truncate(note, width * 0.6f), x, fy, Theme.opaque(Theme.textDim))
        graphics.stringRight("Click to preview", x + width, fy, Theme.withAlpha(Theme.textDim, 170))
    }

    private fun drawSearch(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val sy = searchY()
        val fw = folderWidth()
        val sw = width - fw - 3f
        ClickWidgets.field(graphics, x, sy, sw, ClickWidgets.FIELD, true)
        val textY = ClickWidgets.textY(sy, ClickWidgets.FIELD)
        graphics.icon(Icons.SEARCH, x + 4f, textY, Theme.opaque(Theme.textDim))
        val left = x + 5f + UiFont.iconWidth(Icons.SEARCH) + 3f
        val caret = if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        if (query.isEmpty()) {
            graphics.string(Draw.truncate("Search ${total()} sounds", x + sw - left - 3f), left, textY, Theme.opaque(Theme.textDim))
        } else {
            graphics.string(Draw.truncate(query + caret, x + sw - left - 3f), left, textY, Theme.opaque(Theme.text))
        }

        val fx = x + width - fw
        val hovered = Draw.inside(mouseX, mouseY, fx, sy, fw, ClickWidgets.FIELD)
        graphics.roundPanel(
            fx, sy, fw, ClickWidgets.FIELD, ClickWidgets.FIELD_RADIUS,
            Theme.withAlpha(Theme.accent, if (hovered) 60 else 30),
            if (hovered) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )
        graphics.string(FOLDER, fx + 5f, ClickWidgets.textY(sy, ClickWidgets.FIELD), Theme.opaque(Theme.accent))
    }

    private fun drawList(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val ly = listY()
        graphics.roundPanel(x, ly, width, LIST_H, 4f, Theme.surface(Theme.background, 250), Theme.opaque(Theme.border))
        val shown = matches()
        if (shown.isEmpty()) {
            graphics.string("No sounds match", x + 6f, ly + 5f, Theme.opaque(Theme.textDim))
            return
        }
        scroll = scroll.coerceIn(0f, maxScroll())
        val rowsWidth = width - TRACK_ROOM
        val now = System.currentTimeMillis()
        if (now >= playingUntil) playing = null

        Shapes.pushScissor(graphics, x + 1f, ly + 1f, width - 2f, LIST_H - 2f)
        val first = (scroll / ENTRY).toInt().coerceAtLeast(0)
        val last = ((scroll + LIST_H) / ENTRY).toInt().coerceAtMost(shown.size - 1)
        for (index in first..last) {
            val row = shown[index]
            val ry = ly + 1f + index * ENTRY - scroll
            val textY = ClickWidgets.textY(ry, ENTRY)
            if (row.group != null) {
                graphics.string(row.group.uppercase(), x + 6f, textY, Theme.withAlpha(Theme.textDim, 200))
                graphics.stringRight(row.count.toString(), x + rowsWidth - 4f, textY, Theme.withAlpha(Theme.textDim, 170))
                continue
            }
            val id = row.id ?: continue
            val selected = id == sound.value
            val hovered = !dragging && Draw.inside(mouseX, mouseY, x + 1f, ry, rowsWidth, ENTRY) &&
                Draw.inside(mouseX, mouseY, x, ly, width, LIST_H)
            val h = hover.getOrPut(id) { Anim(100L) }.update(if (hovered) 1f else 0f)
            when {
                selected -> graphics.rect(x + 1f, ry, rowsWidth, ENTRY, Theme.withAlpha(Theme.accent, 32))
                h > 0.01f -> graphics.rect(x + 1f, ry, rowsWidth, ENTRY, Theme.withAlpha(Theme.text, (16 * h).toInt()))
            }
            val color = if (selected) Theme.opaque(Theme.accent) else Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), h)
            val kind = kind(id)
            val isPlaying = playing == id
            val tagWidth = if (isPlaying) 10f else if (kind.isEmpty()) 0f else Draw.width(kind) + 6f
            graphics.string(Draw.truncate(label(id), rowsWidth - 10f - tagWidth), x + 6f, textY, color)
            if (isPlaying) {
                drawBars(graphics, x + rowsWidth - 10f, ry + ENTRY - 2.5f, color)
            } else if (kind.isNotEmpty()) {
                graphics.stringRight(kind, x + rowsWidth - 4f, textY, Theme.withAlpha(Theme.textDim, 120))
            }
        }
        Shapes.popScissor(graphics)
        drawScrollbar(graphics, mouseX, mouseY)
    }

    private fun drawBars(graphics: GuiGraphicsExtractor, left: Float, bottom: Float, color: Int) {
        val t = System.currentTimeMillis() / 1000f
        for (i in 0 until 3) {
            val bounce = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (9f + i * 2.3f) + i * 1.7f))
            val h = 6f * bounce
            Shapes.rect(graphics, left + i * 2.6f, bottom - h, 1.6f, h, 0.8f, color)
        }
    }

    private fun trackBounds(): FloatArray {
        val ly = listY()
        return floatArrayOf(x + width - 2f - TRACK_W, ly + 2f, TRACK_W, LIST_H - 4f)
    }

    private fun thumbHeight(): Float {
        val track = trackBounds()[3]
        val content = matches().size * ENTRY
        return (track * (LIST_H - 2f) / content.coerceAtLeast(1f)).coerceIn(12f, track)
    }

    private fun drawScrollbar(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val max = maxScroll()
        if (max <= 0f) return
        val t = trackBounds()
        val trackHovered = Draw.inside(mouseX, mouseY, t[0] - 2f, t[1], t[2] + 4f, t[3])
        graphics.pill(t[0], t[1], t[2], t[3], Theme.withAlpha(Theme.text, if (trackHovered) 30 else 18))
        val th = thumbHeight()
        val ty = t[1] + scroll / max * (t[3] - th)
        val held = dragging || (trackHovered && Draw.inside(mouseX, mouseY, t[0] - 2f, ty, t[2] + 4f, th))
        graphics.pill(t[0], ty, t[2], th, if (held) Theme.opaque(Theme.accent) else Theme.withAlpha(Theme.text, 100))
    }

    private fun scrollFromThumb(mouseY: Float) {
        val max = maxScroll()
        val t = trackBounds()
        val room = t[3] - thumbHeight()
        scroll = if (room > 0f) ((mouseY - grab - t[1]) / room).coerceIn(0f, 1f) * max else 0f
    }

    /** Puts the chosen sound in the middle of the list. */
    private fun revealSelected() {
        val index = matches().indexOfFirst { it.id == sound.value }
        scroll = if (index < 0) 0f else (index * ENTRY - LIST_H / 2f + ENTRY / 2f).coerceIn(0f, maxScroll())
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        if (Draw.inside(mouseX, mouseY, x, boxY(), width, ClickWidgets.FIELD)) {
            open = !open
            if (open) {
                query = ""
                refresh()
                revealSelected()
            }
            UiSound.click()
            return true
        }
        if (!open) return false

        val sy = searchY()
        val fw = folderWidth()
        if (Draw.inside(mouseX, mouseY, x + width - fw, sy, fw, ClickWidgets.FIELD)) {
            HitsoundLibrary.openFolder()
            folderUntil = System.currentTimeMillis() + FOLDER_NOTE_MS
            UiSound.click()
            return true
        }
        if (Draw.inside(mouseX, mouseY, x, sy, width - fw - 3f, ClickWidgets.FIELD)) return true

        val t = trackBounds()
        if (maxScroll() > 0f && Draw.inside(mouseX, mouseY, t[0] - 2f, t[1], t[2] + 4f, t[3])) {
            val max = maxScroll()
            val th = thumbHeight()
            val ty = t[1] + scroll / max * (t[3] - th)
            // On the thumb it drags from where it was grabbed; on the track it jumps there first.
            grab = if (mouseY in ty..(ty + th)) mouseY - ty else th / 2f
            dragging = true
            scrollFromThumb(mouseY)
            return true
        }

        val ly = listY()
        if (Draw.inside(mouseX, mouseY, x, ly, width - TRACK_ROOM, LIST_H)) {
            val index = ((mouseY - ly - 1f + scroll) / ENTRY).toInt()
            val id = matches().getOrNull(index)?.id ?: return true
            pick(id)
            return true
        }
        return Draw.inside(mouseX, mouseY, x, y, width, height)
    }

    private fun pick(id: String) {
        sound.value = id
        playing = id
        playingUntil = System.currentTimeMillis() + PLAYING_MS
        runCatching { sound.preview() }
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (dragging) scrollFromThumb(mouseY)
    }

    override fun mouseReleased(button: Int) {
        dragging = false
    }

    fun listBounds(): FloatArray = if (open) floatArrayOf(x, listY(), width, LIST_H) else floatArrayOf(0f, 0f, 0f, 0f)

    fun scrollBy(amount: Float): Boolean {
        if (!open) return false
        scroll = (scroll - amount).coerceIn(0f, maxScroll())
        return true
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (!open) return false
        when (keyCode) {
            KEY_ESCAPE -> open = false
            KEY_BACKSPACE -> if (query.isNotEmpty()) { query = query.dropLast(1); scroll = 0f }
            KEY_ENTER -> matches().firstOrNull { it.id != null }?.id?.let { sound.value = it; open = false }
            else -> return false
        }
        return true
    }

    override fun charTyped(character: Char): Boolean {
        if (!open) return false
        if (character.code < 32) return true
        query += character
        scroll = 0f
        return true
    }

    override fun paste(text: String): Boolean {
        if (!open) return false
        query += text.lineSequence().firstOrNull().orEmpty()
        scroll = 0f
        return true
    }

    override fun loseFocus() {
        open = false
        dragging = false
    }

    companion object {
        private const val ENTRY = 11f
        private const val LIST_H = 112f
        private const val FOOT = 10f
        private const val TRACK_W = 3f
        private const val TRACK_ROOM = 8f
        private const val FOLDER = "Folder"
        private const val FOLDER_NOTE_MS = 2200L
        private const val PLAYING_MS = 1400L
        private const val KEY_ESCAPE = 256
        private const val KEY_ENTER = 257
        private const val KEY_BACKSPACE = 259

        /**
         * Your files keep their file names. Game sounds drop their kind and read as words:
         * minecraft:ambient.nether_wastes.mood is Nether Wastes Mood.
         */
        fun label(id: String): String {
            val namespace = id.substringBefore(':', "minecraft")
            val path = id.substringAfter(':')
            if (path.startsWith("hitsound/")) return path.removePrefix("hitsound/")
            val parts = if (namespace == "minecraft") path.split('.').drop(1).ifEmpty { listOf(path) } else path.split('.', '/')
            return parts.joinToString(" ")
                .replace('_', ' ')
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
        }

        /** The small tag beside a game sound: block, entity, item... or the mod it comes from. */
        fun kind(id: String): String {
            val namespace = id.substringBefore(':', "minecraft")
            val path = id.substringAfter(':')
            if (path.startsWith("hitsound/")) return ""
            return if (namespace == "minecraft") path.substringBefore('.').replace('_', ' ') else namespace
        }
    }
}

// -------------------------------------------------------------------------- colour

/** A colour: its hex and swatch on the right, opening a picker underneath. */
class ClickColor(private val color: ColorSetting) : Widget(color) {

    private var open = false
    private var draggingField = false
    private var draggingHue = false
    private var draggingAlpha = false
    private var hue = 0f
    private var saturation = 1f
    private var brightness = 1f
    private var opacity = 255
    private var synced = false
    private var editingHex = false
    private var hexBuffer = ""

    override val height: Float get() = if (open) ClickWidgets.ROW + 3f + PICKER + 3f + ClickWidgets.FIELD + 2f else ClickWidgets.ROW

    private fun hex(): String =
        if (color.supportsAlpha && color.alphaByte != 255) String.format("#%02X%06X", color.alphaByte, color.rgb)
        else String.format("#%06X", color.rgb)

    private fun bars(): Float = if (color.supportsAlpha) BAR * 2f + GAP * 2f else BAR + GAP

    private fun fieldWidth(): Float = width - bars()

    private fun pickerY(): Float = y + ClickWidgets.ROW + 3f

    private fun hexY(): Float = pickerY() + PICKER + 3f

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        if (!synced) sync()
        val swatchX = x + width - SWATCH
        val swatchY = y + (ClickWidgets.ROW - SWATCH) / 2f
        val code = hex()
        val codeWidth = Draw.width(code)
        ClickWidgets.label(graphics, setting.name, x, ClickWidgets.textY(y, ClickWidgets.ROW), width - SWATCH - codeWidth - 9f)
        graphics.stringRight(code, swatchX - 4f, ClickWidgets.textY(y, ClickWidgets.ROW), Theme.opaque(Theme.text))
        graphics.roundRect(swatchX, swatchY, SWATCH, SWATCH, 2.5f, Theme.surface(Theme.background, 255))
        Shapes.rect(graphics, swatchX, swatchY, SWATCH, SWATCH, 2.5f, Theme.withAlpha(color.rgb, color.alphaByte))
        graphics.roundOutline(swatchX, swatchY, SWATCH, SWATCH, 2.5f, if (open) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border))
        if (!open) return

        val py = pickerY()
        val fw = fieldWidth()
        Shapes.saturationField(graphics, x, py, fw, PICKER, ClickWidgets.FIELD_RADIUS, hue)
        graphics.roundOutline(x, py, fw, PICKER, ClickWidgets.FIELD_RADIUS, Theme.opaque(Theme.border))
        val cx = x + fw * saturation
        val cy = py + PICKER * (1f - brightness)
        graphics.circle(cx, cy, 3f, Theme.withAlpha(0x000000, 150))
        graphics.circle(cx, cy, 2.4f, Theme.opaque(0xFFFFFF))
        graphics.circle(cx, cy, 1.3f, Theme.opaque(color.rgb))

        val hueX = x + fw + GAP
        Shapes.hueBar(graphics, hueX, py, BAR, PICKER, BAR / 2f)
        graphics.roundOutline(hueX, py, BAR, PICKER, BAR / 2f, Theme.opaque(Theme.border))
        graphics.pill(hueX - 1.5f, py + PICKER * hue - 1.25f, BAR + 3f, 2.5f, Theme.opaque(0xFFFFFF))

        if (color.supportsAlpha) {
            val alphaX = hueX + BAR + GAP
            Shapes.rect(graphics, alphaX, py, BAR, PICKER, BAR / 2f, Theme.surface(Theme.background, 255))
            Shapes.rect(graphics, alphaX, py, BAR, PICKER, BAR / 2f, Theme.withAlpha(color.rgb, 255), Theme.withAlpha(color.rgb, 0))
            graphics.roundOutline(alphaX, py, BAR, PICKER, BAR / 2f, Theme.opaque(Theme.border))
            graphics.pill(alphaX - 1.5f, py + PICKER * (1f - opacity / 255f) - 1.25f, BAR + 3f, 2.5f, Theme.opaque(0xFFFFFF))
        }

        val hy = hexY()
        ClickWidgets.field(graphics, x, hy, width, ClickWidgets.FIELD, editingHex)
        val caret = if (editingHex && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        val shown = if (editingHex) "#$hexBuffer$caret" else code
        graphics.string(shown, x + 5f, ClickWidgets.textY(hy, ClickWidgets.FIELD), if (editingHex) Theme.opaque(Theme.accent) else Theme.opaque(Theme.textDim))
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        if (Draw.inside(mouseX, mouseY, x, y, width, ClickWidgets.ROW)) {
            commitHex()
            open = !open
            UiSound.click()
            return true
        }
        if (!open) return false

        if (Draw.inside(mouseX, mouseY, x, hexY(), width, ClickWidgets.FIELD)) {
            editingHex = true
            hexBuffer = hex().removePrefix("#")
            UiSound.click()
            return true
        }
        commitHex()

        val py = pickerY()
        val fw = fieldWidth()
        if (Draw.inside(mouseX, mouseY, x, py, fw, PICKER)) {
            draggingField = true
            updateField(mouseX, mouseY)
            return true
        }
        val hueX = x + fw + GAP
        if (Draw.inside(mouseX, mouseY, hueX - 1f, py, BAR + 2f, PICKER)) {
            draggingHue = true
            updateHue(mouseY)
            return true
        }
        if (color.supportsAlpha && Draw.inside(mouseX, mouseY, hueX + BAR + GAP - 1f, py, BAR + 2f, PICKER)) {
            draggingAlpha = true
            updateAlpha(mouseY)
            return true
        }
        return Draw.inside(mouseX, mouseY, x, y, width, height)
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (draggingField) updateField(mouseX, mouseY)
        if (draggingHue) updateHue(mouseY)
        if (draggingAlpha) updateAlpha(mouseY)
    }

    override fun mouseReleased(button: Int) {
        draggingField = false
        draggingHue = false
        draggingAlpha = false
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (editingHex) {
            when (keyCode) {
                TextWidget.KEY_ENTER -> commitHex()
                TextWidget.KEY_ESCAPE -> editingHex = false
                TextWidget.KEY_BACKSPACE -> if (hexBuffer.isNotEmpty()) hexBuffer = hexBuffer.dropLast(1)
            }
            return true
        }
        if (open && keyCode == TextWidget.KEY_ESCAPE) { open = false; return true }
        return false
    }

    override fun charTyped(character: Char): Boolean {
        if (!editingHex) return false
        val limit = if (color.supportsAlpha) 8 else 6
        if (hexBuffer.length < limit && (character.isDigit() || character.lowercaseChar() in 'a'..'f')) hexBuffer += character.uppercaseChar()
        return true
    }

    override fun paste(text: String): Boolean {
        if (!editingHex) return false
        val limit = if (color.supportsAlpha) 8 else 6
        val digits = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .uppercase()
        hexBuffer = (hexBuffer + digits).take(limit)
        if (hexBuffer.length == 6 || hexBuffer.length == 8) commitHex()
        return true
    }

    override fun loseFocus() {
        commitHex()
        open = false
    }

    private fun commitHex() {
        if (!editingHex) return
        editingHex = false
        val text = hexBuffer.trim()
        val parsed = text.toLongOrNull(16)?.toInt() ?: return
        if (text.length > 6 && color.supportsAlpha) color.apply(parsed and 0xFFFFFF, (parsed ushr 24) and 0xFF)
        else color.apply(parsed and 0xFFFFFF, opacity)
        synced = false
    }

    private fun updateField(mouseX: Float, mouseY: Float) {
        saturation = ((mouseX - x) / fieldWidth()).coerceIn(0f, 1f)
        brightness = 1f - ((mouseY - pickerY()) / PICKER).coerceIn(0f, 1f)
        push()
    }

    private fun updateHue(mouseY: Float) {
        hue = ((mouseY - pickerY()) / PICKER).coerceIn(0f, 1f)
        push()
    }

    private fun updateAlpha(mouseY: Float) {
        opacity = ((1f - ((mouseY - pickerY()) / PICKER).coerceIn(0f, 1f)) * 255f).toInt().coerceIn(1, 255)
        push()
    }

    private fun push() {
        color.apply(AwtColor.HSBtoRGB(hue, saturation, brightness) and 0xFFFFFF, opacity)
    }

    private fun sync() {
        val rgb = color.rgb
        val hsb = AwtColor.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
        hue = hsb[0]
        saturation = hsb[1]
        brightness = hsb[2]
        opacity = color.alphaByte
        synced = true
    }

    companion object {
        private const val SWATCH = 9f
        private const val PICKER = 44f
        private const val BAR = 7f
        private const val GAP = 5f
    }
}

// -------------------------------------------------------------------------- text

/** A text field under its label, edited in place. */
class ClickText(private val text: TextSetting) : Widget(text) {

    private var editing = false
    private var buffer = ""

    override val height: Float get() = ClickWidgets.LABEL_ROW + ClickWidgets.FIELD

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        ClickWidgets.label(graphics, setting.name, x, y + 1f, width)
        val fy = y + ClickWidgets.LABEL_ROW
        ClickWidgets.field(graphics, x, fy, width, ClickWidgets.FIELD, editing)
        val shown = if (editing) buffer else text.value
        val placeholder = shown.isEmpty() && !editing
        val caret = if (editing && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        graphics.string(
            Draw.truncate(if (placeholder) text.placeholder else shown + caret, width - 10f),
            x + 5f,
            ClickWidgets.textY(fy, ClickWidgets.FIELD),
            if (placeholder) Theme.opaque(Theme.textDim) else Theme.opaque(Theme.text),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        if (!Draw.inside(mouseX, mouseY, x, y, width, height)) {
            commit()
            return false
        }
        if (!editing) {
            editing = true
            buffer = text.value
            UiSound.click()
        }
        return true
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (!editing) return false
        when (keyCode) {
            TextWidget.KEY_ENTER -> commit()
            TextWidget.KEY_ESCAPE -> editing = false
            TextWidget.KEY_BACKSPACE -> if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
        }
        return true
    }

    override fun charTyped(character: Char): Boolean {
        if (!editing) return false
        if (character.code >= 32) buffer += character
        return true
    }

    override fun paste(text: String): Boolean {
        if (!editing) return false
        buffer += text.replace(Regex("[\\r\\n]+"), " ")
        return true
    }

    override fun loseFocus() = commit()

    private fun commit() {
        if (!editing) return
        editing = false
        text.value = buffer
    }
}

// -------------------------------------------------------------------------- button

/** A button on the right of its label, or under it when the label is long. */
class ClickAction(setting: Setting<*>, private val label: String, private val action: () -> Unit) : Widget(setting) {

    private val hoverAnim2 = Anim(120L)

    private fun buttonWidth(): Float = Draw.width(label) + 12f

    private val stacked: Boolean get() = Draw.width(setting.name) + buttonWidth() + 6f > width

    override val height: Float get() = if (stacked) ClickWidgets.LABEL_ROW + ClickWidgets.FIELD else ClickWidgets.ROW

    private fun bounds(): FloatArray =
        if (stacked) floatArrayOf(x, y + ClickWidgets.LABEL_ROW, width, ClickWidgets.FIELD)
        else floatArrayOf(x + width - buttonWidth(), y, buttonWidth(), ClickWidgets.ROW)

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val b = bounds()
        val labelY = if (stacked) y + 1f else ClickWidgets.textY(y, ClickWidgets.ROW)
        ClickWidgets.label(graphics, setting.name, x, labelY, if (stacked) width else width - b[2] - 6f)
        val h = hoverAnim2.update(if (Draw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) 1f else 0f)
        graphics.roundPanel(
            b[0], b[1], b[2], b[3], ClickWidgets.FIELD_RADIUS,
            Theme.withAlpha(Theme.accent, (30 + 40 * h).toInt()),
            Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), h),
        )
        val textX = if (stacked) b[0] + (b[2] - Draw.width(label)) / 2f else b[0] + 6f
        graphics.string(label, textX, ClickWidgets.textY(b[1], b[3]), Theme.opaque(Theme.accent))
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        val b = bounds()
        if (!Draw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) return false
        UiSound.click()
        action()
        return true
    }
}
