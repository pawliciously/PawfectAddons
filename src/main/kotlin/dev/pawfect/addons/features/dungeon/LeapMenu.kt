package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.DungeonParty.DungeonClass
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringCentered
import dev.pawfect.addons.ui.Draw.stringScaled
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory

/**
 * Spirit Leap drawn as four corners and a middle: each class always has the same spot and the
 * same key, and Healer is always bottom right. Your own class's spot is left empty.
 *
 * Hypixel's own menu stays open underneath. A leap is one left click on that teammate's head
 * in it, sent through vanilla's own container click, exactly as clicking the head yourself
 * would. While this is up, every other way the screen could click a slot is blocked, so the
 * only click that reaches the server is the one you meant.
 */
object LeapMenu {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Leap")

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.visuals)

    private val TITLES = setOf("Spirit Leap", "Teleport to Player")

    /** Where each class sits, and its key. Reading order, so key 5 is the bottom-right corner. */
    enum class Zone(val home: DungeonClass, val key: Int) {
        TOP_LEFT(DungeonClass.ARCHER, 1),
        TOP_RIGHT(DungeonClass.BERSERKER, 2),
        MIDDLE(DungeonClass.MAGE, 3),
        BOTTOM_LEFT(DungeonClass.TANK, 4),
        BOTTOM_RIGHT(DungeonClass.HEALER, 5),
    }

    class Target(val name: String, val dungeonClass: DungeonClass?, val dead: Boolean, val slot: Int?)

    /** Clicks this soon after the menu opens are ignored, so nothing lands before Hypixel has filled it in. */
    private const val OPEN_GUARD_MS = 150L

    /** A second click this soon after a leap is ignored, so a double click can't send two. */
    private const val REPEAT_GUARD_MS = 600L

    private val HEAD_NAME = Regex("""^(?:\[.+?] )?(\w{1,16})$""")

    private var trackedContainer = -1
    private var openedAt = 0L
    private var lastLeapAt = 0L
    private var failed = false

    /** A leap key that has leaped and hasn't been let go yet; holding it doesn't leap again. */
    private var heldKey = -1
    private var message: String? = null
    private var messageAt = 0L

    // ------------------------------------------------------------------ when it takes over

    /** Whether this screen is a leap menu this draws instead of Hypixel's. */
    @JvmStatic
    fun active(screen: AbstractContainerScreen<*>): Boolean {
        if (failed || !config.leapMenu || !SkyBlockData.inDungeons) return false
        if (screen !is ContainerScreen) return false
        if (screen.title.string.removeColor().trim() !in TITLES) return false
        val menu = screen.menu
        if (menu.containerId != trackedContainer) {
            trackedContainer = menu.containerId
            openedAt = System.currentTimeMillis()
            lastLeapAt = 0L
            message = null
        }
        if (heldKey != -1 && !InputConstants.isKeyDown(McCompat.mc.window, heldKey)) heldKey = -1
        return true
    }

    // ------------------------------------------------------------------ who goes where

    /** Teammates by spot. Fixed: same classes, same spots, every time. */
    fun layout(screen: AbstractContainerScreen<*>): Map<Zone, Target> {
        val self = McCompat.player?.name?.string ?: ""
        val heads = HashMap<String, Int>()
        val menu = screen.menu
        for (i in 0 until containerSize(screen)) {
            val stack = menu.slots[i].item
            if (stack.isEmpty || !stack.`is`(Items.PLAYER_HEAD)) continue
            val name = HEAD_NAME.find(stack.hoverName.string.removeColor().trim())?.groupValues?.get(1) ?: continue
            heads[name.lowercase()] = i
        }
        // Everyone the tab list or the menu knows about, minus you, in name order so it never shuffles.
        val names = (DungeonParty.members().map { it.name } + heads.keys.map { key ->
            DungeonParty.member(key)?.name ?: key
        }).distinctBy { it.lowercase() }.filter { !it.equals(self, ignoreCase = true) }.sortedBy { it.lowercase() }
        val targets = names.map { name ->
            val member = DungeonParty.member(name)
            Target(member?.name ?: name, member?.dungeonClass, member?.dead == true, heads[name.lowercase()])
        }

        val out = LinkedHashMap<Zone, Target>()
        val extra = ArrayList<Target>()
        targets.forEach { target ->
            val home = Zone.entries.firstOrNull { it.home == target.dungeonClass }
            if (home != null && home !in out) out[home] = target else extra += target
        }
        // Two of one class, or a class we don't know yet: the free spots, in key order.
        extra.forEach { target -> Zone.entries.firstOrNull { it !in out }?.let { out[it] = target } }
        return out
    }

    private fun containerSize(screen: AbstractContainerScreen<*>): Int = (screen.menu.slots.size - 36).coerceAtLeast(0)

    // ------------------------------------------------------------------ input

    /** The spot under the mouse: the middle oval, or whichever quarter of the screen it's in. */
    fun zoneAt(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double): Zone {
        val x = mouseX / screen.width - 0.5
        val y = mouseY / screen.height - 0.5
        if ((x / MIDDLE_RX) * (x / MIDDLE_RX) + (y / MIDDLE_RY) * (y / MIDDLE_RY) <= 1.0) return Zone.MIDDLE
        return if (y < 0) (if (x < 0) Zone.TOP_LEFT else Zone.TOP_RIGHT) else (if (x < 0) Zone.BOTTOM_LEFT else Zone.BOTTOM_RIGHT)
    }

    @JvmStatic
    fun mouseClicked(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int) {
        if (button != 0) return
        leap(screen, zoneAt(screen, mouseX, mouseY))
    }

    /** True when the key was one of the leap keys. */
    @JvmStatic
    fun keyPressed(screen: AbstractContainerScreen<*>, key: Int): Boolean {
        val digit = when (key) {
            in KEY_1..KEY_5 -> key - KEY_1 + 1
            in KEYPAD_1..KEYPAD_5 -> key - KEYPAD_1 + 1
            else -> return false
        }
        // Minecraft sends a held key again and again; only the press itself leaps.
        if (key == heldKey) return true
        heldKey = key
        Zone.entries.firstOrNull { it.key == digit }?.let { leap(screen, it) }
        return true
    }

    /** Keys that close the menu, which go on to vanilla as normal. */
    @JvmStatic
    fun closesMenu(event: KeyEvent): Boolean =
        event.key() == KEY_ESCAPE || McCompat.mc.options.keyInventory.matches(event)

    private fun leap(screen: AbstractContainerScreen<*>, zone: Zone) {
        val player = McCompat.player ?: return
        val gameMode = McCompat.mc.gameMode ?: return
        val menu = screen.menu
        val now = System.currentTimeMillis()
        // The screen has to be showing the menu you actually have open.
        if (player.containerMenu !== menu || menu.containerId != trackedContainer) return
        if (now - openedAt < OPEN_GUARD_MS || now - lastLeapAt < REPEAT_GUARD_MS) return

        val target = layout(screen)[zone] ?: return
        if (target.dead) return say("${target.name} is dead")
        val slot = target.slot ?: return say("${target.name} isn't in the menu")
        // Checked again right before clicking: still in the chest part, still that player's head.
        if (slot !in 0 until containerSize(screen)) return
        val stack = menu.slots[slot].item
        if (!stack.`is`(Items.PLAYER_HEAD)) return
        val headName = HEAD_NAME.find(stack.hoverName.string.removeColor().trim())?.groupValues?.get(1)
        if (!target.name.equals(headName, ignoreCase = true)) return

        lastLeapAt = now
        logger.info("Leaping to {} (slot {}, container {})", target.name, slot, menu.containerId)
        // The same call vanilla makes for a left click on that slot.
        gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player)
        say("Leaping to ${target.name}")
    }

    private fun say(text: String) {
        message = text
        messageAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ drawing

    /** Draws the menu; false falls back to Hypixel's own for good. */
    @JvmStatic
    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean = try {
        draw(screen, graphics, mouseX, mouseY)
        true
    } catch (t: Throwable) {
        failed = true
        logger.error("Leap menu failed to draw, using Hypixel's from now on", t)
        false
    }

    private fun draw(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        graphics.rect(0f, 0f, w, h, DIM)
        graphics.rect(w / 2f, 0f, 1f, h, LINE)
        graphics.rect(0f, h / 2f, w, 1f, LINE)
        graphics.stringCentered("Spirit Leap", w / 2f, 10f, MUTED)

        val layout = layout(screen)
        if (layout.isEmpty()) graphics.stringCentered("Waiting for the menu...", w / 2f, h / 2f - 4f, MUTED)
        val hovered = zoneAt(screen, mouseX.toDouble(), mouseY.toDouble())
        val cardW = (w * 0.28f).coerceIn(150f, 230f)
        val cardH = 44f
        val pad = (w * 0.05f).coerceAtLeast(12f)
        Zone.entries.forEach { zone ->
            val target = layout[zone] ?: return@forEach
            val (x, y) = when (zone) {
                Zone.TOP_LEFT -> pad to pad + 12f
                Zone.TOP_RIGHT -> w - pad - cardW to pad + 12f
                Zone.MIDDLE -> (w - cardW) / 2f to (h - cardH) / 2f
                Zone.BOTTOM_LEFT -> pad to h - pad - cardH
                Zone.BOTTOM_RIGHT -> w - pad - cardW to h - pad - cardH
            }
            card(graphics, x, y, cardW, cardH, zone, target, hovered == zone)
        }

        val text = message
        if (text != null && System.currentTimeMillis() - messageAt < 1_500L) {
            val tw = Draw.width(text) + 16f
            graphics.roundPanel((w - tw) / 2f, h / 2f + cardH / 2f + 14f, tw, 18f, 4f, CARD, LINE)
            graphics.stringCentered(text, w / 2f, h / 2f + cardH / 2f + 19f, TEXT)
        }
    }

    private fun card(graphics: GuiGraphicsExtractor, x: Float, y: Float, cardW: Float, cardH: Float, zone: Zone, target: Target, hot: Boolean) {
        val usable = !target.dead && target.slot != null
        val color = target.dungeonClass?.color ?: 0xAAAAAA
        val accent = 0xFF000000.toInt() or color
        val fill = if (hot && usable) mixWithCard(color) else CARD
        val border = if (hot && usable) accent else (0x59000000 or color)
        graphics.roundPanel(x, y, cardW, cardH, 6f, fill, border)

        val badge = cardH - 16f
        graphics.roundRect(x + 8f, y + 8f, badge, badge, 4f, if (usable) accent else GREY)
        val letter = target.dungeonClass?.letter ?: "?"
        graphics.stringScaled(letter, x + 8f + (badge - Draw.width(letter) * 2f) / 2f, y + 8f + (badge - Draw.LINE_HEIGHT * 2f) / 2f + 1f, 2f, BADGE_TEXT)

        val textX = x + 16f + badge
        graphics.string(target.dungeonClass?.label?.uppercase() ?: "UNKNOWN", textX, y + 9f, if (usable) accent else MUTED)
        val name = Draw.truncate(target.name, cardW - badge - 52f)
        graphics.stringScaled(name, textX, y + 21f, 1.25f, if (usable) TEXT else MUTED)
        when {
            target.dead -> graphics.string("DEAD", x + cardW - 8f - Draw.width("DEAD"), y + 9f, RED)
            target.slot == null -> graphics.string("NOT IN MENU", x + cardW - 8f - Draw.width("NOT IN MENU"), y + 9f, MUTED)
        }

        val key = zone.key.toString()
        val kx = x + cardW - 22f
        val ky = y + cardH - 20f
        graphics.roundPanel(kx, ky, 14f, 13f, 3f, KEY_FILL, KEY_LINE)
        graphics.stringCentered(key, kx + 7f, ky + 2.5f, TEXT)
    }

    /** The class colour washed into the card, for the spot under the mouse. */
    private fun mixWithCard(color: Int): Int {
        val r = ((color shr 16 and 0xFF) * 0.18f + 0x0F * 0.82f).toInt()
        val g = ((color shr 8 and 0xFF) * 0.18f + 0x0C * 0.82f).toInt()
        val b = ((color and 0xFF) * 0.18f + 0x1D * 0.82f).toInt()
        return (0xF0 shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val MIDDLE_RX = 0.15
    private const val MIDDLE_RY = 0.135

    private const val KEY_1 = 49
    private const val KEY_5 = 53
    private const val KEYPAD_1 = 321
    private const val KEYPAD_5 = 325
    private const val KEY_ESCAPE = 256

    private const val DIM = 0xB80B0A12.toInt()
    private const val LINE = 0x14ECE9F7
    private const val CARD = 0xDC0F0C1D.toInt()
    private const val TEXT = 0xFFECE9F7.toInt()
    private const val MUTED = 0xFF8F89AA.toInt()
    private const val GREY = 0xFF4A4660.toInt()
    private const val RED = 0xFFFF5555.toInt()
    private const val BADGE_TEXT = 0xFF12101B.toInt()
    private const val KEY_FILL = 0x14FFFFFF
    private const val KEY_LINE = 0x40ECE9F7
}
