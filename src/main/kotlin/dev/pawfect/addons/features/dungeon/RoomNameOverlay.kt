package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.WeakHashMap

/**
 * The dungeon room you're in as one line of text: its name, how many of its secrets you've
 * found, and whether its starred mobs are all dead. Moves with the HUD editor.
 */
object RoomNameOverlay {

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.dungeonHud)

    private const val GRAY = 0xAAAAAA
    private const val RED = 0xFF5555
    private const val YELLOW = 0xFFFF55
    private const val GREEN = 0x00AA00
    private const val LIME = 0x55FF55

    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")

    /** Secrets found in each room this run, as the action bar last said while you were in it. */
    private val found = WeakHashMap<DungeonRoom, Int>()

    private var room: DungeonRoom? = null
    private var enteredAt = 0L

    /** Whether starred mobs were left in the room at the last look, and when that was. */
    private var starred = false
    private var starredAt = 0L

    fun render() {
        if (!config.roomName || McCompat.hideGui || !RenderContext.isActive) return
        val screen = McCompat.screen
        if (screen != null && screen !is ChatScreen && !GuiEditManager.isEditorOpen()) return
        val line = line() ?: return

        val font = McCompat.font
        val position = ConfigManager.features.dungeonHud.roomNamePosition
        val scale = position.effectiveScale
        val scaledWidth = (font.width(line) * scale).toInt()
        val scaledHeight = (font.lineHeight * scale).toInt()
        val x = position.getAbsX(scaledWidth)
        val y = position.getAbsY(scaledHeight)

        RenderContext.pushPop {
            RenderContext.translate(x.toFloat(), y.toFloat())
            if (scale != 1f) RenderContext.scale(scale)
            RenderContext.graphics.text(font, line, 0, 0, -1, true)
        }
        GuiEditManager.register(position, HudDefaults.ROOM_NAME, scaledWidth, scaledHeight)
    }

    fun onActionBar(message: Component) {
        val match = SECRETS.find(message.string.replace(Regex("§."), "")) ?: return
        val current = DungeonScanner.current ?: return
        track(current)
        // The bar lags a moment behind walking in, still counting the room you came from.
        if (current !== room || System.currentTimeMillis() - enteredAt < ENTER_LAG_MS) return
        found[current] = match.groupValues[1].toInt()
    }

    private fun line(): Component? {
        if (GuiEditManager.isEditorOpen() && !SkyBlockData.inDungeons) return preview()
        if (!SkyBlockData.inDungeons) return null
        val current = DungeonScanner.current ?: return null
        track(current)
        val name = current.name?.takeIf { current.matched }
        val title = when {
            name != null -> name.substringBeforeLast('-').split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            else -> when (current.type) {
                DungeonRoom.Type.ENTRANCE -> "Entrance"
                DungeonRoom.Type.PUZZLE -> "Puzzle"
                DungeonRoom.Type.TRAP -> "Trap"
                DungeonRoom.Type.MINIBOSS -> "Miniboss"
                DungeonRoom.Type.FAIRY -> "Fairy"
                DungeonRoom.Type.BLOOD -> "Blood"
                else -> "Unknown Room"
            }
        }
        val (secrets, total) = secretsIn(current) ?: (0 to 0)
        return compose(title, secrets, total, cleared(current))
    }

    /** Notes walking into a new room, so the action bar isn't read as the old one's for a moment. */
    private fun track(current: DungeonRoom) {
        if (current === room) return
        room = current
        enteredAt = System.currentTimeMillis()
        starredAt = 0L
    }

    /** Secrets found and the room's total, for a room you've been in that has secrets; else null. */
    fun secretsIn(room: DungeonRoom): Pair<Int, Int>? {
        val total = room.name?.takeIf { room.matched }?.substringAfterLast('-')?.toIntOrNull() ?: return null
        if (total <= 0) return null
        val secrets = if (room.greenChecked) total else (found[room] ?: 0).coerceAtMost(total)
        return secrets to total
    }

    private fun preview(): Component = compose("Room Name", 2, 5, false)

    /** Joins whichever parts are on into one line; null [cleared] leaves the status off. */
    private fun compose(title: String, secrets: Int, total: Int, cleared: Boolean?): Component {
        val parts = ArrayList<MutableComponent>()
        if (config.roomNameShowName) parts += Component.literal(title).withColor(GRAY)
        if (total > 0) parts += Component.literal("$secrets/$total").withColor(secretColor(secrets, total))
        if (cleared != null) parts += when {
            config.roomNameSimple -> Component.literal(if (cleared) "✔" else "✖")
            else -> Component.literal(if (cleared) "Cleared" else "Not Cleared")
        }.withColor(if (cleared) LIME else RED)
        val line = Component.empty()
        parts.forEachIndexed { k, part ->
            if (k > 0) line.append(" ")
            line.append(part)
        }
        return line
    }

    /** Red under a quarter, yellow under 65%, green under all of them, lime once they're all found. */
    private fun secretColor(secrets: Int, total: Int): Int {
        val share = secrets.toFloat() / total
        return when {
            share < 0.25f -> RED
            share < 0.65f -> YELLOW
            share < 1f -> GREEN
            else -> LIME
        }
    }

    /**
     * Whether the room's mobs are done: the map gives it a checkmark, or (for rooms with
     * starred mobs to kill) none are left standing. Null for rooms with nothing to clear.
     */
    private fun cleared(current: DungeonRoom): Boolean? = when (current.type) {
        DungeonRoom.Type.ENTRANCE, DungeonRoom.Type.FAIRY, DungeonRoom.Type.BLOOD -> null
        DungeonRoom.Type.PUZZLE, DungeonRoom.Type.MINIBOSS -> current.mapCleared
        else -> current.mapCleared || (System.currentTimeMillis() - enteredAt >= ENTER_LAG_MS && !starredLeft(current))
    }

    private fun starredLeft(current: DungeonRoom): Boolean {
        val now = System.currentTimeMillis()
        if (now - starredAt < STAR_CHECK_MS) return starred
        starredAt = now
        val level = McCompat.mc.level ?: return false
        starred = level.entitiesForRendering().any { entity ->
            if (entity !is ArmorStand || !entity.isAlive) return@any false
            val name = entity.customName?.string ?: return@any false
            if (!name.contains(STAR) || !name.endsWith(HEART)) return@any false
            val cell = DungeonMapUtils.getPhysicalRoomPos(entity.x, entity.z)
            (cell.x() to cell.y()) in current.segments
        }
        return starred
    }

    private const val STAR = "✯"
    private const val HEART = "❤"
    private const val ENTER_LAG_MS = 1_500L
    private const val STAR_CHECK_MS = 250L
}
