package dev.pawfect.addons.features.profile

import dev.pawfect.addons.features.visual.tooltip.TooltipStyle
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.icon
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringCentered
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.UiScale
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.concurrent.CompletionException
import kotlin.math.ceil

/** `/pa pv <name>`: a player's SkyBlock profile, fetched through me.pawfectaddons.net. */
class ProfileViewerScreen(private val target: String) : Screen(Component.literal("Profile Viewer")) {

    private enum class Tab(val label: String, val glyph: String) {
        OVERVIEW("Overview", Icons.USER),
        INVENTORY("Inventory", Icons.PACKAGE),
        ENDER_CHEST("Ender Chest", Icons.PACKAGE),
        BACKPACKS("Backpacks", Icons.PACKAGE),
        ACCESSORIES("Accessories", Icons.SPARKLE),
        WARDROBE("Wardrobe", Icons.SHIELD),
        PETS("Pets", Icons.TAG),
    }

    private var data: ProfileData? = null
    private var error: String? = null
    private var profileIndex = 0
    private var tab = Tab.OVERVIEW
    private var page = 0
    private var backpack = 0

    private val decoded = HashMap<String, List<ItemStack>>()
    private val head: ItemStack = ItemStack(Items.PLAYER_HEAD).also {
        it.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(target))
    }

    private var hovered: ItemStack = ItemStack.EMPTY
    private var windowX = 0f
    private var windowY = 0f

    init {
        ProfileApi.fetch(target).whenComplete { result, failure ->
            if (failure != null) {
                val cause = (failure as? CompletionException)?.cause ?: failure
                error = cause.message ?: "Could not load that profile."
            } else {
                data = result
                profileIndex = 0
            }
        }
    }

    private val profile: ProfileData.Profile?
        get() = data?.profiles?.getOrNull(profileIndex)

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        graphics.fill(0, 0, width, height, 0xB0000000.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        UiScale.minWidth = WIDTH + 24f
        UiScale.minHeight = HEIGHT + 24f
        UiScale.push(graphics)
        val mx = UiScale.mouseX(mouseX.toDouble())
        val my = UiScale.mouseY(mouseY.toDouble())
        windowX = (UiScale.width - WIDTH) / 2f
        windowY = (UiScale.height - HEIGHT) / 2f
        hovered = ItemStack.EMPTY
        pagerY = -1f

        graphics.dropShadow(windowX, windowY, WIDTH, HEIGHT, RADIUS, 18f, Theme.withAlpha(0x000000, 170), 4f)
        graphics.roundPanel(windowX, windowY, WIDTH, HEIGHT, RADIUS, Theme.surface(Theme.background), Theme.opaque(Theme.border))

        drawHeader(graphics, mx, my)
        val current = profile
        when {
            error != null -> message(graphics, error!!)
            data == null -> message(graphics, "Loading $target...")
            current == null -> message(graphics, "$target has no SkyBlock profiles.")
            else -> {
                drawTabs(graphics, mx, my)
                drawContent(graphics, current, mx, my)
            }
        }
        UiScale.pop(graphics)

        if (!hovered.isEmpty) {
            TooltipStyle.setItem(hovered)
            graphics.setTooltipForNextFrame(McCompat.font, hovered, mouseX, mouseY)
        }
    }

    private fun message(graphics: GuiGraphicsExtractor, text: String) {
        graphics.stringCentered(text, windowX + WIDTH / 2f, windowY + HEIGHT / 2f, Theme.opaque(Theme.textDim))
    }

    // Header: head, name, SkyBlock level, profile switcher, coins.

    private fun drawHeader(graphics: GuiGraphicsExtractor, mx: Float, my: Float) {
        val x = windowX + PAD
        val y = windowY + PAD
        graphics.roundRect(x, y, 36f, 36f, 7f, Theme.surface(Theme.header))
        graphics.pose().pushMatrix()
        graphics.pose().translate(x + 2f, y + 2f)
        graphics.pose().scale(2f, 2f)
        graphics.item(head, 0, 0)
        graphics.pose().popMatrix()

        val name = data?.ign?.takeIf { it.isNotEmpty() } ?: target
        graphics.string(name, x + 46f, y + 4f, Theme.opaque(Theme.text), bold = true)
        val current = profile ?: return
        val level = "SkyBlock Level ${current.sbLevel}"
        graphics.string(level, x + 46f, y + 18f, Theme.opaque(Theme.textDim))
        val levelEnd = x + 46f + Draw.width(level) + 8f
        bar(graphics, levelEnd, y + 21f, 60f, current.sbProgress, Theme.accent)

        // Profile switcher: click to cycle through this player's profiles.
        val chip = profileChip(current)
        val chipWidth = Draw.width(chip) + 22f
        val chipX = windowX + WIDTH - PAD - chipWidth
        val hoverChip = Draw.inside(mx, my, chipX, y + 2f, chipWidth, 18f)
        graphics.pill(chipX, y + 2f, chipWidth, 18f, Theme.surface(if (hoverChip) Theme.border else Theme.header))
        graphics.string(chip, chipX + 9f, y + 7f, Theme.opaque(Theme.text))
        graphics.icon(Icons.CARET_DOWN, chipX + chipWidth - 13f, y + 7f, Theme.opaque(Theme.textDim))

        val coins = buildString {
            append("Purse ").append(coins(current.purse))
            append("   Bank ").append(current.bank?.let(::coins) ?: "hidden")
        }
        graphics.stringRight(coins, windowX + WIDTH - PAD, y + 26f, Theme.opaque(Theme.textDim))
    }

    private fun profileChip(p: ProfileData.Profile): String {
        val mode = when (p.mode) {
            "ironman" -> " (Ironman)"
            "island" -> " (Stranded)"
            "bingo" -> " (Bingo)"
            else -> ""
        }
        val count = data?.profiles?.size ?: 1
        return "${p.name}$mode" + if (count > 1) "  ${profileIndex + 1}/$count" else ""
    }

    // Tabs down the left side.

    private fun tabsX() = windowX + PAD
    private fun tabY(index: Int) = windowY + HEADER + index * (TAB_HEIGHT + 3f)

    private fun drawTabs(graphics: GuiGraphicsExtractor, mx: Float, my: Float) {
        Tab.entries.forEachIndexed { index, entry ->
            val x = tabsX()
            val y = tabY(index)
            val selected = entry == tab
            val hover = Draw.inside(mx, my, x, y, SIDEBAR - 8f, TAB_HEIGHT)
            if (selected || hover) {
                graphics.roundRect(x, y, SIDEBAR - 8f, TAB_HEIGHT, 6f, Theme.surface(if (selected) Theme.header else Theme.panel))
            }
            if (selected) graphics.roundRect(x, y + 5f, 2.5f, TAB_HEIGHT - 10f, 1.2f, Theme.opaque(Theme.accent))
            val color = Theme.opaque(if (selected) Theme.text else Theme.textDim)
            graphics.icon(entry.glyph, x + 9f, y + 8f, if (selected) Theme.opaque(Theme.accent) else color)
            graphics.string(entry.label, x + 24f, y + 8f, color)
        }
    }

    private fun contentX() = windowX + PAD + SIDEBAR
    private fun contentY() = windowY + HEADER
    private fun contentWidth() = WIDTH - PAD * 2f - SIDEBAR
    private fun contentHeight() = HEIGHT - HEADER - PAD

    private fun drawContent(graphics: GuiGraphicsExtractor, p: ProfileData.Profile, mx: Float, my: Float) {
        graphics.roundPanel(contentX(), contentY(), contentWidth(), contentHeight(), 8f, Theme.surface(Theme.panel), Theme.opaque(Theme.border))
        when (tab) {
            Tab.OVERVIEW -> drawOverview(graphics, p)
            Tab.INVENTORY -> drawInventory(graphics, p, mx, my)
            Tab.ENDER_CHEST -> drawPaged(graphics, items(p, "ender_chest"), 45, 9, "Ender chest", mx, my)
            Tab.BACKPACKS -> drawBackpacks(graphics, p, mx, my)
            Tab.ACCESSORIES -> drawPaged(graphics, items(p, "accessories"), 45, 9, "Accessory bag", mx, my)
            Tab.WARDROBE -> drawWardrobe(graphics, p, mx, my)
            Tab.PETS -> drawPaged(graphics, pets(p), 45, 9, "Pets", mx, my)
        }
    }

    // Overview: skills, slayers, dungeons.

    private fun drawOverview(graphics: GuiGraphicsExtractor, p: ProfileData.Profile) {
        val column = (contentWidth() - 16f * 4f) / 3f
        val top = contentY() + 12f
        var x = contentX() + 16f

        val skills = p.skills
        val average = p.skillAverage?.let { " · avg %.1f".format(it) } ?: ""
        title(graphics, "Skills$average", x, top)
        if (skills == null) {
            graphics.string("Skills API is off", x, top + 18f, Theme.opaque(Theme.textDim))
        } else {
            skills.forEachIndexed { i, s -> levelRow(graphics, s.name, s.level, x, top + 16f + i * ROW, column) }
        }

        x += column + 16f
        title(graphics, "Slayers", x, top)
        p.slayers.forEachIndexed { i, s -> levelRow(graphics, s.name, s.level, x, top + 16f + i * ROW, column) }

        val dungeonTop = top + 16f + p.slayers.size * ROW + 10f
        title(graphics, "Dungeons", x, dungeonTop)
        levelRow(graphics, "Catacombs", p.dungeons.catacombs, x, dungeonTop + 16f, column)
        val best = p.dungeons.floors.lastOrNull()?.let { "F${it.first}" } ?: "none"
        val bestMaster = p.dungeons.masterFloors.lastOrNull()?.let { "M${it.first}" }
        stat(graphics, "Highest floor", listOfNotNull(best, bestMaster).joinToString(" / "), x, dungeonTop + 16f + ROW, column)
        stat(graphics, "Secrets", "%,d".format(p.dungeons.secrets), x, dungeonTop + 16f + ROW + 13f, column)

        x += column + 16f
        title(graphics, "Classes", x, top)
        p.dungeons.classes.forEachIndexed { i, c ->
            val label = c.id.replaceFirstChar(Char::uppercase) + if (c.id == p.dungeons.selectedClass) " ★" else ""
            levelRow(graphics, label, c.level, x, top + 16f + i * ROW, column)
        }
        val infoTop = top + 16f + p.dungeons.classes.size * ROW + 10f
        title(graphics, "Profile", x, infoTop)
        val rows = listOf(
            "Fairy souls" to p.fairySouls.toString(),
            "Members" to p.coopSize.toString(),
            "Pets" to p.pets.size.toString(),
            "Active pet" to (p.pets.firstOrNull { it.active }?.let { prettyPet(it) } ?: "none"),
        )
        rows.forEachIndexed { i, (k, v) -> stat(graphics, k, v, x, infoTop + 16f + i * 13f, column) }
        if (!p.inventoryApi) {
            graphics.string("Inventory API is off for this profile", x, infoTop + 16f + rows.size * 13f + 6f, Theme.opaque(Theme.textDim))
        }
    }

    private fun title(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float) {
        graphics.string(text, x, y, Theme.opaque(Theme.text), bold = true)
    }

    private fun levelRow(graphics: GuiGraphicsExtractor, label: String, level: ProfileData.Level, x: Float, y: Float, width: Float) {
        graphics.string(label, x, y, Theme.opaque(Theme.textDim))
        val value = if (level.maxed) "${level.level} MAX" else level.level.toString()
        graphics.stringRight(value, x + width, y, Theme.opaque(if (level.maxed) Theme.accent else Theme.text))
        bar(graphics, x, y + 11f, width, if (level.maxed) 1f else level.progress, if (level.maxed) Theme.accent else Theme.mix(Theme.accent, Theme.text, 0.25f))
    }

    private fun stat(graphics: GuiGraphicsExtractor, label: String, value: String, x: Float, y: Float, width: Float) {
        graphics.string(label, x, y, Theme.opaque(Theme.textDim))
        graphics.stringRight(Draw.truncate(value, width - Draw.width(label) - 8f), x + width, y, Theme.opaque(Theme.text))
    }

    private fun bar(graphics: GuiGraphicsExtractor, x: Float, y: Float, width: Float, progress: Float, color: Int) {
        graphics.roundRect(x, y, width, 3f, 1.5f, Theme.surface(Theme.header))
        val fill = width * progress.coerceIn(0f, 1f)
        if (fill > 0.5f) graphics.roundRect(x, y, fill, 3f, 1.5f, Theme.opaque(color))
    }

    // Inventory: armour and equipment on the left, the 27 main slots, then the hotbar.

    private fun drawInventory(graphics: GuiGraphicsExtractor, p: ProfileData.Profile, mx: Float, my: Float) {
        if (!p.inventoryApi) return message(graphics, "Inventory API is off for this profile.")
        val armor = items(p, "armor").reversed()
        val equipment = items(p, "equipment")
        val inventory = items(p, "inventory")

        val gridWidth = 9 * SLOT
        val totalWidth = SLOT * 2f + 14f + gridWidth
        val left = contentX() + (contentWidth() - totalWidth) / 2f
        val top = contentY() + 26f

        caption(graphics, "Armor", left, top - 14f)
        for (i in 0 until 4) slot(graphics, armor.getOrNull(i), left, top + i * SLOT, mx, my)
        caption(graphics, "Equipment", left + SLOT, top - 14f)
        for (i in 0 until 4) slot(graphics, equipment.getOrNull(i), left + SLOT, top + i * SLOT, mx, my)

        val gridX = left + SLOT * 2f + 14f
        caption(graphics, "Inventory", gridX, top - 14f)
        for (row in 0 until 3) for (col in 0 until 9) {
            slot(graphics, inventory.getOrNull(9 + row * 9 + col), gridX + col * SLOT, top + row * SLOT, mx, my)
        }
        for (col in 0 until 9) slot(graphics, inventory.getOrNull(col), gridX + col * SLOT, top + 3 * SLOT + 6f, mx, my)
    }

    // A container split into pages, with arrows under it.

    private fun drawPaged(
        graphics: GuiGraphicsExtractor,
        stacks: List<ItemStack>,
        perPage: Int,
        columns: Int,
        label: String,
        mx: Float,
        my: Float,
    ) {
        val current = profile
        if (current != null && !current.inventoryApi && tab != Tab.PETS) return message(graphics, "Inventory API is off for this profile.")
        if (stacks.isEmpty()) return message(graphics, "$label is empty.")
        val pages = ceil(stacks.size / perPage.toDouble()).toInt().coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        val rows = perPage / columns
        val left = contentX() + (contentWidth() - columns * SLOT) / 2f
        val top = contentY() + 26f
        caption(graphics, if (pages > 1) "$label · page ${page + 1} of $pages" else label, left, top - 14f)
        for (i in 0 until perPage) {
            val stack = stacks.getOrNull(page * perPage + i) ?: break
            slot(graphics, stack, left + (i % columns) * SLOT, top + (i / columns) * SLOT, mx, my)
        }
        if (pages > 1) pager(graphics, top + rows * SLOT + 10f, mx, my)
    }

    private fun drawBackpacks(graphics: GuiGraphicsExtractor, p: ProfileData.Profile, mx: Float, my: Float) {
        if (!p.inventoryApi) return message(graphics, "Inventory API is off for this profile.")
        val slots = p.backpacks.keys.toList()
        if (slots.isEmpty()) return message(graphics, "No backpacks.")
        backpack = backpack.coerceIn(0, slots.size - 1)

        // Backpack icons along the top; click one to open it.
        val iconsLeft = contentX() + (contentWidth() - slots.size.coerceAtMost(18) * SLOT) / 2f
        val iconsTop = contentY() + 12f
        slots.forEachIndexed { i, slotNumber ->
            val icon = decodedCached("bp_icon_${slotNumber}_${p.id}") { ProfileItems.decode(p.backpackIcons[slotNumber]) }.firstOrNull()
                ?: ItemStack(Items.CHEST)
            val x = iconsLeft + i * SLOT
            if (i == backpack) graphics.roundRect(x - 1f, iconsTop - 1f, SLOT, SLOT, 4f, Theme.withAlpha(Theme.accent, 110))
            slot(graphics, icon, x, iconsTop, mx, my)
        }

        val contents = decodedCached("bp_${slots[backpack]}_${p.id}") { ProfileItems.decode(p.backpacks[slots[backpack]]) }
        val left = contentX() + (contentWidth() - 9 * SLOT) / 2f
        val top = iconsTop + SLOT + 22f
        caption(graphics, "Backpack ${slots[backpack] + 1}", left, top - 14f)
        contents.forEachIndexed { i, stack -> slot(graphics, stack, left + (i % 9) * SLOT, top + (i / 9) * SLOT, mx, my) }
    }

    private fun drawWardrobe(graphics: GuiGraphicsExtractor, p: ProfileData.Profile, mx: Float, my: Float) {
        if (!p.inventoryApi) return message(graphics, "Inventory API is off for this profile.")
        // Hypixel's API stopped including wardrobes, so most profiles have nothing to show here.
        if (p.inventories["wardrobe"] == null) return message(graphics, "Hypixel doesn't share wardrobes through its API.")
        val stacks = items(p, "wardrobe")
        if (stacks.isEmpty()) return message(graphics, "Wardrobe is empty.")
        // Hypixel lays the wardrobe out in pages of 9 sets: a row of helmets, then chestplates, and so on.
        val pages = ceil(stacks.size / 36.0).toInt().coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        val left = contentX() + (contentWidth() - 9 * SLOT) / 2f
        val top = contentY() + 26f
        caption(graphics, if (pages > 1) "Wardrobe · page ${page + 1} of $pages" else "Wardrobe", left, top - 14f)
        for (set in 0 until 9) {
            val equipped = p.wardrobeSlot == page * 9 + set + 1
            if (equipped) graphics.roundRect(left + set * SLOT - 1f, top - 1f, SLOT, SLOT * 4f, 4f, Theme.withAlpha(Theme.accent, 90))
            for (piece in 0 until 4) {
                slot(graphics, stacks.getOrNull(page * 36 + piece * 9 + set), left + set * SLOT, top + piece * SLOT, mx, my)
            }
        }
        if (pages > 1) pager(graphics, top + 4 * SLOT + 10f, mx, my)
    }

    private fun caption(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float) {
        graphics.string(text, x, y, Theme.opaque(Theme.textDim))
    }

    private fun slot(graphics: GuiGraphicsExtractor, stack: ItemStack?, x: Float, y: Float, mx: Float, my: Float) {
        val hover = Draw.inside(mx, my, x, y, SLOT - 2f, SLOT - 2f)
        graphics.roundRect(x, y, SLOT - 2f, SLOT - 2f, 3f, Theme.surface(if (hover) Theme.border else Theme.header))
        if (stack == null || stack.isEmpty) return
        graphics.item(stack, (x + 1f).toInt(), (y + 1f).toInt())
        graphics.itemDecorations(McCompat.font, stack, (x + 1f).toInt(), (y + 1f).toInt())
        if (hover) hovered = stack
    }

    private fun pagerBounds(y: Float): Pair<Float, Float> {
        val center = contentX() + contentWidth() / 2f
        return (center - 40f) to (center + 22f)
    }

    private fun pager(graphics: GuiGraphicsExtractor, y: Float, mx: Float, my: Float) {
        val (prev, next) = pagerBounds(y)
        pagerY = y
        for ((x, label) in listOf(prev to "<", next to ">")) {
            val hover = Draw.inside(mx, my, x, y, 18f, 16f)
            graphics.roundRect(x, y, 18f, 16f, 5f, Theme.surface(if (hover) Theme.border else Theme.header))
            graphics.stringCentered(label, x + 9f, y + 4f, Theme.opaque(Theme.text))
        }
    }

    private var pagerY = -1f

    // Decoding is done once per container and kept, so scrolling tabs stays smooth.

    private fun items(p: ProfileData.Profile, key: String): List<ItemStack> =
        decodedCached("${key}_${p.id}") { ProfileItems.decode(p.inventories[key]) }

    private fun pets(p: ProfileData.Profile): List<ItemStack> =
        decodedCached("pets_${p.id}") { p.pets.map(ProfileItems::pet) }

    private fun decodedCached(key: String, build: () -> List<ItemStack>): List<ItemStack> =
        decoded.getOrPut(key, build)

    private fun prettyPet(pet: ProfileData.Pet): String =
        pet.type.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } + " " + pet.level

    private fun coins(value: Double): String = when {
        value >= 1e9 -> "%.2fB".format(value / 1e9)
        value >= 1e6 -> "%.1fM".format(value / 1e6)
        value >= 1e3 -> "%.1fK".format(value / 1e3)
        else -> "%.0f".format(value)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = UiScale.mouseX(click.x())
        val my = UiScale.mouseY(click.y())
        val current = profile

        // Profile switcher.
        if (current != null) {
            val chipWidth = Draw.width(profileChip(current)) + 22f
            val chipX = windowX + WIDTH - PAD - chipWidth
            if (Draw.inside(mx, my, chipX, windowY + PAD + 2f, chipWidth, 18f)) {
                val count = data?.profiles?.size ?: 1
                profileIndex = (profileIndex + 1) % count
                page = 0
                backpack = 0
                return true
            }
        }

        Tab.entries.forEachIndexed { index, entry ->
            if (Draw.inside(mx, my, tabsX(), tabY(index), SIDEBAR - 8f, TAB_HEIGHT)) {
                if (tab != entry) {
                    tab = entry
                    page = 0
                }
                return true
            }
        }

        if (pagerY >= 0f) {
            val (prev, next) = pagerBounds(pagerY)
            if (Draw.inside(mx, my, prev, pagerY, 18f, 16f)) {
                page = (page - 1).coerceAtLeast(0)
                return true
            }
            if (Draw.inside(mx, my, next, pagerY, 18f, 16f)) {
                page++
                return true
            }
        }

        if (tab == Tab.BACKPACKS && current != null) {
            val slots = current.backpacks.keys.toList()
            val iconsLeft = contentX() + (contentWidth() - slots.size.coerceAtMost(18) * SLOT) / 2f
            val iconsTop = contentY() + 12f
            slots.indices.forEach { i ->
                if (Draw.inside(mx, my, iconsLeft + i * SLOT, iconsTop, SLOT - 2f, SLOT - 2f)) {
                    backpack = i
                    return true
                }
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (verticalAmount != 0.0 && tab != Tab.OVERVIEW && tab != Tab.INVENTORY) {
            page = (page + if (verticalAmount < 0) 1 else -1).coerceAtLeast(0)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    companion object {
        private const val WIDTH = 640f
        private const val HEIGHT = 330f
        private const val RADIUS = 10f
        private const val PAD = 12f
        private const val HEADER = 58f
        private const val SIDEBAR = 118f
        private const val TAB_HEIGHT = 24f
        private const val SLOT = 20f
        private const val ROW = 19f
    }
}
