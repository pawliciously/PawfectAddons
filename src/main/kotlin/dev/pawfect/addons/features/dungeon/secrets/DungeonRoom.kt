package dev.pawfect.addons.features.dungeon.secrets

import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.rooms.Room
import net.minecraft.client.multiplayer.ClientLevel
import org.joml.Vector2i
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import org.slf4j.LoggerFactory

class DungeonRoom(val type: Type, val segments: Set<Pair<Int, Int>>) {

    enum class Facing {
        NW, NE, SW, SE;

        fun vanilla(): Room.Direction = when (this) {
            NW -> Room.Direction.NW
            NE -> Room.Direction.NE
            SW -> Room.Direction.SW
            SE -> Room.Direction.SE
        }
    }

    enum class Type(val shape: String?, val scan: Boolean) {
        ENTRANCE(null, false),
        ROOM(null, true),
        PUZZLE("puzzle", true),
        TRAP("trap", true),
        MINIBOSS("miniboss", true),
        FAIRY(null, false),
        BLOOD(null, false),
        UNKNOWN(null, false),
    }

    enum class MatchState { MATCHING, DOUBLE_CHECKING, MATCHED, FAILED }

    private class Candidate(val facing: Facing, val corner: Pair<Int, Int>, var rooms: List<String>)

    private val segmentsX = segments.map { it.first }.distinct().sorted()
    private val segmentsZ = segments.map { it.second }.distinct().sorted()

    val shape: String = determineShape()

    private val roomsData: Map<String, IntArray> = SecretRoomData.roomsForShape(shape)

    private var candidates: List<Candidate> = buildCandidates()
    private val checked = HashSet<BlockPos>()
    private var doubleChecked = 0
    val claimedSecrets = HashSet<String>()
    val claimedIndices = HashSet<Int>()

    var greenChecked = false

    private var retryIn = 0
    private var attempts = 0

    var matchState: MatchState = if (type.scan) MatchState.MATCHING else MatchState.FAILED
        private set

    var name: String? = null
        private set

    var facing: Facing? = null
        private set

    var corner: Pair<Int, Int>? = null
        private set

    val matched: Boolean get() = matchState == MatchState.DOUBLE_CHECKING || matchState == MatchState.MATCHED

    val remainingCandidates: Int get() = candidates.sumOf { it.rooms.size }

    val checkedBlocks: Int get() = checked.size

    val attemptCount: Int get() = attempts

    private fun determineShape(): String = when (type) {
        Type.PUZZLE -> "puzzle"
        Type.TRAP -> "trap"
        Type.MINIBOSS -> "miniboss"
        else -> when (segments.size) {
            1 -> "1x1"
            2 -> "1x2"
            3 -> if (segmentsX.size == 2 && segmentsZ.size == 2) "l-shape" else "1x3"
            4 -> if (segmentsX.size == 2 && segmentsZ.size == 2) "2x2" else "1x4"
            else -> "1x1"
        }
    }

    private fun possibleFacings(): List<Facing> = when (shape) {
        "1x1", "2x2", "puzzle", "trap", "miniboss" -> Facing.entries.toList()
        "1x2", "1x3", "1x4" -> when {
            segmentsX.size > 1 && segmentsZ.size == 1 -> listOf(Facing.NW, Facing.SE)
            segmentsX.size == 1 && segmentsZ.size > 1 -> listOf(Facing.NE, Facing.SW)
            else -> Facing.entries.toList()
        }
        "l-shape" -> when {
            !segments.contains(segmentsX.first() to segmentsZ.first()) -> listOf(Facing.SW)
            !segments.contains(segmentsX.first() to segmentsZ.last()) -> listOf(Facing.SE)
            !segments.contains(segmentsX.last() to segmentsZ.first()) -> listOf(Facing.NW)
            !segments.contains(segmentsX.last() to segmentsZ.last()) -> listOf(Facing.NE)
            else -> Facing.entries.toList()
        }
        else -> Facing.entries.toList()
    }

    private fun cornerFor(facing: Facing): Pair<Int, Int> = when (facing) {
        Facing.NW -> segmentsX.first() to segmentsZ.first()
        Facing.NE -> (segmentsX.last() + 30) to segmentsZ.first()
        Facing.SW -> segmentsX.first() to (segmentsZ.last() + 30)
        Facing.SE -> (segmentsX.last() + 30) to (segmentsZ.last() + 30)
    }

    private fun buildCandidates(): List<Candidate> {
        if (roomsData.isEmpty()) return emptyList()
        val names = roomsData.keys.toList()
        return possibleFacings().map { facing ->
            Candidate(facing, cornerFor(facing), names)
        }
    }

    fun tick(level: ClientLevel, player: BlockPos) {
        if (matchState == MatchState.FAILED) {
            if (--retryIn > 0) return
            matchState = MatchState.MATCHING
            checked.clear()
            candidates = buildCandidates()
            doubleChecked = 0
            logger.info("Retrying room match, attempt {}", attempts + 1)
        }
        if (matchState != MatchState.MATCHING && matchState != MatchState.DOUBLE_CHECKING) return
        if (!SecretRoomData.loaded) return

        for (pos in BlockPos.betweenClosed(player.offset(-5, -5, -5), player.offset(5, 5, 5))) {
            val immutable = pos.immutable()
            val physical = DungeonMapUtils.getPhysicalRoomPos(immutable)
            if (!segments.contains(physical.x() to physical.y())) continue
            if (!notInDoorway(immutable)) continue
            if (!checked.add(immutable)) continue
            if (checkBlock(level, immutable)) break
        }
    }

    private fun checkBlock(level: ClientLevel, pos: BlockPos): Boolean {
        val key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        val id = SecretRoomData.NUMERIC_ID[key] ?: return false

        candidates.forEach { candidate ->
            val relative = DungeonMapUtils.actualToRelative(candidate.facing.vanilla(), Vector2i(candidate.corner.first, candidate.corner.second), pos)
            val encoded = (relative.x shl 24) or (relative.y shl 16) or (relative.z shl 8) or id.toInt()
            candidate.rooms = candidate.rooms.filter { room ->
                roomsData[room]?.let { java.util.Arrays.binarySearch(it, encoded) >= 0 } == true
            }
        }

        val remaining = candidates.sumOf { it.rooms.size }
        if (remaining == 0) {
            matchState = MatchState.FAILED
            attempts++
            retryIn = 50
            logger.warn("No dungeon room matched after checking {} blocks, retrying in 50 ticks", checked.size)
            return true
        }
        if (remaining == 1) {
            val winner = candidates.first { it.rooms.size == 1 }
            if (matchState == MatchState.MATCHING) {
                matchState = MatchState.DOUBLE_CHECKING
                name = winner.rooms.first()
                facing = winner.facing
                corner = winner.corner
                logger.info("Room {} matched after {} blocks, double checking", name, checked.size)
                return false
            }
            if (matchState == MatchState.DOUBLE_CHECKING && ++doubleChecked >= 10) {
                matchState = MatchState.MATCHED
                logger.info("Room {} confirmed", name)
                return true
            }
        }
        return false
    }

    private fun notInDoorway(pos: BlockPos): Boolean {
        if (pos.y < 66 || pos.y > 73) return true
        val x = Math.floorMod(pos.x - 8, 32)
        val z = Math.floorMod(pos.z - 8, 32)
        return (x < 13 || x > 17 || z > 2 && z < 28) && (z < 13 || z > 17 || x > 2 && x < 28)
    }

    companion object {
        private val logger = LoggerFactory.getLogger("PawfectAddons/Secrets")
    }
}
