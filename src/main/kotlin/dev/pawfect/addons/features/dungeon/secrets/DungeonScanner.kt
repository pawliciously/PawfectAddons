package dev.pawfect.addons.features.dungeon.secrets

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.rooms.Room
import net.minecraft.core.BlockPos
import net.minecraft.client.player.LocalPlayer
import org.joml.Vector2i
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import org.slf4j.LoggerFactory

object DungeonScanner {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Secrets")

    private val rooms = HashMap<Pair<Int, Int>, DungeonRoom>()

    private const val GREEN_COLOR: Byte = 30
    private const val WHITE_COLOR: Byte = 34
    private const val RED_COLOR: Byte = 18

    private const val GRID_MIN = -201.0
    private const val GRID_MAX = -8.0

    private const val ABSENT_GRACE = 60
    private const val EDGE_MARGIN = 6

    private var mapEntrance: Pair<Int, Int>? = null
    private var mapRoomSize = 0
    private var physicalEntrance: Pair<Int, Int>? = null
    private var levelKey = 0
    private var absentTicks = 0
    private var ticks = 0

    /** How often the map is looked over for newly opened rooms, and how long each far room gets a turn. */
    private const val DISCOVER_EVERY = 10
    private const val PROBE_TURN = 4

    var current: DungeonRoom? = null
        private set

    /** You're in the dungeon but outside the room grid: the boss. */
    var inBoss = false
        private set

    /** Lines the dungeon map up with the world: the entrance room's corner in each, and the map's room size. */
    class MapAnchor(val physicalX: Int, val physicalZ: Int, val mapX: Int, val mapZ: Int, val roomSize: Int) {
        /** Where a world position falls on the map, in map pixels. */
        fun toMap(x: Double, z: Double): Pair<Double, Double> =
            ((x - physicalX) / 32.0 * (roomSize + 4) + mapX) to ((z - physicalZ) / 32.0 * (roomSize + 4) + mapZ)
    }

    /** Every room this run that you've been in or the map shows opened, once each. */
    fun knownRooms(): Collection<DungeonRoom> = rooms.values.distinct()

    fun anchor(): MapAnchor? {
        val physical = physicalEntrance ?: return null
        val map = mapEntrance ?: return null
        if (mapRoomSize <= 0) return null
        return MapAnchor(physical.first, physical.second, map.first, map.second, mapRoomSize)
    }

    var status: String = "idle"
        private set(value) {
            // Logged when it changes, so a log shows why rooms weren't picked up.
            if (value != field && !value.startsWith("matched") && !value.startsWith("matching")) logger.info("Scanner: {}", value)
            field = value
        }

    fun reset() {
        absentTicks = 0
        rooms.clear()
        mapEntrance = null
        mapRoomSize = 0
        physicalEntrance = null
        current = null
        inBoss = false
        status = "reset"
    }

    private fun mortPos(): net.minecraft.world.phys.Vec3? {
        val level = McCompat.mc.level ?: return null
        level.entitiesForRendering().forEach { entity ->
            if (entity is net.minecraft.world.entity.decoration.ArmorStand) {
                val name = entity.customName
                if (name != null && name.string.contains("Mort")) return entity.position()
            }
        }
        return null
    }

    /** The dungeon map in slot 9, while you have it. */
    fun mapData(): MapItemSavedData? {
        val player = McCompat.player ?: return null
        val level = McCompat.mc.level ?: return null
        val stack = player.inventory.getItem(8)
        if (!stack.`is`(net.minecraft.world.item.Items.FILLED_MAP)) return null
        return MapItem.getSavedData(stack, level)
    }

    fun onTick() {
        val level = McCompat.mc.level
        val key = System.identityHashCode(level)
        if (key != levelKey) {
            levelKey = key
            reset()
        }

        if (!SkyBlockData.inDungeons) {
            absentTicks++
            if (absentTicks == 1 && current != null) logger.info("Scanner: not in a dungeon by the scoreboard, waiting {} ticks", ABSENT_GRACE)
            if (absentTicks < ABSENT_GRACE) return
            if (current != null || rooms.isNotEmpty() || physicalEntrance != null) reset()
            status = "not in a dungeon"
            return
        }
        absentTicks = 0

        val player = McCompat.player ?: return
        if (level == null) return

        // The rooms sit in a 6 by 6 grid of 32 block cells from -200; the boss is somewhere else.
        // Checked first: the map leaves your hotbar in the boss, and nothing below runs without it.
        inBoss = player.x !in GRID_MIN..GRID_MAX || player.z !in GRID_MIN..GRID_MAX
        if (inBoss) {
            current = null
            status = "outside the room grid, in the boss"
            return
        }

        if (!SecretRoomData.loaded) {
            status = "room data not loaded"
            return
        }

        val map = mapData()
        if (map == null) {
            current = null
            status = "no dungeon map in slot 9"
            return
        }

        if (mapEntrance == null || mapRoomSize == 0) {
            val found = DungeonMapUtils.getMapEntrancePosAndRoomSize(map)
            if (found == null) {
                status = "entrance not found on map"
                return
            }
            mapEntrance = found.left().x() to found.left().y()
            mapRoomSize = found.rightInt()
            logger.info("Dungeon entrance at map {} size {}", mapEntrance, mapRoomSize)
        }

        val entrance = mapEntrance ?: return

        if (physicalEntrance == null) {
            val mort = mortPos()
            if (mort != null) {
                val anchor = DungeonMapUtils.getPhysicalRoomPos(mort.x, mort.z)
                physicalEntrance = anchor.x() to anchor.y()
                logger.info("Found Mort at {}, entrance room at {}", mort, physicalEntrance)
            } else {
                physicalEntrance = deriveEntrance(map, entrance, player)
                if (physicalEntrance == null) {
                    status = "anchoring the map, walk into the middle of a room"
                    return
                }
                logger.info("Anchored the entrance room at {} from the map marker", physicalEntrance)
            }
        }

        val physical = physicalEntrance ?: return

        if (ticks++ % DISCOVER_EVERY == 0) discover(map, entrance, physical)
        probeOne(level)

        val physicalRaw = DungeonMapUtils.getPhysicalRoomPos(player.x, player.z)
        val physicalPos = physicalRaw.x() to physicalRaw.y()
        val mapPos = DungeonMapUtils.getMapPosFromPhysical(
            Vector2i(physical.first, physical.second),
            Vector2i(entrance.first, entrance.second),
            mapRoomSize,
            physicalRaw,
        )

        var room = rooms[physicalPos]

        if (room == null) {
            val vanillaType = DungeonMapUtils.getRoomType(map, mapPos)
            if (vanillaType == null || vanillaType == Room.Type.UNKNOWN) {
                status = "unknown room type at map ${mapPos.x()}, ${mapPos.y()}"
                return
            }
            room = createRoom(map, entrance, physical, mapPos, vanillaType, physicalPos)
            if (room == null) {
                status = "no segments"
                return
            }
            logger.info("Entered {} room, shape {}, segments {}", room.type, room.shape, room.segments)
        }

        checkmarks(map, room)

        current = room
        room.tick(level, BlockPos.containing(player.x, player.y, player.z))
        status = when (room.matchState) {
            DungeonRoom.MatchState.MATCHED, DungeonRoom.MatchState.DOUBLE_CHECKING -> "matched ${room.name} (${room.facing})"
            DungeonRoom.MatchState.FAILED -> "no match for ${room.shape} room"
            else -> "matching ${room.shape} room"
        }
    }

    /** Builds the room at [mapPos] from the map's shape for it, and files it under each of its segments. */
    private fun createRoom(
        map: MapItemSavedData,
        entrance: Pair<Int, Int>,
        physical: Pair<Int, Int>,
        mapPos: org.joml.Vector2ic,
        vanillaType: Room.Type,
        physicalPos: Pair<Int, Int>,
    ): DungeonRoom? {
        val segments = if (vanillaType == Room.Type.ROOM) {
            DungeonMapUtils.getPhysicalPosFromMap(
                Vector2i(entrance.first, entrance.second),
                mapRoomSize,
                Vector2i(physical.first, physical.second),
                *DungeonMapUtils.getRoomSegments(map, mapPos, mapRoomSize, vanillaType.color),
            ).map { it.x() to it.y() }.toSet()
        } else {
            setOf(physicalPos)
        }
        if (segments.isEmpty()) return null
        val room = DungeonRoom(typeOf(vanillaType), segments)
        segments.forEach { rooms[it] = room }
        return room
    }

    /**
     * Picks up every room the map shows opened, so they can be matched without walking in.
     * Rooms the map only shows grey (seen, not opened) are left alone.
     */
    private fun discover(map: MapItemSavedData, entrance: Pair<Int, Int>, physical: Pair<Int, Int>) {
        val step = mapRoomSize + 4
        val nw = DungeonMapUtils.getMapPosForNWMostRoom(Vector2i(entrance.first, entrance.second), mapRoomSize)
        for (cx in 0 until 6) for (cz in 0 until 6) {
            val mapPos = Vector2i(nw.x() + cx * step, nw.y() + cz * step)
            if (mapPos.x() !in 0..127 || mapPos.y() !in 0..127) continue
            val vanillaType = DungeonMapUtils.getRoomType(map, mapPos) ?: continue
            if (vanillaType == Room.Type.UNKNOWN) continue
            val at = DungeonMapUtils.getPhysicalPosFromMap(Vector2i(entrance.first, entrance.second), mapRoomSize, Vector2i(physical.first, physical.second), mapPos)
            val physicalPos = at.x() to at.y()
            if (physicalPos.first !in -200..-40 || physicalPos.second !in -200..-40) continue
            if (rooms.containsKey(physicalPos)) continue
            val room = createRoom(map, entrance, physical, mapPos, vanillaType, physicalPos) ?: continue
            logger.info("Found {} room on the map, shape {}, segments {}", room.type, room.shape, room.segments)
        }
        rooms.values.distinct().forEach { if (it !== current) checkmarks(map, it) }
    }

    /** Reads the room's checkmark off the map: white or green is cleared, green on a matched room is every secret found. */
    private fun checkmarks(map: MapItemSavedData, room: DungeonRoom) {
        if (room.greenChecked) return
        val colour = checkmarkColour(map, room)
        if (colour == WHITE_COLOR || colour == GREEN_COLOR) room.mapCleared = true
        if (room.matched && colour == GREEN_COLOR) {
            room.greenChecked = true
            SecretTracker.markAllClaimed(room)
            logger.info("Room {} is green checked, clearing its secrets", room.name)
        }
    }

    /** Matches one far-off room a little, taking turns between them. */
    private fun probeOne(level: net.minecraft.client.multiplayer.ClientLevel) {
        val waiting = rooms.values.distinct().filter { it !== current && it.type.scan && !it.matched && it.matchState == DungeonRoom.MatchState.MATCHING }
        if (waiting.isEmpty()) return
        waiting[(ticks / PROBE_TURN) % waiting.size].probe(level)
    }

    private fun deriveEntrance(
        map: MapItemSavedData,
        entrance: Pair<Int, Int>,
        player: LocalPlayer,
    ): Pair<Int, Int>? {
        val step = mapRoomSize + 4
        if (step <= 4) return null

        val physicalRaw = DungeonMapUtils.getPhysicalRoomPos(player.x, player.z)
        val localX = Math.floorMod(Math.floor(player.x).toInt() - physicalRaw.x(), 32)
        val localZ = Math.floorMod(Math.floor(player.z).toInt() - physicalRaw.y(), 32)
        if (localX < EDGE_MARGIN || localX > 31 - EDGE_MARGIN) return null
        if (localZ < EDGE_MARGIN || localZ > 31 - EDGE_MARGIN) return null

        val mapRoom = DungeonMapUtils.getMapRoomPos(map, Vector2i(entrance.first, entrance.second), mapRoomSize)
            ?: return null
        val deltaX = mapRoom.x() - entrance.first
        val deltaY = mapRoom.y() - entrance.second
        if (deltaX % step != 0 || deltaY % step != 0) return null

        return (physicalRaw.x() - deltaX / step * 32) to (physicalRaw.y() - deltaY / step * 32)
    }

    private fun checkmarkColour(map: MapItemSavedData, room: DungeonRoom): Byte {
        val entrance = mapEntrance ?: return -1
        val physical = physicalEntrance ?: return -1
        val half = mapRoomSize / 2
        room.segments.forEach { segment ->
            val topLeft = DungeonMapUtils.getMapPosFromPhysical(
                Vector2i(physical.first, physical.second),
                Vector2i(entrance.first, entrance.second),
                mapRoomSize,
                Vector2i(segment.first, segment.second),
            )
            for (offset in 0 until half) {
                val colour = DungeonMapUtils.getColor(map, topLeft.x() + half, topLeft.y() + half + offset)
                if (colour == WHITE_COLOR || colour == GREEN_COLOR || colour == RED_COLOR) return colour
            }
        }
        return -1
    }

    private fun typeOf(type: Room.Type): DungeonRoom.Type = when (type) {
        Room.Type.ENTRANCE -> DungeonRoom.Type.ENTRANCE
        Room.Type.PUZZLE -> DungeonRoom.Type.PUZZLE
        Room.Type.TRAP -> DungeonRoom.Type.TRAP
        Room.Type.MINIBOSS -> DungeonRoom.Type.MINIBOSS
        Room.Type.FAIRY -> DungeonRoom.Type.FAIRY
        Room.Type.BLOOD -> DungeonRoom.Type.BLOOD
        else -> DungeonRoom.Type.ROOM
    }

    fun matchedRoom(): DungeonRoom? = current?.takeIf { it.matched }

    fun relativeToActual(room: DungeonRoom, x: Int, y: Int, z: Int): BlockPos =
        DungeonMapUtils.relativeToActual(room.facing!!.vanilla(), Vector2i(room.corner!!.first, room.corner!!.second), BlockPos(x, y, z))

    fun actualToRelative(room: DungeonRoom, pos: BlockPos): BlockPos =
        DungeonMapUtils.actualToRelative(room.facing!!.vanilla(), Vector2i(room.corner!!.first, room.corner!!.second), pos)

    fun relativeToActual(room: DungeonRoom, pos: net.minecraft.world.phys.Vec3): net.minecraft.world.phys.Vec3 =
        DungeonMapUtils.relativeToActual(room.facing!!.vanilla(), Vector2i(room.corner!!.first, room.corner!!.second), pos)

    fun actualToRelative(room: DungeonRoom, pos: net.minecraft.world.phys.Vec3): net.minecraft.world.phys.Vec3 =
        DungeonMapUtils.actualToRelative(room.facing!!.vanilla(), Vector2i(room.corner!!.first, room.corner!!.second), pos)

    fun describe(): List<String> {
        val room = current
        return listOf(
            "Data: ${SecretRoomData.lastLoadReport}",
            "In dungeon: ${SkyBlockData.inDungeons}  map: ${mapData() != null}",
            "Map entrance: ${mapEntrance ?: "none"} size $mapRoomSize",
            "Physical entrance: ${physicalEntrance ?: "none"} (Mort ${if (mortPos() != null) "found" else "missing"})",
            "Absent ticks: $absentTicks",
            "Status: $status",
            "Room: ${room?.name ?: "none"} shape ${room?.shape ?: "-"} facing ${room?.facing ?: "-"}",
            "Segments: ${room?.segments ?: "-"}",
            "Candidates left: ${room?.remainingCandidates ?: 0} after ${room?.checkedBlocks ?: 0} blocks, ${room?.attemptCount ?: 0} failed attempts",
            "Secrets: ${room?.name?.let { SecretRoomData.secretsFor(it).size } ?: 0} (${SecretTracker.claimedCount()} claimed)",
            "Last claim: ${SecretTracker.lastClaimReport}",
        )
    }
}
