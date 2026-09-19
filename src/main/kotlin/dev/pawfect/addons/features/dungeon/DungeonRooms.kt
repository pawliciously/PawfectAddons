package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.utils.McCompat
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

object DungeonRooms {

    const val CELL = 32
    private const val GRID_SHIFT = 8

    private const val SCAN_TOP = 140
    private const val SCAN_BOTTOM = 62

    class Room(
        val originX: Int,
        val originZ: Int,
        val id: String,
        val rotation: Int,
    )

    private val cache = HashMap<Long, Room?>()
    private var cachedCellX = Int.MIN_VALUE
    private var cachedCellZ = Int.MIN_VALUE
    private var cached: Room? = null

    private fun key(cellX: Int, cellZ: Int): Long = (cellX.toLong() shl 32) xor (cellZ.toLong() and 0xFFFFFFFFL)

    private fun roomAt(cellX: Int, cellZ: Int): Room? =
        cache.getOrPut(key(cellX, cellZ)) { runCatching { identify(cellX, cellZ) }.getOrNull() }

    fun nearby(): List<Room> = listOfNotNull(current())

    fun cellIndex(value: Int): Int = Math.floorDiv(value + GRID_SHIFT, CELL)

    fun cellOrigin(index: Int): Int = index * CELL - GRID_SHIFT

    fun current(): Room? {
        val player = McCompat.player ?: return null
        val level = McCompat.mc.level ?: return null

        val cellX = cellIndex(Math.floor(player.x).toInt())
        val cellZ = cellIndex(Math.floor(player.z).toInt())
        if (cellX == cachedCellX && cellZ == cachedCellZ) return cached

        cachedCellX = cellX
        cachedCellZ = cellZ
        cached = roomAt(cellX, cellZ)
        return cached
    }

    fun invalidate() {
        cache.clear()
        cached = null
        cachedCellX = Int.MIN_VALUE
        cachedCellZ = Int.MIN_VALUE
    }

    private fun identify(cellX: Int, cellZ: Int): Room? {
        val level = McCompat.mc.level ?: return null
        val originX = cellOrigin(cellX)
        val originZ = cellOrigin(cellZ)

        val heights = IntArray(CELL * CELL)
        val cursor = BlockPos.MutableBlockPos()
        var solid = 0

        for (dz in 0 until CELL) {
            for (dx in 0 until CELL) {
                var found = -1
                for (y in SCAN_TOP downTo SCAN_BOTTOM) {
                    cursor.set(originX + dx, y, originZ + dz)
                    if (level.getBlockState(cursor).block !== Blocks.AIR) {
                        found = y
                        break
                    }
                }
                if (found >= 0) solid++
                heights[dz * CELL + dx] = found
            }
        }

        if (solid < CELL * CELL / 4) return null

        var bestHash = ""
        var bestRotation = 0
        for (rotation in 0 until 4) {
            val hash = hashOf(heights, rotation)
            if (rotation == 0 || hash < bestHash) {
                bestHash = hash
                bestRotation = rotation
            }
        }

        return Room(originX, originZ, bestHash, bestRotation)
    }

    private fun hashOf(heights: IntArray, rotation: Int): String {
        var value = -0x7ee3623bL
        for (dz in 0 until CELL) {
            for (dx in 0 until CELL) {
                val source = rotate(dx, dz, rotation)
                value = value xor heights[source.second * CELL + source.first].toLong()
                value *= 0x100000001b3L
            }
        }
        return java.lang.Long.toHexString(value)
    }

    private fun rotate(dx: Int, dz: Int, rotation: Int): Pair<Int, Int> {
        val last = CELL - 1
        return when (rotation) {
            1 -> dz to (last - dx)
            2 -> (last - dx) to (last - dz)
            3 -> (last - dz) to dx
            else -> dx to dz
        }
    }

    fun toCanonical(room: Room, x: Int, z: Int): Pair<Int, Int> {
        val dx = x - room.originX
        val dz = z - room.originZ
        return rotate(dx, dz, room.rotation)
    }

    fun fromCanonical(room: Room, cx: Int, cz: Int): Pair<Int, Int> {
        val inverse = (4 - room.rotation) % 4
        val local = rotate(cx, cz, inverse)
        return (room.originX + local.first) to (room.originZ + local.second)
    }

    fun describe(): List<String> {
        val player = McCompat.player ?: return listOf("No player.")
        val room = current()
        val cellX = cellIndex(Math.floor(player.x).toInt())
        val cellZ = cellIndex(Math.floor(player.z).toInt())
        val out = ArrayList<String>()
        out.add("Cell: $cellX, $cellZ  (origin ${cellOrigin(cellX)}, ${cellOrigin(cellZ)})")
        if (room == null) {
            out.add("Room: not identified")
            return out
        }
        out.add("Room id: ${room.id}")
        out.add("Rotation: ${room.rotation}")
        val canonical = toCanonical(room, Math.floor(player.x).toInt(), Math.floor(player.z).toInt())
        out.add("You at canonical: ${canonical.first}, ${canonical.second}")
        out.add("Waypoints here: ${DungeonWaypoints.forRoom(room.id).size}")
        return out
    }
}
