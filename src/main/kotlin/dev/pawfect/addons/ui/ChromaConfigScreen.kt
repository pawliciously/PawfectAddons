package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigGuiManager
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.SettingSection
import dev.pawfect.addons.config.settings.ToggleSetting
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.gradientHorizontal
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
import net.minecraft.network.chat.Component

class ChromaConfigScreen(
    sections: List<SettingSection>,
    initialTab: String? = null,
) : Screen(Component.literal("PawfectAddons")) {

    private class Tab(val def: Chroma.TabDef, val categories: List<SettingCategory>) {
        val id get() = def.id
    }

    private class Entry(val category: SettingCategory, val groupTitle: String, val widget: Widget) {
        val pinKey = Chroma.pinKey(category, groupTitle, widget.setting)
    }

    private class Band(val key: String, val label: String, val y: Float)

    private class Box(val key: String, val title: String, val entries: List<Entry>, val band: String?) {
        var x = 0f
        var y = 0f
        var width = 0f

        val collapsed: Boolean get() = ConfigManager.features.theme.collapsedGroups.contains(key)

        fun toggleCollapsed() {
            val list = ConfigManager.features.theme.collapsedGroups
            if (!list.remove(key)) list.add(key)
        }

        val height: Float
            get() = if (collapsed) BOX_HEADER else BOX_HEADER + BOX_PADDING * 2f + entries.sumOf { it.widget.height.toDouble() }.toFloat()
    }

    private val categories = sections.flatMap { it.categories }
    private val byId = categories.associateBy { it.id }
    private val tabs: List<Tab> = buildTabs()

    private val widgets = HashMap<String, Widget>()
    private val hover = HashMap<String, Anim>()

    private var tabId: String = (initialTab?.let { wanted -> tabs.firstOrNull { tab -> tab.categories.any { it.id == wanted } }?.id }
        ?: ConfigManager.features.theme.chromaTab.takeIf { saved -> tabs.any { it.id == saved } }
        ?: tabs.firstOrNull { it.id == "slayers" }?.id
        ?: tabs.first().id)
    private val tab: Tab get() = tabs.first { it.id == tabId }

    private val openAnim = Anim(240L, 0f)
    private val contentAnim = Anim(220L, 1f)
    private val indicatorAnim = Anim(200L)
    private var indicatorReady = false
    private val tooltipAnim = Anim(140L)
    private var tooltipIndex = -1
    private var lastTooltip = -1

    private var windowX = 0f
    private var windowY = 0f
    private var draggingWindow = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var focused: Widget? = null
    private var dragged: Widget? = null
    private var scroll = 0f
    private var maxScroll = 0f
    private val scrollAnim = Anim(140L)
    private var searchQuery = ""
    private var searchFocused = false
    private var boxes: List<Box> = emptyList()
    private var bands: List<Band> = emptyList()

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        graphics.fill(0, 0, width, height, 0xA8000000.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        UiScale.minWidth = WINDOW_WIDTH + 24f
        UiScale.minHeight = WINDOW_HEIGHT + 24f
        UiScale.push(graphics)
        val mx = UiScale.mouseX(mouseX.toDouble())
        val my = UiScale.mouseY(mouseY.toDouble())
        try {
            updateWindowPosition(mx, my)
            dragged?.mouseDragged(mx, my)
            boxes = buildBoxes()

            val open = Anim.easeOutCubic(openAnim.update(1f).toDouble()).toFloat()
            val cx = windowX + WINDOW_WIDTH / 2f
            val cy = windowY + WINDOW_HEIGHT / 2f
            graphics.pose().pushMatrix()
            graphics.pose().translate(cx, cy + (1f - open) * 10f)
            graphics.pose().scale(0.94f + 0.06f * open, 0.94f + 0.06f * open)
            graphics.pose().translate(-cx, -cy)

            drawWindow(graphics)
            drawIcons(graphics, mx, my)
            drawHeader(graphics, mx, my)
            drawContent(graphics, mx, my)
            drawTooltip(graphics)

            graphics.pose().popMatrix()
            Notifications.renderScaled(graphics)
        } finally {
            UiScale.pop(graphics)
        }
    }

    private fun hoverFor(id: String): Anim = hover.getOrPut(id) { Anim(160L) }

    private fun buildTabs(): List<Tab> {
        val placed = HashSet<String>()
        val built = Chroma.TABS.mapNotNull { def ->
            val cats = def.categories.mapNotNull { byId[it] }.onEach { placed += it.id }
            if (def.id != Chroma.FAVOURITES && cats.isEmpty()) null else Tab(def, cats)
        }.toMutableList()
        val rest = categories.filter { it.id !in placed }
        if (rest.isNotEmpty()) {
            val misc = built.indexOfFirst { it.id == "misc" }
            if (misc >= 0) built[misc] = Tab(built[misc].def, built[misc].categories + rest)
            else built += Tab(Chroma.TabDef("other", "Other", Icons.GEAR, rest.map { it.id }), rest)
        }
        return built
    }

    private fun selectTab(id: String) {
        if (id == tabId && searchQuery.isEmpty()) return
        tabId = id
        searchQuery = ""
        searchFocused = false
        scroll = 0f
        scrollAnim.set(0f)
        contentAnim.set(0f)
        dropFocus()
        UiSound.click()
    }

    private fun widgetFor(category: SettingCategory, groupTitle: String, setting: Setting<*>): Widget? {
        val key = Chroma.pinKey(category, groupTitle, setting)
        return widgets[key] ?: WidgetFactory.create(setting)?.also { widgets[key] = it }
    }

    private fun buildBoxes(): List<Box> {
        val query = searchQuery.trim().lowercase()
        if (query.isNotEmpty()) {
            return categories.flatMap { category ->
                category.groups.filter { group ->
                    category.title.lowercase().contains(query) || group.title.lowercase().contains(query) ||
                        group.settings.any { it.name.lowercase().contains(query) }
                }.map { group -> box(category, group.title, group.settings, category.title) }
            }
        }
        if (tab.id == Chroma.FAVOURITES) {
            val wanted = Chroma.pins.toSet()
            return categories.mapNotNull { category ->
                val entries = category.groups.flatMap { group ->
                    group.settings.filter { Chroma.pinKey(category, group.title, it) in wanted }
                        .mapNotNull { setting -> widgetFor(category, group.title, setting)?.let { Entry(category, group.title, it) } }
                }
                if (entries.isEmpty()) null else Box("fav/${category.id}", category.title, entries, null)
            }
        }
        val multi = tab.categories.size > 1
        return tab.categories.flatMap { category ->
            var section: String? = null
            category.groups.map { group ->
                section = group.section ?: section
                box(category, group.title, group.settings, bandLabel(category, section, multi))
            }
        }
    }

    private fun bandLabel(category: SettingCategory, section: String?, multi: Boolean): String? {
        val own = section?.takeUnless { it.equals(category.title, ignoreCase = true) }
        return when {
            multi && own != null -> "${category.title}  /  $own"
            multi -> category.title
            else -> section
        }
    }

    private fun box(category: SettingCategory, groupTitle: String, settings: List<Setting<*>>, band: String?): Box {
        val entries = settings.mapNotNull { setting -> widgetFor(category, groupTitle, setting)?.let { Entry(category, groupTitle, it) } }
        return Box("${category.id}/$groupTitle", groupTitle, entries, band)
    }

    private fun tabCategories(t: Tab): List<SettingCategory> = if (t.id == Chroma.FAVOURITES) categories else t.categories

    private fun counts(t: Tab): Pair<Int, Int> {
        val wanted = Chroma.pins.toSet()
        val toggles = tabCategories(t).flatMap { category ->
            category.groups.flatMap { group ->
                group.settings.filterIsInstance<ToggleSetting>()
                    .filter { t.id != Chroma.FAVOURITES || Chroma.pinKey(category, group.title, it) in wanted }
            }
        }
        return toggles.count { it.value } to toggles.size
    }

    private fun lastUpdated(t: Tab): String =
        tabCategories(t).map { it.updated }.filter { it.isNotEmpty() }.maxWithOrNull(::compareVersions).orEmpty()

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun bandKey(label: String): String = "chroma/$tabId/$label"

    private fun bandCollapsed(key: String): Boolean = ConfigManager.features.theme.collapsedSections.contains(key)

    private fun updateWindowPosition(mouseX: Float, mouseY: Float) {
        val theme = ConfigManager.features.theme
        if (draggingWindow) {
            windowX = mouseX - dragOffsetX
            windowY = mouseY - dragOffsetY
        } else if (theme.windowX < 0f || theme.windowY < 0f) {
            windowX = (UiScale.width - WINDOW_WIDTH) / 2f
            windowY = (UiScale.height - WINDOW_HEIGHT) / 2f
        } else {
            windowX = theme.windowX
            windowY = theme.windowY
        }
        val margin = 6f
        windowX = windowX.coerceIn(margin, (UiScale.width - WINDOW_WIDTH - margin).coerceAtLeast(margin))
        windowY = windowY.coerceIn(margin, (UiScale.height - WINDOW_HEIGHT - margin).coerceAtLeast(margin))
        windowX = kotlin.math.floor(windowX)
        windowY = kotlin.math.floor(windowY)
    }

    private fun drawWindow(graphics: GuiGraphicsExtractor) {
        graphics.dropShadow(windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_RADIUS, 24f, Theme.withAlpha(0x000000, 160), 6f)
        graphics.roundPanel(windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_RADIUS, Theme.surface(Theme.background), Theme.opaque(Theme.border))
        Shapes.pushScissor(graphics, windowX, windowY, ICON_COLUMN, WINDOW_HEIGHT)
        graphics.roundRect(windowX + 1f, windowY + 1f, WINDOW_WIDTH - 2f, WINDOW_HEIGHT - 2f, WINDOW_RADIUS - 1f, Theme.surface(Theme.header, 240))
        Shapes.popScissor(graphics)
        graphics.rect(windowX + ICON_COLUMN, windowY + EDGE + 6f, 1f, WINDOW_HEIGHT - EDGE - 12f, Theme.withAlpha(Theme.border, 200))
        Chroma.drawEdge(graphics, windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_RADIUS, EDGE)
    }

    private fun iconY(index: Int): Float = windowY + EDGE + 8f + index * ICON_STEP

    private fun drawIcons(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val cellX = windowX + (ICON_COLUMN - ICON_CELL) / 2f
        val activeIndex = tabs.indexOfFirst { it.id == tabId }
        val target = iconY(activeIndex) - windowY
        if (!indicatorReady || draggingWindow) { indicatorAnim.set(target); indicatorReady = true }
        val indicator = windowY + indicatorAnim.update(target)
        if (searchQuery.isEmpty()) Chroma.drawGlow(graphics, cellX, indicator, ICON_CELL, 7f)

        tooltipIndex = -1
        tabs.forEachIndexed { i, t ->
            val y = iconY(i)
            val hovered = Draw.inside(mouseX, mouseY, cellX, y, ICON_CELL, ICON_CELL) && !searchFocused
            if (hovered) tooltipIndex = i
            val active = t.id == tabId && searchQuery.isEmpty()
            val h = hoverFor("icon/${t.id}").update(if (hovered) 1f else 0f)
            if (h > 0.01f && !active) graphics.roundRect(cellX, y, ICON_CELL, ICON_CELL, 7f, Theme.withAlpha(Theme.text, (14 * h).toInt()))
            val lit = if (active) 1f else h
            val color = if (active) Chroma.onGlow() else Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), lit)
            graphics.iconCentered(t.def.icon, cellX + ICON_CELL / 2f, y + ICON_CELL / 2f, ICON_SCALE, color)
        }
    }

    private fun drawTooltip(graphics: GuiGraphicsExtractor) {
        val shown = tooltipAnim.update(if (tooltipIndex >= 0) 1f else 0f)
        if (tooltipIndex >= 0) lastTooltip = tooltipIndex
        val index = lastTooltip
        if (shown < 0.02f || index !in tabs.indices) return
        val t = tabs[index]
        val (on, total) = counts(t)
        val detail = if (total > 0) "$on of $total on" else if (t.id == Chroma.FAVOURITES) "Star a setting to keep it here" else ""
        val width = maxOf(Draw.width(t.def.title), Draw.width(detail)) + 20f
        val heightBox = if (detail.isEmpty()) 20f else 31f
        val x = windowX + ICON_COLUMN + 6f - (1f - shown) * 6f
        val y = iconY(index) + (ICON_CELL - heightBox) / 2f
        val alpha = (255 * shown).toInt()
        graphics.dropShadow(x, y, width, heightBox, 6f, 10f, Theme.withAlpha(0x000000, (150 * shown).toInt()))
        graphics.roundPanel(x, y, width, heightBox, 6f, Theme.withAlpha(Theme.header, (245 * shown).toInt()), Theme.withAlpha(Theme.accent, (190 * shown).toInt()))
        graphics.circle(x + 7f, y + 10f, 2f, Theme.withAlpha(Theme.accent, alpha))
        graphics.string(t.def.title, x + 13f, y + 6f, Theme.withAlpha(Theme.text, alpha), bold = true)
        if (detail.isNotEmpty()) graphics.string(detail, x + 13f, y + 17f, Theme.withAlpha(Theme.textDim, alpha))
    }

    private class Button(val id: String, val label: String, val x: Float, val y: Float, val width: Float)

    private fun headerButtons(): List<Button> {
        if (!isDev()) return emptyList()
        val label = "New menu"
        val w = Draw.width(label) + 16f
        val y = windowY + EDGE + (HEADER_HEIGHT - BUTTON_HEIGHT) / 2f
        return listOf(Button("new", label, windowX + WINDOW_WIDTH - 10f - w, y, w))
    }

    private fun searchBounds(): FloatArray {
        val left = (headerButtons().minOfOrNull { it.x - 6f } ?: (windowX + WINDOW_WIDTH - 10f)) - SEARCH_WIDTH
        return floatArrayOf(left, windowY + EDGE + (HEADER_HEIGHT - BUTTON_HEIGHT) / 2f, SEARCH_WIDTH, BUTTON_HEIGHT)
    }

    private fun drawHeader(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val left = windowX + ICON_COLUMN + 14f
        val textY = windowY + EDGE + (HEADER_HEIGHT - Draw.LINE_HEIGHT) / 2f
        val searching = searchQuery.isNotBlank()
        val title = if (searching) "Search" else tab.def.title
        graphics.string(title, left, textY, Theme.opaque(Theme.accent), bold = true)
        var x = left + Draw.width(title) + 9f
        if (!searching) {
            val (on, total) = counts(tab)
            if (total > 0) {
                val countText = "$on of $total on"
                graphics.string(countText, x, textY, Theme.opaque(Theme.textDim))
                x += Draw.width(countText) + 9f
            }
            val version = lastUpdated(tab)
            if (version.isNotEmpty()) graphics.string("Last updated - $version", x, textY, Theme.withAlpha(Theme.textDim, 170))
        } else {
            graphics.string("${boxes.sumOf { it.entries.size }} settings", x, textY, Theme.opaque(Theme.textDim))
        }

        val s = searchBounds()
        val searchHover = Draw.inside(mouseX, mouseY, s[0], s[1], s[2], s[3])
        val hs = hoverFor("search").update(if (searchFocused || searchHover) 1f else 0f)
        if (searchFocused) graphics.dropShadow(s[0], s[1], s[2], s[3], s[3] / 2f, 6f, Theme.withAlpha(Theme.accent, 90))
        graphics.pill(s[0], s[1], s[2], s[3], Theme.surface(Theme.header, 235), 1f, Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), hs))
        val shown = if (searchQuery.isEmpty() && !searchFocused) "Search..." else searchQuery + if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        val searchColor = if (searchQuery.isEmpty() && !searchFocused) Theme.opaque(Theme.textDim) else Theme.opaque(Theme.text)
        val sy = s[1] + (s[3] - Draw.LINE_HEIGHT) / 2f
        graphics.string(Draw.truncate(shown, s[2] - 26f), s[0] + 9f, sy, searchColor)
        graphics.stringRight(Icons.SEARCH, s[0] + s[2] - 8f, sy, Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.accent), hs))

        headerButtons().forEach { b ->
            val hovered = Draw.inside(mouseX, mouseY, b.x, b.y, b.width, BUTTON_HEIGHT)
            val h = hoverFor(b.id).update(if (hovered) 1f else 0f)
            graphics.pill(b.x, b.y, b.width, BUTTON_HEIGHT, Theme.lerp(Theme.surface(Theme.header, 235), Theme.withAlpha(Theme.accent, 60), h), 1f, Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), h))
            graphics.string(b.label, b.x + 8f, b.y + (BUTTON_HEIGHT - Draw.LINE_HEIGHT) / 2f, Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), h))
        }
        graphics.roundRect(windowX + ICON_COLUMN + 10f, windowY + EDGE + HEADER_HEIGHT, WINDOW_WIDTH - ICON_COLUMN - 20f, 1f, 0.5f, Theme.withAlpha(Theme.border, 190))
    }

    private fun contentBounds(): FloatArray {
        val left = windowX + ICON_COLUMN + 1f
        val top = windowY + EDGE + HEADER_HEIGHT + 1f
        return floatArrayOf(left, top, windowX + WINDOW_WIDTH - 1f, windowY + WINDOW_HEIGHT - 4f)
    }

    private fun layout(): Float {
        val b = contentBounds()
        val left = b[0] + CONTENT_PADDING
        val right = b[2] - CONTENT_PADDING - 4f
        val columnWidth = (right - left - COLUMN_GAP) / 2f
        val heights = FloatArray(2)
        val slide = (1f - Anim.easeOutCubic(contentAnim.update(1f).toDouble()).toFloat()) * 12f
        val marks = ArrayList<Band>()
        var current: String? = null
        var folded = false
        boxes.forEach { box ->
            val wanted = box.band
            if (wanted != null && wanted != current) {
                current = wanted
                val key = bandKey(wanted)
                folded = bandCollapsed(key)
                val flush = heights.max()
                marks += Band(key, wanted, b[1] + CONTENT_PADDING + flush - scrollAnim.value + slide)
                heights[0] = flush + BAND_HEIGHT
                heights[1] = flush + BAND_HEIGHT
            }
            if (folded) {
                box.width = 0f
                return@forEach
            }
            val column = if (heights[0] <= heights[1]) 0 else 1
            box.width = columnWidth
            box.x = left + column * (columnWidth + COLUMN_GAP)
            box.y = b[1] + CONTENT_PADDING + heights[column] - scrollAnim.value + slide
            heights[column] += box.height + COLUMN_GAP
        }
        bands = marks
        return heights.max()
    }

    private fun shownBoxes(): List<Box> = boxes.filter { it.width > 0f }

    private fun drawContent(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val b = contentBounds()
        val total = layout()
        maxScroll = (total + CONTENT_PADDING - (b[3] - b[1])).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)
        scrollAnim.update(scroll)

        Shapes.pushScissor(graphics, b[0], b[1], b[2] - b[0], b[3] - b[1])
        if (boxes.isEmpty()) {
            val message = if (searchQuery.isNotBlank()) "Nothing matches \"$searchQuery\"." else "Nothing pinned yet. Hover a setting and click its star to keep it here."
            graphics.string(message, (b[0] + b[2]) / 2f - Draw.width(message) / 2f, b[1] + 40f, Theme.opaque(Theme.textDim))
        }
        bands.forEach { band -> drawBand(graphics, band, b[0] + CONTENT_PADDING, b[2] - CONTENT_PADDING - 4f, mouseX, mouseY) }
        shownBoxes().forEach { box -> drawBox(graphics, box, mouseX, mouseY) }
        Shapes.popScissor(graphics)

        if (maxScroll > 0f) {
            val trackHeight = b[3] - b[1] - 12f
            val trackY = b[1] + 6f
            val thumbHeight = (trackHeight * (trackHeight / (trackHeight + maxScroll))).coerceAtLeast(26f)
            val thumbY = trackY + (scrollAnim.value / maxScroll) * (trackHeight - thumbHeight)
            graphics.pill(b[2] - 5f, trackY, 2.5f, trackHeight, Theme.withAlpha(Theme.border, 110))
            graphics.pill(b[2] - 5f, thumbY, 2.5f, thumbHeight, Theme.withAlpha(Theme.accent, 220))
        }
    }

    private fun bandHit(band: Band, mouseX: Float, mouseY: Float): Boolean {
        val b = contentBounds()
        return Draw.inside(mouseX, mouseY, b[0] + CONTENT_PADDING, band.y, b[2] - b[0] - CONTENT_PADDING * 2f, BAND_HEIGHT - COLUMN_GAP)
    }

    private fun drawBand(graphics: GuiGraphicsExtractor, band: Band, left: Float, right: Float, mouseX: Float, mouseY: Float) {
        val folded = bandCollapsed(band.key)
        val h = hoverFor("band/${band.key}").update(if (bandHit(band, mouseX, mouseY)) 1f else 0f)
        val centerY = band.y + (BAND_HEIGHT - COLUMN_GAP) / 2f
        val textY = centerY - Draw.LINE_HEIGHT / 2f
        val color = Theme.lerp(Theme.withAlpha(Theme.accent, 220), Theme.opaque(Theme.text), h)
        val caret = if (folded) Icons.CARET_RIGHT else Icons.CARET_DOWN
        graphics.icon(caret, left + 1f, textY, color)
        val label = band.label.uppercase()
        val textX = left + UiFont.iconWidth(caret) + 6f
        graphics.string(label, textX, textY, color, bold = true)
        var ruleX = textX + Draw.width(label) + 8f
        if (folded) {
            val hidden = "${boxesIn(band).size} hidden"
            graphics.string(hidden, ruleX, textY, Theme.withAlpha(Theme.textDim, 190))
            ruleX += Draw.width(hidden) + 8f
        }
        graphics.gradientHorizontal(ruleX, centerY - 0.5f, (right - ruleX).coerceAtLeast(0f), 1f, Theme.withAlpha(Theme.accent, 140), Theme.withAlpha(Theme.border, 50))
    }

    private fun boxesIn(band: Band): List<Box> = boxes.filter { it.band != null && bandKey(it.band) == band.key }

    private fun drawBox(graphics: GuiGraphicsExtractor, box: Box, mouseX: Float, mouseY: Float) {
        val b = contentBounds()
        if (box.y > b[3] || box.y + box.height < b[1]) return
        val hovered = Draw.inside(mouseX, mouseY, box.x, box.y, box.width, box.height)
        val h = hoverFor("box/${box.key}").update(if (hovered) 1f else 0f)
        graphics.dropShadow(box.x, box.y, box.width, box.height, BOX_RADIUS, 8f, Theme.withAlpha(0x000000, 90), 2f)
        graphics.roundPanel(box.x, box.y, box.width, box.height, BOX_RADIUS, Theme.surface(Theme.panel),
            Theme.lerp(Theme.opaque(Theme.border), Theme.withAlpha(Theme.accent, 150), h * 0.6f))

        val headerHovered = Draw.inside(mouseX, mouseY, box.x, box.y, box.width, BOX_HEADER)
        val textY = box.y + (BOX_HEADER - Draw.LINE_HEIGHT) / 2f
        graphics.circle(box.x + 10f, box.y + BOX_HEADER / 2f, 2.2f + 0.6f * h, Theme.opaque(Theme.accent))
        graphics.string(box.title, box.x + 17f, textY, if (headerHovered) Theme.opaque(Theme.accent) else Theme.opaque(Theme.text), bold = true)
        if (box.collapsed) {
            graphics.stringRight("+", box.x + box.width - 9f, textY, Theme.opaque(Theme.textDim))
            return
        }
        graphics.roundRect(box.x + 9f, box.y + BOX_HEADER, box.width - 18f, 1f, 0.5f, Theme.withAlpha(Theme.border, 150))

        var y = box.y + BOX_HEADER + BOX_PADDING
        box.entries.forEach { entry ->
            val widget = entry.widget
            widget.x = box.x + STAR_COLUMN
            widget.y = y
            widget.width = box.width - STAR_COLUMN - 2f
            widget.draw(graphics, mouseX, mouseY)
            val pinned = entry.pinKey in Chroma.pins
            val rowHovered = Draw.inside(mouseX, mouseY, box.x, y, box.width, Widget.ROW_HEIGHT)
            if (pinned || rowHovered) {
                val starHovered = Draw.inside(mouseX, mouseY, box.x + 2f, y, STAR_COLUMN, Widget.ROW_HEIGHT)
                val color = when {
                    pinned -> Theme.opaque(Theme.accent)
                    starHovered -> Theme.opaque(Theme.text)
                    else -> Theme.withAlpha(Theme.textDim, 150)
                }
                graphics.icon(Icons.SPARKLE, box.x + 5f, y + (Widget.ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f, color)
            }
            y += widget.height
        }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = UiScale.mouseX(click.x())
        val my = UiScale.mouseY(click.y())

        val cellX = windowX + (ICON_COLUMN - ICON_CELL) / 2f
        tabs.forEachIndexed { i, t ->
            if (Draw.inside(mx, my, cellX, iconY(i), ICON_CELL, ICON_CELL)) { selectTab(t.id); return true }
        }

        headerButtons().forEach { b ->
            if (Draw.inside(mx, my, b.x, b.y, b.width, BUTTON_HEIGHT)) {
                UiSound.click()
                if (b.id == "new") {
                    ConfigManager.features.theme.classicMenu = false
                    ConfigManager.features.theme.chromaTab = tabId
                    ConfigManager.save(ConfigFileType.FEATURES, "switched to the click GUI")
                    PawfectAddons.queueScreen { ConfigGuiManager.open(tab.categories.firstOrNull()?.id) }
                }
                return true
            }
        }

        val s = searchBounds()
        searchFocused = Draw.inside(mx, my, s[0], s[1], s[2], s[3])
        if (searchFocused) { dropFocus(); return true }

        if (Draw.inside(mx, my, windowX, windowY, WINDOW_WIDTH, EDGE + HEADER_HEIGHT)) {
            draggingWindow = true
            dragOffsetX = mx - windowX
            dragOffsetY = my - windowY
            UiScale.lock()
            return true
        }

        val b = contentBounds()
        if (Draw.inside(mx, my, b[0], b[1], b[2] - b[0], b[3] - b[1])) {
            for (band in bands) {
                if (bandHit(band, mx, my)) {
                    val list = ConfigManager.features.theme.collapsedSections
                    if (!list.remove(band.key)) list.add(band.key)
                    dropFocus()
                    UiSound.click()
                    return true
                }
            }
            for (box in shownBoxes()) {
                if (Draw.inside(mx, my, box.x, box.y, box.width, BOX_HEADER)) {
                    box.toggleCollapsed(); dropFocus(); UiSound.click(); return true
                }
                if (box.collapsed) continue
                var y = box.y + BOX_HEADER + BOX_PADDING
                for (entry in box.entries) {
                    if (Draw.inside(mx, my, box.x + 2f, y, STAR_COLUMN, Widget.ROW_HEIGHT)) {
                        val pins = Chroma.pins
                        if (!pins.remove(entry.pinKey)) pins.add(entry.pinKey)
                        UiSound.click()
                        return true
                    }
                    y += entry.widget.height
                }
                for (entry in box.entries) {
                    val widget = entry.widget
                    if (widget.mouseClicked(mx, my, click.button())) {
                        if (focused !== widget) dropFocus()
                        focused = widget
                        dragged = widget
                        UiScale.lock()
                        return true
                    }
                }
            }
        }
        dropFocus()
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragged?.mouseReleased(click.button())
        dragged = null
        if (draggingWindow) {
            draggingWindow = false
            ConfigManager.features.theme.windowX = windowX
            ConfigManager.features.theme.windowY = windowY
        }
        UiScale.unlock()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (verticalAmount == 0.0) return false
        val mx = UiScale.mouseX(mouseX)
        val my = UiScale.mouseY(mouseY)
        for (box in shownBoxes()) {
            if (box.collapsed) continue
            for (entry in box.entries) {
                val widget = entry.widget
                val bounds = when (widget) {
                    is WaypointListWidget -> widget.listBounds()
                    is SoundListWidget -> widget.listBounds()
                    is PacketLogWidget -> widget.listBounds()
                    is PresetListWidget -> widget.listBounds()
                    else -> continue
                }
                if (!Draw.inside(mx, my, bounds[0], bounds[1], bounds[2], bounds[3])) continue
                val handled = when (widget) {
                    is WaypointListWidget -> widget.scrollBy(verticalAmount.toFloat() * SCROLL_STEP)
                    is SoundListWidget -> widget.scrollBy(verticalAmount.toFloat() * SCROLL_STEP)
                    is PacketLogWidget -> widget.scrollBy(verticalAmount.toFloat() * SCROLL_STEP)
                    is PresetListWidget -> widget.scrollBy(verticalAmount.toFloat() * SCROLL_STEP)
                    else -> false
                }
                if (handled) return true
            }
        }
        if (Draw.inside(mx, my, windowX, windowY, ICON_COLUMN, WINDOW_HEIGHT)) {
            val index = tabs.indexOfFirst { it.id == tabId }
            val next = (index + if (verticalAmount < 0) 1 else -1).coerceIn(0, tabs.size - 1)
            if (next != index) selectTab(tabs[next].id)
            return true
        }
        scroll = (scroll - verticalAmount.toFloat() * SCROLL_STEP).coerceIn(0f, maxScroll)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isPaste) {
            val clipboard = runCatching { McCompat.mc.keyboardHandler.clipboard }.getOrNull().orEmpty()
            if (searchFocused) { searchQuery += clipboard.lineSequence().firstOrNull().orEmpty(); scroll = 0f; return true }
            focused?.let { if (it.paste(clipboard)) return true }
        }
        if (searchFocused) {
            when (event.key()) {
                KEY_ESCAPE -> searchFocused = false
                KEY_BACKSPACE -> if (searchQuery.isNotEmpty()) { searchQuery = searchQuery.dropLast(1); scroll = 0f }
            }
            return true
        }
        focused?.let { if (it.keyPressed(event.key())) return true }
        if (event.key() == KEY_ESCAPE) { onClose(); return true }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val character = event.codepoint().toChar()
        if (searchFocused) { searchQuery += character; scroll = 0f; contentAnim.set(0.4f); return true }
        focused?.let { if (it.charTyped(character)) return true }
        return super.charTyped(event)
    }

    private fun dropFocus() {
        focused?.loseFocus()
        focused = null
    }

    override fun onClose() {
        dropFocus()
        ConfigManager.features.theme.chromaTab = tabId
        ConfigManager.save(ConfigFileType.FEATURES, "config screen closed")
        super.onClose()
    }

    private fun isDev(): Boolean = McCompat.mc.user.name.equals(DEV_NAME, ignoreCase = true)

    companion object {
        const val WINDOW_WIDTH = 760f
        const val WINDOW_HEIGHT = 420f
        private const val WINDOW_RADIUS = 9f
        private const val EDGE = 2.5f
        private const val ICON_COLUMN = 46f
        private const val ICON_CELL = 32f
        private const val ICON_STEP = 36f
        private const val ICON_SCALE = 1.25f
        private const val HEADER_HEIGHT = 32f
        private const val BUTTON_HEIGHT = 16f
        private const val SEARCH_WIDTH = 150f
        private const val CONTENT_PADDING = 10f
        private const val COLUMN_GAP = 8f
        private const val BOX_HEADER = 20f
        private const val BOX_PADDING = 5f
        private const val BOX_RADIUS = 6f
        private const val BAND_HEIGHT = 24f
        private const val STAR_COLUMN = 12f
        private const val SCROLL_STEP = 18f
        private const val KEY_ESCAPE = 256
        private const val KEY_BACKSPACE = 259
        private const val DEV_NAME = "pawliciously"
    }
}
