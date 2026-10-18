package dev.pawfect.addons.features.dev

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector2i
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Dev only. Saves every dungeon room the world has loaded, unrotated, for the DungeonSim practice
 * server, and keeps a list of the rooms still to get. Nothing shows during the run: captures are
 * held until you leave the dungeon, so it never tells you about rooms you haven't opened.
 *
 * Rooms sit in a 6 by 6 grid of 32 block cells from -200, 31 blocks of room and a 1 block gap.
 * Cells of one room are joined by blocks across that gap; separate rooms only meet at doorways.
 */
object RoomCapture {

    private val logger = LoggerFactory.getLogger("PawfectAddons/RoomCapture")

    private const val GRID = -200
    private const val CELL = 32
    private const val CELLS = 6

    /**
     * Cells across this floor's grid; beyond it is boss and lobby build, not rooms. F1 (4x5) and F4 (6x5) get their
     * longer side both ways.
     */
    private fun gridFor(floor: String?): Int = when (floor?.drop(1)) {
        "" -> 4
        "1", "2", "3" -> 5
        else -> CELLS
    }

    private const val SCAN_EVERY = 10
    private const val ABSENT_GRACE = 60

    private var cells = CELLS

    /** Blocks read per tick while copying a room, so a 2x2 takes about a second. */
    private const val READ_BUDGET = 60_000

    /**
     * A room matches when under 5% of its matchable blocks disagree with the data (blown walls, broken blocks,
     * puzzle layouts: a water board is 1.4% off its data) and the next best room is well behind.
     */
    private const val MAX_MISS_RATE = 0.05
    private const val MIN_HITS = 50

    /** The closest two rooms in the data (Banners and Sloth) are 5.5% apart, so the runner up must trail by this, or by the winner's own rate. */
    private const val MIN_LEAD = 0.02

    /**
     * Wrong rooms are dropped part way once they're clearly off. Kept loose, since the blocks that differ (a
     * puzzle's layout) can bunch up in a few layers and push a right room's running rate up for a while.
     */
    private const val DROP_MISS_RATE = 0.2
    private const val DROP_MIN_MISSES = 200

    private const val WATER_BOARD = "water-puzzle"
    private const val WATER_BOARD_LAYOUTS = 4

    private val SHAPE_ORDER = listOf("1x1", "1x2", "1x3", "1x4", "2x2", "l-shape", "puzzle", "trap", "miniboss", "special")
    private val SPECIAL_ROOMS = listOf("entrance", "blood", "fairy")
    private val ONE_BY_ONE_POOLS = listOf("1x1", "puzzle", "trap", "miniboss")

    private val dir: Path get() = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev").resolve("rooms")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PawfectAddons-RoomCapture").apply { isDaemon = true } }

    val enabled: Boolean get() = ConfigManager.features.dev.roomCapture

    /**
     * The real Hypixel, by server address. Practice servers like bloodcamp.net fake Hypixel's location packet
     * (so HypixelLocation says yes) but their rooms aren't worth saving.
     */
    fun onHypixel(): Boolean = McCompat.mc.currentServer?.ip?.contains("hypixel", ignoreCase = true) == true

    private data class Cell(val cx: Int, val cz: Int) {
        val x: Int get() = GRID + cx * CELL
        val z: Int get() = GRID + cz * CELL
        val key: Pair<Int, Int> get() = x to z
    }

    /** [source] is "dungeondata" for rooms imported by DungeonSim's tools; any real capture replaces those. */
    private class IndexEntry(val clean: Boolean, val floor: String?, val capturedAt: String, val missRate: Double, val source: String? = null)

    /** Saved captures by "shape/room" (water board layouts as "puzzle/water-puzzle#layout-N"). Only touched on [worker]. */
    private var index: MutableMap<String, IndexEntry>? = null

    /** What the last finished run left missing, for /pa dev rooms. */
    @Volatile
    private var lastMissing: List<String>? = null

    // This run
    private var levelKey = 0
    private var inRun = false
    private var absentTicks = 0
    private var ticks = 0
    private val exists = HashMap<Cell, Boolean>()
    private val joined = HashMap<Pair<Cell, Cell>, Boolean>()
    private val started = HashSet<Cell>()
    private var job: ReadJob? = null
    private val results = ArrayList<Result>()

    /** Map room types seen this run by cell, to name the entrance, blood and fairy rooms, which have no data to match. */
    private val mapTypes = HashMap<Pair<Int, Int>, DungeonRoom.Type>()

    /** Set by /pa dev rooms grab: copy everything loaded right now, a whole room per tick, then save straight away. */
    private var grabbing = false

    fun grab() {
        if (!SkyBlockData.inDungeons) {
            ChatUtils.chat("§cYou're not in a dungeon.")
            return
        }
        grabbing = true
        ChatUtils.chat("§7Grabbing every loaded room...")
    }

    fun onTick() {
        if (!DevAccount.isDev() || (!grabbing && (!enabled || !onHypixel()))) return
        val level = McCompat.mc.level
        val key = System.identityHashCode(level)
        if (key != levelKey) {
            levelKey = key
            // The start countdown warps you to another server, so a new world isn't the end of the run.
            // Only the scoreboard saying you've left the dungeon is.
            job = null
            exists.clear()
            joined.clear()
            started.clear()
        }
        if (level == null) return

        if (!SkyBlockData.inDungeons) {
            if (inRun && ++absentTicks >= ABSENT_GRACE) finishRun()
            return
        }
        absentTicks = 0
        if (!inRun) {
            inRun = true
            logger.info("Room capture started for {}", SkyBlockData.dungeonFloor)
        }
        cells = gridFor(SkyBlockData.dungeonFloor)
        SecretRoomData.load()
        if (!SecretRoomData.loaded) return

        rememberMapTypes()

        job?.let { current ->
            if (!current.step(level, if (grabbing) Int.MAX_VALUE else READ_BUDGET)) {
                logger.info("Room at {} unloaded mid copy, will retry", current.cells)
                started.removeAll(current.cells)
                job = null
            } else if (current.finished) {
                job = null
                val floor = SkyBlockData.dungeonFloor
                val opened = current.cells.any { DungeonScanner.knownRooms().any { room -> it.key in room.segments } }
                worker.execute { runCatching { process(current, floor, opened)?.let { synchronized(results) { results.add(it) } } }.onFailure { logger.error("Room capture failed", it) } }
            }
            return
        }

        if (!grabbing && ticks++ % SCAN_EVERY != 0) return
        findMort(level)
        surveyCells(level)
        val next = nextRoom()
        if (next != null) {
            started.addAll(next)
            job = ReadJob(next, level)
        } else if (grabbing) {
            finishGrab()
        }
    }

    private fun finishGrab() {
        grabbing = false
        val grid = (0 until cells).flatMap { cx -> (0 until cells).map { cz -> Cell(cx, cz) } }
        val unloaded = grid.count { it !in exists }
        val waiting = grid.count { exists[it] == true && it !in started }
        ChatUtils.chat("§7Grab done. §f$unloaded §7cells weren't loaded, §f$waiting §7room cells are waiting on them. Saving...")
        val types = HashMap(mapTypes)
        worker.execute { runCatching { save(types, final = false) }.onFailure { logger.error("Saving room captures failed", it) } }
    }

    /** Mort stands in the entrance room from the start, before the map shows anything. */
    private fun findMort(level: ClientLevel) {
        if (DungeonRoom.Type.ENTRANCE in mapTypes.values) return
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || entity.customName?.string?.contains("Mort") != true) continue
            val cell = DungeonMapUtils.getPhysicalRoomPos(entity.x, entity.z)
            mapTypes[cell.x() to cell.y()] = DungeonRoom.Type.ENTRANCE
            return
        }
    }

    private fun rememberMapTypes() {
        DungeonScanner.knownRooms().forEach { room ->
            if (room.type == DungeonRoom.Type.ENTRANCE || room.type == DungeonRoom.Type.BLOOD || room.type == DungeonRoom.Type.FAIRY) {
                room.segments.forEach { mapTypes[it] = room.type }
            }
        }
    }

    // Finding rooms

    private fun surveyCells(level: ClientLevel) {
        for (cx in 0 until cells) for (cz in 0 until cells) {
            val cell = Cell(cx, cz)
            if (cell !in exists && loaded(level, cell.x, cell.z, cell.x + 30, cell.z + 30)) exists[cell] = hasRoom(level, cell)
        }
        // Each gap is read once, from the west or north cell, whether or not the far side has a room
        for (cx in 0 until cells) for (cz in 0 until cells) {
            val cell = Cell(cx, cz)
            for (eastward in booleanArrayOf(true, false)) {
                val neighbour = if (eastward) Cell(cx + 1, cz) else Cell(cx, cz + 1)
                if (neighbour.cx >= cells || neighbour.cz >= cells || (cell to neighbour) in joined) continue
                if (exists[cell] != true && exists[neighbour] != true) continue
                val gapLoaded = if (eastward) loaded(level, cell.x + 31, cell.z, cell.x + 31, cell.z + 30)
                else loaded(level, cell.x, cell.z + 31, cell.x + 30, cell.z + 31)
                if (gapLoaded) joined[cell to neighbour] = isJoined(level, cell, eastward)
            }
        }
    }

    /** A room with every cell found and every edge checked, not copied yet. */
    private fun nextRoom(): Set<Cell>? {
        val seen = HashSet<Cell>()
        for ((cell, present) in exists) {
            if (!present || cell in seen || cell in started) continue
            val group = HashSet<Cell>()
            var complete = true
            val queue = ArrayDeque(listOf(cell))
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                if (!group.add(at)) continue
                for (neighbour in neighbours(at)) {
                    if (neighbour.cx !in 0 until cells || neighbour.cz !in 0 until cells) continue
                    when (joinedBetween(at, neighbour)) {
                        null -> complete = false
                        true -> if (exists[neighbour] == true) queue.add(neighbour) else complete = false
                        false -> Unit
                    }
                }
            }
            seen.addAll(group)
            if (!complete || group.any { it in started }) continue
            if (group.size > 4) {
                logger.warn("Cells {} look like one room of {}, skipping", group, group.size)
                started.addAll(group)
                continue
            }
            return group
        }
        return null
    }

    private fun neighbours(cell: Cell) = listOf(
        Cell(cell.cx + 1, cell.cz), Cell(cell.cx - 1, cell.cz), Cell(cell.cx, cell.cz + 1), Cell(cell.cx, cell.cz - 1),
    )

    private fun joinedBetween(a: Cell, b: Cell): Boolean? =
        if (a.cx < b.cx || a.cz < b.cz) joined[a to b] else joined[b to a]

    /** ClientLevel.hasChunksAt always says yes, and unarrived chunks read as air; the chunk cache knows what has arrived. */
    private fun loaded(level: ClientLevel, minX: Int, minZ: Int, maxX: Int, maxZ: Int): Boolean {
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) {
            if (!level.chunkSource.hasChunk(cx, cz)) return false
        }
        return true
    }

    private fun hasRoom(level: ClientLevel, cell: Cell): Boolean {
        val spots = intArrayOf(5, 15, 25)
        return spots.any { dx -> spots.any { dz -> columnHasBlocks(level, cell.x + dx, cell.z + dz) } }
    }

    /** Joined cells have room across the gap away from the doorway; separate rooms only meet in it. */
    private fun isJoined(level: ClientLevel, cell: Cell, eastward: Boolean): Boolean =
        intArrayOf(4, 26).all { along ->
            if (eastward) columnHasBlocks(level, cell.x + 31, cell.z + along)
            else columnHasBlocks(level, cell.x + along, cell.z + 31)
        }

    private fun columnHasBlocks(level: ClientLevel, x: Int, z: Int): Boolean {
        val pos = BlockPos.MutableBlockPos()
        for (y in 40..140) if (!level.getBlockState(pos.set(x, y, z)).isAir) return true
        return false
    }

    // Copying a room

    /** Reads a room's blocks a slice at a time on the client thread, or holds a saved unmatched room to match again. */
    private class ReadJob(val cells: Set<Cell>, val minY: Int, val maxY: Int) {
        constructor(cells: Set<Cell>, level: ClientLevel) : this(cells, maxOf(level.minY, 0), minOf(level.maxY, 255))

        val minX = cells.minOf { it.x }
        val minZ = cells.minOf { it.z }
        val maxX = cells.maxOf { it.x } + 30
        val maxZ = cells.maxOf { it.z } + 30
        val sizeX = maxX - minX + 1
        val sizeZ = maxZ - minZ + 1
        val sizeY = maxY - minY + 1
        val states = arrayOfNulls<BlockState>(sizeX * sizeY * sizeZ)
        val blockEntities = HashMap<BlockPos, String>()
        private val cellKeys = cells.map { it.key }.toSet()
        private var y = minY
        val finished: Boolean get() = y > maxY

        /** Reads up to the budget; false when part of the room has unloaded. */
        fun step(level: ClientLevel, budget: Int): Boolean {
            if (!loaded(level, minX, minZ, maxX, maxZ)) return false
            val air = Blocks.AIR.defaultBlockState()
            val pos = BlockPos.MutableBlockPos()
            var read = 0
            while (y <= maxY && read < budget) {
                for (dz in 0 until sizeZ) for (dx in 0 until sizeX) {
                    val x = minX + dx
                    val z = minZ + dz
                    // The empty corner of an L shape belongs to another room
                    val inRoom = (Math.floorDiv(x - GRID, CELL) * CELL + GRID to Math.floorDiv(z - GRID, CELL) * CELL + GRID) in cellKeys
                    val state = if (inRoom) level.getBlockState(pos.set(x, y, z)) else air
                    states[index(dx, y - minY, dz)] = state
                    if (inRoom && state.hasBlockEntity()) {
                        level.getBlockEntity(pos)?.let { blockEntities[pos.immutable()] = it.saveWithoutMetadata(level.registryAccess()).toString() }
                    }
                }
                read += sizeX * sizeZ
                y++
            }
            return true
        }

        fun index(dx: Int, dy: Int, dz: Int) = (dy * sizeZ + dz) * sizeX + dx
    }

    private class Result(
        val room: String?,
        val shape: String,
        val variant: String?,
        val cells: Set<Pair<Int, Int>>,
        val facing: DungeonRoom.Facing,
        val missRate: Double,
        val clean: Boolean,
        val floor: String?,
        val origin: IntArray,
        val size: IntArray,
        val palette: List<String>,
        val data: IntArray,
        val blockEntities: List<Pair<IntArray, String>>,
    )

    private class Match(val room: String, val shape: String, val facing: DungeonRoom.Facing, val missRate: Double)

    /** Matches, unrotates and packs a copied room. Runs on [worker]. */
    private fun process(job: ReadJob, floor: String?, opened: Boolean): Result? {
        val shape = shapeOf(job.cells)
        val match = match(job, shape)
        val facing = match?.facing ?: DungeonRoom.Facing.NW
        val corner = if (match != null) cornerFor(facing, job.cells) else Vector2i(job.minX, job.minZ)
        val rotation = rotationFor(facing)

        // Where the room's blocks land once unrotated
        val corners = listOf(job.minX to job.minZ, job.maxX to job.minZ, job.minX to job.maxZ, job.maxX to job.maxZ)
            .map { (x, z) -> DungeonMapUtils.actualToRelative(facing.vanilla(), corner, BlockPos(x, 0, z)) }
        val relMinX = corners.minOf { it.x }
        val relMinZ = corners.minOf { it.z }
        val relSizeX = corners.maxOf { it.x } - relMinX + 1
        val relSizeZ = corners.maxOf { it.z } - relMinZ + 1

        // Trim empty layers top and bottom
        var low = job.sizeY
        var high = -1
        for (dy in 0 until job.sizeY) {
            var any = false
            for (i in dy * job.sizeX * job.sizeZ until (dy + 1) * job.sizeX * job.sizeZ) {
                if (job.states[i]?.isAir == false) {
                    any = true
                    break
                }
            }
            if (any) {
                low = minOf(low, dy)
                high = dy
            }
        }
        if (high < 0) {
            logger.warn("Room at {} was empty", job.cells)
            return null
        }
        val sizeY = high - low + 1

        val palette = LinkedHashMap<String, Int>()
        val data = IntArray(relSizeX * sizeY * relSizeZ)
        val airIndex = palette.getOrPut("minecraft:air") { 0 }
        data.fill(airIndex)
        val pos = BlockPos.MutableBlockPos()
        for (dy in low..high) for (dz in 0 until job.sizeZ) for (dx in 0 until job.sizeX) {
            val state = job.states[job.index(dx, dy, dz)] ?: continue
            if (state.isAir) continue
            val rel = DungeonMapUtils.actualToRelative(facing.vanilla(), corner, pos.set(job.minX + dx, 0, job.minZ + dz))
            val key = BlockStateParser.serialize(state.rotate(rotation))
            val id = palette.getOrPut(key) { palette.size }
            data[((dy - low) * relSizeZ + (rel.z - relMinZ)) * relSizeX + (rel.x - relMinX)] = id
        }

        val blockEntities = job.blockEntities.map { (at, nbt) ->
            val rel = DungeonMapUtils.actualToRelative(facing.vanilla(), corner, at)
            intArrayOf(rel.x, rel.y, rel.z) to nbt
        }

        val variant = if (match?.room == WATER_BOARD) waterBoardLayout(palette, data, relMinX, job.minY + low, relMinZ, relSizeX, relSizeZ, sizeY) else null
        val result = Result(
            room = match?.room,
            shape = match?.shape ?: shape,
            variant = variant,
            cells = job.cells.map { it.key }.toSet(),
            facing = facing,
            missRate = match?.missRate ?: 1.0,
            clean = !opened,
            floor = floor,
            origin = intArrayOf(relMinX, job.minY + low, relMinZ),
            size = intArrayOf(relSizeX, sizeY, relSizeZ),
            palette = palette.keys.toList(),
            data = data,
            blockEntities = blockEntities,
        )
        logger.info("Copied room at {}: {} ({}) facing {}, miss rate {}", job.cells.map { it.key }, match?.room ?: "unmatched", result.shape, facing, "%.4f".format(result.missRate))
        return result
    }

    /**
     * Matches the saved unmatched rooms again, for when the matcher has improved since. Rooms that match now are
     * returned with the files they came from, to delete once they're saved properly. Runs on [worker].
     */
    private fun rematchUnknowns(): List<Pair<Result, Path>> {
        val folder = dir.resolve("unknown")
        if (!Files.isDirectory(folder)) return emptyList()
        val matched = ArrayList<Pair<Result, Path>>()
        Files.list(folder).use { it.toList() }.filter { it.toString().endsWith(".json") }.forEach { file ->
            runCatching {
                val json = Files.newBufferedReader(file).use { com.google.gson.JsonParser.parseReader(it).asJsonObject }
                val cells = json.getAsJsonArray("cells").map { cell ->
                    val pair = cell.asJsonArray
                    Cell(Math.floorDiv(pair[0].asInt - GRID, CELL), Math.floorDiv(pair[1].asInt - GRID, CELL))
                }.toSet()
                val origin = json.getAsJsonArray("origin").map { it.asInt }
                val size = json.getAsJsonArray("size").map { it.asInt }
                val job = ReadJob(cells, origin[1], origin[1] + size[1] - 1)
                // Unmatched rooms are saved the way they lay in the world, from the room's north west corner
                if (origin[0] != 0 || origin[2] != 0 || size[0] != job.sizeX || size[2] != job.sizeZ) return@runCatching

                val palette = json.getAsJsonArray("palette").map {
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, it.asString, false).blockState()
                }
                val data = json.getAsJsonArray("data")
                for (i in 0 until data.size()) job.states[i] = palette[data[i].asInt]
                json.getAsJsonArray("blockEntities").forEach { entry ->
                    val pos = entry.asJsonObject.getAsJsonArray("pos").map { it.asInt }
                    job.blockEntities[BlockPos(job.minX + pos[0], pos[1], job.minZ + pos[2])] = entry.asJsonObject.get("nbt").asString
                }

                val floor = json.get("floor")?.takeIf { !it.isJsonNull }?.asString
                val clean = json.get("clean")?.asBoolean ?: false
                val result = process(job, floor, !clean) ?: return@runCatching
                if (result.room != null) matched.add(result to file)
            }.onFailure { logger.warn("Could not match {} again", file.fileName, it) }
        }
        if (matched.isNotEmpty()) logger.info("Matched {} saved unknown rooms: {}", matched.size, matched.joinToString { it.first.room ?: "?" })
        return matched
    }

    private fun shapeOf(cells: Set<Cell>): String {
        val xs = cells.map { it.cx }.distinct().size
        val zs = cells.map { it.cz }.distinct().size
        return when (cells.size) {
            1 -> "1x1"
            2 -> "1x2"
            3 -> if (xs == 2 && zs == 2) "l-shape" else "1x3"
            4 -> if (xs == 2 && zs == 2) "2x2" else "1x4"
            else -> "1x1"
        }
    }

    /** Same facings and corners [DungeonRoom] tries, so captures share its coordinates with the secret data. */
    private fun facingsFor(shape: String, cells: Set<Cell>): List<DungeonRoom.Facing> {
        val xs = cells.map { it.x }.distinct().sorted()
        val zs = cells.map { it.z }.distinct().sorted()
        val keys = cells.map { it.key }.toSet()
        return when (shape) {
            "1x2", "1x3", "1x4" -> when {
                xs.size > 1 && zs.size == 1 -> listOf(DungeonRoom.Facing.NW, DungeonRoom.Facing.SE)
                xs.size == 1 && zs.size > 1 -> listOf(DungeonRoom.Facing.NE, DungeonRoom.Facing.SW)
                else -> DungeonRoom.Facing.entries
            }
            "l-shape" -> when {
                (xs.first() to zs.first()) !in keys -> listOf(DungeonRoom.Facing.SW)
                (xs.first() to zs.last()) !in keys -> listOf(DungeonRoom.Facing.SE)
                (xs.last() to zs.first()) !in keys -> listOf(DungeonRoom.Facing.NW)
                (xs.last() to zs.last()) !in keys -> listOf(DungeonRoom.Facing.NE)
                else -> DungeonRoom.Facing.entries
            }
            else -> DungeonRoom.Facing.entries
        }
    }

    private fun cornerFor(facing: DungeonRoom.Facing, cells: Set<Cell>): Vector2i {
        val xs = cells.map { it.x }
        val zs = cells.map { it.z }
        return when (facing) {
            DungeonRoom.Facing.NW -> Vector2i(xs.min(), zs.min())
            DungeonRoom.Facing.NE -> Vector2i(xs.max() + 30, zs.min())
            DungeonRoom.Facing.SW -> Vector2i(xs.min(), zs.max() + 30)
            DungeonRoom.Facing.SE -> Vector2i(xs.max() + 30, zs.max() + 30)
        }
    }

    /** Turns block states the same way [DungeonMapUtils.actualToRelative] turns positions. */
    private fun rotationFor(facing: DungeonRoom.Facing): Rotation = when (facing) {
        DungeonRoom.Facing.NW -> Rotation.NONE
        DungeonRoom.Facing.NE -> Rotation.COUNTERCLOCKWISE_90
        DungeonRoom.Facing.SW -> Rotation.CLOCKWISE_90
        DungeonRoom.Facing.SE -> Rotation.CLOCKWISE_180
    }

    private class Candidate(val room: String, val shape: String, val facing: DungeonRoom.Facing, val corner: Vector2i, val skeleton: IntArray) {
        var hits = 0
        var misses = 0
        val alive: Boolean get() = misses <= DROP_MIN_MISSES || misses <= (hits + misses) * DROP_MISS_RATE
        val missRate: Double get() = if (hits + misses == 0) 1.0 else misses.toDouble() / (hits + misses)
    }

    /** Checks every matchable block against every room of the shape, the way [DungeonRoom] does a few at a time. */
    private fun match(job: ReadJob, shape: String): Match? {
        val pools = if (shape == "1x1") ONE_BY_ONE_POOLS else listOf(shape)
        val candidates = ArrayList<Candidate>()
        for (facing in facingsFor(shape, job.cells)) {
            val corner = cornerFor(facing, job.cells)
            for (pool in pools) for ((room, skeleton) in SecretRoomData.roomsForShape(pool)) {
                candidates.add(Candidate(room, pool, facing, corner, skeleton))
            }
        }
        if (candidates.isEmpty()) return null

        var alive: List<Candidate> = candidates
        val pos = BlockPos.MutableBlockPos()
        for (dy in 0 until job.sizeY) for (dz in 0 until job.sizeZ) for (dx in 0 until job.sizeX) {
            val state = job.states[job.index(dx, dy, dz)] ?: continue
            if (state.isAir) continue
            val id = SecretRoomData.NUMERIC_ID[BuiltInRegistries.BLOCK.getKey(state.block).toString()] ?: continue
            pos.set(job.minX + dx, job.minY + dy, job.minZ + dz)
            if (!notInDoorway(pos)) continue
            for (candidate in alive) {
                val rel = DungeonMapUtils.actualToRelative(candidate.facing.vanilla(), candidate.corner, pos)
                val encoded = (rel.x shl 24) or (rel.y shl 16) or (rel.z shl 8) or id.toInt()
                if (java.util.Arrays.binarySearch(candidate.skeleton, encoded) >= 0) candidate.hits++ else candidate.misses++
            }
            if (alive.any { !it.alive }) {
                alive = alive.filter { it.alive }
                if (alive.isEmpty()) return null
            }
        }

        val ranked = alive.filter { it.hits >= MIN_HITS }.sortedBy { it.missRate }
        val best = ranked.firstOrNull()?.takeIf { it.missRate <= MAX_MISS_RATE } ?: return null
        val runnerUp = ranked.firstOrNull { it.room != best.room }
        if (runnerUp != null && runnerUp.missRate - best.missRate < maxOf(MIN_LEAD, best.missRate)) {
            logger.warn("Room {} {} %.4f too close to {} {} %.4f, leaving it unmatched".format(best.room, best.facing, best.missRate, runnerUp.room, runnerUp.facing, runnerUp.missRate))
            return null
        }
        return Match(best.room, best.shape, best.facing, best.missRate)
    }

    /** Doorways change with the neighbours, so matching skips them like [DungeonRoom] does. */
    private fun notInDoorway(pos: BlockPos): Boolean {
        if (pos.y < 66 || pos.y > 73) return true
        val x = Math.floorMod(pos.x - 8, 32)
        val z = Math.floorMod(pos.z - 8, 32)
        return (x < 13 || x > 17 || z > 2 && z < 28) && (z < 13 || z > 17 || x > 2 && x < 28)
    }

    /**
     * The water board's four layouts each put a different pair of lever blocks at the top of the board,
     * as Skyblocker's solver reads them (room coordinates 14 to 16, 77 to 78, 26 to 27).
     */
    private fun waterBoardLayout(
        palette: Map<String, Int>, data: IntArray, originX: Int, originY: Int, originZ: Int, sizeX: Int, sizeZ: Int, sizeY: Int,
    ): String {
        val byId = palette.entries.associate { (state, id) -> id to state.substringBefore('[') }
        val found = HashSet<String>()
        for (x in 14..16) for (y in 77..78) for (z in 26..27) {
            val dx = x - originX
            val dy = y - originY
            val dz = z - originZ
            if (dx !in 0 until sizeX || dy !in 0 until sizeY || dz !in 0 until sizeZ) continue
            byId[data[(dy * sizeZ + dz) * sizeX + dx]]?.let { found.add(it.removePrefix("minecraft:")) }
        }
        val layout = when {
            "gold_block" in found && "terracotta" in found -> 1
            "emerald_block" in found && "quartz_block" in found -> 2
            "quartz_block" in found && "diamond_block" in found -> 3
            "gold_block" in found && "quartz_block" in found -> 4
            else -> 0
        }
        return if (layout == 0) "layout-unknown-${found.sorted().joinToString("-")}" else "layout-$layout"
    }

    // Saving and the missing list

    private fun finishRun() {
        if (!inRun) return
        inRun = false
        absentTicks = 0
        job = null
        val types = HashMap(mapTypes)
        reset()
        // Queued behind any rooms still being processed
        worker.execute { runCatching { save(types, final = true) }.onFailure { logger.error("Saving room captures failed", it) } }
    }

    private fun reset() {
        exists.clear()
        joined.clear()
        started.clear()
        mapTypes.clear()
        ticks = 0
    }

    /**
     * Writes what's been copied. Before the run ends ([final] false, from a grab) rooms that didn't match are held
     * back, since the map may still turn out to show them as the blood or fairy room.
     */
    private fun save(types: Map<Pair<Int, Int>, DungeonRoom.Type>, final: Boolean) {
        // At the end of a run, saved unmatched rooms get another go at matching
        val rematched = if (final) rematchUnknowns() else emptyList()
        val captured = synchronized(results) { results.toList().also { results.clear() } } + rematched.map { it.first }
        val index = loadIndex()
        val added = ArrayList<String>()
        val improved = ArrayList<String>()
        val replaced = ArrayList<String>()
        val held = ArrayList<Result>()
        var unknown = 0

        for (result in captured) {
            val (key, file) = when {
                result.room != null && result.variant != null ->
                    "${result.shape}/${result.room}#${result.variant}" to dir.resolve(result.shape).resolve(result.room).resolve("${result.variant}.json")
                result.room != null -> "${result.shape}/${result.room}" to dir.resolve(result.shape).resolve("${result.room}.json")
                else -> {
                    val special = result.cells.firstNotNullOfOrNull { types[it] }?.name?.lowercase()
                    if (special != null) {
                        "special/$special" to dir.resolve("special").resolve("$special.json")
                    } else if (!final) {
                        held.add(result)
                        continue
                    } else {
                        unknown++
                        val stamp = Instant.now().toString().replace(':', '-')
                        val cell = result.cells.minWith(compareBy({ it.first }, { it.second }))
                        write(dir.resolve("unknown").resolve("${result.floor ?: "unknown"}_${stamp}_${cell.first}_${cell.second}.json"), result, null)
                        continue
                    }
                }
            }
            val existing = index[key]
            val imported = existing?.source != null
            if (existing != null && !imported && (existing.clean || !result.clean)) continue
            write(file, result, key)
            index[key] = IndexEntry(result.clean, result.floor, Instant.now().toString(), result.missRate)
            val label = label(key)
            when {
                existing == null -> added.add(label)
                imported -> replaced.add(label)
                else -> improved.add(label)
            }
        }
        saveIndex(index)
        if (held.isNotEmpty()) synchronized(results) { results.addAll(held) }
        // Saved properly above, or already had a cleaner copy
        rematched.forEach { (_, file) -> runCatching { Files.deleteIfExists(file) } }

        val missing = missing(index)
        lastMissing = missing
        Files.write(dir.resolve("missing.txt"), missing.map { it.replace(Regex("§."), "") })
        val total = catalogue().size
        val have = total - missingCount(index)
        val imported = index.values.count { it.source != null }
        val importedNote = if (imported == 0) "" else " §8($imported imported)"

        McCompat.mc.execute {
            ChatUtils.chat("§dRoom capture§7: §f${captured.size} §7rooms copied this run, §a${added.size} new§7, §b${replaced.size} §7replaced imported copies, §f${improved.size} §7cleaner, §f$unknown §7unmatched${if (held.isEmpty()) "" else ", §f${held.size} §7held until the run ends"}. §f$have§7/§f$total §7rooms saved$importedNote§7.")
            if (rematched.isNotEmpty()) ChatUtils.chat("§7Matched §f${rematched.size} §7saved unknown rooms again: §f${rematched.joinToString(", ") { SecretRoomData.displayName(it.first.room ?: "?") }}")
            if (added.isNotEmpty()) ChatUtils.chat("§aNew: §f${added.joinToString(", ")}")
            if (replaced.isNotEmpty()) ChatUtils.chat("§bReal copies of imported rooms: §f${replaced.joinToString(", ")}")
            if (missing.isEmpty()) ChatUtils.chat("§aEvery room is saved.") else missing.forEach { ChatUtils.chat(it) }
        }
    }

    private fun write(file: Path, result: Result, key: String?) {
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(file).use { out ->
            JsonWriter(out).use { json ->
                json.beginObject()
                json.name("room").value(result.room)
                json.name("name").value(result.room?.let(SecretRoomData::displayName) ?: key?.substringAfter('/'))
                json.name("shape").value(result.shape)
                json.name("variant").value(result.variant)
                json.name("facing").value(result.facing.name)
                json.name("clean").value(result.clean)
                json.name("floor").value(result.floor)
                json.name("missRate").value(result.missRate)
                json.name("capturedAt").value(Instant.now().toString())
                json.name("cells").beginArray()
                result.cells.forEach { json.beginArray().value(it.first.toLong()).value(it.second.toLong()).endArray() }
                json.endArray()
                json.name("origin").beginArray()
                result.origin.forEach { json.value(it.toLong()) }
                json.endArray()
                json.name("size").beginArray()
                result.size.forEach { json.value(it.toLong()) }
                json.endArray()
                json.name("order").value("yzx")
                json.name("palette").beginArray()
                result.palette.forEach { json.value(it) }
                json.endArray()
                json.name("data").beginArray()
                result.data.forEach { json.value(it.toLong()) }
                json.endArray()
                json.name("blockEntities").beginArray()
                result.blockEntities.forEach { (at, nbt) ->
                    json.beginObject()
                    json.name("pos").beginArray().value(at[0].toLong()).value(at[1].toLong()).value(at[2].toLong()).endArray()
                    json.name("nbt").value(nbt)
                    json.endObject()
                }
                json.endArray()
                json.endObject()
            }
        }
    }

    /** Every room we want, as index keys: the room data, all four water board layouts, and the special rooms. */
    private fun catalogue(): List<String> {
        val keys = ArrayList<String>()
        for (shape in SHAPE_ORDER) {
            if (shape == "special") {
                SPECIAL_ROOMS.forEach { keys.add("special/$it") }
                continue
            }
            for (room in SecretRoomData.roomsForShape(shape).keys.sortedBy(SecretRoomData::displayName)) {
                if (room == WATER_BOARD) (1..WATER_BOARD_LAYOUTS).forEach { keys.add("$shape/$room#layout-$it") }
                else keys.add("$shape/$room")
            }
        }
        return keys
    }

    private fun missingCount(index: Map<String, IndexEntry>): Int = catalogue().count { it !in index }

    /** Chat lines of what's still missing, one per shape. */
    private fun missing(index: Map<String, IndexEntry>): List<String> =
        catalogue().filter { it !in index }
            .groupBy { it.substringBefore('/') }
            .toSortedMap(compareBy { SHAPE_ORDER.indexOf(it) })
            .map { (shape, keys) -> "§7Missing §f$shape §7(${keys.size}): §f${keys.joinToString(", ") { label(it) }}" }

    private fun label(key: String): String {
        val room = key.substringAfter('/').substringBefore('#')
        val name = if (key.startsWith("special/")) room.replaceFirstChar { it.uppercase() } else SecretRoomData.displayName(room)
        val layout = key.substringAfter('#', "")
        return if (layout.isEmpty()) name else "$name ${layout.replace('-', ' ')}"
    }

    private fun loadIndex(): MutableMap<String, IndexEntry> {
        index?.let { return it }
        val file = dir.resolve("index.json")
        val loaded: MutableMap<String, IndexEntry> = runCatching {
            Files.newBufferedReader(file).use { gson.fromJson<MutableMap<String, IndexEntry>>(it, object : TypeToken<MutableMap<String, IndexEntry>>() {}.type) }
        }.getOrNull() ?: HashMap()
        index = loaded
        return loaded
    }

    private fun saveIndex(index: Map<String, IndexEntry>) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("index.json"), gson.toJson(index.toSortedMap()))
    }

    /** For /pa dev rooms: the list as of the last finished run, never this one. */
    fun status() {
        SecretRoomData.load()
        worker.execute {
            val index = loadIndex()
            val missing = lastMissing ?: missing(index)
            val total = catalogue().size
            McCompat.mc.execute {
                ChatUtils.chat("§dRoom capture §7is ${if (enabled) "§aon" else "§coff"}§7. §f${total - missingCount(index)}§7/§f$total §7rooms saved in §f$dir")
                if (missing.isEmpty()) ChatUtils.chat("§aEvery room is saved.") else missing.forEach { ChatUtils.chat(it) }
            }
        }
    }
}
