package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.ThemeConfig
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.SettingGroup
import dev.pawfect.addons.config.settings.SettingSection
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.icon
import dev.pawfect.addons.ui.Draw.iconCentered
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

class ClickGuiScreen(
    sections: List<SettingSection>,
    private val initialTab: String? = null,
) : Screen(Component.literal("PawfectAddons")) {

    private class Module(val category: SettingCategory) {
        val key get() = category.id
        val switchable = ModuleSwitch.switchable(category)
        val enabled: Boolean get() = ModuleSwitch.enabled(category.id)
    }

    private class Hit(val module: Module, val group: SettingGroup, val setting: Setting<*>?) {
        val label: String get() = if (setting == null) group.title else "${group.title} / ${setting.name}"
    }

    private inner class Panel(val section: SettingSection, val modules: List<Module>) {
        val id get() = section.id
        var x = 0f
        var y = 0f
        var bodyHeight = 0f

        val collapsed: Boolean get() = theme.clickPanels[id]?.collapsed == true

        fun saved(): ThemeConfig.ClickPanel = theme.clickPanels.getOrPut(id) {
            ThemeConfig.ClickPanel().also { it.x = x; it.y = y }
        }

        val width: Float get() = PANEL_WIDTH

        val height: Float get() = if (collapsed) HEADER else HEADER + bodyHeight + BODY_PAD * 2f
    }

    private sealed class Line(val top: Float, val height: Float) {
        class Band(val label: String, top: Float) : Line(top, BAND)
        class Label(val group: SettingGroup, top: Float) : Line(top, GROUP_LABEL)
        class Row(val group: SettingGroup, val widget: Widget, top: Float) : Line(top, widget.height)
    }

    private inner class Config(val module: Module, val panel: Panel) {
        var x = 0f
        var y = 0f
        var scroll = 0f
        var contentHeight = 0f
        var bodyHeight = 0f
        var lines: List<Line> = emptyList()
        var placed = false
        val anim = Anim(160L, 0f)

        val width: Float get() = if (wide) WIDE_CONFIG else CONFIG_WIDTH
        val height: Float get() = HEADER + bodyHeight + CONFIG_PAD * 2f
        val wide: Boolean by lazy { rowsOf(module).any { it.second is PacketLogWidget || it.second is WaypointListWidget } }
    }

    private val theme get() = ConfigManager.features.theme

    private val panels: List<Panel> = sections.filter { it.categories.isNotEmpty() }.map { section ->
        Panel(section, section.categories.map { Module(it) })
    }
    private val byId = panels.associateBy { it.id }
    private val modules = panels.flatMap { it.modules }

    private val widgets = HashMap<String, Widget>()
    private val hover = HashMap<String, Anim>()
    private val openAnim = Anim(220L, 0f)

    private var query = rememberedQuery
    private var config: Config? = null

    private var focused: Widget? = null
    private var dragged: Widget? = null

    private var pressedPanel: Panel? = null
    private var draggingPanel = false
    private var draggingConfig = false
    private var pressX = 0f
    private var pressY = 0f
    private var grabX = 0f
    private var grabY = 0f

    private var draggingBar = false
    private var barGrab = 0f

    private var flashKey: String? = null
    private var flashUntil = 0L

    private var linkTip = -1
    private var lastLinkTip = -1
    private val linkTipAnim = Anim(120L)

    private var placed = false

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.extractBackground(graphics, mouseX, mouseY, deltaTicks)
        graphics.fill(0, 0, width, height, 0x50000000)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        UiScale.minWidth = MIN_WIDTH
        UiScale.minHeight = 300f
        UiScale.push(graphics)
        val mx = UiScale.mouseX(mouseX.toDouble())
        val my = UiScale.mouseY(mouseY.toDouble())
        try {
            placeOnce()
            if (pressedPanel == null) panels.forEach { clamp(it) }
            dragHeld(mx, my)
            dragged?.mouseDragged(mx, my)

            val open = Anim.easeOutCubic(openAnim.update(1f).toDouble()).toFloat()
            graphics.pose().pushMatrix()
            graphics.pose().translate(0f, (1f - open) * -8f)

            val window = config
            window?.let { layoutConfig(it) }
            val overWindow = window != null && Draw.inside(mx, my, window.x, window.y, window.width, window.height)
            val top = if (overWindow) null else topmostAt(mx, my)
            ordered().forEach { panel ->
                val px = if (panel === top) mx else OFF
                val py = if (panel === top) my else OFF
                drawAround(graphics, window) { drawPanel(graphics, panel, px, py) }
            }
            window?.let { drawConfig(graphics, it, if (overWindow) mx else OFF, if (overWindow) my else OFF) }
            graphics.pose().popMatrix()

            drawSearch(graphics, mx, my)
            drawLinks(graphics, mx, my)
            Notifications.renderScaled(graphics)
        } finally {
            UiScale.pop(graphics)
        }
    }

    private inline fun drawAround(graphics: GuiGraphicsExtractor, window: Config?, draw: () -> Unit) {
        if (window == null) { draw(); return }
        val screenW = UiScale.width
        val screenH = UiScale.height
        val inset = 1.5f
        val left = window.x + inset
        val top = window.y + inset
        val right = window.x + window.width - inset
        val bottom = window.y + window.height - inset
        val strips = arrayOf(
            floatArrayOf(0f, 0f, screenW, top),
            floatArrayOf(0f, bottom, screenW, screenH - bottom),
            floatArrayOf(0f, top, left, bottom - top),
            floatArrayOf(right, top, screenW - right, bottom - top),
        )
        for (strip in strips) {
            if (strip[2] <= 0f || strip[3] <= 0f) continue
            Shapes.pushScissor(graphics, strip[0], strip[1], strip[2], strip[3])
            draw()
            Shapes.popScissor(graphics)
        }
    }

    private fun placeOnce() {
        if (placed) return
        placed = true
        placeDefaults()
        val wanted = initialTab ?: rememberedModule
        val module = modules.firstOrNull { it.key == wanted }
        val panel = module?.let { m -> panels.first { m in it.modules } } ?: initialTab?.let { byId[it] }
        if (panel != null) {
            if (panel.collapsed) panel.saved().collapsed = false
            bringToFront(panel)
        }
        if (module != null && panel != null) openConfig(module, panel, null, quiet = true)
        rememberedModule = null
    }

    private fun needle(): String = query.trim().lowercase()

    private fun searching(): Boolean = needle().isNotEmpty()

    private fun hitsIn(module: Module): List<Hit> {
        val needle = needle()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        module.category.groups.forEach { group ->
            if (group.title.lowercase().contains(needle)) hits += Hit(module, group, null)
            group.settings.filter { it.name.lowercase().contains(needle) }.forEach { hits += Hit(module, group, it) }
        }
        return hits
    }

    private fun matches(module: Module): Boolean =
        !searching() || module.category.title.lowercase().contains(needle()) || hitsIn(module).isNotEmpty()

    private fun shownModules(panel: Panel): List<Module> = panel.modules.filter { matches(it) }

    private fun settingMatches(setting: Setting<*>): Boolean = searching() && setting.name.lowercase().contains(needle())

    private fun rowsOf(module: Module): List<Pair<SettingGroup, Widget>> = module.category.groups.flatMap { group ->
        group.settings.mapNotNull { setting ->
            val key = "${module.key}/${group.title}/${setting.name}"
            (widgets[key] ?: ClickWidgets.create(setting)?.also { widgets[key] = it })?.let { group to it }
        }
    }

    private fun widgetKey(module: Module, group: SettingGroup, setting: Setting<*>?): String =
        if (setting == null) "${module.key}/${group.title}" else "${module.key}/${group.title}/${setting.name}"

    private fun ordered(): List<Panel> {
        val order = theme.clickOrder
        val known = order.mapNotNull { byId[it] }
        return known + panels.filter { it.id !in order }
    }

    private fun bringToFront(panel: Panel) {
        val order = theme.clickOrder
        order.retainAll { it in byId }
        order.remove(panel.id)
        panels.filter { it.id !in order && it !== panel }.forEach { order.add(it.id) }
        order.add(panel.id)
    }

    private fun placeDefaults() {
        val total = panels.size * PANEL_WIDTH + (panels.size - 1).coerceAtLeast(0) * GAP
        var x = floor(((UiScale.width - total) / 2f).coerceAtLeast(MARGIN))
        panels.forEach { panel ->
            val saved = theme.clickPanels[panel.id]
            if (saved == null) {
                panel.x = x
                panel.y = TOP
            } else {
                panel.x = saved.x
                panel.y = saved.y
            }
            x += PANEL_WIDTH + GAP
        }
        panels.forEach { clamp(it) }
    }

    private fun clamp(panel: Panel) {
        panel.x = floor(panel.x.coerceIn(0f, (UiScale.width - panel.width).coerceAtLeast(0f)))
        panel.y = floor(panel.y.coerceIn(0f, (UiScale.height - HEADER).coerceAtLeast(0f)))
    }

    private fun dragHeld(mouseX: Float, mouseY: Float) {
        val window = config
        if (draggingBar && window != null) scrollFromBar(window, mouseY)
        if (draggingConfig && window != null) {
            window.x = floor((mouseX - grabX).coerceIn(0f, (UiScale.width - window.width).coerceAtLeast(0f)))
            window.y = floor((mouseY - grabY).coerceIn(MARGIN, (UiScale.height - BOTTOM_ROOM - window.height).coerceAtLeast(MARGIN)))
            return
        }
        val panel = pressedPanel ?: return
        if (!draggingPanel && abs(mouseX - pressX) + abs(mouseY - pressY) > DRAG_SLOP) draggingPanel = true
        if (!draggingPanel) return
        panel.x = mouseX - grabX
        panel.y = mouseY - grabY
        clamp(panel)
    }

    private fun topmostAt(mouseX: Float, mouseY: Float): Panel? =
        ordered().lastOrNull { Draw.inside(mouseX, mouseY, it.x, it.y, it.width, it.height) }

    private fun moduleHeight(module: Module): Float {
        val hits = hitsIn(module)
        if (hits.isEmpty()) return MODULE_HEIGHT
        val shown = hits.size.coerceAtMost(MAX_HITS) + if (hits.size > MAX_HITS) 1 else 0
        return MODULE_HEIGHT + shown * HIT_HEIGHT + 2f
    }

    private fun layoutPanel(panel: Panel) {
        val shown = shownModules(panel)
        panel.bodyHeight = if (shown.isEmpty()) NO_MATCH_HEIGHT else shown.sumOf { moduleHeight(it).toDouble() }.toFloat()
    }

    private fun bodyTop(panel: Panel): Float = panel.y + HEADER + BODY_PAD

    private fun drawPanel(graphics: GuiGraphicsExtractor, panel: Panel, mouseX: Float, mouseY: Float) {
        layoutPanel(panel)
        val x = panel.x
        val y = panel.y
        val w = panel.width
        val h = panel.height
        val missed = searching() && shownModules(panel).isEmpty()

        graphics.dropShadow(x, y, w, h, RADIUS, 12f, Theme.withAlpha(0x000000, 110), 4f)
        graphics.roundPanel(x, y, w, h, RADIUS, Theme.surface(Theme.panel, 240), Theme.opaque(Theme.border))

        Shapes.pushScissor(graphics, x, y, w, HEADER)
        graphics.roundRect(x + 1f, y + 1f, w - 2f, h - 2f, RADIUS - 1f, Theme.surface(Theme.header, 250))
        Shapes.popScissor(graphics)
        if (!panel.collapsed) graphics.rect(x + 1f, y + HEADER - ACCENT_LINE, w - 2f, ACCENT_LINE, Theme.opaque(Theme.accent))

        val textY = y + (HEADER - ACCENT_LINE - Draw.LINE_HEIGHT) / 2f
        val headerHovered = pressedPanel == null && Draw.inside(mouseX, mouseY, x, y, w, HEADER)
        graphics.icon(panel.section.icon, x + 7f, textY, Theme.opaque(Theme.accent))
        val nameX = x + 7f + UiFont.iconWidth(panel.section.icon) + 5f
        val count = panel.modules.size.toString()
        graphics.string(
            Draw.truncate(panel.section.title, x + w - 13f - Draw.width(count) - nameX),
            nameX,
            textY,
            if (headerHovered) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text),
            bold = true,
        )
        graphics.stringRight(count, x + w - 7f, textY, Theme.opaque(Theme.textDim))

        if (!panel.collapsed) drawModules(graphics, panel, mouseX, mouseY)

        if (missed) graphics.roundRect(x, y, w, h, RADIUS, Theme.withAlpha(Theme.background, 165))
    }

    private fun drawModules(graphics: GuiGraphicsExtractor, panel: Panel, mouseX: Float, mouseY: Float) {
        val shown = shownModules(panel)
        var y = bodyTop(panel)
        if (shown.isEmpty()) {
            graphics.string("No match", panel.x + MODULE_INSET, y + (NO_MATCH_HEIGHT - Draw.LINE_HEIGHT) / 2f, Theme.opaque(Theme.textDim))
            return
        }
        shown.forEach { module ->
            drawModule(graphics, panel, module, y, mouseX, mouseY)
            var hy = y + MODULE_HEIGHT
            val hits = hitsIn(module)
            hits.take(MAX_HITS).forEach { hit ->
                drawHit(graphics, panel, hit.label, widgetKey(module, hit.group, hit.setting), hy, mouseX, mouseY, true)
                hy += HIT_HEIGHT
            }
            if (hits.size > MAX_HITS) drawHit(graphics, panel, "${hits.size - MAX_HITS} more", "${module.key}/more", hy, mouseX, mouseY, false)
            y += moduleHeight(module)
        }
    }

    private fun drawModule(graphics: GuiGraphicsExtractor, panel: Panel, module: Module, y: Float, mouseX: Float, mouseY: Float) {
        val x = panel.x
        val w = panel.width
        val open = config?.module === module
        val hovered = Draw.inside(mouseX, mouseY, x + 1f, y, w - 2f, MODULE_HEIGHT)
        val h = hover.getOrPut(module.key) { Anim(120L) }.update(if (hovered) 1f else 0f)
        when {
            open -> graphics.rect(x + 1f, y, w - 2f, MODULE_HEIGHT, Theme.withAlpha(Theme.accent, 30))
            h > 0.01f -> graphics.rect(x + 1f, y, w - 2f, MODULE_HEIGHT, Theme.withAlpha(Theme.text, (13 * h).toInt()))
        }
        if (open) graphics.rect(x + 1f, y + 2f, 1.5f, MODULE_HEIGHT - 4f, Theme.opaque(Theme.accent))

        val color = when {
            open -> Theme.opaque(Theme.accent)
            !module.enabled -> Theme.lerp(Theme.withAlpha(Theme.textDim, 190), Theme.opaque(Theme.textDim), h)
            else -> Theme.lerp(Theme.mix(Theme.textDim, Theme.text, 0.7f), Theme.opaque(Theme.text), h)
        }
        val textX = x + MODULE_INSET
        val textY = y + (MODULE_HEIGHT - Draw.LINE_HEIGHT) / 2f
        val right = if (module.switchable) SWITCH_W + 8f else UiFont.iconWidth(Icons.CARET_RIGHT) + 10f
        val shown = Draw.truncate(module.category.title, w - MODULE_INSET - right - 2f)
        mark(graphics, shown, textX, textY)
        graphics.string(shown, textX, textY, color)
        if (module.switchable) {
            val sx = x + w - 6f - SWITCH_W
            drawSwitch(graphics, "row/${module.key}", sx, y + (MODULE_HEIGHT - SWITCH_H) / 2f, module.enabled)
        } else {
            val caret = Icons.CARET_RIGHT
            val caretColor = if (open) Theme.opaque(Theme.accent) else Theme.lerp(Theme.withAlpha(Theme.textDim, 150), Theme.opaque(Theme.text), h)
            graphics.icon(caret, x + w - 6f - UiFont.iconWidth(caret), textY, caretColor)
        }
    }

    private fun drawSwitch(graphics: GuiGraphicsExtractor, key: String, x: Float, y: Float, on: Boolean) {
        val t = hover.getOrPut("switch/$key") { Anim(140L, if (on) 1f else 0f) }.update(if (on) 1f else 0f)
        graphics.pill(x, y, SWITCH_W, SWITCH_H, Theme.lerp(Theme.withAlpha(Theme.text, 42), Theme.opaque(Theme.accent), t))
        graphics.circle(x + SWITCH_H / 2f + (SWITCH_W - SWITCH_H) * t, y + SWITCH_H / 2f, SWITCH_H / 2f - 1.3f,
            Theme.lerp(Theme.opaque(Theme.text), ClickWidgets.onAccent(), t))
    }

    private fun switchHit(mx: Float, my: Float, x: Float, rowY: Float, rowHeight: Float, rightEdge: Float): Boolean =
        Draw.inside(mx, my, rightEdge - SWITCH_W - 4f, rowY, SWITCH_W + 8f, rowHeight)

    private fun flip(module: Module) {
        dropFocus()
        val on = !module.enabled
        ModuleSwitch.set(module.category, on)
        UiSound.toggle(on)
    }

    private fun drawHit(graphics: GuiGraphicsExtractor, panel: Panel, label: String, key: String, y: Float, mouseX: Float, mouseY: Float, marked: Boolean) {
        val x = panel.x
        val w = panel.width
        val hovered = Draw.inside(mouseX, mouseY, x + 1f, y, w - 2f, HIT_HEIGHT)
        val h = hover.getOrPut("hit/$key") { Anim(100L) }.update(if (hovered) 1f else 0f)
        if (h > 0.01f) graphics.rect(x + 1f, y, w - 2f, HIT_HEIGHT, Theme.withAlpha(Theme.text, (11 * h).toInt()))
        val textX = x + HIT_INSET
        val textY = y + (HIT_HEIGHT - Draw.LINE_HEIGHT) / 2f
        graphics.rect(x + HIT_INSET - 6f, y + 2f, 1f, HIT_HEIGHT - 4f, Theme.withAlpha(Theme.border, 255))
        val shown = Draw.truncate(label, w - HIT_INSET - 6f)
        if (marked) mark(graphics, shown, textX, textY)
        graphics.string(shown, textX, textY, Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), h))
    }

    private fun mark(graphics: GuiGraphicsExtractor, shown: String, textX: Float, textY: Float) {
        val needle = query.trim()
        if (needle.isEmpty()) return
        val at = shown.lastIndexOf(needle, ignoreCase = true)
        if (at < 0) return
        val markX = textX + Draw.width(shown.substring(0, at))
        val markW = Draw.width(shown.substring(at, (at + needle.length).coerceAtMost(shown.length)))
        graphics.roundRect(markX - 1f, textY - 1f, markW + 2f, Draw.LINE_HEIGHT + 1f, 2f, Theme.withAlpha(Theme.accent, 90))
    }

    private fun openConfig(module: Module, panel: Panel, jumpTo: String?, quiet: Boolean = false) {
        dropFocus()
        val current = config
        if (current != null && current.module === module && jumpTo == null) {
            config = null
            if (!quiet) UiSound.close()
            return
        }
        val window = Config(module, panel)
        if (current != null && current.placed && movedConfig) {
            window.x = current.x
            window.y = current.y
            window.placed = true
        }
        window.scroll = if (current?.module === module) current.scroll else 0f
        config = window
        if (jumpTo != null) {
            module.category.groups.firstOrNull { jumpTo == widgetKey(module, it, null) || jumpTo.startsWith(widgetKey(module, it, null) + "/") }
                ?.let { setFolded(module, it, false) }
        }
        layoutConfig(window)
        if (jumpTo != null) {
            val line = window.lines.firstOrNull { lineKey(module, it) == jumpTo }
            if (line != null) window.scroll = (line.top - window.bodyHeight / 3f).coerceIn(0f, maxScroll(window))
            flashKey = jumpTo
            flashUntil = System.currentTimeMillis() + FLASH_MS
        }
        if (!quiet) UiSound.open()
    }

    private fun lineKey(module: Module, line: Line): String? = when (line) {
        is Line.Label -> widgetKey(module, line.group, null)
        is Line.Row -> widgetKey(module, line.group, line.widget.setting)
        is Line.Band -> null
    }

    private fun layoutConfig(window: Config) {
        val lines = ArrayList<Line>()
        var y = 0f
        var section: String? = null
        val title = window.module.category.title
        window.module.category.groups.forEachIndexed { index, group ->
            val wanted = group.section ?: section
            if (wanted != null && wanted != section && !(index == 0 && wanted.equals(title, ignoreCase = true))) {
                if (index > 0) y += GROUP_GAP
                lines += Line.Band(wanted, y)
                y += BAND
            }
            section = wanted
            if (index > 0 && lines.lastOrNull() !is Line.Band) y += GROUP_GAP
            lines += Line.Label(group, y)
            y += GROUP_LABEL
            if (folded(window.module, group)) return@forEachIndexed
            rowsOf(window.module).filter { it.first === group }.forEach { (_, widget) ->
                lines += Line.Row(group, widget, y)
                y += widget.height + WIDGET_GAP
            }
        }
        window.lines = lines
        window.contentHeight = (y - WIDGET_GAP).coerceAtLeast(0f)
        val room = (UiScale.height - TOP - BOTTOM_ROOM - HEADER - CONFIG_PAD * 2f).coerceAtLeast(80f)
        window.bodyHeight = window.contentHeight.coerceAtMost(room)
        window.scroll = window.scroll.coerceIn(0f, maxScroll(window))
        if (!window.placed) {
            window.placed = true
            val panel = window.panel
            val right = panel.x + panel.width + CONFIG_GAP
            window.x = if (right + window.width <= UiScale.width - MARGIN) right else panel.x - CONFIG_GAP - window.width
            window.x = floor(window.x.coerceIn(MARGIN, (UiScale.width - window.width - MARGIN).coerceAtLeast(MARGIN)))
            window.y = floor(panel.y.coerceIn(TOP, (UiScale.height - BOTTOM_ROOM - window.height).coerceAtLeast(TOP)))
        }
        window.y = window.y.coerceIn(MARGIN, (UiScale.height - BOTTOM_ROOM - window.height).coerceAtLeast(MARGIN))
        window.x = window.x.coerceIn(0f, (UiScale.width - window.width).coerceAtLeast(0f))
    }

    private fun foldKey(module: Module, group: SettingGroup): String = "${module.key}/${group.title}"

    private fun folded(module: Module, group: SettingGroup): Boolean = foldKey(module, group) !in theme.clickExpanded

    private fun setFolded(module: Module, group: SettingGroup, fold: Boolean) {
        val list = theme.clickExpanded
        val key = foldKey(module, group)
        if (fold) list.remove(key) else if (key !in list) list.add(key)
    }

    private fun maxScroll(window: Config): Float = (window.contentHeight - window.bodyHeight).coerceAtLeast(0f)

    private fun configBody(window: Config): Float = window.y + HEADER + CONFIG_PAD

    private fun closeBounds(window: Config): FloatArray {
        val size = 14f
        val gap = (HEADER - ACCENT_LINE - size) / 2f
        return floatArrayOf(window.x + window.width - size - gap - 1f, window.y + gap, size, size)
    }

    private fun drawConfig(graphics: GuiGraphicsExtractor, window: Config, mouseX: Float, mouseY: Float) {
        val shown = Anim.easeOutCubic(window.anim.update(1f).toDouble()).toFloat()
        val x = window.x
        val y = window.y
        val w = window.width
        val h = window.height
        graphics.pose().pushMatrix()
        graphics.pose().translate((1f - shown) * -6f, 0f)

        graphics.dropShadow(x, y, w, h, RADIUS, 16f, Theme.withAlpha(0x000000, 140), 5f)
        graphics.roundPanel(x, y, w, h, RADIUS, Theme.surface(Theme.background, 248), Theme.opaque(Theme.border))
        Shapes.pushScissor(graphics, x, y, w, HEADER)
        graphics.roundRect(x + 1f, y + 1f, w - 2f, h - 2f, RADIUS - 1f, Theme.surface(Theme.header, 252))
        Shapes.popScissor(graphics)
        graphics.rect(x + 1f, y + HEADER - ACCENT_LINE, w - 2f, ACCENT_LINE, Theme.opaque(Theme.accent))

        val category = window.module.category
        val textY = y + (HEADER - ACCENT_LINE - Draw.LINE_HEIGHT) / 2f
        graphics.icon(category.icon, x + 8f, textY, Theme.opaque(Theme.accent))
        val nameX = x + 8f + UiFont.iconWidth(category.icon) + 5f
        graphics.string(category.title, nameX, textY, Theme.opaque(Theme.text), bold = true)
        val sectionX = nameX + Draw.width(category.title) + 7f
        val sectionRoom = closeBounds(window)[0] - sectionX - 4f - if (window.module.switchable) SWITCH_W + 8f else 0f
        graphics.string(Draw.truncate(window.panel.section.title, sectionRoom), sectionX, textY, Theme.opaque(Theme.textDim))

        val c = closeBounds(window)
        if (window.module.switchable) {
            drawSwitch(graphics, "header/${window.module.key}", c[0] - 6f - SWITCH_W, y + (HEADER - ACCENT_LINE - SWITCH_H) / 2f, window.module.enabled)
        }
        val closeHovered = Draw.inside(mouseX, mouseY, c[0], c[1], c[2], c[3])
        val ch = hover.getOrPut("config/close") { Anim(120L) }.update(if (closeHovered) 1f else 0f)
        if (ch > 0.01f) graphics.roundRect(c[0], c[1], c[2], c[3], 3f, Theme.withAlpha(Theme.text, (20 * ch).toInt()))
        graphics.iconCentered(Icons.CLOSE, c[0] + c[2] / 2f, c[1] + c[3] / 2f, CLOSE_SCALE,
            Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), ch))

        drawConfigBody(graphics, window, mouseX, mouseY)
        graphics.pose().popMatrix()
    }

    private fun drawConfigBody(graphics: GuiGraphicsExtractor, window: Config, mouseX: Float, mouseY: Float) {
        val top = configBody(window)
        val left = window.x + CONFIG_INSET
        val inner = window.width - CONFIG_INSET * 2f - SCROLL_ROOM
        val inBody = Draw.inside(mouseX, mouseY, window.x, top, window.width, window.bodyHeight)
        val mx = if (inBody) mouseX else OFF
        val my = if (inBody) mouseY else OFF
        val now = System.currentTimeMillis()
        val flash = if (now < flashUntil) flashKey else null
        val pulse = if (flash != null) 0.5f + 0.5f * sin((flashUntil - now) / 120f) else 0f

        Shapes.pushScissor(graphics, window.x + 1f, top - 2f, window.width - 2f, window.bodyHeight + 4f)
        window.lines.forEach { line ->
            val y = top + line.top - window.scroll
            val visible = y + line.height >= top - 2f && y <= top + window.bodyHeight + 2f
            val key = lineKey(window.module, line)
            if (key != null && key == flash) {
                graphics.roundRect(left - 4f, y - 1f, inner + 8f, line.height + 2f, 3f, Theme.withAlpha(Theme.accent, (24 + 40 * pulse).toInt()))
            }
            when (line) {
                is Line.Band -> if (visible) {
                    val label = line.label.uppercase()
                    val ty = y + (BAND - Draw.LINE_HEIGHT) / 2f
                    graphics.string(label, left, ty, Theme.opaque(Theme.accent), bold = true)
                    val ruleX = left + Draw.width(label) + 7f
                    graphics.rect(ruleX, y + BAND / 2f, (left + inner - ruleX).coerceAtLeast(0f), 1f, Theme.withAlpha(Theme.accent, 90))
                }
                is Line.Label -> if (visible) {
                    val ty = y + (GROUP_LABEL - Draw.LINE_HEIGHT) / 2f
                    val label = line.group.title
                    val isFolded = folded(window.module, line.group)
                    val hovered = Draw.inside(mx, my, left - 4f, y, inner + 8f, GROUP_LABEL)
                    val lh = hover.getOrPut("fold/${key}") { Anim(120L) }.update(if (hovered) 1f else 0f)
                    val caret = if (isFolded) Icons.CARET_RIGHT else Icons.CARET_DOWN
                    graphics.icon(caret, left, ty, Theme.lerp(Theme.withAlpha(Theme.textDim, 200), Theme.opaque(Theme.accent), lh))
                    val labelX = left + UiFont.iconWidth(caret) + 5f
                    mark(graphics, label, labelX, ty)
                    graphics.string(label, labelX, ty, Theme.lerp(Theme.opaque(Theme.text), Theme.opaque(Theme.accent), lh * 0.6f), bold = true)
                    var ruleX = labelX + Draw.width(label) + 7f
                    if (isFolded) {
                        val count = line.group.settings.size.toString()
                        graphics.string(count, ruleX, ty, Theme.withAlpha(Theme.textDim, 200))
                        ruleX += Draw.width(count) + 6f
                    }
                    graphics.rect(ruleX, y + GROUP_LABEL / 2f, (left + inner - ruleX).coerceAtLeast(0f), 1f, Theme.withAlpha(Theme.border, 200))
                }
                is Line.Row -> {
                    val widget = line.widget
                    widget.x = left
                    widget.y = y
                    widget.width = inner
                    if (visible) {
                        if (settingMatches(widget.setting)) {
                            graphics.roundRect(left - 4f, y - 1f, inner + 8f, widget.height + 2f, 3f, Theme.withAlpha(Theme.accent, 22))
                            graphics.rect(left - 4f, y + 1f, 1.5f, (widget.height - 2f).coerceAtLeast(1f), Theme.opaque(Theme.accent))
                        }
                        widget.draw(graphics, mx, my)
                    }
                }
            }
        }
        Shapes.popScissor(graphics)
        drawScrollbar(graphics, window, mouseX, mouseY)
    }

    private fun barBounds(window: Config): FloatArray {
        val top = configBody(window)
        val trackHeight = window.bodyHeight - 4f
        val thumb = (trackHeight * window.bodyHeight / window.contentHeight.coerceAtLeast(1f)).coerceIn(14f, trackHeight)
        val max = maxScroll(window)
        val thumbY = top + 2f + if (max > 0f) window.scroll / max * (trackHeight - thumb) else 0f
        return floatArrayOf(window.x + window.width - 6f, top + 2f, 2.5f, trackHeight, thumbY, thumb)
    }

    private fun drawScrollbar(graphics: GuiGraphicsExtractor, window: Config, mouseX: Float, mouseY: Float) {
        if (maxScroll(window) <= 0f) return
        val b = barBounds(window)
        val near = draggingBar || Draw.inside(mouseX, mouseY, b[0] - 3f, b[1], b[2] + 5f, b[3])
        Shapes.pill(graphics, b[0], b[1], b[2], b[3], Theme.withAlpha(Theme.text, if (near) 30 else 16))
        Shapes.pill(graphics, b[0], b[4], b[2], b[5], if (near) Theme.opaque(Theme.accent) else Theme.withAlpha(Theme.text, 90))
    }

    private fun scrollFromBar(window: Config, mouseY: Float) {
        val b = barBounds(window)
        val room = b[3] - b[5]
        window.scroll = if (room > 0f) ((mouseY - barGrab - b[1]) / room).coerceIn(0f, 1f) * maxScroll(window) else 0f
    }

    private fun searchBounds(): FloatArray =
        floatArrayOf(floor((UiScale.width - SEARCH_WIDTH) / 2f), UiScale.height - SEARCH_HEIGHT - 12f, SEARCH_WIDTH, SEARCH_HEIGHT)

    private fun drawSearch(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val s = searchBounds()
        val active = query.isNotEmpty()
        val h = hover.getOrPut("search") { Anim(140L) }.update(if (active || Draw.inside(mouseX, mouseY, s[0], s[1], s[2], s[3])) 1f else 0f)
        graphics.dropShadow(s[0], s[1], s[2], s[3], RADIUS, 10f, Theme.withAlpha(0x000000, 100), 3f)
        graphics.roundPanel(
            s[0], s[1], s[2], s[3], RADIUS,
            Theme.surface(Theme.panel, 240),
            Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), h),
        )
        val textY = s[1] + (s[3] - Draw.LINE_HEIGHT) / 2f
        graphics.icon(Icons.SEARCH, s[0] + 8f, textY, if (active) Theme.opaque(Theme.accent) else Theme.opaque(Theme.textDim))
        val left = s[0] + 8f + UiFont.iconWidth(Icons.SEARCH) + 6f
        val room = s[0] + s[2] - 8f - left
        if (query.isEmpty()) {
            graphics.string(Draw.truncate("Search modules and settings", room), left, textY, Theme.opaque(Theme.textDim))
        } else {
            val caret = if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
            val text = query + caret
            var start = 0
            while (start < text.length && Draw.width(text.substring(start)) > room) start++
            graphics.string(text.substring(start), left, textY, Theme.opaque(Theme.text))
        }
    }

    private class Link(val id: String, val label: String, val url: String) {
        val texture: Identifier = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "textures/gui/links/$id.png")
    }

    private fun linkX(index: Int): Float = UiScale.width - MARGIN - (LINKS.size - index) * LINK_SIZE - (LINKS.size - 1 - index) * LINK_GAP

    private fun linkY(): Float = UiScale.height - MARGIN - LINK_SIZE

    private fun drawLinks(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        linkTip = -1
        val y = linkY()
        LINKS.forEachIndexed { index, link ->
            val x = linkX(index)
            val hovered = Draw.inside(mouseX, mouseY, x, y, LINK_SIZE, LINK_SIZE)
            if (hovered) linkTip = index
            val h = hover.getOrPut("link/${link.id}") { Anim(140L) }.update(if (hovered) 1f else 0f)
            graphics.roundPanel(
                x, y, LINK_SIZE, LINK_SIZE, RADIUS,
                Theme.lerp(Theme.surface(Theme.panel, 240), Theme.mix(Theme.panel, Theme.accent, 0.12f), h),
                Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), h),
            )
            val tint = Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.accent), h)
            val scale = LINK_ICON / LINK_TEXTURE
            graphics.pose().pushMatrix()
            graphics.pose().translate(x + (LINK_SIZE - LINK_ICON) / 2f, y + (LINK_SIZE - LINK_ICON) / 2f)
            graphics.pose().scale(scale, scale)
            graphics.blit(RenderPipelines.GUI_TEXTURED, link.texture, 0, 0, 0f, 0f, LINK_TEXTURE.toInt(), LINK_TEXTURE.toInt(), LINK_TEXTURE.toInt(), LINK_TEXTURE.toInt(), tint)
            graphics.pose().popMatrix()
        }

        val shown = linkTipAnim.update(if (linkTip >= 0) 1f else 0f)
        if (linkTip >= 0) lastLinkTip = linkTip
        val link = LINKS.getOrNull(lastLinkTip) ?: return
        if (shown < 0.02f) return
        val alpha = (255 * shown).toInt()
        val tipWidth = Draw.width(link.label) + 12f
        val tipX = (linkX(lastLinkTip) + LINK_SIZE / 2f - tipWidth / 2f).coerceAtMost(UiScale.width - 4f - tipWidth)
        val tipY = y - 18f + (1f - shown) * 3f
        graphics.roundPanel(tipX, tipY, tipWidth, 14f, 4f, Theme.withAlpha(Theme.header, (245 * shown).toInt()), Theme.withAlpha(Theme.border, alpha))
        graphics.string(link.label, tipX + 6f, tipY + (14f - Draw.LINE_HEIGHT) / 2f, Theme.withAlpha(Theme.text, alpha))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = UiScale.mouseX(click.x())
        val my = UiScale.mouseY(click.y())
        val button = click.button()

        val ly = linkY()
        LINKS.forEachIndexed { index, link ->
            if (button == 0 && Draw.inside(mx, my, linkX(index), ly, LINK_SIZE, LINK_SIZE)) {
                UiSound.click()
                runCatching { Util.getPlatform().openUri(link.url) }
                return true
            }
        }

        val window = config
        if (window != null && Draw.inside(mx, my, window.x, window.y, window.width, window.height)) {
            clickConfig(window, mx, my, button)
            return true
        }

        val panel = topmostAt(mx, my)
        if (panel == null) {
            dropFocus()
            return true
        }
        bringToFront(panel)

        if (Draw.inside(mx, my, panel.x, panel.y, panel.width, HEADER)) {
            dropFocus()
            pressedPanel = panel
            draggingPanel = false
            pressX = mx
            pressY = my
            grabX = mx - panel.x
            grabY = my - panel.y
            UiScale.lock()
            return true
        }
        if (panel.collapsed || button != 0 && button != 1) return true

        var y = bodyTop(panel)
        for (module in shownModules(panel)) {
            if (Draw.inside(mx, my, panel.x, y, panel.width, MODULE_HEIGHT)) {
                if (module.switchable && button == 0 && switchHit(mx, my, panel.x, y, MODULE_HEIGHT, panel.x + panel.width - 6f)) flip(module)
                else openConfig(module, panel, null)
                return true
            }
            var hy = y + MODULE_HEIGHT
            val hits = hitsIn(module)
            for (hit in hits.take(MAX_HITS)) {
                if (Draw.inside(mx, my, panel.x, hy, panel.width, HIT_HEIGHT)) {
                    openConfig(module, panel, widgetKey(module, hit.group, hit.setting))
                    return true
                }
                hy += HIT_HEIGHT
            }
            if (hits.size > MAX_HITS && Draw.inside(mx, my, panel.x, hy, panel.width, HIT_HEIGHT)) {
                val next = hits[MAX_HITS]
                openConfig(module, panel, widgetKey(module, next.group, next.setting))
                return true
            }
            y += moduleHeight(module)
        }
        dropFocus()
        return true
    }

    private fun clickConfig(window: Config, mx: Float, my: Float, button: Int) {
        val c = closeBounds(window)
        if (button == 0 && Draw.inside(mx, my, c[0], c[1], c[2], c[3])) {
            closeConfig()
            UiSound.close()
            return
        }
        if (window.module.switchable && button == 0 && switchHit(mx, my, window.x, window.y, HEADER, c[0] - 6f)) {
            flip(window.module)
            return
        }
        if (Draw.inside(mx, my, window.x, window.y, window.width, HEADER)) {
            dropFocus()
            draggingConfig = true
            movedConfig = true
            grabX = mx - window.x
            grabY = my - window.y
            UiScale.lock()
            return
        }
        if (maxScroll(window) > 0f) {
            val b = barBounds(window)
            if (Draw.inside(mx, my, b[0] - 3f, b[1], b[2] + 5f, b[3])) {
                dropFocus()
                barGrab = if (my in b[4]..(b[4] + b[5])) my - b[4] else b[5] / 2f
                draggingBar = true
                scrollFromBar(window, my)
                UiScale.lock()
                return
            }
        }
        if (!Draw.inside(mx, my, window.x, configBody(window), window.width, window.bodyHeight)) {
            dropFocus()
            return
        }
        val top = configBody(window)
        for (line in window.lines) {
            if (line is Line.Label) {
                val ly = top + line.top - window.scroll
                if (button == 0 && Draw.inside(mx, my, window.x, ly, window.width, GROUP_LABEL)) {
                    dropFocus()
                    setFolded(window.module, line.group, !folded(window.module, line.group))
                    UiSound.pluck()
                    return
                }
                continue
            }
            if (line !is Line.Row) continue
            val widget = line.widget
            if (widget.mouseClicked(mx, my, button)) {
                if (focused !== widget) dropFocus()
                focused = widget
                dragged = widget
                UiScale.lock()
                return
            }
        }
        dropFocus()
    }

    private fun closeConfig() {
        dropFocus()
        config = null
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragged?.mouseReleased(click.button())
        dragged = null
        draggingBar = false
        draggingConfig = false
        pressedPanel?.let { panel ->
            if (draggingPanel) {
                panel.saved().x = panel.x
                panel.saved().y = panel.y
            } else {
                panel.saved().collapsed = !panel.collapsed
                UiSound.click()
            }
        }
        pressedPanel = null
        draggingPanel = false
        UiScale.unlock()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (verticalAmount == 0.0) return false
        val mx = UiScale.mouseX(mouseX)
        val my = UiScale.mouseY(mouseY)
        val window = config ?: return true
        if (!Draw.inside(mx, my, window.x, window.y, window.width, window.height)) return true
        val amount = verticalAmount.toFloat() * SCROLL_STEP

        for (line in window.lines) {
            if (line !is Line.Row) continue
            val widget = line.widget
            val bounds = when (widget) {
                is ClickSound -> widget.listBounds()
                is WaypointListWidget -> widget.listBounds()
                is SoundListWidget -> widget.listBounds()
                is PacketLogWidget -> widget.listBounds()
                is PresetListWidget -> widget.listBounds()
                else -> continue
            }
            if (!Draw.inside(mx, my, bounds[0], bounds[1], bounds[2], bounds[3])) continue
            val handled = when (widget) {
                is ClickSound -> widget.scrollBy(amount)
                is WaypointListWidget -> widget.scrollBy(amount)
                is SoundListWidget -> widget.scrollBy(amount)
                is PacketLogWidget -> widget.scrollBy(amount)
                is PresetListWidget -> widget.scrollBy(amount)
                else -> false
            }
            if (handled) return true
        }
        window.scroll = (window.scroll - amount).coerceIn(0f, maxScroll(window))
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        if (event.isPaste) {
            val clipboard = runCatching { McCompat.mc.keyboardHandler.clipboard }.getOrNull().orEmpty()
            focused?.let { if (it.paste(clipboard)) return true }
            query += clipboard.lineSequence().firstOrNull().orEmpty()
            return true
        }
        focused?.let { if (it.keyPressed(key)) return true }
        when (key) {
            KEY_BACKSPACE -> { if (query.isNotEmpty()) query = query.dropLast(1); return true }
            KEY_ESCAPE -> {
                when {
                    config != null -> closeConfig()
                    query.isNotEmpty() -> query = ""
                    else -> onClose()
                }
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val character = event.codepoint().toChar()
        focused?.let { if (it.charTyped(character)) return true }
        if (character.code >= 32) query += character
        return true
    }

    private fun dropFocus() {
        focused?.loseFocus()
        focused = null
    }

    override fun removed() {
        rememberedModule = config?.module?.key
        rememberedQuery = query
        super.removed()
    }

    override fun onClose() {
        dropFocus()
        panels.filter { theme.clickPanels.containsKey(it.id) }.forEach { it.saved().x = it.x; it.saved().y = it.y }
        theme.clickPanels.keys.retainAll(byId.keys + DEV_PANELS)
        theme.collapsedGroups.removeAll { it.startsWith("click/") }
        ConfigManager.save(ConfigFileType.FEATURES, "config screen closed")
        super.onClose()
        rememberedModule = null
        rememberedQuery = ""
        movedConfig = false
    }

    companion object {
        private var rememberedModule: String? = null
        private var rememberedQuery = ""

        private var movedConfig = false

        private val LINKS = listOf(
            Link("discord", "Discord", "https://discord.gg/FmuqVEPEdg"),
            Link("website", "pawfectaddons.net", "https://pawfectaddons.net"),
            Link("github", "GitHub", "https://github.com/pawliciously/PawfectAddons"),
        )

        private val DEV_PANELS = setOf("dev")

        private const val OFF = -1000f
        private const val MIN_WIDTH = 820f
        private const val PANEL_WIDTH = 124f
        private const val GAP = 10f
        private const val MARGIN = 8f
        private const val TOP = 14f
        private const val BOTTOM_ROOM = 42f
        private const val HEADER = 20f
        private const val ACCENT_LINE = 1.5f
        private const val RADIUS = 5f
        private const val BODY_PAD = 2f
        private const val MODULE_HEIGHT = 16f
        private const val MODULE_INSET = 9f
        private const val HIT_HEIGHT = 12f
        private const val HIT_INSET = 18f
        private const val MAX_HITS = 4
        private const val NO_MATCH_HEIGHT = 15f
        private const val CONFIG_WIDTH = 236f
        private const val WIDE_CONFIG = 300f
        private const val CONFIG_GAP = 6f
        private const val CONFIG_PAD = 7f
        private const val CONFIG_INSET = 10f
        private const val SCROLL_ROOM = 4f
        private const val BAND = 17f
        private const val GROUP_LABEL = 15f
        private const val GROUP_GAP = 7f
        private const val WIDGET_GAP = 3f
        private const val FLASH_MS = 1400L
        private const val DRAG_SLOP = 2f
        private const val SCROLL_STEP = 18f
        private const val SEARCH_WIDTH = 200f
        private const val SEARCH_HEIGHT = 20f
        private const val LINK_SIZE = 20f
        private const val LINK_ICON = 11f
        private const val LINK_TEXTURE = 64f
        private const val LINK_GAP = 4f
        private const val SWITCH_W = 16f
        private const val CLOSE_SCALE = 1f
        private const val SWITCH_H = 9f
        private const val KEY_ESCAPE = 256
        private const val KEY_BACKSPACE = 259
    }
}
