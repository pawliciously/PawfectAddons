package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.minecraft.client.gui.screens.ChatScreen

/** The name of the dungeon room you're in, in a small panel that moves with the HUD editor. */
object RoomNameOverlay {

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.dungeonHud)

    fun render() {
        if (!config.roomName || McCompat.hideGui || !RenderContext.isActive) return
        val screen = McCompat.screen
        if (screen != null && screen !is ChatScreen && !GuiEditManager.isEditorOpen()) return
        val (name, detail) = text() ?: return

        val width = PAD * 2f + Draw.width(name) + (detail?.let { GAP + Draw.width(it) } ?: 0f)
        val position = ConfigManager.features.dungeonHud.roomNamePosition
        val scale = position.effectiveScale
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (HEIGHT * scale).toInt()
        val x = position.getAbsX(scaledWidth)
        val y = position.getAbsY(scaledHeight)

        RenderContext.pushPop {
            RenderContext.translate(x.toFloat(), y.toFloat())
            if (scale != 1f) RenderContext.scale(scale)
            val graphics = RenderContext.graphics
            graphics.roundPanel(0f, 0f, width, HEIGHT, RADIUS, Theme.surface(Theme.background, 230), Theme.opaque(Theme.border))
            val textY = (HEIGHT - Draw.LINE_HEIGHT) / 2f
            graphics.string(name, PAD, textY, Theme.opaque(Theme.text))
            if (detail != null) graphics.string(detail, PAD + Draw.width(name) + GAP, textY, Theme.opaque(Theme.textDim))
        }
        GuiEditManager.register(position, HudDefaults.ROOM_NAME, scaledWidth, scaledHeight)
    }

    /** The room's name and its secret count, or what kind of room it is when it has no name. */
    private fun text(): Pair<String, String?>? {
        if (GuiEditManager.isEditorOpen() && !SkyBlockData.inDungeons) return "Room Name" to null
        if (!SkyBlockData.inDungeons) return null
        val room = DungeonScanner.current ?: return null
        val name = room.name
        if (room.matched && name != null) {
            val secrets = name.substringAfterLast('-').toIntOrNull()
            val title = name.substringBeforeLast('-').split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            return title to secrets?.let { if (it == 1) "1 secret" else "$it secrets" }
        }
        return when (room.type) {
            DungeonRoom.Type.ENTRANCE -> "Entrance" to null
            DungeonRoom.Type.PUZZLE -> "Puzzle" to null
            DungeonRoom.Type.TRAP -> "Trap" to null
            DungeonRoom.Type.MINIBOSS -> "Miniboss" to null
            DungeonRoom.Type.FAIRY -> "Fairy" to null
            DungeonRoom.Type.BLOOD -> "Blood" to null
            else -> "Unknown room" to null
        }
    }

    private const val HEIGHT = 16f
    private const val PAD = 6f
    private const val GAP = 6f
    private const val RADIUS = 4f
}
