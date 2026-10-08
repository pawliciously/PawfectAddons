package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.ThemeConfig
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.SettingGroup
import dev.pawfect.addons.config.settings.SettingSection
import dev.pawfect.addons.config.settings.ToggleSetting
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.icon
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

/**
 * The click GUI: one panel per category that you can drag anywhere, with its modules listed
 * underneath. A module lights up in the accent when it's on; left click switches it, right click
 * (or its arrow) opens its settings in place. Typing anywhere searches, from the bar along the
 * bottom. Panel positions are saved.
 */
class ClickGuiScreen(
    sections: List<SettingSection>,
    private val initialTab: String? = null,
) : Screen(Component.literal("PawfectAddons")) {

    private class Module(val category: SettingCategory, val group: SettingGroup, val switch: ToggleSetting?) {
        val key = "${category.id}/${group.title}"
        val settings: List<Setting<*>> = group.settings.filter { it !== switch }
    }

    private inner class Panel(val category: SettingCategory, val modules: List<Module>) {
        val id get() = category.id
        var x = 0f
        var y = 0f
        var scroll = 0f
        var bodyHeight = 0f
        var contentHeight = 0f
        val collapsed: Boolean get() = theme.clickPanels[id]?.collapsed == true

        /** The saved entry, made from where the panel is now when it was never saved. */
        fun saved(): ThemeConfig.ClickPanel = theme.clickPanels.getOrPut(id) {
            ThemeConfig.ClickPanel().also { it.x = x; it.y = y }
        }

        val width: Float get() = if (openModule?.let { open -> modules.any { it.key == open && it.wide() } } == true) WIDE_WIDTH else PANEL_WIDTH

        val height: Float get() = if (collapsed) HEADER else HEADER + bodyHeight + BODY_PAD * 2f
    }

    private val theme get() = ConfigManager.features.theme
    private val categories = sections.flatMap { it.categories }
    private val panels: List<Panel> = categories.map { category ->
        Panel(category, category.groups.map { group -> Module(category, group, switchOf(group)) })
    }
    private val byId = panels.associateBy { it.id }

    private val widgets = HashMap<String, Widget>()
    private val hover = HashMap<String, Anim>()
    private val openAnim = Anim(220L, 0f)

    private var openModule: String? = remembered
    private var query = rememberedQuery

    private var focused: Widget? = null
    private var dragged: Widget? = null

    private var pressedPanel: Panel? = null
    private var draggingPanel = false
    private var pressX = 0f
    private var pressY = 0f
    private var grabX = 0f
    private var grabY = 0f

    private var draggingBar: Panel? = null
    private var barGrab = 0f

    private var linkTip = -1
    private var lastLinkTip = -1
    private val linkTipAnim = Anim(120L)

    private var placed = false

    init {
        initialTab?.let { wanted -> byId[wanted]?.let { bringToFront(it) } }
    }

    /** Lays the panels out on the first frame, once the scale for this screen is known. */
    private fun placeOnce() {
        if (placed) return
        placed = true
        placeDefaults()
        initialTab?.let { wanted -> byId[wanted]?.let { panel -> if (panel.collapsed) panel.saved().collapsed = false } }
    }

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.extractBackground(graphics, mouseX, mouseY, deltaTicks)
        graphics.fill(0, 0, width, height, 0x50000000)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        UiScale.minWidth = COLUMNS * PANEL_WIDTH + (COLUMNS - 1) * GAP + MARGIN * 2f
        UiScale.minHeight = 300f
        UiScale.push(graphics)
        val mx = UiScale.mouseX(mouseX.toDouble())
        val my = UiScale.mouseY(mouseY.toDouble())
        try {
            placeOnce()
            if (pressedPanel == null) panels.forEach { clamp(it) }
            dragPanel(mx, my)
            draggingBar?.let { scrollFromBar(it, my) }
            dragged?.mouseDragged(mx, my)

            // Panels drop in from just above their spot as the menu opens.
            val open = Anim.easeOutCubic(openAnim.update(1f).toDouble()).toFloat()
            graphics.pose().pushMatrix()
            graphics.pose().translate(0f, (1f - open) * -8f)

            val top = topmostAt(mx, my)
            ordered().forEach { panel ->
                // Only the panel on top reacts to the mouse.
                val px = if (panel === top) mx else -1000f
                val py = if (panel === top) my else -1000f
                drawPanel(graphics, panel, px, py)
            }
            graphics.pose().popMatrix()

            drawSearch(graphics, mx, my)
            drawLinks(graphics, mx, my)
            Notifications.renderScaled(graphics)
        } finally {
            UiScale.pop(graphics)
        }
    }

    // ------------------------------------------------------------------ modules

    /**
     * The switch a module is named after: an "Enabled" toggle, or a first toggle that shares a
     * word with the group's title, like Packet Overlay in Overlay. Groups without one are
     * settings only.
     */
    private fun switchOf(group: SettingGroup): ToggleSetting? {
        val toggles = group.settings.filterIsInstance<ToggleSetting>()
        toggles.firstOrNull { it.name.equals("Enabled", ignoreCase = true) }?.let { return it }
        val first = group.settings.firstOrNull() as? ToggleSetting ?: return null
        val title = stems(group.title)
        return first.takeIf { stems(it.name).any { word -> word in title } }
    }

    private fun stems(text: String): Set<String> =
        text.lowercase().split(' ', '-', '/').filter { it.length >= 3 }.map { it.removeSuffix("s") }.toSet()

    private fun Module.wide(): Boolean = widgetsOf(this).any { it is PacketLogWidget || it is WaypointListWidget }

    private fun widgetsOf(module: Module): List<Widget> = module.settings.mapNotNull { setting ->
        val key = "${module.key}/${setting.name}"
        widgets[key] ?: ClickWidgets.create(setting)?.also { widgets[key] = it }
    }

    private fun hasSettings(module: Module): Boolean = module.settings.isNotEmpty()

    private fun searching(): Boolean = query.isNotBlank()

    private fun matches(module: Module): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return module.group.title.lowercase().contains(needle) ||
            module.category.title.lowercase().contains(needle) ||
            module.settings.any { it.name.lowercase().contains(needle) }
    }

    private fun shownModules(panel: Panel): List<Module> = panel.modules.filter { matches(it) }

    private fun toggleOpen(module: Module) {
        dropFocus()
        openModule = if (openModule == module.key) null else module.key
        UiSound.click()
    }

    // ------------------------------------------------------------------ panels

    private fun ordered(): List<Panel> {
        val order = theme.clickOrder
        val known = order.mapNotNull { byId[it] }
        return known + panels.filter { it.id !in order }
    }

    private fun bringToFront(panel: Panel) {
        val order = theme.clickOrder
        order.remove(panel.id)
        panels.filter { it.id !in order && it !== panel }.forEach { order.add(it.id) }
        order.add(panel.id)
    }

    /** Panels that were never moved go in columns, each one under the shortest column so far. */
    private fun placeDefaults() {
        val total = COLUMNS * PANEL_WIDTH + (COLUMNS - 1) * GAP
        val left = floor(((UiScale.width - total) / 2f).coerceAtLeast(MARGIN))
        val heights = FloatArray(COLUMNS) { TOP }
        panels.forEach { panel ->
            val column = heights.indices.minBy { heights[it] }
            val saved = theme.clickPanels[panel.id]
            if (saved == null) {
                panel.x = left + column * (PANEL_WIDTH + GAP)
                panel.y = heights[column]
            } else {
                panel.x = saved.x
                panel.y = saved.y
            }
            heights[column] += HEADER + BODY_PAD * 2f + panel.modules.size * MODULE_HEIGHT + GAP
        }
        panels.forEach { clamp(it) }
    }

    private fun clamp(panel: Panel) {
        panel.x = floor(panel.x.coerceIn(0f, (UiScale.width - panel.width).coerceAtLeast(0f)))
        panel.y = floor(panel.y.coerceIn(0f, (UiScale.height - HEADER).coerceAtLeast(0f)))
    }

    private fun dragPanel(mouseX: Float, mouseY: Float) {
        val panel = pressedPanel ?: return
        if (!draggingPanel && abs(mouseX - pressX) + abs(mouseY - pressY) > DRAG_SLOP) draggingPanel = true
        if (!draggingPanel) return
        panel.x = mouseX - grabX
        panel.y = mouseY - grabY
        clamp(panel)
    }

    private fun topmostAt(mouseX: Float, mouseY: Float): Panel? =
        ordered().lastOrNull { Draw.inside(mouseX, mouseY, it.x, it.y, it.width, it.height) }

    /** The body's rows, laid out from its top; also works out how tall the body is. */
    private fun layout(panel: Panel) {
        var content = 0f
        val shown = shownModules(panel)
        if (shown.isEmpty()) content = NO_MATCH_HEIGHT
        shown.forEach { module ->
            content += MODULE_HEIGHT
            if (module.key == openModule && hasSettings(module)) {
                content += SETTINGS_PAD * 2f + widgetsOf(module).sumOf { it.height.toDouble() + WIDGET_GAP }.toFloat() - WIDGET_GAP
            }
        }
        panel.contentHeight = content
        val room = (UiScale.height - panel.y - HEADER - BODY_PAD * 2f - MARGIN).coerceAtLeast(MODULE_HEIGHT * 3f)
        panel.bodyHeight = content.coerceAtMost(room)
        panel.scroll = panel.scroll.coerceIn(0f, maxScroll(panel))
    }

    private fun maxScroll(panel: Panel): Float = (panel.contentHeight - panel.bodyHeight).coerceAtLeast(0f)

    private fun bodyTop(panel: Panel): Float = panel.y + HEADER + BODY_PAD

    private fun drawPanel(graphics: GuiGraphicsExtractor, panel: Panel, mouseX: Float, mouseY: Float) {
        layout(panel)
        val x = panel.x
        val y = panel.y
        val w = panel.width
        val h = panel.height
        val missed = searching() && shownModules(panel).isEmpty()

        graphics.dropShadow(x, y, w, h, RADIUS, 12f, Theme.withAlpha(0x000000, 110), 4f)
        graphics.roundPanel(x, y, w, h, RADIUS, Theme.surface(Theme.panel, 240), Theme.opaque(Theme.border))

        // The header is the panel's own top, so it follows the corners and stops square.
        Shapes.pushScissor(graphics, x, y, w, HEADER)
        graphics.roundRect(x + 1f, y + 1f, w - 2f, h - 2f, RADIUS - 1f, Theme.surface(Theme.header, 250))
        Shapes.popScissor(graphics)
        if (!panel.collapsed) graphics.rect(x + 1f, y + HEADER - ACCENT_LINE, w - 2f, ACCENT_LINE, Theme.opaque(Theme.accent))

        val textY = y + (HEADER - ACCENT_LINE - Draw.LINE_HEIGHT) / 2f
        val headerHovered = pressedPanel == null && Draw.inside(mouseX, mouseY, x, y, w, HEADER)
        graphics.icon(panel.category.icon, x + 7f, textY, Theme.opaque(Theme.accent))
        val nameX = x + 7f + UiFont.iconWidth(panel.category.icon) + 5f
        val switches = panel.modules.mapNotNull { it.switch }
        val count = if (switches.isEmpty()) "" else "${switches.count { it.value }}/${switches.size}"
        val countWidth = if (count.isEmpty()) 0f else Draw.width(count) + 6f
        graphics.string(
            Draw.truncate(panel.category.title, x + w - 7f - countWidth - nameX),
            nameX,
            textY,
            if (headerHovered) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text),
            bold = true,
        )
        if (count.isNotEmpty()) graphics.stringRight(count, x + w - 7f, textY, Theme.opaque(Theme.textDim))

        if (!panel.collapsed) drawBody(graphics, panel, mouseX, mouseY)

        // A panel with nothing that matches the search fades back.
        if (missed) graphics.roundRect(x, y, w, h, RADIUS, Theme.withAlpha(Theme.background, 165))
    }

    private fun drawBody(graphics: GuiGraphicsExtractor, panel: Panel, mouseX: Float, mouseY: Float) {
        val top = bodyTop(panel)
        val x = panel.x
        val w = panel.width
        val inBody = Draw.inside(mouseX, mouseY, x, top, w, panel.bodyHeight)
        val mx = if (inBody) mouseX else -1000f
        val my = if (inBody) mouseY else -1000f
        val shown = shownModules(panel)

        Shapes.pushScissor(graphics, x + 1f, top, w - 2f, panel.bodyHeight)
        if (shown.isEmpty()) {
            graphics.string("No match", x + MODULE_INSET, top + (NO_MATCH_HEIGHT - Draw.LINE_HEIGHT) / 2f, Theme.opaque(Theme.textDim))
        }
        var y = top - panel.scroll
        shown.forEach { module ->
            drawModule(graphics, module, x, y, w, mx, my)
            y += MODULE_HEIGHT
            if (module.key == openModule && hasSettings(module)) {
                val list = widgetsOf(module)
                val blockHeight = SETTINGS_PAD * 2f + list.sumOf { it.height.toDouble() + WIDGET_GAP }.toFloat() - WIDGET_GAP
                graphics.rect(x + 1f, y, w - 2f, blockHeight, Theme.surface(Theme.background, 245))
                graphics.rect(x + 1f, y, w - 2f, 1f, Theme.opaque(Theme.border))
                graphics.rect(x + 1f, y + blockHeight - 1f, w - 2f, 1f, Theme.opaque(Theme.border))
                var wy = y + SETTINGS_PAD
                list.forEach { widget ->
                    widget.x = x + SETTINGS_INSET
                    widget.y = wy
                    widget.width = w - SETTINGS_INSET * 2f
                    if (wy + widget.height >= top && wy <= top + panel.bodyHeight) widget.draw(graphics, mx, my)
                    wy += widget.height + WIDGET_GAP
                }
                y += blockHeight
            }
        }
        Shapes.popScissor(graphics)
        drawScrollbar(graphics, panel, mouseX, mouseY)
    }

    private fun drawModule(graphics: GuiGraphicsExtractor, module: Module, x: Float, y: Float, w: Float, mouseX: Float, mouseY: Float) {
        val hovered = Draw.inside(mouseX, mouseY, x + 1f, y, w - 2f, MODULE_HEIGHT)
        val h = hover.getOrPut(module.key) { Anim(120L) }.update(if (hovered) 1f else 0f)
        if (h > 0.01f) graphics.rect(x + 1f, y, w - 2f, MODULE_HEIGHT, Theme.withAlpha(Theme.text, (13 * h).toInt()))

        val switch = module.switch
        val color = when {
            switch?.value == true -> Theme.opaque(Theme.accent)
            switch != null -> Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), h)
            else -> Theme.lerp(Theme.mix(Theme.textDim, Theme.text, 0.55f), Theme.opaque(Theme.text), h)
        }
        val name = module.group.title
        val textX = x + MODULE_INSET
        val textY = y + (MODULE_HEIGHT - Draw.LINE_HEIGHT) / 2f
        val caretRoom = if (hasSettings(module)) ClickWidgets.caretWidth() + 10f else 4f
        val shown = Draw.truncate(name, w - MODULE_INSET - caretRoom)

        // The part of the name the search matched sits on a soft accent mark.
        val needle = query.trim()
        val at = if (needle.isEmpty()) -1 else shown.indexOf(needle, ignoreCase = true)
        if (at >= 0) {
            val markX = textX + Draw.width(shown.substring(0, at))
            val markW = Draw.width(shown.substring(at, (at + needle.length).coerceAtMost(shown.length)))
            graphics.roundRect(markX - 1f, textY - 1f, markW + 2f, Draw.LINE_HEIGHT + 1f, 2f, Theme.withAlpha(Theme.accent, 90))
        }
        graphics.string(shown, textX, textY, if (at >= 0) Theme.opaque(Theme.text) else color)

        if (hasSettings(module)) {
            val open = module.key == openModule
            val caret = if (open) Icons.CARET_DOWN else Icons.CARET_RIGHT
            val caretColor = if (open) Theme.opaque(Theme.accent) else Theme.lerp(Theme.withAlpha(Theme.textDim, 170), Theme.opaque(Theme.text), h)
            graphics.icon(caret, x + w - 6f - UiFont.iconWidth(caret), textY, caretColor)
        }
    }

    private fun barBounds(panel: Panel): FloatArray {
        val top = bodyTop(panel)
        val trackHeight = panel.bodyHeight - 4f
        val thumb = (trackHeight * panel.bodyHeight / panel.contentHeight.coerceAtLeast(1f)).coerceIn(14f, trackHeight)
        val max = maxScroll(panel)
        val thumbY = top + 2f + if (max > 0f) panel.scroll / max * (trackHeight - thumb) else 0f
        return floatArrayOf(panel.x + panel.width - 4f, top + 2f, 2f, trackHeight, thumbY, thumb)
    }

    private fun drawScrollbar(graphics: GuiGraphicsExtractor, panel: Panel, mouseX: Float, mouseY: Float) {
        if (maxScroll(panel) <= 0f) return
        val b = barBounds(panel)
        val near = draggingBar === panel || Draw.inside(mouseX, mouseY, b[0] - 3f, b[1], b[2] + 5f, b[3])
        Shapes.pill(graphics, b[0], b[1], b[2], b[3], Theme.withAlpha(Theme.text, if (near) 30 else 16))
        Shapes.pill(graphics, b[0], b[4], b[2], b[5], if (near) Theme.opaque(Theme.accent) else Theme.withAlpha(Theme.text, 90))
    }

    private fun scrollFromBar(panel: Panel, mouseY: Float) {
        val b = barBounds(panel)
        val room = b[3] - b[5]
        panel.scroll = if (room > 0f) ((mouseY - barGrab - b[1]) / room).coerceIn(0f, 1f) * maxScroll(panel) else 0f
    }

    // ------------------------------------------------------------------ search and links

    private fun searchBounds(): FloatArray =
        floatArrayOf(floor((UiScale.width - SEARCH_WIDTH) / 2f), UiScale.height - SEARCH_HEIGHT - 12f, SEARCH_WIDTH, SEARCH_HEIGHT)

    private fun drawSearch(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val s = searchBounds()
        val active = searching()
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
            // Long searches keep their end in view.
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

    // ------------------------------------------------------------------ input

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

        val panel = topmostAt(mx, my)
        if (panel == null) {
            dropFocus()
            return true
        }
        bringToFront(panel)

        if (Draw.inside(mx, my, panel.x, panel.y, panel.width, HEADER)) {
            dropFocus()
            // Dragging moves the panel; a click that doesn't move folds it.
            pressedPanel = panel
            draggingPanel = false
            pressX = mx
            pressY = my
            grabX = mx - panel.x
            grabY = my - panel.y
            UiScale.lock()
            return true
        }
        if (panel.collapsed) return true

        if (maxScroll(panel) > 0f) {
            val b = barBounds(panel)
            if (Draw.inside(mx, my, b[0] - 3f, b[1], b[2] + 5f, b[3])) {
                dropFocus()
                barGrab = if (my in b[4]..(b[4] + b[5])) my - b[4] else b[5] / 2f
                draggingBar = panel
                scrollFromBar(panel, my)
                UiScale.lock()
                return true
            }
        }

        val top = bodyTop(panel)
        if (!Draw.inside(mx, my, panel.x, top, panel.width, panel.bodyHeight)) {
            dropFocus()
            return true
        }

        var y = top - panel.scroll
        for (module in shownModules(panel)) {
            if (Draw.inside(mx, my, panel.x, y, panel.width, MODULE_HEIGHT)) {
                val onCaret = mx >= panel.x + panel.width - ClickWidgets.caretWidth() - 12f
                val switch = module.switch
                when {
                    button == 1 && hasSettings(module) -> toggleOpen(module)
                    button != 0 -> {}
                    onCaret && hasSettings(module) -> toggleOpen(module)
                    switch != null -> { dropFocus(); switch.value = !switch.value; UiSound.click() }
                    hasSettings(module) -> toggleOpen(module)
                }
                return true
            }
            y += MODULE_HEIGHT
            if (module.key == openModule && hasSettings(module)) {
                val list = widgetsOf(module)
                for (widget in list) {
                    if (widget.mouseClicked(mx, my, button)) {
                        if (focused !== widget) dropFocus()
                        focused = widget
                        dragged = widget
                        UiScale.lock()
                        return true
                    }
                }
                y += SETTINGS_PAD * 2f + list.sumOf { it.height.toDouble() + WIDGET_GAP }.toFloat() - WIDGET_GAP
            }
        }
        dropFocus()
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragged?.mouseReleased(click.button())
        dragged = null
        draggingBar = null
        pressedPanel?.let { panel ->
            if (draggingPanel) {
                panel.saved().x = panel.x
                panel.saved().y = panel.y
            } else {
                panel.saved().collapsed = !panel.collapsed
                panel.scroll = 0f
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
        val panel = topmostAt(mx, my) ?: return true
        val amount = verticalAmount.toFloat() * SCROLL_STEP

        // A list under the cursor scrolls before the panel does.
        val open = shownModules(panel).firstOrNull { it.key == openModule }
        if (open != null && !panel.collapsed) {
            for (widget in widgetsOf(open)) {
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
        }
        panel.scroll = (panel.scroll - amount).coerceIn(0f, maxScroll(panel))
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        if (event.isPaste) {
            val clipboard = runCatching { McCompat.mc.keyboardHandler.clipboard }.getOrNull().orEmpty()
            focused?.let { if (it.paste(clipboard)) return true }
            setQuery(query + clipboard.lineSequence().firstOrNull().orEmpty())
            return true
        }
        // An open dropdown, list or field gets keys first, so Esc closes it before the menu.
        focused?.let { if (it.keyPressed(key)) return true }
        when (key) {
            KEY_BACKSPACE -> { if (query.isNotEmpty()) setQuery(query.dropLast(1)); return true }
            KEY_ESCAPE -> {
                if (query.isNotEmpty()) { setQuery(""); return true }
                onClose()
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val character = event.codepoint().toChar()
        focused?.let { if (it.charTyped(character)) return true }
        // Like Odin, typing anywhere goes to the search bar.
        if (character.code >= 32) setQuery(query + character)
        return true
    }

    private fun setQuery(text: String) {
        query = text
        panels.forEach { it.scroll = 0f }
    }

    private fun dropFocus() {
        focused?.loseFocus()
        focused = null
    }

    override fun removed() {
        // Kept for a reopen, like when the theme changes; closing the menu clears them.
        remembered = openModule
        rememberedQuery = query
        super.removed()
    }

    override fun onClose() {
        dropFocus()
        panels.filter { theme.clickPanels.containsKey(it.id) }.forEach { it.saved().x = it.x; it.saved().y = it.y }
        ConfigManager.save(ConfigFileType.FEATURES, "config screen closed")
        super.onClose()
        remembered = null
        rememberedQuery = ""
    }

    companion object {
        private var remembered: String? = null
        private var rememberedQuery = ""

        private val LINKS = listOf(
            Link("discord", "Discord", "https://discord.gg/FmuqVEPEdg"),
            Link("website", "pawfectaddons.net", "https://pawfectaddons.net"),
            Link("github", "GitHub", "https://github.com/pawliciously/PawfectAddons"),
        )

        private const val COLUMNS = 7
        private const val PANEL_WIDTH = 128f
        private const val WIDE_WIDTH = 236f
        private const val GAP = 8f
        private const val MARGIN = 8f
        private const val TOP = 12f
        private const val HEADER = 20f
        private const val ACCENT_LINE = 1.5f
        private const val RADIUS = 5f
        private const val BODY_PAD = 2f
        private const val MODULE_HEIGHT = 15f
        private const val MODULE_INSET = 8f
        private const val NO_MATCH_HEIGHT = 15f
        private const val SETTINGS_PAD = 5f
        private const val SETTINGS_INSET = 7f
        private const val WIDGET_GAP = 3f
        private const val DRAG_SLOP = 2f
        private const val SCROLL_STEP = 18f
        private const val SEARCH_WIDTH = 190f
        private const val SEARCH_HEIGHT = 20f
        private const val LINK_SIZE = 20f
        private const val LINK_ICON = 11f
        private const val LINK_TEXTURE = 64f
        private const val LINK_GAP = 4f
        private const val KEY_ESCAPE = 256
        private const val KEY_BACKSPACE = 259
    }
}
