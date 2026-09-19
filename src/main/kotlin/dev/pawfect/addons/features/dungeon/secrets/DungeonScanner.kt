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

    private const val ABSENT_GRACE = 60
    private const val EDGE_MARGIN = 6

    private var mapEntrance: Pair<Int, Int>? = null
    private var mapRoomSize = 0
    private var physicalEntrance: Pair<Int, Int>? = null
    private var levelKey = 0
    private var absentTicks = 0

    var current: DungeonRoom? = null
        private set

    var status: String = "idle"
        private set

    fun reset() {
        absentTicks = 0
        rooms.clear()
        mapEntrance = null
        mapRoomSize = 0
        physicalEntrance = null
        current = null
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

    private fun mapData(): MapItemSavedData? {
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
            if (absentTicks < ABSENT_GRACE) return
            if (current != null || rooms.isNotEmpty() || physicalEntrance != null) reset()
            status = "not in a dungeon"
            return
        }
        absentTicks = 0

        val player = McCompat.player ?: return
        if (level == null) return
        if (!SecretRoomData.loaded) {
            status = "room data not loaded"
            return
        }

        val map = mapData()
        if (map == null) {
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
            val type = typeOf(vanillaType)
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
            if (segments.isEmpty()) {
                status = "no segments"
                return
            }
            room = DungeonRoom(type, segments)
            segments.forEach { rooms[it] = room }
            logger.info("Entered {} room, shape {}, segments {}", type, room.shape, segments)
        }

        if (room.matched && !room.greenChecked) {
            val colour = checkmarkColour(map, room)
            if (colour == GREEN_COLOR) {
                room.greenChecked = true
                SecretTracker.markAllClaimed()
                logger.info("Room {} is green checked, clearing its secrets", room.name)
            }
        }

        current = room
        room.tick(level, BlockPos.containing(player.x, player.y, player.z))
        status = when (room.matchState) {
            DungeonRoom.MatchState.MATCHED, DungeonRoom.MatchState.DOUBLE_CHECKING -> "matched ${room.name} (${room.facing})"
            DungeonRoom.MatchState.FAILED -> "no match for ${room.shape} room"
            else -> "matching ${room.shape} room"
        }
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
