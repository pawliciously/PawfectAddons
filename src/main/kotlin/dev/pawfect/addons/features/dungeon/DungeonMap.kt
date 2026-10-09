package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.DungeonParty.DungeonClass
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import org.slf4j.LoggerFactory

/**
 * The dungeon map, drawn from the vanilla map in your hotbar and nothing else: the rooms it has
 * revealed, its checkmarks, and its player markers. On top of that, the names of rooms you've
 * been in and your teammates' heads where the map marks them. A head is only named when it's
 * certain who it is: a teammate close enough to be loaded, or the one marker left over for the
 * one teammate who isn't.
 */
object DungeonMap {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Map")

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.dungeonHud)

    /** A player on the map, in map pixels. [name] is null for a marker we can't put a name to. */
    class Marker(val name: String?, val x: Double, val y: Double, val self: Boolean, val dungeonClass: DungeonClass?, val skin: PlayerSkin?)

    private val TEXTURE: Identifier = Identifier.fromNamespaceAndPath("pawfectaddons", "dungeon_map")
    private var texture: DynamicTexture? = null
    private var shownHash = 0
    private var failed = false

    /** The part of the map with rooms on it, in map pixels. */
    private var boundsX = 0
    private var boundsY = 0
    private var boundsW = 128
    private var boundsH = 128

    /** The map can be drawn: in a dungeon, out of the boss, holding the map, lined up with the world. */
    fun available(): Boolean =
        !failed && SkyBlockData.inDungeons && !DungeonScanner.inBoss && DungeonScanner.mapData() != null && DungeonScanner.anchor() != null

    // ------------------------------------------------------------------ HUD

    fun renderHud() {
        if (!config.map || McCompat.hideGui || !RenderContext.isActive) return
        val screen = McCompat.screen
        if (screen != null && screen !is ChatScreen && !GuiEditManager.isEditorOpen()) return
        val position = ConfigManager.features.dungeonHud.mapPosition
        val scale = position.effectiveScale
        val size = (HUD_SIZE * scale).toInt()
        if (!available()) {
            if (GuiEditManager.isEditorOpen()) {
                val x = position.getAbsX(size).toFloat()
                val y = position.getAbsY(size).toFloat()
                RenderContext.graphics.roundPanel(x, y, size.toFloat(), size.toFloat(), 6f, PANEL, LINE)
                text(RenderContext.graphics, "Dungeon Map", x + 6f, y + 6f, 1f, MUTED)
                GuiEditManager.register(position, HudDefaults.DUNGEON_MAP, size, size)
            }
            return
        }
        val x = position.getAbsX(size).toFloat()
        val y = position.getAbsY(size).toFloat()
        draw(RenderContext.graphics, x, y, size.toFloat(), -1.0, -1.0, null)
        GuiEditManager.register(position, HudDefaults.DUNGEON_MAP, size, size)
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the map in a square at [x], [y]. With [clickable], heads it says yes to light up
     * under the mouse; the name of the one under the mouse is returned.
     */
    fun draw(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float, mouseX: Double, mouseY: Double, clickable: ((String) -> Boolean)?): String? = try {
        drawMap(graphics, x, y, size, mouseX, mouseY, clickable)
    } catch (t: Throwable) {
        failed = true
        logger.error("Dungeon map failed to draw, turning it off for this session", t)
        null
    }

    private fun drawMap(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float, mouseX: Double, mouseY: Double, clickable: ((String) -> Boolean)?): String? {
        val map = DungeonScanner.mapData() ?: return null
        val anchor = DungeonScanner.anchor() ?: return null
        refresh(map)

        graphics.roundPanel(x, y, size, size, 6f, PANEL, LINE)
        val f = frame(x, y, size, anchor)
        val k = f.k
        fun sx(mapX: Double) = f.sx(mapX)
        fun sy(mapY: Double) = f.sy(mapY)

        graphics.blit(
            RenderPipelines.GUI_TEXTURED, TEXTURE, sx(boundsX.toDouble()).toInt(), sy(boundsY.toDouble()).toInt(),
            boundsX.toFloat(), boundsY.toFloat(), (boundsW * k).toInt(), (boundsH * k).toInt(), boundsW, boundsH, 128, 128, -1,
        )

        // Names of rooms you've been in, over the middle of the room.
        if (config.mapRoomNames) DungeonScanner.knownRooms().forEach { room ->
            val name = room.name?.takeIf { room.matched } ?: return@forEach
            val title = name.substringBeforeLast('-').split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            val corners = room.segments.map { anchor.toMap(it.first.toDouble(), it.second.toDouble()) }
            val cx = corners.map { it.first }.average() + anchor.roomSize / 2.0
            val cy = corners.map { it.second }.average() + anchor.roomSize / 2.0
            val spanX = (corners.maxOf { it.first } - corners.minOf { it.first } + anchor.roomSize) * k
            val scale = minOf(0.6f, (spanX.toFloat() - 2f) / McCompat.font.width(title).coerceAtLeast(1))
            if (scale < 0.3f) return@forEach
            text(graphics, title, sx(cx) - McCompat.font.width(title) * scale / 2f, sy(cy) - 4f * scale, scale, ROOM_TEXT)
        }

        val head = f.head
        val markers = markers(map, anchor)
        val hovered = hit(f, markers, mouseX, mouseY, clickable)
        markers.sortedBy { it.self }.forEach { marker ->
            val px = sx(marker.x)
            val py = sy(marker.y)
            val over = hovered != null && marker.name == hovered
            val ring = when {
                over -> 0xFFFFFFFF.toInt()
                marker.self -> 0xFFECE9F7.toInt()
                else -> 0xFF000000.toInt() or (marker.dungeonClass?.color ?: 0x8F89AA)
            }
            val s = if (over) head * 1.25f else head
            graphics.rect(px - s / 2f - 1f, py - s / 2f - 1f, s + 2f, s + 2f, ring)
            val skin = marker.skin
            if (skin != null) {
                val pose = graphics.pose()
                pose.pushMatrix()
                pose.translate(px - s / 2f, py - s / 2f)
                pose.scale(s / 8f, s / 8f)
                PlayerFaceExtractor.extractRenderState(graphics, skin, 0, 0, 8)
                pose.popMatrix()
            } else {
                graphics.rect(px - s / 2f, py - s / 2f, s, s, UNKNOWN_HEAD)
            }
        }
        if (hovered != null) {
            val name = hovered
            val w = McCompat.font.width(name) + 6f
            graphics.roundPanel(mouseX.toFloat() + 8f, mouseY.toFloat() - 14f, w, 12f, 3f, PANEL, LINE)
            text(graphics, name, mouseX.toFloat() + 11f, mouseY.toFloat() - 12f, 1f, TEXT)
        }
        return hovered
    }

    /** Where the map sits inside its square, and how big a head is. */
    private class Frame(val ox: Float, val oy: Float, val k: Float, val head: Float) {
        fun sx(mapX: Double) = (ox + (mapX - boundsX) * k).toFloat()
        fun sy(mapY: Double) = (oy + (mapY - boundsY) * k).toFloat()
    }

    private fun frame(x: Float, y: Float, size: Float, anchor: DungeonScanner.MapAnchor): Frame {
        val inner = size - PAD * 2f
        val k = inner / maxOf(boundsW, boundsH)
        return Frame(x + PAD + (inner - boundsW * k) / 2f, y + PAD + (inner - boundsH * k) / 2f, k, (anchor.roomSize * k * 0.5f).coerceIn(6f, 14f))
    }

    /** The clickable head under the mouse, by the same layout the map is drawn with. */
    private fun hit(f: Frame, markers: List<Marker>, mouseX: Double, mouseY: Double, clickable: ((String) -> Boolean)?): String? {
        if (clickable == null) return null
        val half = f.head / 2f
        return markers.firstOrNull { marker ->
            val name = marker.name ?: return@firstOrNull false
            if (marker.self || !clickable(name)) return@firstOrNull false
            val px = f.sx(marker.x)
            val py = f.sy(marker.y)
            mouseX >= px - half && mouseX <= px + half && mouseY >= py - half && mouseY <= py + half
        }?.name
    }

    /** The clickable head under the mouse for a map drawn in this square, or null. */
    fun headAt(x: Float, y: Float, size: Float, mouseX: Double, mouseY: Double, clickable: (String) -> Boolean): String? {
        val map = DungeonScanner.mapData() ?: return null
        val anchor = DungeonScanner.anchor() ?: return null
        refresh(map)
        return hit(frame(x, y, size, anchor), markers(map, anchor), mouseX, mouseY, clickable)
    }

    // ------------------------------------------------------------------ players

    /**
     * You, your teammates close enough to be loaded (exact), and the map's other markers. A
     * leftover marker gets a name only when exactly one teammate is unaccounted for.
     */
    fun markers(map: MapItemSavedData, anchor: DungeonScanner.MapAnchor): List<Marker> {
        val self = McCompat.player ?: return emptyList()
        val level = McCompat.mc.level ?: return emptyList()
        val out = ArrayList<Marker>()
        val (selfX, selfY) = anchor.toMap(self.x, self.z)
        out += Marker(self.name.string, selfX, selfY, true, DungeonParty.member(self.name.string)?.dungeonClass, self.skin)

        val placed = HashSet<String>()
        placed += self.name.string.lowercase()
        val alive = DungeonParty.members().filter { !it.dead && it.name.lowercase() !in placed }
        level.players().forEach { player ->
            val member = alive.firstOrNull { it.name.equals(player.name.string, ignoreCase = true) } ?: return@forEach
            val (mx, my) = anchor.toMap(player.x, player.z)
            out += Marker(member.name, mx, my, false, member.dungeonClass, player.skin)
            placed += member.name.lowercase()
        }

        // Map markers that aren't anyone we've already placed.
        val leftover = map.decorations.filter { decoration ->
            val type = decoration.type()
            if (type == MapDecorationTypes.PLAYER_OFF_MAP || type == MapDecorationTypes.PLAYER_OFF_LIMITS) return@filter false
            val mx = decoration.x() / 2.0 + 64.0
            val my = decoration.y() / 2.0 + 64.0
            out.none { (it.x - mx) * (it.x - mx) + (it.y - my) * (it.y - my) <= MARKER_MATCH_SQ }
        }
        val missing = alive.filter { it.name.lowercase() !in placed }
        leftover.forEach { decoration ->
            val mx = decoration.x() / 2.0 + 64.0
            val my = decoration.y() / 2.0 + 64.0
            val member = if (leftover.size == 1 && missing.size == 1) missing.first() else null
            val skin = member?.let { McCompat.mc.connection?.getPlayerInfoIgnoreCase(it.name)?.skin }
            out += Marker(member?.name, mx, my, false, member?.dungeonClass, skin)
        }
        return out
    }

    // ------------------------------------------------------------------ the map texture

    /** Copies the map's pixels into the texture when they change, restyling a few colours. */
    private fun refresh(map: MapItemSavedData) {
        val colors = map.colors
        val hash = colors.contentHashCode()
        if (texture != null && hash == shownHash) return
        shownHash = hash
        val tex = texture ?: DynamicTexture({ TEXTURE.toString() }, NativeImage(128, 128, true)).also {
            McCompat.mc.textureManager.register(TEXTURE, it)
            texture = it
        }
        val image = tex.pixels ?: return
        var minX = 128
        var minY = 128
        var maxX = -1
        var maxY = -1
        for (py in 0 until 128) for (px in 0 until 128) {
            val packed = colors[px + py * 128].toInt() and 0xFF
            if (packed == 0) {
                image.setPixel(px, py, 0)
                continue
            }
            image.setPixel(px, py, restyle(packed))
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
        }
        tex.upload()
        if (maxX >= minX) {
            boundsX = minX
            boundsY = minY
            boundsW = maxX - minX + 1
            boundsH = maxY - minY + 1
        }
    }

    /**
     * Vanilla colours, except plain rooms and rooms not opened yet. The entrance, blood and the
     * checkmarks share colours with each other, so those stay exactly as the map has them.
     */
    private fun restyle(packed: Int): Int = when (packed) {
        ROOM -> 0xFF7A5232.toInt()
        UNOPENED -> 0xFF3B3848.toInt()
        else -> MapColor.getColorFromPackedId(packed)
    }

    private fun text(graphics: GuiGraphicsExtractor, text: String, x: Float, y: Float, scale: Float, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        if (scale != 1f) pose.scale(scale, scale)
        graphics.text(McCompat.font, Component.literal(text), 0, 0, color, true)
        pose.popMatrix()
    }

    private val ROOM = MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.LOWEST).toInt() and 0xFF
    private val UNOPENED = MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.NORMAL).toInt() and 0xFF

    /** How close, squared in map pixels, a map marker has to be to a placed player to be them. */
    private const val MARKER_MATCH_SQ = 9.0

    private const val HUD_SIZE = 128f
    private const val PAD = 5f

    private const val PANEL = 0xE6141221.toInt()
    private const val LINE = 0x30ECE9F7
    private const val TEXT = 0xFFECE9F7.toInt()
    private const val MUTED = 0xFF8F89AA.toInt()
    private const val ROOM_TEXT = 0xFFE8E2F5.toInt()
    private const val UNKNOWN_HEAD = 0xFF5A5670.toInt()
}
