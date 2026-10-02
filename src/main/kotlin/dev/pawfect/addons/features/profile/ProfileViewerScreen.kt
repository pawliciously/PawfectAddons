package dev.pawfect.addons.features.profile

import dev.pawfect.addons.features.chat.Emojis
import dev.pawfect.addons.features.cosmetics.Cosmetics
import dev.pawfect.addons.features.visual.tooltip.TooltipStyle
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.icon
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundGradient
import dev.pawfect.addons.ui.Draw.roundOutline
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringCentered
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.UiScale
import dev.pawfect.addons.ui.UiFont
import dev.pawfect.addons.utils.ItemUtil.rarityColor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.math.ceil
import kotlin.math.sin

class ProfileViewerScreen(private val target: String) : Screen(Component.literal("Profile Viewer")) {

    private enum class Tab(val label: String, val icon: () -> ItemStack) {
        OVERVIEW("Overview", { ItemStack(Items.PLAYER_HEAD) }),
        INVENTORY("Inventory", { ItemStack(Items.CHEST) }),
        STORAGE("Storage", { ItemStack(Items.ENDER_CHEST) }),
        ACCESSORIES("Accessories", { ItemStack(Items.NETHER_STAR) }),
        PETS("Pets", { ItemStack(Items.BONE) }),
    }

    private enum class Store(val label: String, val key: String) {
        ENDER_CHEST("Ender Chest", "ender_chest"),
        BACKPACKS("Backpacks", ""),
        VAULT("Personal Vault", "vault"),
        POTIONS("Potion Bag", "potions"),
        FISHING("Fishing Bag", "fishing_bag"),
        QUIVER("Quiver", "quiver"),
    }

    private class Clickable(val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)

    private var data: ProfileData? = null
    private var error: String? = null
    private var request: CompletableFuture<ProfileData>? = null
    private var profileIndex = 0
    private val loads = HashMap<String, CompletableFuture<LoadedProfile>>()

    private var tab = Tab.OVERVIEW
    private var store = Store.ENDER_CHEST
    private var page = 0
    private var pageCount = 1
    private var backpack = 0
    private var dropdownOpen = false

    private var about: List<String> = emptyList()
    private var editor: AboutEditor? = null
    private var saving: CompletableFuture<List<String>>? = null
    private var aboutStatus: String? = null
    private var aboutSpace = 0f

    private val clickables = ArrayList<Clickable>()
    private var mx = 0f
    private var my = 0f
    private var itemTooltip: ItemStack = ItemStack.EMPTY
    private var itemTooltipValue: Double? = null
    private var textTooltip: List<Component>? = null
    private var wx = 0f
    private var wy = 0f

    private val tabIcons = Tab.entries.associateWith { it.icon() }
    private val head: ItemStack = ItemStack(Items.PLAYER_HEAD).also {
        it.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(target))
    }

    init {
        fetch()
    }

    private fun fetch() {
        error = null
        data = null
        loads.clear()
        about = emptyList()
        editor = null
        saving = null
        aboutStatus = null
        val pending = ProfileApi.fetch(target)
        request = pending
        pending.whenComplete { result, failure ->
            if (request !== pending) return@whenComplete
            if (failure != null) {
                val cause = (failure as? CompletionException)?.cause ?: failure
                error = cause.message ?: "Could not load that profile."
            } else {
                data = result
                about = result.description
                profileIndex = result.profiles.indexOfFirst { it.selected }.coerceAtLeast(0)
            }
        }
    }

    private val profile: ProfileData.Profile?
        get() = data?.profiles?.getOrNull(profileIndex)

    private val loaded: LoadedProfile?
        get() {
            val p = profile ?: return null
            return loads.getOrPut(p.id) { LoadedProfile.load(p) }.getNow(null)
        }

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        graphics.fill(0, 0, width, height, 0xB4000000.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        aboutSpace = if (error == null && profile != null && (about.isNotEmpty() || isSelf)) aboutHeight() + 12f else 0f
        val h = H + aboutSpace
        UiScale.minWidth = W + 24f
        UiScale.minHeight = h + 24f
        UiScale.push(graphics)
        mx = UiScale.mouseX(mouseX.toDouble())
        my = UiScale.mouseY(mouseY.toDouble())
        wx = ((UiScale.width - W) / 2f).toInt().toFloat()
        wy = ((UiScale.height - h) / 2f).toInt().toFloat()
        clickables.clear()
        itemTooltip = ItemStack.EMPTY
        itemTooltipValue = null
        textTooltip = null

        graphics.dropShadow(wx, wy, W, h, 14f, 22f, Theme.withAlpha(0x000000, 180), 6f)
        graphics.roundPanel(wx, wy, W, h, 14f, Theme.surface(Theme.background), Theme.opaque(Theme.border))
        graphics.roundGradient(wx + 1f, wy + 1f, W - 2f, 96f, 13f, Theme.withAlpha(Theme.accent, 34), Theme.withAlpha(Theme.accent, 0))

        val p = profile
        val failure = error ?: loads[p?.id]?.takeIf { it.isCompletedExceptionally }?.let { "Could not read this profile's items." }
        when {
            failure != null -> drawError(graphics, failure)
            data == null || p == null && data?.profiles?.isNotEmpty() == true -> drawLoading(graphics)
            p == null -> drawError(graphics, "$target has never played SkyBlock.")
            else -> {
                drawHeader(graphics, p)
                drawAbout(graphics)
                drawTabs(graphics)
                val l = loaded
                if (l == null) drawSkeleton(graphics) else when (tab) {
                    Tab.OVERVIEW -> drawOverview(graphics, p, l)
                    Tab.INVENTORY -> drawInventory(graphics, l)
                    Tab.STORAGE -> drawStorage(graphics, l)
                    Tab.ACCESSORIES -> drawGridTab(graphics, l, l.container("accessories"), "Accessory Bag", "accessory", "accessories", l.networth?.categories?.find { it.label == "Accessories" }?.value)
                    Tab.PETS -> drawPets(graphics, l)
                }
                if (dropdownOpen) drawDropdown(graphics)
                drawSuggestions(graphics)
            }
        }
        UiScale.pop(graphics)

        if (!itemTooltip.isEmpty) {
            TooltipStyle.setItem(itemTooltip)
            val lines = ArrayList(Screen.getTooltipFromItem(McCompat.mc, itemTooltip))
            itemTooltipValue?.let {
                lines += Component.empty()
                lines += LegacyText.parse("§6Value: §e${coins(it)} coins")
            }
            graphics.setTooltipForNextFrame(McCompat.font, lines, itemTooltip.tooltipImage, mouseX, mouseY)
        } else {
            textTooltip?.let {
                TooltipStyle.setItem(ItemStack.EMPTY)
                graphics.setComponentTooltipForNextFrame(McCompat.font, it, mouseX, mouseY)
            }
        }
    }

    private val contentX get() = wx + PAD
    private val contentY get() = wy + PAD + HEADER + 12f + aboutSpace + TAB_HEIGHT + 12f
    private val contentW get() = W - PAD * 2f
    private val contentH get() = wy + H + aboutSpace - PAD - contentY

    private fun hover(x: Float, y: Float, w: Float, h: Float) = Draw.inside(mx, my, x, y, w, h)

    private fun click(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
        clickables += Clickable(x, y, w, h, action)
    }

    private fun drawHeader(graphics: GuiGraphicsExtractor, p: ProfileData.Profile) {
        val x = wx + PAD
        val y = wy + PAD

        graphics.roundGradient(x, y, HEADER, HEADER, 12f, Theme.surface(Theme.mix(Theme.panel, Theme.accent, 0.18f)), Theme.surface(Theme.panel))
        val cosmetic = data?.let { Cosmetics.displayFor(it.uuid) }
        val nameColor = cosmetic?.name?.let { 0xFF000000.toInt() or it.colorAt(0f, 0f) }
        graphics.roundOutline(x, y, HEADER, HEADER, 12f, Theme.withAlpha(nameColor ?: Theme.accent, 110))
        drawItem(graphics, head, x + 4f, y + 4f, 3f)

        val nameX = x + HEADER + 14f
        var badgeX = nameX
        cosmetic?.badges?.forEach { badge ->
            val w = McCompat.font.width(badge.component) * NAME_SCALE
            scaledComponent(graphics, badge.component, badgeX, y + 1f, NAME_SCALE)
            if (hover(badgeX, y, w, 14f)) {
                textTooltip = listOf(
                    Component.literal(badge.label).withColor(badge.color),
                    LegacyText.parse("§7PawfectAddons badge"),
                )
            }
            badgeX += w + 3f
        }
        if (badgeX > nameX) badgeX += 2f
        val name = data?.ign?.takeIf { it.isNotEmpty() } ?: target
        val painted = cosmetic?.name
        val nameWidth = if (painted != null) {
            val component = Cosmetics.paint(name, UiFont.style(bold = true), painted)
            scaledComponent(graphics, component, badgeX, y + 1f, NAME_SCALE)
            McCompat.font.width(component) * NAME_SCALE
        } else {
            scaledText(graphics, name, badgeX, y + 1f, NAME_SCALE, Theme.opaque(Theme.text), bold = true)
            McCompat.font.width(UiFont.component(name, true)) * NAME_SCALE
        }
        cosmetic?.emoji?.let { scaledComponent(graphics, it, badgeX + nameWidth + 4f, y, NAME_SCALE) }

        var chipX = nameX
        val chipY = y + 22f
        val switcher = p.name
        val switchW = Draw.width(switcher) + 26f
        val switchHover = hover(chipX, chipY, switchW, 16f) || dropdownOpen
        graphics.pill(chipX, chipY, switchW, 16f, Theme.surface(if (switchHover) Theme.border else Theme.header), 1f, Theme.withAlpha(Theme.accent, if (switchHover) 200 else 90))
        graphics.string(switcher, chipX + 9f, chipY + 4f, Theme.opaque(Theme.text), bold = true)
        graphics.icon(Icons.CARET_DOWN, chipX + switchW - 15f, chipY + 4f, Theme.opaque(Theme.textDim))
        if ((data?.profiles?.size ?: 0) > 1) click(chipX, chipY, switchW, 16f) { dropdownOpen = !dropdownOpen }
        chipX += switchW + 6f

        val chipLimit = wx + W - PAD - STAT_CARDS_WIDTH - 10f
        val facts = listOfNotNull(
            modeLabel(p.mode),
            if (p.coopSize > 1) "Co-op of ${p.coopSize}" to null else null,
            "${p.fairySouls} Fairy Souls" to null,
        )
        for ((label, color) in facts) {
            if (chipX + Draw.width(label) + 16f > chipLimit) break
            chipX += chip(graphics, label, chipX, chipY, color) + 6f
        }

        val levelY = y + 42f
        val badge = p.sbLevel.toString()
        val badgeW = Draw.width(badge) + 14f
        val levelColor = sbLevelColor(p.sbLevel)
        graphics.pill(nameX, levelY, badgeW, 14f, Theme.withAlpha(levelColor, 60), 1f, Theme.withAlpha(levelColor, 200))
        graphics.stringCentered(badge, nameX + badgeW / 2f, levelY + 3f, Theme.opaque(levelColor))
        val barX = nameX + badgeW + 8f
        bar(graphics, barX, levelY + 5f, LEVEL_BAR, p.sbProgress, levelColor, 4f)
        val xpInto = (p.sbProgress * 100f).toInt()
        graphics.string("$xpInto / 100 XP", barX + LEVEL_BAR + 8f, levelY + 3f, Theme.opaque(Theme.textDim))
        if (hover(nameX, levelY - 2f, badgeW + LEVEL_BAR + 72f, 18f)) {
            textTooltip = listOf(
                LegacyText.parse("§bSkyBlock Level ${p.sbLevel}"),
                LegacyText.parse("§7$xpInto / 100 XP to level ${p.sbLevel + 1}"),
            )
        }

        val cardW = 116f
        var cardX = wx + W - PAD - cardW
        val bank = p.bank
        statCard(graphics, cardX, y, cardW, "Bank", if (bank != null) coins(bank) else "Hidden", if (bank == null) "Banking API is off" else "coins", null)
        cardX -= cardW + 8f
        statCard(graphics, cardX, y, cardW, "Purse", coins(p.purse), "coins", null)
        cardX -= cardW + 8f
        val networth = loaded?.networth
        val failed = loaded?.networthFailed == true
        val networthText = when {
            networth != null -> coins(networth.total)
            failed -> "Unavailable"
            else -> "..."
        }
        val sub = when {
            networth != null -> "hover for breakdown"
            failed -> "prices didn't load"
            else -> "calculating"
        }
        statCard(graphics, cardX, y, cardW, "Networth", networthText, sub, Theme.accent)
        if (networth != null && hover(cardX, y, cardW, HEADER)) {
            textTooltip = buildList {
                add(LegacyText.parse("§6Networth §e${coins(networth.total)}"))
                add(Component.empty())
                networth.categories.forEach { add(LegacyText.parse("§7${it.label}: §e${coins(it.value)}")) }
                if (profile?.sacks?.isEmpty() == true) {
                    add(Component.empty())
                    add(LegacyText.parse("§8Sacks and essence aren't counted yet."))
                }
            }
        }
    }

    private val isSelf: Boolean
        get() {
            val viewed = data?.uuid?.replace("-", "") ?: return false
            val own = McCompat.mc.user?.profileId?.toString()?.replace("-", "") ?: return false
            return viewed.equals(own, ignoreCase = true)
        }

    private fun aboutHeight(): Float =
        if (editor != null) 14f + AboutText.LINES * ABOUT_LINE + 14f else 14f + about.size.coerceIn(1, AboutText.LINES) * ABOUT_LINE

    private val aboutY get() = wy + PAD + HEADER + 12f

    private fun drawAbout(graphics: GuiGraphicsExtractor) {
        if (aboutSpace <= 0f) return
        val x = wx + PAD
        val y = aboutY
        val w = contentW
        val h = aboutHeight()
        val edit = editor
        val accent = data?.let { Cosmetics.displayFor(it.uuid) }?.name?.colorAt(0f, 0f) ?: Theme.accent
        graphics.roundPanel(x, y, w, h, 10f, Theme.surface(Theme.panel), if (edit != null) Theme.withAlpha(Theme.accent, 200) else Theme.opaque(Theme.border))
        graphics.roundRect(x + 12f, y + 8f, 2f, (if (edit != null) AboutText.LINES * ABOUT_LINE + 2f else h - 16f), 1f, Theme.withAlpha(accent, 210))
        val textX = x + 24f
        val buttonX = x + w - 12f - ABOUT_BUTTON

        if (edit == null) {
            if (about.isEmpty()) {
                graphics.string("Add a few lines about yourself. Other PawfectAddons players see them on your profile.", textX, y + 8f, Theme.withAlpha(Theme.textDim, 200))
            } else {
                about.forEachIndexed { i, line -> aboutLine(graphics, line, textX, y + 8f + i * ABOUT_LINE, Theme.opaque(Theme.text)) }
            }
            if (isSelf) {
                val buttonY = if (about.size > 1) y + 7f else y + (h - 16f) / 2f
                aboutButton(graphics, if (about.isEmpty()) "Add" else "Edit", buttonX, buttonY, primary = about.isEmpty(), enabled = true) {
                    editor = AboutEditor(about, ABOUT_TEXT_WIDTH)
                    aboutStatus = null
                    dropdownOpen = false
                }
            }
            return
        }

        val fieldX = x + 18f
        val fieldW = buttonX - 12f - fieldX
        graphics.roundRect(fieldX, y + 5f, fieldW, AboutText.LINES * ABOUT_LINE + 6f, 7f, Theme.surface(Theme.background))
        edit.lines.forEachIndexed { i, line ->
            val rowY = y + 8f + i * ABOUT_LINE
            val tooWide = AboutText.width(line) > ABOUT_TEXT_WIDTH
            when {
                tooWide -> graphics.roundRect(fieldX + 2f, rowY - 1.5f, fieldW - 4f, ABOUT_LINE, 4f, Theme.withAlpha(0xFF5C7A, 60))
                i == edit.row -> graphics.roundRect(fieldX + 2f, rowY - 1.5f, fieldW - 4f, ABOUT_LINE, 4f, Theme.withAlpha(Theme.text, 12))
            }
            aboutLine(graphics, line, textX, rowY, Theme.opaque(Theme.text))
            if (saving == null) click(fieldX, rowY - 1.5f, fieldW, ABOUT_LINE) { edit.place(i, mx - textX) }
        }
        if (edit.empty) aboutLine(graphics, "Say hi, flex a drop, anything you like :sparkles:", textX + 2f, y + 8f, Theme.withAlpha(Theme.textDim, 170))
        if (saving == null && (System.currentTimeMillis() / 530) % 2 == 0L) {
            graphics.roundRect(textX + edit.caretX(), y + 7f + edit.row * ABOUT_LINE, 1f, 10f, 0.5f, Theme.opaque(Theme.text))
        }

        val footY = y + 14f + AboutText.LINES * ABOUT_LINE + 1f
        val status = aboutStatus
        if (status != null) {
            graphics.string(Draw.truncate(status, fieldW), fieldX + 2f, footY, 0xFFFF7A90.toInt())
        } else {
            graphics.string("Enter adds a line, Tab picks an emoji, Ctrl + Enter saves", fieldX + 2f, footY, Theme.withAlpha(Theme.textDim, 170))
        }

        val busy = saving != null
        val columnY = y + (h - 38f) / 2f
        aboutButton(graphics, if (busy) "Saving" else "Save", buttonX, columnY, primary = true, enabled = !busy && edit.changed) { saveAbout() }
        aboutButton(graphics, "Cancel", buttonX, columnY + 22f, primary = false, enabled = !busy) {
            editor = null
            aboutStatus = null
        }
    }

    private fun aboutLine(graphics: GuiGraphicsExtractor, line: String, x: Float, y: Float, color: Int) {
        if (line.isEmpty()) return
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y + Theme.textOffset)
        graphics.text(McCompat.font, AboutText.render(line), 0, 0, color, Theme.fontShadow)
        graphics.pose().popMatrix()
    }

    private fun aboutButton(graphics: GuiGraphicsExtractor, label: String, x: Float, y: Float, primary: Boolean, enabled: Boolean, action: () -> Unit) {
        val hovered = enabled && hover(x, y, ABOUT_BUTTON, 16f)
        val fill = when {
            primary && enabled -> Theme.withAlpha(Theme.accent, if (hovered) 255 else 175)
            hovered -> Theme.surface(Theme.border)
            else -> Theme.surface(Theme.header)
        }
        graphics.pill(x, y, ABOUT_BUTTON, 16f, fill, 1f, Theme.withAlpha(if (primary) Theme.accent else Theme.border, if (enabled) 220 else 90))
        val color = if (primary && enabled) 0xFFFFFFFF.toInt() else Theme.withAlpha(Theme.text, if (enabled) 255 else 110)
        graphics.stringCentered(label, x + ABOUT_BUTTON / 2f, y + 4f, color)
        if (enabled) click(x, y, ABOUT_BUTTON, 16f, action)
    }

    private fun drawSuggestions(graphics: GuiGraphicsExtractor) {
        val edit = editor ?: return
        if (saving != null) return
        val options = edit.suggestions()
        if (options.isEmpty()) return
        val rowH = 14f
        val w = options.maxOf { Draw.width(":$it:") } + 34f
        val h = options.size * rowH + 6f
        val x = (wx + PAD + 24f + edit.caretX() - 12f).coerceAtMost(wx + W - PAD - w)
        val y = aboutY + 8f + (edit.row + 1) * ABOUT_LINE + 2f
        graphics.dropShadow(x, y, w, h, 7f, 10f, Theme.withAlpha(0x000000, 150), 3f)
        graphics.roundPanel(x, y, w, h, 7f, Theme.opaque(Theme.header), Theme.opaque(Theme.border))
        options.forEachIndexed { i, code ->
            val ry = y + 3f + i * rowH
            val hovered = hover(x + 3f, ry, w - 6f, rowH)
            when {
                i == edit.pick -> graphics.roundRect(x + 3f, ry, w - 6f, rowH, 5f, Theme.withAlpha(Theme.accent, 70))
                hovered -> graphics.roundRect(x + 3f, ry, w - 6f, rowH, 5f, Theme.withAlpha(Theme.text, 14))
            }
            Emojis.glyph(code)?.let { glyph ->
                graphics.pose().pushMatrix()
                graphics.pose().translate(x + 9f, ry + 3f + Theme.textOffset)
                graphics.text(McCompat.font, glyph, 0, 0, 0xFFFFFFFF.toInt(), false)
                graphics.pose().popMatrix()
            }
            graphics.string(":$code:", x + 24f, ry + 3f, Theme.opaque(if (i == edit.pick) Theme.text else Theme.textDim))
            click(x + 3f, ry, w - 6f, rowH) { edit.accept(i) }
        }
    }

    private fun saveAbout() {
        val edit = editor ?: return
        if (saving != null) return
        val wide = edit.overflowing()
        if (wide >= 0) {
            aboutStatus = "Line ${wide + 1} is too long to fit."
            return
        }
        if (!edit.changed) {
            editor = null
            return
        }
        aboutStatus = null
        val pending = ProfileApi.saveDescription(edit.result)
        saving = pending
        pending.whenComplete { result, failure ->
            if (saving !== pending) return@whenComplete
            saving = null
            if (failure != null) {
                val cause = (failure as? CompletionException)?.cause ?: failure
                aboutStatus = cause.message ?: "Could not save your description."
            } else {
                about = result
                if (editor === edit) editor = null
            }
        }
    }

    private fun statCard(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, label: String, value: String, sub: String, accent: Int?) {
        val hovered = hover(x, y, w, HEADER)
        graphics.roundPanel(x, y, w, HEADER, 10f, Theme.surface(if (hovered) Theme.header else Theme.panel), Theme.opaque(Theme.border))
        if (accent != null) graphics.roundRect(x + 10f, y + 9f, 3f, 9f, 1.5f, Theme.opaque(accent))
        graphics.string(label, x + if (accent != null) 18f else 10f, y + 9f, Theme.opaque(Theme.textDim))
        scaledText(graphics, Draw.truncate(value, (w - 20f) / 1.35f), x + 10f, y + 22f, 1.35f, Theme.opaque(Theme.text), bold = true)
        graphics.string(Draw.truncate(sub, w - 20f), x + 10f, y + 41f, Theme.withAlpha(Theme.textDim, 170))
    }

    private fun chip(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, color: Int?): Float {
        val w = Draw.width(text) + 16f
        val tint = color ?: Theme.textDim
        graphics.pill(x, y, w, 16f, Theme.withAlpha(tint, 36), 1f, Theme.withAlpha(tint, 90))
        graphics.string(text, x + 8f, y + 4f, Theme.opaque(color ?: Theme.text))
        return w
    }

    private fun drawDropdown(graphics: GuiGraphicsExtractor) {
        val profiles = data?.profiles ?: return
        val x = wx + PAD + HEADER + 14f
        val y = wy + PAD + 41f
        val w = 210f
        val rowH = 20f
        val h = profiles.size * rowH + 8f
        graphics.dropShadow(x, y, w, h, 8f, 12f, Theme.withAlpha(0x000000, 150), 3f)
        graphics.roundPanel(x, y, w, h, 8f, Theme.opaque(Theme.header), Theme.opaque(Theme.border))
        click(0f, 0f, UiScale.width, UiScale.height) { dropdownOpen = false }
        profiles.forEachIndexed { index, p ->
            val ry = y + 4f + index * rowH
            val hovered = hover(x + 4f, ry, w - 8f, rowH)
            if (hovered || index == profileIndex) graphics.roundRect(x + 4f, ry, w - 8f, rowH, 6f, Theme.surface(if (hovered) Theme.border else Theme.panel))
            graphics.string(p.name, x + 12f, ry + 6f, Theme.opaque(if (index == profileIndex) Theme.accent else Theme.text), bold = index == profileIndex)
            val detail = listOfNotNull(modeLabel(p.mode)?.first, if (p.selected) "active" else null, "Lvl ${p.sbLevel}").joinToString(" · ")
            graphics.stringRight(detail, x + w - 12f, ry + 6f, Theme.opaque(Theme.textDim))
            click(x + 4f, ry, w - 8f, rowH) {
                profileIndex = index
                dropdownOpen = false
                page = 0
                backpack = 0
            }
        }
    }

    private fun drawTabs(graphics: GuiGraphicsExtractor) {
        var x = wx + PAD
        val y = wy + PAD + HEADER + 12f + aboutSpace
        Tab.entries.forEach { entry ->
            val w = Draw.width(entry.label) + 42f
            val selected = entry == tab
            val hovered = hover(x, y, w, TAB_HEIGHT)
            when {
                selected -> graphics.pill(x, y, w, TAB_HEIGHT, Theme.withAlpha(Theme.accent, 70), 1f, Theme.withAlpha(Theme.accent, 220))
                hovered -> graphics.pill(x, y, w, TAB_HEIGHT, Theme.surface(Theme.header), 1f, Theme.opaque(Theme.border))
            }
            val icon = if (entry == Tab.OVERVIEW) head else tabIcons.getValue(entry)
            drawItem(graphics, icon, x + 8f, y + 4f, 1f)
            graphics.string(entry.label, x + 28f, y + 8f, Theme.opaque(if (selected) Theme.text else Theme.textDim), bold = selected)
            click(x, y, w, TAB_HEIGHT) {
                if (tab != entry) {
                    tab = entry
                    page = 0
                }
            }
            x += w + 6f
        }
    }

    private fun drawOverview(graphics: GuiGraphicsExtractor, p: ProfileData.Profile, l: LoadedProfile) {
        val gap = 10f
        val skillsW = (contentW * 0.45f).toInt().toFloat()
        val sideW = ((contentW - skillsW - gap * 2f) / 2f).toInt().toFloat()
        val top = contentY
        val h = contentH

        val skills = p.skills
        val average = p.skillAverage?.let { "Average %.1f".format(it) }
        card(graphics, contentX, top, skillsW, h, "Skills", average)
        if (skills == null) {
            centered(graphics, "This player has their Skills API turned off.", contentX, top, skillsW, h)
        } else {
            val colW = (skillsW - 24f - 14f) / 2f
            val rowH = (h - 40f) / 6f
            skills.forEachIndexed { i, s ->
                val cx = contentX + 12f + (i / 6) * (colW + 14f)
                val cy = top + 32f + (i % 6) * rowH
                levelRow(graphics, skillIcon(s.id), s.name, s.level, cx, cy, colW) { skillTooltip(s.name, s.level) }
            }
        }

        val slayerX = contentX + skillsW + gap
        card(graphics, slayerX, top, sideW, h, "Slayers", p.slayers.sumOf { it.level.xp }.takeIf { it > 0 }?.let { "${shortNumber(it)} XP" })
        val slayerRow = (h - 40f) / 6f
        p.slayers.forEachIndexed { i, s ->
            levelRow(graphics, slayerIcon(s.id), slayerName(s.id), s.level, slayerX + 12f, top + 32f + i * slayerRow, sideW - 24f) {
                buildList {
                    add(LegacyText.parse("§c${s.name} §7Level ${s.level.level}"))
                    add(LegacyText.parse("§7Slayer XP: §e${"%,d".format(s.level.xp.toLong())}"))
                    progressLine(s.level)?.let(::add)
                    if (s.kills.any { it.second > 0 }) {
                        add(Component.empty())
                        s.kills.filter { it.second > 0 }.forEach { (tier, kills) -> add(LegacyText.parse("§7Tier ${roman(tier)} kills: §f${"%,d".format(kills)}")) }
                    }
                }
            }
        }

        val dungeonX = slayerX + sideW + gap
        val d = p.dungeons
        card(graphics, dungeonX, top, sideW, h, "Dungeons", d.selectedClass?.replaceFirstChar(Char::uppercase))
        val inner = sideW - 24f
        val cataY = top + 32f
        drawItem(graphics, ItemStack(Items.WITHER_SKELETON_SKULL), dungeonX + 12f, cataY + 2f, 1.25f)
        graphics.string("Catacombs", dungeonX + 40f, cataY + 1f, Theme.opaque(Theme.textDim))
        scaledText(graphics, d.catacombs.level.toString(), dungeonX + 40f, cataY + 11f, 1.5f, levelColor(d.catacombs), bold = true)
        bar(graphics, dungeonX + 12f, cataY + 30f, inner, if (d.catacombs.maxed) 1f else d.catacombs.progress, levelColor(d.catacombs), 4f)
        if (hover(dungeonX + 12f, cataY, inner, 36f)) textTooltip = skillTooltip("Catacombs", d.catacombs)

        val classTop = cataY + 44f
        val classRow = 22f
        d.classes.forEachIndexed { i, c ->
            val label = c.id.replaceFirstChar(Char::uppercase)
            val selected = c.id == d.selectedClass
            levelRow(graphics, classIcon(c.id), if (selected) "$label  (selected)" else label, c.level, dungeonX + 12f, classTop + i * classRow, inner, compact = true) {
                skillTooltip("$label class", c.level)
            }
        }

        val footY = classTop + d.classes.size * classRow + 6f
        divider(graphics, dungeonX + 12f, footY, inner)
        val best = listOfNotNull(d.floors.lastOrNull()?.let { "F${it.first}" }, d.masterFloors.lastOrNull()?.let { "M${it.first}" }).joinToString(" / ").ifEmpty { "None" }
        stat(graphics, "Best floor", best, dungeonX + 12f, footY + 7f, inner)
        stat(graphics, "Secrets", "%,d".format(d.secrets), dungeonX + 12f, footY + 20f, inner)
        if (hover(dungeonX + 12f, footY + 4f, inner, 12f) && (d.floors.isNotEmpty() || d.masterFloors.isNotEmpty())) {
            textTooltip = buildList {
                add(LegacyText.parse("§cFloor completions"))
                d.floors.forEach { (floor, runs) -> add(LegacyText.parse("§7${if (floor == 0) "Entrance" else "Floor $floor"}: §f${"%,d".format(runs)}")) }
                d.masterFloors.forEach { (floor, runs) -> add(LegacyText.parse("§7Master $floor: §f${"%,d".format(runs)}")) }
            }
        }
    }

    private fun levelRow(
        graphics: GuiGraphicsExtractor,
        icon: ItemStack,
        label: String,
        level: ProfileData.Level,
        x: Float,
        y: Float,
        w: Float,
        compact: Boolean = false,
        tooltip: () -> List<Component>,
    ) {
        val rowH = if (compact) 20f else 28f
        val hovered = hover(x - 4f, y - 3f, w + 8f, rowH)
        if (hovered) graphics.roundRect(x - 4f, y - 3f, w + 8f, rowH, 6f, Theme.withAlpha(Theme.text, 14))
        drawItem(graphics, icon, x, y + if (compact) 0f else 1f, if (compact) 0.875f else 1f)
        val textX = x + if (compact) 18f else 22f
        val value = if (level.maxed) "${level.level}" else level.level.toString()
        val valueW = Draw.width(value) + 2f
        graphics.string(Draw.truncate(label, w - (textX - x) - valueW - 6f), textX, y + 1f, Theme.opaque(if (hovered) Theme.text else Theme.textDim))
        textRight(graphics, value, x + w, y + 1f, levelColor(level), bold = true)
        bar(graphics, textX, y + if (compact) 12f else 13f, x + w - textX, if (level.maxed) 1f else level.progress, levelColor(level), if (compact) 2.5f else 3.5f)
        if (hovered) textTooltip = tooltip()
    }

    private fun skillTooltip(name: String, level: ProfileData.Level): List<Component> = buildList {
        add(LegacyText.parse("§a$name §7Level ${level.level}${if (level.maxed) " §6(max)" else ""}"))
        add(LegacyText.parse("§7Total XP: §e${"%,d".format(level.xp.toLong())}"))
        progressLine(level)?.let(::add)
    }

    private fun progressLine(level: ProfileData.Level): Component? {
        if (level.maxed) return null
        val into = level.into
        val next = level.next
        return if (into != null && next != null && next > 0) {
            LegacyText.parse("§7Progress: §e${shortNumber(into)}§7 / §e${shortNumber(next)} §7(${(level.progress * 100).toInt()}%)")
        } else {
            LegacyText.parse("§7Progress to ${level.level + 1}: §e${(level.progress * 100).toInt()}%")
        }
    }

    private fun drawInventory(graphics: GuiGraphicsExtractor, l: LoadedProfile) {
        if (!l.profile.inventoryApi) return apiOff(graphics)
        val armor = l.container("armor").reversed()
        val equipment = l.container("equipment")
        val inventory = l.container("inventory")

        val armorLabel = "Armor"
        val equipLabel = "Equipment"
        val armorW = maxOf(Draw.width(armorLabel), SLOT)
        val equipW = maxOf(Draw.width(equipLabel), SLOT)
        val gridW = 9 * SLOT
        val groupGap = 22f
        val innerW = armorW + 12f + equipW + groupGap + gridW
        val innerH = 14f + 4 * SLOT + 8f
        val cardW = innerW + 40f
        val cardH = innerH + 52f
        val cardX = (contentX + (contentW - cardW) / 2f).toInt().toFloat()
        val cardY = (contentY + (contentH - cardH) / 2f).toInt().toFloat()
        val value = l.networth?.categories?.find { it.label == "Inventory" }?.value
        card(graphics, cardX, cardY, cardW, cardH, "Inventory", value?.let { "Worth ${coins(it)}" })

        val top = cardY + 36f
        var x = cardX + 20f
        groupLabel(graphics, armorLabel, x, top, armorW)
        for (i in 0 until 4) slot(graphics, l, armor.getOrNull(i), x + (armorW - SLOT) / 2f, top + 14f + i * SLOT)
        x += armorW + 12f
        groupLabel(graphics, equipLabel, x, top, equipW)
        for (i in 0 until 4) slot(graphics, l, equipment.getOrNull(i), x + (equipW - SLOT) / 2f, top + 14f + i * SLOT)
        x += equipW + groupGap

        groupLabel(graphics, "Inventory", x, top, gridW)
        for (row in 0 until 3) for (col in 0 until 9) {
            slot(graphics, l, inventory.getOrNull(9 + row * 9 + col), x + col * SLOT, top + 14f + row * SLOT)
        }
        val hotbarY = top + 14f + 3 * SLOT + 8f
        for (col in 0 until 9) slot(graphics, l, inventory.getOrNull(col), x + col * SLOT, hotbarY)
    }

    private fun groupLabel(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, w: Float) {
        graphics.stringCentered(text, x + w / 2f, y, Theme.opaque(Theme.textDim))
    }

    private fun drawStorage(graphics: GuiGraphicsExtractor, l: LoadedProfile) {
        if (!l.profile.inventoryApi) return apiOff(graphics)
        val available = Store.entries.filter { s ->
            if (s == Store.BACKPACKS) l.backpacks.isNotEmpty() else l.container(s.key).any { !it.isEmpty }
        }
        if (available.isEmpty()) return centered(graphics, "Nothing in storage.", contentX, contentY, contentW, contentH)
        if (store !in available) store = available.first()

        val listW = 150f
        card(graphics, contentX, contentY, listW, contentH, "Storage", null)
        available.forEachIndexed { i, s ->
            val ry = contentY + 32f + i * 24f
            val selected = s == store
            val hovered = hover(contentX + 8f, ry, listW - 16f, 22f)
            if (selected || hovered) graphics.roundRect(contentX + 8f, ry, listW - 16f, 22f, 6f, if (selected) Theme.withAlpha(Theme.accent, 60) else Theme.surface(Theme.header))
            if (selected) graphics.roundRect(contentX + 8f, ry + 6f, 2.5f, 10f, 1.2f, Theme.opaque(Theme.accent))
            val count = if (s == Store.BACKPACKS) l.backpacks.size.toString() else null
            graphics.string(s.label, contentX + 18f, ry + 7f, Theme.opaque(if (selected) Theme.text else Theme.textDim), bold = selected)
            count?.let { graphics.stringRight(it, contentX + listW - 16f, ry + 7f, Theme.opaque(Theme.textDim)) }
            click(contentX + 8f, ry, listW - 16f, 22f) {
                if (store != s) {
                    store = s
                    page = 0
                }
            }
        }

        val areaX = contentX + listW + 10f
        val areaW = contentW - listW - 10f
        when (store) {
            Store.BACKPACKS -> drawBackpacks(graphics, l, areaX, areaW)
            Store.ENDER_CHEST -> gridCard(graphics, l, l.container("ender_chest"), "Ender Chest", areaX, areaW, perPage = 45, worth = null)
            else -> gridCard(graphics, l, l.container(store.key), store.label, areaX, areaW, perPage = 45, worth = null)
        }
    }

    private fun drawBackpacks(graphics: GuiGraphicsExtractor, l: LoadedProfile, areaX: Float, areaW: Float) {
        backpack = backpack.coerceIn(0, l.backpacks.size - 1)
        val (slotNumber, contents) = l.backpacks[backpack]
        val iconRows = ceil(l.backpacks.size / 9.0).toInt()
        val rows = ceil(contents.size / 9.0).toInt().coerceAtLeast(1)
        val cardW = 9 * SLOT + 40f
        val cardH = 36f + iconRows * SLOT + 22f + rows * SLOT + 18f
        val cardX = (areaX + (areaW - cardW) / 2f).toInt().toFloat()
        val cardY = (contentY + (contentH - cardH) / 2f).toInt().toFloat()
        card(graphics, cardX, cardY, cardW, cardH, "Backpack ${slotNumber + 1}", "${l.backpacks.size} backpacks")

        val left = cardX + 20f
        val iconsTop = cardY + 34f
        l.backpacks.forEachIndexed { i, (slot, _) ->
            val ix = left + (i % 9) * SLOT
            val iy = iconsTop + (i / 9) * SLOT
            val icon = l.backpackIcons[slot] ?: ItemStack(Items.CHEST)
            slot(graphics, l, icon, ix, iy, selected = i == backpack, showValue = false)
            click(ix, iy, SLOT - 2f, SLOT - 2f) { backpack = i }
        }
        val gridTop = iconsTop + iconRows * SLOT + 12f
        divider(graphics, left, gridTop - 6f, 9 * SLOT - 2f)
        contents.forEachIndexed { i, stack -> slot(graphics, l, stack, left + (i % 9) * SLOT, gridTop + 4f + (i / 9) * SLOT) }
    }

    private fun drawGridTab(graphics: GuiGraphicsExtractor, l: LoadedProfile, stacks: List<ItemStack>, title: String, one: String, many: String, worth: Double?) {
        if (!l.profile.inventoryApi) return apiOff(graphics)
        val count = stacks.count { !it.isEmpty }
        if (count == 0) return centered(graphics, "No $many.", contentX, contentY, contentW, contentH)
        gridCard(graphics, l, stacks.filter { !it.isEmpty }, title, contentX, contentW, perPage = 45, worth = worth, subtitle = "$count ${if (count == 1) one else many}")
    }

    private fun drawPets(graphics: GuiGraphicsExtractor, l: LoadedProfile) {
        if (l.pets.isEmpty()) return centered(graphics, "No pets.", contentX, contentY, contentW, contentH)
        val worth = l.networth?.categories?.find { it.label == "Pets" }?.value
        gridCard(graphics, l, l.pets, "Pets", contentX, contentW, perPage = 45, worth = worth, subtitle = "${l.pets.size} pets", pets = true)
    }

    private fun gridCard(
        graphics: GuiGraphicsExtractor,
        l: LoadedProfile,
        stacks: List<ItemStack>,
        title: String,
        areaX: Float,
        areaW: Float,
        perPage: Int,
        worth: Double?,
        subtitle: String? = null,
        pets: Boolean = false,
    ) {
        if (stacks.isEmpty()) return centered(graphics, "$title is empty.", areaX, contentY, areaW, contentH)
        pageCount = ceil(stacks.size / perPage.toDouble()).toInt().coerceAtLeast(1)
        page = page.coerceIn(0, pageCount - 1)
        val rows = minOf(perPage / 9, ceil(stacks.size / 9.0).toInt()).coerceAtLeast(1)
        val pager = pageCount > 1
        val cardW = 9 * SLOT + 40f
        val cardH = 36f + rows * SLOT + (if (pager) 30f else 12f) + 4f
        val cardX = (areaX + (areaW - cardW) / 2f).toInt().toFloat()
        val cardY = (contentY + (contentH - cardH) / 2f).toInt().toFloat()
        val right = listOfNotNull(subtitle, worth?.let { "Worth ${coins(it)}" }).joinToString(" · ").ifEmpty { null }
        card(graphics, cardX, cardY, cardW, cardH, title, right)

        val left = cardX + 20f
        val top = cardY + 36f
        for (i in 0 until perPage) {
            val index = page * perPage + i
            val stack = stacks.getOrNull(index) ?: break
            val petIndex = if (pets) index else -1
            slot(graphics, l, stack, left + (i % 9) * SLOT, top + (i / 9) * SLOT, petIndex = petIndex)
        }
        if (pager) drawPager(graphics, cardX + cardW / 2f, top + rows * SLOT + 8f)
    }

    private fun drawPager(graphics: GuiGraphicsExtractor, centerX: Float, y: Float) {
        val label = "${page + 1} / $pageCount"
        val labelW = Draw.width(label) + 16f
        val prevX = centerX - labelW / 2f - 22f
        val nextX = centerX + labelW / 2f + 4f
        pagerButton(graphics, prevX, y, "<", page > 0) { page-- }
        graphics.stringCentered(label, centerX, y + 5f, Theme.opaque(Theme.textDim))
        pagerButton(graphics, nextX, y, ">", page < pageCount - 1) { page++ }
    }

    private fun pagerButton(graphics: GuiGraphicsExtractor, x: Float, y: Float, label: String, enabled: Boolean, action: () -> Unit) {
        val hovered = enabled && hover(x, y, 18f, 18f)
        graphics.roundPanel(x, y, 18f, 18f, 6f, Theme.surface(if (hovered) Theme.border else Theme.header), Theme.opaque(Theme.border))
        graphics.stringCentered(label, x + 9f, y + 5f, Theme.withAlpha(Theme.text, if (enabled) 255 else 80))
        if (enabled) click(x, y, 18f, 18f, action)
    }

    private fun slot(
        graphics: GuiGraphicsExtractor,
        l: LoadedProfile,
        stack: ItemStack?,
        x: Float,
        y: Float,
        selected: Boolean = false,
        showValue: Boolean = true,
        petIndex: Int = -1,
    ) {
        val size = SLOT - 2f
        val hovered = hover(x, y, size, size)
        val well = Theme.surface(Theme.mix(Theme.background, Theme.panel, 0.35f))
        graphics.roundRect(x, y, size, size, 4f, if (hovered) Theme.surface(Theme.header) else well)
        val rarity = stack?.takeUnless { it.isEmpty }?.rarityColor()
        val edge = when {
            selected || hovered -> Theme.opaque(Theme.accent)
            rarity != null -> Theme.withAlpha(rarity, 150)
            else -> Theme.withAlpha(Theme.border, 160)
        }
        graphics.roundOutline(x, y, size, size, 4f, edge)
        if (stack == null || stack.isEmpty) return

        drawItem(graphics, stack, x + 2f, y + 2f, 1f)
        val badge = when {
            petIndex >= 0 -> l.profile.pets.getOrNull(petIndex)?.level?.toString()
            stack.count > 1 -> stack.count.toString()
            else -> null
        }
        if (badge != null) {
            val color = if (petIndex >= 0) 0xFFFFD27A.toInt() else 0xFFFFFFFF.toInt()
            scaledTextRight(graphics, badge, x + size - 1.5f, y + size - 7.5f, 0.75f, color)
        }
        if (petIndex >= 0 && l.profile.pets.getOrNull(petIndex)?.active == true) {
            graphics.roundOutline(x - 1f, y - 1f, size + 2f, size + 2f, 5f, 0xFF7CFFA4.toInt(), 1.5f)
        }
        if (hovered) {
            itemTooltip = stack
            if (showValue) {
                itemTooltipValue = if (petIndex >= 0) l.networth?.petValues?.get(petIndex) else l.networth?.values?.get(stack)
            }
        }
    }

    private fun drawItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, scale: Float) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
        if (scale != 1f) graphics.pose().scale(scale, scale)
        graphics.item(stack, 0, 0)
        graphics.itemDecorations(McCompat.font, stack, 0, 0, "")
        graphics.pose().popMatrix()
    }

    private fun card(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, title: String, right: String?) {
        graphics.roundPanel(x, y, w, h, 10f, Theme.surface(Theme.panel), Theme.opaque(Theme.border))
        graphics.string(title, x + 12f, y + 11f, Theme.opaque(Theme.text), bold = true)
        right?.let { graphics.stringRight(Draw.truncate(it, w - Draw.width(title) - 36f), x + w - 12f, y + 11f, Theme.opaque(Theme.textDim)) }
        divider(graphics, x + 12f, y + 24f, w - 24f)
    }

    private fun divider(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float) {
        graphics.roundRect(x, y, w, 1f, 0.5f, Theme.withAlpha(Theme.border, 200))
    }

    private fun stat(graphics: GuiGraphicsExtractor, label: String, value: String, x: Float, y: Float, w: Float) {
        graphics.string(label, x, y, Theme.opaque(Theme.textDim))
        textRight(graphics, Draw.truncate(value, w - Draw.width(label) - 8f), x + w, y, Theme.opaque(Theme.text), bold = true)
    }

    private fun bar(graphics: GuiGraphicsExtractor, x: Float, y: Float, w: Float, progress: Float, color: Int, h: Float) {
        graphics.roundRect(x, y, w, h, h / 2f, Theme.withAlpha(Theme.text, 22))
        val fill = w * progress.coerceIn(0f, 1f)
        if (fill >= h) graphics.roundRect(x, y, fill, h, h / 2f, Theme.opaque(color))
        else if (fill > 0.5f) graphics.roundRect(x, y, h, h, h / 2f, Theme.withAlpha(color, (255 * fill / h).toInt()))
    }

    private fun centered(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, w: Float, h: Float) {
        graphics.stringCentered(text, x + w / 2f, y + h / 2f - 4f, Theme.opaque(Theme.textDim))
    }

    private fun apiOff(graphics: GuiGraphicsExtractor) {
        card(graphics, contentX, contentY, contentW, contentH, "Inventory API is off", null)
        centered(graphics, "This player hides their inventories from the API, so there's nothing to show here.", contentX, contentY + 10f, contentW, contentH)
    }

    private fun textRight(graphics: GuiGraphicsExtractor, text: String, right: Float, y: Float, color: Int, bold: Boolean) {
        graphics.string(text, right - Draw.width(text) - if (bold) 1f else 0f, y, color, bold = bold)
    }

    private fun scaledText(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, scale: Float, color: Int, bold: Boolean = false) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
        graphics.pose().scale(scale, scale)
        graphics.string(text, 0f, 0f, color, bold = bold)
        graphics.pose().popMatrix()
    }

    private fun scaledComponent(graphics: GuiGraphicsExtractor, text: Component, x: Float, y: Float, scale: Float) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
        graphics.pose().scale(scale, scale)
        graphics.pose().translate(0f, Theme.textOffset)
        graphics.text(McCompat.font, text, 0, 0, Theme.opaque(Theme.text), Theme.fontShadow)
        graphics.pose().popMatrix()
    }

    private fun scaledTextRight(graphics: GuiGraphicsExtractor, text: String, right: Float, y: Float, scale: Float, color: Int) {
        val w = Draw.width(text) * scale
        graphics.pose().pushMatrix()
        graphics.pose().translate(right - w, y)
        graphics.pose().scale(scale, scale)
        graphics.string(text, 0f, 0f, color, shadow = true)
        graphics.pose().popMatrix()
    }

    private fun drawLoading(graphics: GuiGraphicsExtractor) {
        val x = wx + PAD
        val y = wy + PAD
        graphics.roundRect(x, y, HEADER, HEADER, 12f, Theme.surface(Theme.panel))
        drawItem(graphics, head, x + 4f, y + 4f, 3f)
        scaledText(graphics, target, x + HEADER + 14f, y + 1f, NAME_SCALE, Theme.opaque(Theme.text), bold = true)
        val dots = ".".repeat(((System.currentTimeMillis() / 350) % 4).toInt())
        graphics.string("Loading profile$dots", x + HEADER + 14f, y + 24f, Theme.opaque(Theme.textDim))
        drawSkeleton(graphics)
    }

    private fun drawSkeleton(graphics: GuiGraphicsExtractor) {
        val pulse = (0.5f + 0.5f * sin(System.currentTimeMillis() / 260.0).toFloat())
        val alpha = (24 + 26 * pulse).toInt()
        val w = (contentW - 20f) / 3f
        for (i in 0 until 3) {
            val x = contentX + i * (w + 10f)
            graphics.roundRect(x, contentY, w, contentH, 10f, Theme.withAlpha(Theme.text, alpha / 3))
            for (row in 0 until 6) {
                graphics.roundRect(x + 14f, contentY + 34f + row * 34f, w * 0.55f, 6f, 3f, Theme.withAlpha(Theme.text, alpha))
                graphics.roundRect(x + 14f, contentY + 46f + row * 34f, w - 28f, 4f, 2f, Theme.withAlpha(Theme.text, alpha / 2))
            }
        }
    }

    private fun drawError(graphics: GuiGraphicsExtractor, message: String) {
        val cx = wx + W / 2f
        val cy = wy + (H + aboutSpace) / 2f
        graphics.circle(cx, cy - 34f, 16f, Theme.withAlpha(0xFF5C7A, 50))
        graphics.icon(Icons.CLOSE, cx - 4f, cy - 38f, 0xFFFF7A90.toInt())
        graphics.stringCentered(message, cx, cy - 6f, Theme.opaque(Theme.text))
        val label = "Try again"
        val bw = Draw.width(label) + 28f
        val bx = cx - bw / 2f
        val by = cy + 12f
        val hovered = hover(bx, by, bw, 20f)
        graphics.pill(bx, by, bw, 20f, if (hovered) Theme.opaque(Theme.accent) else Theme.withAlpha(Theme.accent, 170))
        graphics.stringCentered(label, cx, by + 6f, 0xFFFFFFFF.toInt())
        click(bx, by, bw, 20f) { fetch() }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val x = UiScale.mouseX(click.x())
        val y = UiScale.mouseY(click.y())
        for (i in clickables.indices.reversed()) {
            val c = clickables[i]
            if (Draw.inside(x, y, c.x, c.y, c.w, c.h)) {
                c.action()
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val paged = tab == Tab.ACCESSORIES || tab == Tab.PETS || (tab == Tab.STORAGE && store != Store.BACKPACKS)
        if (paged && verticalAmount != 0.0 && pageCount > 1) {
            page = (page + if (verticalAmount < 0) 1 else -1).coerceIn(0, pageCount - 1)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val edit = editor
        if (edit != null) {
            when {
                event.key() == 256 -> if (!edit.dismiss() && saving == null) {
                    editor = null
                    aboutStatus = null
                }
                saving != null -> Unit
                (event.key() == 257 || event.key() == 335) && event.hasControlDown() -> saveAbout()
                else -> {
                    aboutStatus = null
                    edit.key(event)
                }
            }
            return true
        }
        if (dropdownOpen && event.key() == 256) {
            dropdownOpen = false
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val edit = editor ?: return super.charTyped(event)
        if (saving == null) {
            aboutStatus = null
            edit.type(event.codepointAsString())
        }
        return true
    }

    private fun levelColor(level: ProfileData.Level): Int =
        if (level.maxed) 0xFFFFC34D.toInt() else Theme.opaque(Theme.accent)

    private fun modeLabel(mode: String): Pair<String, Int>? = when (mode) {
        "ironman" -> "Ironman" to 0xC8C8C8
        "island" -> "Stranded" to 0x7CD67C
        "bingo" -> "Bingo" to 0xFF9A3D
        else -> null
    }

    private fun sbLevelColor(level: Int): Int = when (level / 40) {
        0 -> 0xAAAAAA
        1 -> 0xFFFFFF
        2 -> 0xFFFF55
        3 -> 0x55FF55
        4 -> 0x00AA00
        5 -> 0x55FFFF
        6 -> 0x00AAAA
        7 -> 0x5555FF
        8 -> 0xFF55FF
        9 -> 0xAA00AA
        10 -> 0xFFAA00
        11 -> 0xFF5555
        else -> 0xAA0000
    }

    private fun skillIcon(id: String): ItemStack = ItemStack(
        when (id) {
            "farming" -> Items.GOLDEN_HOE
            "mining" -> Items.STONE_PICKAXE
            "combat" -> Items.STONE_SWORD
            "foraging" -> Items.JUNGLE_SAPLING
            "fishing" -> Items.FISHING_ROD
            "enchanting" -> Items.ENCHANTING_TABLE
            "alchemy" -> Items.BREWING_STAND
            "taming" -> Items.LEAD
            "carpentry" -> Items.CRAFTING_TABLE
            "hunting" -> Items.TRIPWIRE_HOOK
            "runecrafting" -> Items.MAGMA_CREAM
            "social" -> Items.CAKE
            else -> Items.BOOK
        },
    )

    private fun slayerIcon(id: String): ItemStack = ItemStack(
        when (id) {
            "zombie" -> Items.ROTTEN_FLESH
            "spider" -> Items.COBWEB
            "wolf" -> Items.MUTTON
            "enderman" -> Items.ENDER_PEARL
            "blaze" -> Items.BLAZE_POWDER
            "vampire" -> Items.REDSTONE
            else -> Items.IRON_SWORD
        },
    )

    private fun slayerName(id: String): String = when (id) {
        "zombie" -> "Revenant"
        "spider" -> "Tarantula"
        "wolf" -> "Sven"
        "enderman" -> "Voidgloom"
        "blaze" -> "Inferno"
        "vampire" -> "Riftstalker"
        else -> id
    }

    private fun classIcon(id: String): ItemStack = ItemStack(
        when (id) {
            "healer" -> Items.SPLASH_POTION
            "mage" -> Items.BLAZE_ROD
            "berserk" -> Items.IRON_SWORD
            "archer" -> Items.BOW
            "tank" -> Items.LEATHER_CHESTPLATE
            else -> Items.BOOK
        },
    )

    private fun roman(n: Int): String = listOf("I", "II", "III", "IV", "V", "VI").getOrElse(n - 1) { n.toString() }

    companion object {
        private const val W = 720f
        private const val H = 404f
        private const val PAD = 16f
        private const val HEADER = 56f
        private const val TAB_HEIGHT = 24f
        private const val SLOT = 22f
        private const val LEVEL_BAR = 128f
        private const val STAT_CARDS_WIDTH = 116f * 3 + 8f * 2
        private const val NAME_SCALE = 1.6f
        private const val ABOUT_LINE = 11f
        private const val ABOUT_BUTTON = 64f
        private const val ABOUT_TEXT_WIDTH = 568f

        fun coins(value: Double): String = when {
            value >= 1e12 -> "%.2fT".format(value / 1e12)
            value >= 1e9 -> "%.2fB".format(value / 1e9)
            value >= 1e6 -> "%.1fM".format(value / 1e6)
            value >= 1e3 -> "%.1fK".format(value / 1e3)
            else -> "%,.0f".format(value)
        }

        fun shortNumber(value: Double): String = when {
            value >= 1e9 -> "%.2fB".format(value / 1e9)
            value >= 1e6 -> "%.2fM".format(value / 1e6)
            value >= 1e4 -> "%.1fK".format(value / 1e3)
            else -> "%,.0f".format(value)
        }
    }
}
