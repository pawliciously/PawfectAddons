package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.settings.SettingCategory
import dev.pawfect.addons.config.settings.SettingSection
import dev.pawfect.addons.config.settings.SettingGroup
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.icon
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SessionTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class PawfectConfigScreen(
    private val sections: List<SettingSection>,
    initialTab: String? = null,
) : Screen(Component.literal("PawfectAddons")) {

    private val categories: List<SettingCategory> = sections.flatMap { it.categories }

    private class Row(
        val category: SettingCategory,
        val y: Float,
    )

    private class Band(
        val label: String,
        val y: Float,
    )

    private class GroupBox(
        val group: SettingGroup,
        val widgets: List<Widget>,
        val ownerTitle: String,
        val key: String,
    ) {
        var x = 0f
        var y = 0f
        var width = 0f

        val collapsed: Boolean
            get() = ConfigManager.features.theme.collapsedGroups.contains(key)

        fun toggleCollapsed() {
            val list = ConfigManager.features.theme.collapsedGroups
            if (!list.remove(key)) list.add(key)
        }

        val contentHeight: Float
            get() = if (collapsed) HEADER + PADDING
            else widgets.sumOf { it.height.toDouble() }.toFloat() + PADDING * 2f + HEADER

        companion object {
            const val PADDING = 6f
            const val HEADER = 20f
        }
    }

    private val boxesByCategory = HashMap<String, List<GroupBox>>()

    private var bands: List<Band> = emptyList()

    private var selectedId: String = initialTab
        ?.takeIf { requested -> categories.any { it.id == requested } }
        ?: ConfigManager.features.theme.lastCategory
            .takeIf { saved -> categories.any { it.id == saved } }
        ?: categories.firstOrNull()?.id.orEmpty()

    private val selectedCategory: SettingCategory?
        get() = categories.firstOrNull { it.id == selectedId }

    private var draggingWindow = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var focused: Widget? = null
    private var dragged: Widget? = null

    private var scroll = 0f
    private var sidebarScroll = 0f
    private val sidebarScrollAnim = Anim(140L)
    private var maxScroll = 0f
    private val scrollAnim = Anim(140L)
    private val indicatorAnim = Anim(180L)
    private var indicatorReady = false

    private var searchQuery = ""
    private var searchFocused = false

    private var windowX = 0f
    private var windowY = 0f

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        graphics.fill(0, 0, width, height, 0xB0000000.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        UiScale.minWidth = WINDOW_WIDTH + 24f
        UiScale.minHeight = WINDOW_HEIGHT + 24f
        UiScale.push(graphics)
        val mx = UiScale.mouseX(mouseX.toDouble())
        val my = UiScale.mouseY(mouseY.toDouble())

        updateWindowPosition(mx, my)

        dragged?.mouseDragged(mx, my)

        if (ConfigManager.features.theme.showPlayerIsland) drawPlayerIsland(graphics)
        drawWindow(graphics)
        drawSidebar(graphics, mx, my)
        drawContent(graphics, mx, my)
        drawFooter(graphics, mx, my)
        Notifications.renderScaled(graphics)

        UiScale.pop(graphics)
    }

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
        clampWindow()
    }

    private fun clampWindow() {
        val margin = 6f
        val headroom = if (ConfigManager.features.theme.showPlayerIsland) ISLAND_HEIGHT + ISLAND_GAP else 0f
        val maxX = (UiScale.width - WINDOW_WIDTH - margin).coerceAtLeast(margin)
        val minY = margin + headroom
        val maxY = (UiScale.height - WINDOW_HEIGHT - margin).coerceAtLeast(minY)
        windowX = windowX.coerceIn(margin, maxX)
        windowY = windowY.coerceIn(minY, maxY)
    }

    private fun drawPlayerIsland(graphics: GuiGraphicsExtractor) {
        val player = McCompat.player ?: return
        val name = player.gameProfile.name
        val uptime = "${Icons.CLOCK} ${SessionTracker.formatted()}"

        val head = ISLAND_HEIGHT - ISLAND_PADDING * 2f
        val textWidth = maxOf(Draw.width(name), Draw.width(uptime))
        val islandWidth = ISLAND_PADDING * 3f + head + textWidth
        val islandX = windowX + (WINDOW_WIDTH - islandWidth) / 2f
        val islandY = windowY - ISLAND_HEIGHT - ISLAND_GAP

        graphics.dropShadow(
            islandX,
            islandY,
            islandWidth,
            ISLAND_HEIGHT,
            ISLAND_RADIUS,
            14f,
            Theme.withAlpha(0x000000, 160),
            4f,
        )
        graphics.roundPanel(
            islandX,
            islandY,
            islandWidth,
            ISLAND_HEIGHT,
            ISLAND_RADIUS,
            Theme.surface(Theme.background),
            Theme.opaque(Theme.border),
        )

        val headX = islandX + ISLAND_PADDING
        val headY = islandY + ISLAND_PADDING
        graphics.roundRect(headX, headY, head, head, 5f, Theme.surface(Theme.header, 255))
        drawPlayerHead(graphics, headX, headY, head)

        val textX = headX + head + ISLAND_PADDING
        graphics.string(name, textX, islandY + ISLAND_PADDING + 1f, Theme.opaque(Theme.text), bold = true)
        graphics.string(uptime, textX, islandY + ISLAND_PADDING + 12f, Theme.opaque(Theme.textDim))
    }

    private fun drawPlayerHead(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float) {
        val player = McCompat.player ?: return
        val skin = player.skin.body().texturePath()
        val scale = size / 8f

        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
        graphics.pose().scale(scale, scale)
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, 0, 0, 8f, 8f, 8, 8, 64, 64)
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, 0, 0, 40f, 8f, 8, 8, 64, 64)
        graphics.pose().popMatrix()
    }

    private fun drawWindow(graphics: GuiGraphicsExtractor) {
        graphics.dropShadow(
            windowX,
            windowY,
            WINDOW_WIDTH,
            WINDOW_HEIGHT,
            WINDOW_RADIUS,
            22f,
            Theme.withAlpha(0x000000, 170),
            6f,
        )
        graphics.roundPanel(
            windowX,
            windowY,
            WINDOW_WIDTH,
            WINDOW_HEIGHT,
            WINDOW_RADIUS,
            Theme.surface(Theme.background),
            Theme.opaque(Theme.border),
        )

        graphics.roundRect(
            windowX + 14f,
            windowY + TITLE_HEIGHT,
            WINDOW_WIDTH - 28f,
            1f,
            0.5f,
            Theme.withAlpha(Theme.border, 190),
        )
        graphics.pill(windowX + 14f, windowY + TITLE_HEIGHT - 0.5f, 22f, 2f, Theme.opaque(Theme.accent))

        val titleY = windowY + (TITLE_HEIGHT - Draw.LINE_HEIGHT) / 2f
        Brand.logo(graphics, windowX + 14f, windowY + (TITLE_HEIGHT - LOGO_SIZE) / 2f, LOGO_SIZE)
        val titleX = windowX + 14f + LOGO_SIZE + 7f
        graphics.string("PawfectAddons", titleX, titleY, Theme.opaque(Theme.text))
        selectedCategory?.updated?.takeIf { it.isNotEmpty() }?.let { version ->
            graphics.string(
                "Last updated - $version",
                titleX + Draw.width("PawfectAddons") + 8f,
                titleY,
                Theme.withAlpha(Theme.textDim, 190),
            )
        }

        val fieldX = searchX()
        val fieldY = searchY()
        val fieldWidth = SEARCH_WIDTH
        val fieldHeight = SEARCH_HEIGHT

        if (searchFocused) {
            graphics.dropShadow(
                fieldX,
                fieldY,
                fieldWidth,
                fieldHeight,
                fieldHeight / 2f,
                6f,
                Theme.withAlpha(Theme.accent, 90),
            )
        }
        graphics.pill(
            fieldX,
            fieldY,
            fieldWidth,
            fieldHeight,
            Theme.surface(Theme.header, 235),
            1f,
            if (searchFocused) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )

        val shown = if (searchQuery.isEmpty() && !searchFocused) "Search..." else searchQuery
        val color = if (searchQuery.isEmpty() && !searchFocused) Theme.opaque(Theme.textDim) else Theme.opaque(Theme.text)
        val textY = fieldY + (fieldHeight - Draw.LINE_HEIGHT) / 2f
        graphics.string(Draw.truncate(shown, fieldWidth - 30f), fieldX + 10f, textY, color)
        graphics.stringRight(
            Icons.SEARCH,
            fieldX + fieldWidth - 8f,
            textY,
            Theme.opaque(if (searchFocused) Theme.accent else Theme.textDim),
        )
    }

    private fun searchX(): Float = windowX + WINDOW_WIDTH - 12f - SEARCH_WIDTH

    private fun searchY(): Float = windowY + (TITLE_HEIGHT - SEARCH_HEIGHT) / 2f

    private fun tabX(): Float = windowX + 10f

    private fun tabWidth(): Float = SIDEBAR_WIDTH - 18f

    private fun sidebarTop(): Float = windowY + TITLE_HEIGHT + 8f

    private fun sidebarViewHeight(): Float = WINDOW_HEIGHT - TITLE_HEIGHT - FOOTER_HEIGHT - 16f

    private fun sidebarContentHeight(): Float =
        categories.size * (TAB_HEIGHT + TAB_GAP) + categories.count { it.dividerAbove } * DIVIDER_SPAN

    private fun sidebarMaxScroll(): Float =
        (sidebarContentHeight() - sidebarViewHeight()).coerceAtLeast(0f)

    private fun sidebarRows(): List<Row> {
        val offset = sidebarScrollAnim.value
        var y = sidebarTop() - offset
        val rows = ArrayList<Row>(categories.size)
        categories.forEachIndexed { index, category ->
            if (category.dividerAbove && index > 0) y += DIVIDER_SPAN
            rows.add(Row(category, y))
            y += TAB_HEIGHT + TAB_GAP
        }
        return rows
    }

    private fun drawSidebar(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val sidebarY = windowY + TITLE_HEIGHT + 1f
        val sidebarHeight = WINDOW_HEIGHT - TITLE_HEIGHT - FOOTER_HEIGHT - 2f

        graphics.roundRect(
            windowX + SIDEBAR_WIDTH,
            sidebarY + 6f,
            1f,
            sidebarHeight - 12f,
            0.5f,
            Theme.withAlpha(Theme.border, 170),
        )

        sidebarScroll = sidebarScroll.coerceIn(0f, sidebarMaxScroll())
        sidebarScrollAnim.update(sidebarScroll)

        val entryX = tabX()
        val entryWidth = tabWidth()

        Shapes.pushScissor(graphics, windowX + 1f, sidebarTop() - 4f, SIDEBAR_WIDTH - 1f, sidebarViewHeight() + 8f)

        val rows = sidebarRows()
        val activeRow = rows.firstOrNull { it.category.id == selectedId }
        if (activeRow != null) {
            val target = activeRow.y - windowY
            if (!indicatorReady || draggingWindow) {
                indicatorAnim.set(target)
                indicatorReady = true
            }
            val indicator = windowY + indicatorAnim.update(target)
            graphics.roundRect(
                entryX,
                indicator,
                entryWidth,
                TAB_HEIGHT,
                TAB_RADIUS,
                Theme.withAlpha(Theme.accent, 34),
            )
            graphics.pill(entryX + 3f, indicator + 5f, 2.5f, TAB_HEIGHT - 10f, Theme.opaque(Theme.accent))
        }

        rows.forEachIndexed { index, row ->
            val category = row.category
            if (category.dividerAbove && index > 0) {
                graphics.roundRect(
                    entryX + 6f,
                    row.y - DIVIDER_SPAN / 2f - TAB_GAP / 2f,
                    entryWidth - 12f,
                    1f,
                    0.5f,
                    Theme.withAlpha(Theme.border, 170),
                )
            }
            val active = category.id == selectedId
            val hovered = Draw.inside(mouseX, mouseY, entryX, row.y, entryWidth, TAB_HEIGHT)

            if (hovered && !active) {
                graphics.roundRect(entryX, row.y, entryWidth, TAB_HEIGHT, TAB_RADIUS, Theme.withAlpha(Theme.text, 16))
            }

            val color = when {
                active -> Theme.opaque(Theme.accent)
                hovered -> Theme.opaque(Theme.text)
                else -> Theme.opaque(Theme.textDim)
            }
            val baseline = row.y + (TAB_HEIGHT - Draw.LINE_HEIGHT) / 2f
            val iconLeft = entryX + CARET_COL
            graphics.string(category.icon, iconLeft + (ICON_COL - Draw.width(category.icon)) / 2f, baseline, color)
            graphics.string(category.title, iconLeft + ICON_COL + 4f, baseline, color)
        }

        Shapes.popScissor(graphics)

        val maxScroll = sidebarMaxScroll()
        if (maxScroll > 0f) {
            val trackH = sidebarViewHeight()
            val thumbH = (trackH * trackH / sidebarContentHeight()).coerceAtLeast(20f)
            val thumbY = sidebarTop() + (sidebarScrollAnim.value / maxScroll) * (trackH - thumbH)
            graphics.pill(windowX + SIDEBAR_WIDTH - 4f, thumbY, 2.5f, thumbH, Theme.withAlpha(Theme.accent, 150))
        }
    }

    private fun contentBounds(): FloatArray {
        val left = windowX + 1f + SIDEBAR_WIDTH + 1f
        val top = windowY + TITLE_HEIGHT + 1f
        val right = windowX + WINDOW_WIDTH - 1f
        val bottom = windowY + WINDOW_HEIGHT - FOOTER_HEIGHT - 1f
        return floatArrayOf(left, top, right, bottom)
    }

    private fun drawContent(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val bounds = contentBounds()
        val left = bounds[0]
        val top = bounds[1]
        val right = bounds[2]
        val bottom = bounds[3]

        val boxes = layout(left + CONTENT_PADDING, right - CONTENT_PADDING)
        val totalHeight = boxes.maxOfOrNull { it.y + it.contentHeight }?.minus(top - scrollAnim.value) ?: 0f
        maxScroll = (totalHeight + CONTENT_PADDING - (bottom - top)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)
        scrollAnim.update(scroll)

        Shapes.pushScissor(graphics, left, top, right - left, bottom - top)

        bands.forEach { band -> drawBand(graphics, band, left + CONTENT_PADDING, right - CONTENT_PADDING) }
        boxes.forEach { box -> drawGroupBox(graphics, box, mouseX, mouseY) }

        Shapes.popScissor(graphics)

        if (maxScroll > 0f) {
            val trackHeight = bottom - top - 12f
            val trackY = top + 6f
            val thumbHeight = (trackHeight * (trackHeight / (trackHeight + maxScroll))).coerceAtLeast(28f)
            val thumbY = trackY + (scrollAnim.value / maxScroll) * (trackHeight - thumbHeight)
            graphics.pill(right - 4f, trackY, 3f, trackHeight, Theme.withAlpha(Theme.border, 110))
            graphics.pill(right - 4f, thumbY, 3f, thumbHeight, Theme.withAlpha(Theme.accent, 220))
        }
    }

    private fun drawBand(graphics: GuiGraphicsExtractor, band: Band, left: Float, right: Float) {
        val label = band.label.uppercase()
        val textWidth = Draw.width(label)
        val centerY = band.y + (BAND_HEIGHT - COLUMN_GAP) / 2f
        val textX = left + 2f
        val ruleX = textX + textWidth + 9f

        graphics.string(label, textX, centerY - Draw.LINE_HEIGHT / 2f, Theme.withAlpha(Theme.accent, 235), bold = true)
        graphics.roundRect(ruleX, centerY - 0.5f, (right - ruleX).coerceAtLeast(0f), 1f, 0.5f, Theme.withAlpha(Theme.border, 190))
    }

    private fun drawGroupBox(graphics: GuiGraphicsExtractor, box: GroupBox, mouseX: Float, mouseY: Float) {
        val boxY = box.y
        val boxHeight = box.contentHeight

        graphics.dropShadow(
            box.x,
            boxY,
            box.width,
            boxHeight,
            CARD_RADIUS,
            9f,
            Theme.withAlpha(0x000000, 105),
            3f,
        )
        graphics.roundPanel(
            box.x,
            boxY,
            box.width,
            boxHeight,
            CARD_RADIUS,
            Theme.surface(Theme.panel),
            Theme.opaque(Theme.border),
        )

        val headerHovered = Draw.inside(mouseX, mouseY, box.x, boxY, box.width, GroupBox.HEADER)
        val chevron = if (box.collapsed) Icons.CARET_RIGHT else Icons.CARET_DOWN
        val heading = if (searching) "${box.ownerTitle}  /  ${box.group.title}" else box.group.title

        graphics.icon(
            chevron,
            box.x + 11f,
            boxY + (GroupBox.HEADER - Draw.LINE_HEIGHT) / 2f,
            Theme.withAlpha(Theme.accent, if (headerHovered) 255 else 170),
        )
        graphics.string(
            heading,
            box.x + 11f + UiFont.iconWidth(chevron) + 5f,
            boxY + (GroupBox.HEADER - Draw.LINE_HEIGHT) / 2f,
            if (headerHovered) Theme.opaque(Theme.text) else Theme.opaque(Theme.accent),
        )

        if (box.collapsed) return

        graphics.roundRect(
            box.x + 10f,
            boxY + GroupBox.HEADER,
            box.width - 20f,
            1f,
            0.5f,
            Theme.withAlpha(Theme.border, 150),
        )

        var widgetY = boxY + GroupBox.HEADER + GroupBox.PADDING
        box.widgets.forEach { widget ->
            widget.x = box.x + 2f
            widget.y = widgetY
            widget.width = box.width - 4f
            widget.draw(graphics, mouseX, mouseY)
            widgetY += widget.height
        }
    }

    private fun drawFooter(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val footerY = windowY + WINDOW_HEIGHT - FOOTER_HEIGHT
        graphics.roundRect(
            windowX + 14f,
            footerY,
            WINDOW_WIDTH - 28f,
            1f,
            0.5f,
            Theme.withAlpha(Theme.border, 190),
        )

        graphics.string(
            "PawfectAddons  v${PawfectAddons.VERSION}",
            windowX + 12f,
            footerY + (FOOTER_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.textDim),
        )

        graphics.stringRight(
            "Esc to close",
            windowX + WINDOW_WIDTH - 10f,
            footerY + (FOOTER_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.textDim),
        )
    }

    private fun boxesFor(category: SettingCategory): List<GroupBox> = boxesByCategory.getOrPut(category.id) {
        category.groups.map { group ->
            GroupBox(
                group,
                group.settings.mapNotNull { WidgetFactory.create(it) },
                category.title,
                "${category.id}/${group.title}",
            )
        }
    }

    private val searching: Boolean get() = searchQuery.isNotBlank()

    private fun visibleBoxes(): List<GroupBox> {
        val query = searchQuery.trim().lowercase()

        if (query.isEmpty()) {
            val category = selectedCategory ?: return emptyList()
            return boxesFor(category).filter { it.group.visible }
        }

        return categories.flatMap { category ->
            boxesFor(category).filter { box ->
                box.group.visible && (
                    box.ownerTitle.lowercase().contains(query) ||
                        box.group.title.lowercase().contains(query) ||
                        box.widgets.any { it.setting.name.lowercase().contains(query) }
                    )
            }
        }
    }

    private fun layout(left: Float, right: Float): List<GroupBox> {
        val bounds = contentBounds()
        val available = right - left
        val columnWidth = (available - COLUMN_GAP * (COLUMNS - 1)) / COLUMNS
        val columnHeights = FloatArray(COLUMNS)
        val boxes = visibleBoxes()

        val marks = ArrayList<Band>()
        var section: String? = null

        boxes.forEach { box ->
            val wanted = box.group.section
            if (wanted != null && wanted != section) {
                section = wanted
                val flush = columnHeights.max()
                for (i in columnHeights.indices) columnHeights[i] = flush + BAND_HEIGHT
                marks.add(Band(wanted, bounds[1] + CONTENT_PADDING + flush - scrollAnim.value))
            }
            var column = 0
            for (i in 1 until COLUMNS) if (columnHeights[i] < columnHeights[column]) column = i
            box.width = columnWidth
            box.widgets.forEach { it.width = columnWidth - 4f }
            box.x = left + column * (columnWidth + COLUMN_GAP)
            box.y = bounds[1] + CONTENT_PADDING + columnHeights[column] - scrollAnim.value
            columnHeights[column] += box.contentHeight + COLUMN_GAP
        }
        bands = marks
        return boxes
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = UiScale.mouseX(click.x())
        val my = UiScale.mouseY(click.y())

        val entryX = tabX()
        val entryWidth = tabWidth()
        if (Draw.inside(mx, my, windowX + 1f, sidebarTop() - 4f, SIDEBAR_WIDTH - 1f, sidebarViewHeight() + 8f)) {
            sidebarRows().forEach { row ->
                if (Draw.inside(mx, my, entryX, row.y, entryWidth, TAB_HEIGHT)) {
                    val category = row.category
                    if (selectedId != category.id || searching) {
                        selectedId = category.id
                        searchQuery = ""
                        searchFocused = false
                        scroll = 0f
                        scrollAnim.set(0f)
                        dropFocus()
                        UiSound.click()
                    }
                    return true
                }
            }
        }

        searchFocused = Draw.inside(mx, my, searchX(), searchY(), SEARCH_WIDTH, SEARCH_HEIGHT)
        if (searchFocused) return true

        if (Draw.inside(mx, my, windowX, windowY, WINDOW_WIDTH, TITLE_HEIGHT)) {
            draggingWindow = true
            dragOffsetX = mx - windowX
            dragOffsetY = my - windowY
            UiScale.lock()
            return true
        }

        val bounds = contentBounds()
        if (Draw.inside(mx, my, bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1])) {
            val boxes = visibleBoxes()
            for (box in boxes) {
                if (Draw.inside(mx, my, box.x, box.y, box.width, GroupBox.HEADER)) {
                    box.toggleCollapsed()
                    dropFocus()
                    UiSound.click()
                    return true
                }
                if (box.collapsed) continue
                for (widget in box.widgets) {
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

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (verticalAmount == 0.0) return false
        val mx = UiScale.mouseX(mouseX)
        val my = UiScale.mouseY(mouseY)
        if (Draw.inside(mx, my, windowX + 1f, sidebarTop() - 4f, SIDEBAR_WIDTH - 1f, sidebarViewHeight() + 8f)) {
            sidebarScroll = (sidebarScroll - verticalAmount.toFloat() * SCROLL_STEP)
                .coerceIn(0f, sidebarMaxScroll())
            return true
        }
        for (box in visibleBoxes()) {
            for (widget in box.widgets) {
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

        scroll = (scroll - verticalAmount.toFloat() * SCROLL_STEP).coerceIn(0f, maxScroll)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (searchFocused) {
            when (event.key()) {
                KEY_ESCAPE -> searchFocused = false
                KEY_BACKSPACE -> if (searchQuery.isNotEmpty()) searchQuery = searchQuery.dropLast(1)
            }
            return true
        }

        focused?.let { if (it.keyPressed(event.key())) return true }

        if (event.key() == KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val character = event.codepoint().toChar()
        if (searchFocused) {
            searchQuery += character
            return true
        }
        focused?.let { if (it.charTyped(character)) return true }
        return super.charTyped(event)
    }

    private fun dropFocus() {
        focused?.loseFocus()
        focused = null
    }

    override fun onClose() {
        dropFocus()
        ConfigManager.features.theme.lastCategory = selectedId
        ConfigManager.save(ConfigFileType.FEATURES, "config screen closed")
        super.onClose()
    }

    companion object {
        const val WINDOW_WIDTH = 780f
        private const val SEARCH_WIDTH = 190f
        private const val SEARCH_HEIGHT = 16f
        const val WINDOW_HEIGHT = 410f
        const val WINDOW_RADIUS = 10f
        const val LOGO_SIZE = 16f
        const val DIVIDER_SPAN = 9f
        const val CARD_RADIUS = 8f
        const val TITLE_HEIGHT = 30f
        const val SIDEBAR_WIDTH = 140f
        const val FOOTER_HEIGHT = 28f
        const val TAB_HEIGHT = 24f
        const val TAB_GAP = 3f
        const val TAB_RADIUS = 6f
        const val SECTION_HEIGHT = 20f
        const val SECTION_GAP = 8f
        const val INDENT = 6f
        const val CARET_COL = 12f
        const val ICON_COL = 14f
        const val ISLAND_HEIGHT = 40f
        const val ISLAND_GAP = 10f
        const val ISLAND_PADDING = 8f
        const val ISLAND_RADIUS = 10f
        const val CONTENT_PADDING = 12f
        const val COLUMN_GAP = 8f
        const val COLUMNS = 3
        const val BAND_HEIGHT = 28f
        const val SCROLL_STEP = 18f
        const val KEY_ESCAPE = 256
        const val KEY_BACKSPACE = 259
    }
}
