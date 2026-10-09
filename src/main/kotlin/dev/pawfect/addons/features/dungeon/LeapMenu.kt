package dev.pawfect.addons.features.dungeon

import com.mojang.blaze3d.platform.InputConstants
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.DungeonParty.DungeonClass
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory

/**
 * Spirit Leap as four boxes, one per quarter of the screen: click anywhere in a quarter, or
 * press its key, to leap to whoever is there. Out of the boss the dungeon map sits in the middle,
 * and clicking a teammate's head on it leaps to them too. Each class has a fixed corner, so
 * the layout is the same every run you play the same class, and Healer is always bottom right.
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

    /** The four quarters of the screen, with their keys. */
    enum class Corner(val key: Int, val left: Boolean, val top: Boolean) {
        TOP_LEFT(1, true, true),
        TOP_RIGHT(2, false, true),
        BOTTOM_LEFT(3, true, false),
        BOTTOM_RIGHT(4, false, false),
    }

    class Target(val name: String, val dungeonClass: DungeonClass?, val dead: Boolean, val slot: Int?)

    /** The order classes take the corners in, skipping yours. Healer is kept for bottom right. */
    private val CORNER_ORDER = listOf(DungeonClass.ARCHER, DungeonClass.BERSERKER, DungeonClass.MAGE, DungeonClass.TANK)

    /** Clicks this soon after the menu opens are ignored, so nothing lands before Hypixel has filled it in. */
    private const val OPEN_GUARD_MS = 150L

    /** A second click this soon after a leap is ignored, so a double click can't send two. */
    private const val REPEAT_GUARD_MS = 600L

    private val HEAD_NAME = Regex("""^(?:\[.+?] )?(\w{1,16})$""")

    private var trackedContainer = -1
    private var trackedScreen = java.lang.ref.WeakReference<AbstractContainerScreen<*>>(null)
    private var openedAt = 0L
    private var lastLeapAt = 0L
    private var failed = false
    private var message: String? = null
    private var messageAt = 0L

    /** A leap key that has leaped and hasn't been let go yet; holding it doesn't leap again. */
    private var heldKey = -1

    /** How far each box has grown toward its hovered size, for a short ease. */
    private val grow = FloatArray(Corner.entries.size)
    private var lastFrame = 0L

    // ------------------------------------------------------------------ when it takes over

    /** Whether this screen is a leap menu this draws instead of Hypixel's. */
    @JvmStatic
    fun active(screen: AbstractContainerScreen<*>): Boolean {
        if (failed || !config.leapMenu || !SkyBlockData.inDungeons) return false
        if (screen !is ContainerScreen) return false
        if (screen.title.string.removeColor().trim() !in TITLES) return false
        val menu = screen.menu
        // A new screen is a new menu even if Hypixel happens to reuse the container number.
        if (menu.containerId != trackedContainer || trackedScreen.get() !== screen) {
            trackedContainer = menu.containerId
            trackedScreen = java.lang.ref.WeakReference(screen)
            openedAt = System.currentTimeMillis()
            lastLeapAt = 0L
            message = null
            grow.fill(0f)
        }
        if (heldKey != -1 && !InputConstants.isKeyDown(McCompat.mc.window, heldKey)) heldKey = -1
        return true
    }

    // ------------------------------------------------------------------ who goes where

    /** Each class's corner, given yours: the same every time for the same class. */
    private fun homes(own: DungeonClass?): Map<DungeonClass, Corner> {
        val out = HashMap<DungeonClass, Corner>()
        val order = CORNER_ORDER.filter { it != own }
        val corners = if (own == DungeonClass.HEALER) Corner.entries else Corner.entries - Corner.BOTTOM_RIGHT
        if (own != DungeonClass.HEALER) out[DungeonClass.HEALER] = Corner.BOTTOM_RIGHT
        order.zip(corners).forEach { (dungeonClass, corner) -> out[dungeonClass] = corner }
        return out
    }

    /** Teammates by corner. Fixed: same classes, same corners, every time. */
    fun layout(screen: AbstractContainerScreen<*>): Map<Corner, Target> {
        val self = McCompat.player?.name?.string ?: ""
        val heads = HashMap<String, Int>()
        val menu = screen.menu
        for (i in 0 until containerSize(screen)) {
            val name = headName(screen, i) ?: continue
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

        val homes = homes(DungeonParty.member(self)?.dungeonClass)
        val out = LinkedHashMap<Corner, Target>()
        val extra = ArrayList<Target>()
        targets.forEach { target ->
            val home = target.dungeonClass?.let { homes[it] }
            if (home != null && home !in out) out[home] = target else extra += target
        }
        // Two of one class, or a class we don't know yet: the free corners, in key order.
        extra.forEach { target -> Corner.entries.firstOrNull { it !in out }?.let { out[it] = target } }
        return out
    }

    private fun containerSize(screen: AbstractContainerScreen<*>): Int = (screen.menu.slots.size - 36).coerceAtLeast(0)

    /** The player a slot's head belongs to, or null when it isn't a player head. */
    private fun headName(screen: AbstractContainerScreen<*>, slot: Int): String? {
        val stack = screen.menu.slots[slot].item
        if (stack.isEmpty || !stack.`is`(Items.PLAYER_HEAD)) return null
        return HEAD_NAME.find(stack.hoverName.string.removeColor().trim())?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ input

    /** The quarter of the screen the mouse is in. */
    fun cornerAt(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double): Corner {
        val left = mouseX < screen.width / 2.0
        val top = mouseY < screen.height / 2.0
        return Corner.entries.first { it.left == left && it.top == top }
    }

    @JvmStatic
    fun mouseClicked(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, button: Int) {
        if (button != 0) return
        val map = mapSquare(screen)
        if (map != null && inside(map, mouseX, mouseY)) {
            // On the map only a teammate's head leaps; the rest of the map does nothing.
            val layout = layout(screen)
            val name = DungeonMap.headAt(map[0], map[1], map[2], mouseX, mouseY) { canLeapTo(layout, it) } ?: return
            leapTo(screen, layout.values.firstOrNull { it.name.equals(name, ignoreCase = true) })
            return
        }
        leapTo(screen, layout(screen)[cornerAt(screen, mouseX, mouseY)])
    }

    /** The map's square in the middle of the screen (x, y, size), or null in the boss or without a map. */
    private fun mapSquare(screen: AbstractContainerScreen<*>): FloatArray? {
        if (!DungeonMap.available()) return null
        val size = minOf(screen.height * 0.62f, screen.width * 0.40f)
        return floatArrayOf((screen.width - size) / 2f, (screen.height - size) / 2f, size)
    }

    private fun inside(square: FloatArray, x: Double, y: Double): Boolean =
        x >= square[0] && x < square[0] + square[2] && y >= square[1] && y < square[1] + square[2]

    private fun canLeapTo(layout: Map<Corner, Target>, name: String): Boolean =
        layout.values.any { it.name.equals(name, ignoreCase = true) && !it.dead && it.slot != null }

    /** True when the key was one of the leap keys. */
    @JvmStatic
    fun keyPressed(screen: AbstractContainerScreen<*>, key: Int): Boolean {
        val digit = when (key) {
            in KEY_1..KEY_4 -> key - KEY_1 + 1
            in KEYPAD_1..KEYPAD_4 -> key - KEYPAD_1 + 1
            else -> return false
        }
        // Minecraft sends a held key again and again; only the press itself leaps.
        if (key == heldKey) return true
        heldKey = key
        Corner.entries.firstOrNull { it.key == digit }?.let { leapTo(screen, layout(screen)[it]) }
        return true
    }

    /** Keys that close the menu, which go on to vanilla as normal. */
    @JvmStatic
    fun closesMenu(event: KeyEvent): Boolean =
        event.key() == KEY_ESCAPE || McCompat.mc.options.keyInventory.matches(event)

    /** Every leap, from a box, a key or the map, comes through here. */
    private fun leapTo(screen: AbstractContainerScreen<*>, chosen: Target?) {
        val player = McCompat.player ?: return
        val gameMode = McCompat.mc.gameMode ?: return
        val menu = screen.menu
        val now = System.currentTimeMillis()
        // The screen has to be showing the menu you actually have open.
        if (player.containerMenu !== menu || menu.containerId != trackedContainer || trackedScreen.get() !== screen) return
        if (now - openedAt < OPEN_GUARD_MS || now - lastLeapAt < REPEAT_GUARD_MS) return

        val target = chosen ?: return
        if (target.dead) return say("${target.name} is dead")
        val slot = target.slot ?: return say("${target.name} isn't in the menu")
        // Checked again right before clicking: still in the chest part, still that player's head.
        if (slot !in 0 until containerSize(screen)) return
        if (!target.name.equals(headName(screen, slot), ignoreCase = true)) return

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
        val halfW = w / 2f
        val halfH = h / 2f
        graphics.rect(0f, 0f, w, h, DIM)

        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now

        val layout = layout(screen)
        val map = mapSquare(screen)
        if (layout.isEmpty() && map == null) centered(graphics, "Waiting for the menu...", halfW, halfH - 4f, 1f, MUTED)
        val onMap = map != null && inside(map, mouseX.toDouble(), mouseY.toDouble())
        val hovered = if (onMap) null else cornerAt(screen, mouseX.toDouble(), mouseY.toDouble())
        // With the map up, the boxes sit out in the screen corners around it; without it they huddle in the middle.
        val scale = if (map != null) minOf(1f, ((w - map[2]) / 2f - EDGE * 2f) / BOX_W, (halfH - EDGE * 2f) / BOX_H)
        else minOf(1f, (halfW - GAP - 6f) / BOX_W, (halfH - GAP - 6f) / BOX_H)
        if (map != null) DungeonMap.draw(graphics, map[0], map[1], map[2], mouseX.toDouble(), mouseY.toDouble()) { canLeapTo(layout, it) }

        Corner.entries.forEachIndexed { i, corner ->
            val target = layout[corner] ?: return@forEachIndexed
            val usable = !target.dead && target.slot != null
            val goal = if (corner == hovered && usable) 1f else 0f
            grow[i] += (goal - grow[i]) * (dt * 14f).coerceAtMost(1f)

            val pose = graphics.pose()
            pose.pushMatrix()
            if (map != null) pose.translate(if (corner.left) EDGE else w - EDGE, if (corner.top) EDGE else h - EDGE)
            else pose.translate(if (corner.left) halfW - GAP else halfW + GAP, if (corner.top) halfH - GAP else halfH + GAP)
            val g = 1f + grow[i] * GROW
            pose.scale(scale * g, scale * g)
            // Anchored on the corner nearest the map in the middle, or nearest the screen edge with the map up.
            val localX = if (map != null) (if (corner.left) 0f else -BOX_W) else (if (corner.left) -BOX_W else 0f)
            val localY = if (map != null) (if (corner.top) 0f else -BOX_H) else (if (corner.top) -BOX_H else 0f)
            box(screen, graphics, localX, localY, corner, target, usable)
            pose.popMatrix()
        }

        val text = message
        if (text != null && now - messageAt < 1_500L) {
            val tw = McCompat.font.width(text) + 16f
            graphics.roundPanel(halfW - tw / 2f, h - 34f, tw, 18f, 4f, BOX, LINE)
            centered(graphics, text, halfW, h - 29f, 1f, TEXT)
        }
    }

    private fun box(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, x: Float, y: Float, corner: Corner, target: Target, usable: Boolean) {
        val classColor = 0xFF000000.toInt() or (target.dungeonClass?.color ?: 0xAAAAAA)
        graphics.roundPanel(x, y, BOX_W, BOX_H, 9f, BOX, if (usable) (0x66000000 or (classColor and 0xFFFFFF)) else LINE)

        // The face from the head in Hypixel's menu, so it's the right skin.
        val face = (BOX_H * 0.76f).toInt()
        val profile = target.slot?.let { screen.menu.slots[it].item.get(DataComponents.PROFILE) }
        val faceX = (x + 9f).toInt()
        val faceY = (y + (BOX_H - face) / 2f).toInt()
        if (profile != null) PlayerFaceExtractor.extractRenderState(graphics, profile, faceX, faceY, face)
        else graphics.rect(faceX.toFloat(), faceY.toFloat(), face.toFloat(), face.toFloat(), FACE_EMPTY)
        if (!usable) graphics.rect(faceX.toFloat(), faceY.toFloat(), face.toFloat(), face.toFloat(), FACE_DIM)

        val textX = x + 18f + face
        val room = BOX_W - (textX - x) - 10f
        val className = target.dungeonClass?.label ?: "Unknown"
        text(graphics, className, textX, y + 19f, fit(className, room, 1.3f), if (usable) classColor else MUTED)
        val (line, color) = when {
            target.dead -> "Dead" to RED
            target.slot == null -> "${target.name} (not in menu)" to MUTED
            else -> target.name to GRAY
        }
        text(graphics, line, textX, y + 37f, fit(line, room, 1.6f), color)

        text(graphics, corner.key.toString(), x + BOX_W - 14f, y + BOX_H - 15f, 1f, KEY)
    }

    /** The largest scale up to [max] that fits [text] in [room]. */
    private fun fit(text: String, room: Float, max: Float): Float =
        minOf(max, room / McCompat.font.width(text).coerceAtLeast(1))

    private fun text(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, scale: Float, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        if (scale != 1f) pose.scale(scale, scale)
        graphics.text(McCompat.font, Component.literal(text), 0, 0, color, true)
        pose.popMatrix()
    }

    private fun centered(graphics: GuiGraphicsExtractor, text: String, centerX: Float, y: Float, scale: Float, color: Int) =
        text(graphics, text, centerX - McCompat.font.width(text) * scale / 2f, y, scale, color)

    private const val BOX_W = 200f
    private const val BOX_H = 75f
    private const val GAP = 24f
    private const val EDGE = 12f
    private const val GROW = 0.05f

    private const val KEY_1 = 49
    private const val KEY_4 = 52
    private const val KEYPAD_1 = 321
    private const val KEYPAD_4 = 324
    private const val KEY_ESCAPE = 256

    private const val DIM = 0x99000000.toInt()
    private const val BOX = 0xE6141221.toInt()
    private const val LINE = 0x30ECE9F7
    private const val TEXT = 0xFFECE9F7.toInt()
    private const val GRAY = 0xFFAAAAAA.toInt()
    private const val MUTED = 0xFF6E6A85.toInt()
    private const val RED = 0xFFFF5555.toInt()
    private const val KEY = 0x80ECE9F7.toInt()
    private const val FACE_EMPTY = 0xFF2A2640.toInt()
    private const val FACE_DIM = 0xA0141221.toInt()
}
