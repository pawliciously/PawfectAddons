package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.features.PanelBackground
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.DungeonParty.DungeonClass
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.gpu.MenuBackgroundRenderState
import dev.pawfect.addons.ui.gpu.UiPipelines
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
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import org.joml.Matrix3x2f
import org.slf4j.LoggerFactory
import kotlin.math.exp

/**
 * The dungeon map, drawn from the vanilla map in your hotbar and nothing else: the rooms it has
 * revealed, its checkmarks, and its player markers. On top of that, the names and secret counts
 * of rooms you've been in, and your teammates' heads turned the way they face. A head is only
 * named when it's certain who it is: a teammate close enough to be loaded, or a map marker whose
 * number matches its teammate's place in the tab list.
 */
object DungeonMap {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Map")

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.dungeonHud)

    /** A player on the map, in map pixels, facing [yaw]. [name] is null for a marker we can't put a name to. */
    class Marker(val name: String?, val x: Double, val y: Double, val yaw: Float, val self: Boolean, val dungeonClass: DungeonClass?, val skin: PlayerSkin?)

    private val TEXTURE: Identifier = Identifier.fromNamespaceAndPath("pawfectaddons", "dungeon_map")
    private var texture: DynamicTexture? = null
    private var shownHash = 0
    private var failed = false

    private val startedAt = System.nanoTime()

    /** Where each map marker is drawn, easing toward where the map says, so heads glide between updates. */
    private val eased = HashMap<String, FloatArray>()
    private var easedAt = 0L
    private var easedMap: MapItemSavedData? = null

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
                background(RenderContext.graphics, x, y, size.toFloat())
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

        background(graphics, x, y, size)
        val f = frame(x, y, size, anchor)
        val k = f.k
        fun sx(mapX: Double) = f.sx(mapX)
        fun sy(mapY: Double) = f.sy(mapY)

        // Always the whole map, so it never zooms as rooms are revealed.
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, f.ox.toInt(), f.oy.toInt(), 0f, 0f, (MAP * k).toInt(), (MAP * k).toInt(), MAP, MAP, MAP, MAP, -1)

        // Names and secret counts of rooms you've been in, over the middle of the room.
        if (config.mapRoomNames || config.mapSecrets) DungeonScanner.knownRooms().forEach { room ->
            val name = room.name?.takeIf { room.matched } ?: return@forEach
            val corners = room.segments.map { anchor.toMap(it.first.toDouble(), it.second.toDouble()) }
            val cx = corners.map { it.first }.average() + anchor.roomSize / 2.0
            val cy = corners.map { it.second }.average() + anchor.roomSize / 2.0
            val span = ((corners.maxOf { it.first } - corners.minOf { it.first } + anchor.roomSize) * k).toFloat() - 2f

            val lines = ArrayList<Triple<String, Float, Int>>()
            if (config.mapRoomNames) {
                val title = name.substringBeforeLast('-').split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
                val scale = minOf(0.6f, span / McCompat.font.width(title).coerceAtLeast(1))
                if (scale >= 0.3f) lines += Triple(title, scale, ROOM_TEXT)
            }
            if (config.mapSecrets) RoomNameOverlay.secretsIn(room)?.let { (found, total) ->
                val count = "$found/$total"
                val color = when {
                    room.greenChecked -> SECRETS_GREEN
                    room.mapCleared -> SECRETS_WHITE
                    else -> SECRETS_GRAY
                }
                lines += Triple(count, minOf(0.75f, span / McCompat.font.width(count).coerceAtLeast(1)), color)
            }
            if (lines.isEmpty()) return@forEach
            var ty = sy(cy) - (lines.sumOf { (8f * it.second).toDouble() }.toFloat() + (lines.size - 1)) / 2f
            lines.forEach { (line, scale, color) ->
                text(graphics, line, sx(cx) - McCompat.font.width(line) * scale / 2f, ty, scale, color)
                ty += 8f * scale + 1f
            }
        }

        val head = f.head
        val markers = markers(map, anchor)
        val hovered = hit(f, markers, mouseX, mouseY, clickable)
        markers.sortedBy { it.self }.forEach { marker ->
            val over = hovered != null && marker.name == hovered
            val ring = when {
                over -> 0xFFFFFFFF.toInt()
                marker.self -> 0xFFECE9F7.toInt()
                else -> 0xFF000000.toInt() or (marker.dungeonClass?.color ?: 0x8F89AA)
            }
            val s = if (over) head * 1.25f else head
            // Turned with the player: the top of the head points the way they face.
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(sx(marker.x), sy(marker.y))
            pose.rotate(Math.toRadians(marker.yaw + 180.0).toFloat())
            graphics.rect(-s / 2f - 1f, -s / 2f - 1f, s + 2f, s + 2f, ring)
            val skin = marker.skin
            if (skin != null) {
                pose.translate(-s / 2f, -s / 2f)
                pose.scale(s / 8f, s / 8f)
                PlayerFaceExtractor.extractRenderState(graphics, skin, 0, 0, 8)
            } else {
                graphics.rect(-s / 2f, -s / 2f, s, s, UNKNOWN_HEAD)
            }
            pose.popMatrix()
        }
        if (hovered != null) {
            val name = hovered
            val w = McCompat.font.width(name) + 6f
            graphics.roundPanel(mouseX.toFloat() + 8f, mouseY.toFloat() - 14f, w, 12f, 3f, PANEL, LINE)
            text(graphics, name, mouseX.toFloat() + 11f, mouseY.toFloat() - 12f, 1f, TEXT)
        }
        return hovered
    }

    /** The panel behind the map, in the same styles as chat and the scoreboard. */
    private fun background(graphics: GuiGraphicsExtractor, x: Float, y: Float, size: Float) {
        config.sanitize()
        val opacity = config.mapOpacity.coerceIn(0f, 1f)
        val radius = config.mapRadius.coerceIn(0, 12)
        val style = config.mapBackground
        val panel = Theme.palette.panel
        val back = Theme.palette.background
        val accent = Theme.palette.accent
        if (style == PanelBackground.GLASS) {
            Shapes.rect(graphics, x, y, size, size, radius.toFloat(), argb(Theme.mix(panel, 0xFFFFFF, 0.04f), opacity), argb(back, opacity))
        } else {
            val state = MenuBackgroundRenderState(
                Matrix3x2f(graphics.pose()),
                x,
                y,
                x + size,
                y + size,
                argb(Theme.mix(panel, 0x000000, 0.1f), opacity),
                0xFF000000.toInt() or (back and 0xFFFFFF),
                accent,
                config.mapStrength.coerceIn(0.1f, 1f),
                (System.nanoTime() - startedAt) / 1_000_000_000f,
                style.style.toFloat(),
                UiPipelines.INVENTORY_BACKGROUND,
                radius,
            )
            (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addGuiElement(state)
        }
        if (config.mapOutline) Shapes.outline(graphics, x, y, size, size, radius.toFloat(), argb(accent, 0.35f * opacity + 0.1f))
    }

    /** Where the map sits inside its square, and how big a head is. */
    private class Frame(val ox: Float, val oy: Float, val k: Float, val head: Float) {
        fun sx(mapX: Double) = (ox + mapX * k).toFloat()
        fun sy(mapY: Double) = (oy + mapY * k).toFloat()
    }

    private fun frame(x: Float, y: Float, size: Float, anchor: DungeonScanner.MapAnchor): Frame {
        val k = (size - PAD * 2f) / MAP
        return Frame(x + PAD, y + PAD, k, (anchor.roomSize * k * 0.65f).coerceIn(6f, 14f))
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
     * You and your teammates close enough to be loaded, exactly, then the map's markers for
     * everyone else. Hypixel marks you with the frame icon and numbers everyone else's marker by
     * their place in the tab list; that numbering is only trusted while every loaded teammate's
     * marker sits where they actually are.
     */
    fun markers(map: MapItemSavedData, anchor: DungeonScanner.MapAnchor): List<Marker> {
        val self = McCompat.player ?: return emptyList()
        val level = McCompat.mc.level ?: return emptyList()
        val partial = McCompat.mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val selfName = self.name.string
        val out = ArrayList<Marker>()
        val selfPos = self.getPosition(partial)
        val (selfX, selfY) = anchor.toMap(selfPos.x, selfPos.z)
        out += Marker(selfName, selfX, selfY, self.getViewYRot(partial), true, DungeonParty.member(selfName)?.dungeonClass, self.skin)

        val living = DungeonParty.ordered().filter { !it.dead && !it.name.equals(selfName, ignoreCase = true) }
        val loaded = HashMap<String, Marker>()
        level.players().forEach { player ->
            val member = living.firstOrNull { it.name.equals(player.name.string, ignoreCase = true) } ?: return@forEach
            val pos = player.getPosition(partial)
            val (mx, my) = anchor.toMap(pos.x, pos.z)
            val marker = Marker(member.name, mx, my, player.getViewYRot(partial), false, member.dungeonClass, player.skin)
            loaded[member.name.lowercase()] = marker
            out += marker
        }

        // The map's markers in the order Hypixel sent them ("icon-0", "icon-1", ...), minus your own.
        val icons = map.decorations.withIndex().filter { (_, decoration) ->
            val type = decoration.type().value()
            type != MapDecorationTypes.FRAME.value() && type != MapDecorationTypes.PLAYER_OFF_MAP.value() && type != MapDecorationTypes.PLAYER_OFF_LIMITS.value()
        }
        val near = (anchor.roomSize + 4.0) * (anchor.roomSize + 4.0)
        fun distSq(marker: Marker, decoration: MapDecoration): Double {
            val dx = marker.x - mapX(decoration)
            val dy = marker.y - mapY(decoration)
            return dx * dx + dy * dy
        }

        val numbered = icons.isNotEmpty() && icons.size <= living.size && icons.all { (index, decoration) ->
            val member = living.getOrNull(index) ?: return@all false
            val seen = loaded[member.name.lowercase()]
            seen == null || distSq(seen, decoration) <= near
        }
        val now = System.nanoTime()
        if (easedMap !== map) eased.clear()
        easedMap = map
        val step = (1.0 - exp(-(now - easedAt).coerceIn(0L, 200_000_000L) / EASE_NANOS)).toFloat()
        easedAt = now

        if (numbered) {
            icons.forEach { (index, decoration) ->
                val member = living[index]
                if (member.name.lowercase() in loaded) return@forEach
                val (ex, ey, yaw) = ease(member.name.lowercase(), decoration, step)
                val skin = McCompat.mc.connection?.getPlayerInfoIgnoreCase(member.name)?.skin
                out += Marker(member.name, ex, ey, yaw, false, member.dungeonClass, skin)
            }
            return out
        }

        // No numbering to go by: pair each loaded teammate with their nearest marker, and only
        // name what's left when it's one marker for one teammate.
        val claimed = HashSet<Int>()
        loaded.values.forEach { marker ->
            icons.filter { it.index !in claimed }
                .minByOrNull { distSq(marker, it.value) }
                ?.takeIf { distSq(marker, it.value) <= near }
                ?.let { claimed += it.index }
        }
        val missing = living.filter { it.name.lowercase() !in loaded }
        val leftover = icons.filter { it.index !in claimed }.take(if (living.isEmpty()) icons.size else missing.size)
        leftover.forEach { (index, decoration) ->
            val member = if (leftover.size == 1 && missing.size == 1) missing.first() else null
            val (ex, ey, yaw) = ease(member?.name?.lowercase() ?: "#$index", decoration, step)
            val skin = member?.let { McCompat.mc.connection?.getPlayerInfoIgnoreCase(it.name)?.skin }
            out += Marker(member?.name, ex, ey, yaw, false, member?.dungeonClass, skin)
        }
        return out
    }

    /** Moves a marker's drawn spot and facing part of the way to where the map has it. */
    private fun ease(key: String, decoration: MapDecoration, step: Float): Triple<Double, Double, Float> {
        val tx = mapX(decoration).toFloat()
        val ty = mapY(decoration).toFloat()
        val tyaw = decoration.rot() * 22.5f
        val at = eased[key]
        if (at == null || (at[0] - tx) * (at[0] - tx) + (at[1] - ty) * (at[1] - ty) > JUMP_SQ) {
            eased[key] = floatArrayOf(tx, ty, tyaw)
            return Triple(tx.toDouble(), ty.toDouble(), tyaw)
        }
        at[0] += (tx - at[0]) * step
        at[1] += (ty - at[1]) * step
        val turn = ((tyaw - at[2]) % 360f + 540f) % 360f - 180f
        at[2] += turn * step
        return Triple(at[0].toDouble(), at[1].toDouble(), at[2])
    }

    private fun mapX(decoration: MapDecoration) = decoration.x() / 2.0 + 64.0
    private fun mapY(decoration: MapDecoration) = decoration.y() / 2.0 + 64.0

    // ------------------------------------------------------------------ the map texture

    /** Copies the map's pixels into the texture when they change, restyling a few colours. */
    private fun refresh(map: MapItemSavedData) {
        val colors = map.colors
        val hash = colors.contentHashCode()
        if (texture != null && hash == shownHash) return
        shownHash = hash
        val tex = texture ?: DynamicTexture({ TEXTURE.toString() }, NativeImage(MAP, MAP, true)).also {
            McCompat.mc.textureManager.register(TEXTURE, it)
            texture = it
        }
        val image = tex.pixels ?: return
        for (py in 0 until MAP) for (px in 0 until MAP) {
            val packed = colors[px + py * MAP].toInt() and 0xFF
            image.setPixel(px, py, if (packed == 0) 0 else restyle(packed))
        }
        tex.upload()
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

    private fun argb(rgb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

    private val ROOM = MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.LOWEST).toInt() and 0xFF
    private val UNOPENED = MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.NORMAL).toInt() and 0xFF

    /** The map is 128 pixels a side. */
    private const val MAP = 128

    /** How fast a map marker catches up with the map, and how far it can be before it just jumps. */
    private const val EASE_NANOS = 90_000_000.0
    private const val JUMP_SQ = 40f * 40f

    private const val HUD_SIZE = 128f
    private const val PAD = 4f

    private const val PANEL = 0xE6141221.toInt()
    private const val LINE = 0x30ECE9F7
    private const val TEXT = 0xFFECE9F7.toInt()
    private const val MUTED = 0xFF8F89AA.toInt()
    private const val ROOM_TEXT = 0xFFE8E2F5.toInt()
    private const val SECRETS_GRAY = 0xFFAAAAAA.toInt()
    private const val SECRETS_WHITE = 0xFFFFFFFF.toInt()
    private const val SECRETS_GREEN = 0xFF55FF55.toInt()
    private const val UNKNOWN_HEAD = 0xFF5A5670.toInt()
}
