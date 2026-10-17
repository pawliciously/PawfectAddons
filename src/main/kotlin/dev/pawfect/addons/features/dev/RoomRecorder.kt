package dev.pawfect.addons.features.dev

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.debug.DebugText
import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector2i
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.atan2


/**
 * Dev only, for DungeonSim. Two recorders, on together from the Dev tab:
 *
 * Mobs: every mob the client sees in the dungeon grid (starred mobs, Fels, minibosses, bats), where and when it
 * first showed up, how far away you were then, its name tag, gear and skin, when it went visible (Fels wake up)
 * and when it died. Wither and Blood keys that appear are saved with the mob that died next to them, so a
 * miniboss that drops a key shows up as such. Saved per room when the run ends, unrotated like room captures.
 *
 * Levers: right clicking a lever (or the Mushroom room's mushroom) snapshots the room, then saves every block
 * that changed in the next 3 seconds, and keeps watching for 30 seconds to see if the passage closes again.
 *
 * When you walk into a room that still needs either, chat says so. Only ever about the room you're standing in.
 */
object RoomRecorder {

    private val logger = LoggerFactory.getLogger("PawfectAddons/RoomRecorder")

    private const val GRID = -200
    private const val CELL = 32
    private const val GRID_SPAN = 6 * CELL
    private const val SCAN_EVERY = 5
    private const val ABSENT_GRACE = 60

    private const val LEVER_DIFF_TICKS = 60
    private const val LEVER_WATCH_TICKS = 600
    private const val SNAPSHOT_MIN_Y = 40
    private const val SNAPSHOT_MAX_Y = 160

    /** A key counts as dropped by a mob that died this close to it, this recently. */
    private const val KEY_DROP_RANGE = 8.0
    private const val KEY_DROP_MS = 3000L

    private const val MAX_SESSIONS = 10

    /** Room pools that have mobs worth recording. Puzzles and the special rooms don't. */
    private val MOB_SHAPES = listOf("1x1", "1x2", "1x3", "1x4", "2x2", "l-shape", "miniboss", "trap")

    private val dir: Path get() = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev").resolve("record")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PawfectAddons-RoomRecorder").apply { isDaemon = true } }

    val enabled: Boolean get() = ConfigManager.features.dev.roomRecording && DevAccount.isDev()

    private class Mob(
        var id: Int,
        val uuid: UUID,
        val type: String,
        val firstPos: Vec3,
        val firstYaw: Float,
        val seenAt: Long,
        val playerDistance: Double,
        val invisibleAtFirst: Boolean,
        val playerName: String?,
        val skin: String?,
    ) {
        var lastPos: Vec3 = firstPos
        var name: String? = null
        var firstName: String? = null
        var ownName: String? = null
        var health = 0f
        var maxHealth = 0f
        var invisible = invisibleAtFirst
        val equipment = LinkedHashMap<String, String>()
        val events = ArrayList<String>()
        var diedAt: Long? = null
        var gone = false
    }

    private class KeyDrop(val name: String, val pos: Vec3, val at: Long, val droppedBy: Mob?, val playerDistance: Double)

    private class Entered(val at: Long, val clearedAlready: Boolean)

    /** A room PA matched this run, kept here because the scanner forgets its rooms the moment you warp out. */
    private class KnownRoom(
        val name: String,
        val shape: String,
        val facing: DungeonRoom.Facing,
        val corner: Vector2i,
        val segments: Set<Pair<Int, Int>>,
    )

    // This run
    private var levelKey = 0
    private var inRun = false
    private var absentTicks = 0
    private var ticks = 0
    private var runStart = 0L
    /** Mobs loaded right now, by entity id. */
    private val tracked = HashMap<Int, Mob>()
    /** Every mob this run, by UUID, which stays the same when a mob leaves your range and comes back. */
    private val mobs = LinkedHashMap<UUID, Mob>()
    private val known = HashMap<Pair<Int, Int>, KnownRoom>()
    private val seenKeys = HashSet<Int>()
    private val keys = ArrayList<KeyDrop>()
    private val entered = HashMap<Pair<Int, Int>, Entered>()
    private val notified = HashSet<String>()
    private var lastRoom: DungeonRoom? = null
    private val levers = ArrayList<LeverJob>()

    fun register() {
        UseBlockCallback.EVENT.register { _, _, _, hit ->
            runCatching { onUse(hit.blockPos) }.onFailure { logger.error("Lever record failed", it) }
            InteractionResult.PASS
        }
    }

    fun onTick() {
        if (!enabled) {
            if (inRun) finishRun()
            return
        }
        val level = McCompat.mc.level
        val key = System.identityHashCode(level)
        if (key != levelKey) {
            levelKey = key
            // Entity ids belong to the old world; keep what we learned, stop matching ids
            tracked.values.forEach { it.gone = true }
            tracked.clear()
            seenKeys.clear()
            levers.clear()
        }
        if (level == null) return
        if (!SkyBlockData.inDungeons) {
            if (inRun && ++absentTicks >= ABSENT_GRACE) finishRun()
            return
        }
        absentTicks = 0
        if (!inRun) {
            inRun = true
            runStart = System.currentTimeMillis()
            logger.info("Room recording started for {}", SkyBlockData.dungeonFloor)
        }
        ticks++
        watchRoom()
        if (ticks % 20 == 0) rememberRooms()
        stepLevers(level)
        if (ticks % SCAN_EVERY == 0) scanEntities(level)
    }

    // Walking into rooms

    private fun watchRoom() {
        val room = DungeonScanner.current ?: return
        if (room !== lastRoom) {
            lastRoom = room
            entered.getOrPut(firstCell(room.segments)) { Entered(System.currentTimeMillis(), room.greenChecked || room.mapCleared) }
        }
        // Matching can finish a moment after you walk in, so this is checked every tick
        val name = room.name ?: return
        if (!room.matched || !notified.add(name)) return
        remember(room)
        notify(room, name)
    }

    private fun rememberRooms() {
        DungeonScanner.knownRooms().forEach(::remember)
    }

    private fun remember(room: DungeonRoom) {
        if (!room.matched) return
        val name = room.name ?: return
        val facing = room.facing ?: return
        val corner = room.corner ?: return
        val first = firstCell(room.segments)
        if (known[first]?.name == name) return
        known[first] = KnownRoom(name, room.shape, facing, Vector2i(corner.first, corner.second), room.segments.toSet())
    }

    private fun firstCell(segments: Set<Pair<Int, Int>>): Pair<Int, Int> = segments.minWith(compareBy({ it.first }, { it.second }))

    private fun notify(room: DungeonRoom, name: String) {
        val needs = ArrayList<String>()
        if (room.shape in MOB_SHAPES && !mobsDone(room.shape, name)) needs.add("§fmobs")
        val wanted = leverSecrets(name)
        if (wanted.isNotEmpty()) {
            val left = wanted.count { leverKey(it.x, it.y, it.z) !in recordedLevers(room.shape, name) }
            if (left > 0) needs.add("§flevers §7(§f$left §7of §f${wanted.size} §7left)")
        }
        if (needs.isEmpty()) return
        ChatUtils.chat("§dRecord §8» §f${SecretRoomData.displayName(name)} §7needs ${needs.joinToString(" §7and ")}")
    }

    // Mobs

    private fun scanEntities(level: ClientLevel) {
        val now = System.currentTimeMillis()
        val self = McCompat.player ?: return
        val stands = ArrayList<ArmorStand>()
        val present = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity === self || !inGrid(entity.x, entity.z)) continue
            if (entity is ArmorStand) {
                val name = entity.customName?.let(DebugText::legacy)
                if (name != null && isKey(name)) noteKey(entity, name, now, self)
                else if (name != null) stands.add(entity)
                continue
            }
            if (entity is ItemEntity) {
                val name = DebugText.legacy(entity.item.hoverName)
                if (isKey(name)) noteKey(entity, name, now, self)
                continue
            }
            if (entity !is LivingEntity) continue
            // Real players have random (version 4) UUIDs; Hypixel's NPCs and minibosses don't
            if (entity is Player && entity.uuid.version() == 4) continue
            present.add(entity.id)
            val mob = tracked.getOrPut(entity.id) {
                val again = mobs[entity.uuid]
                if (again != null) {
                    again.id = entity.id
                    again.gone = false
                    again.events.add("back in range ${rel(now)} player %.1f".format(entity.distanceTo(self)))
                    again
                } else {
                    newMob(entity, now, self).also { mobs[entity.uuid] = it }
                }
            }
            update(mob, entity, now, self)
        }
        for (mob in tracked.values) {
            if (mob.id in present) continue
            mob.gone = true
            if (mob.diedAt == null) mob.events.add("out of range ${rel(now)}")
        }
        tracked.values.removeIf { it.gone }
        // Name tags float above their mob as separate stands
        for (mob in tracked.values) {
            if (mob.diedAt != null) continue
            val tag = stands.filter { stand ->
                val dx = stand.x - mob.lastPos.x
                val dz = stand.z - mob.lastPos.z
                val dy = stand.y - mob.lastPos.y
                dx * dx + dz * dz < 1.5 && dy in -0.5..4.0
            }.minByOrNull { it.y - mob.lastPos.y }
            if (tag != null) {
                val name = DebugText.legacy(tag.customName)
                if (mob.firstName == null) mob.firstName = name
                mob.name = name
            }
        }
    }

    private fun newMob(entity: LivingEntity, now: Long, self: Player): Mob {
        val skin = (entity as? Player)?.gameProfile?.properties?.get("textures")?.firstOrNull()?.value
        return Mob(
            id = entity.id,
            uuid = entity.uuid,
            type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString(),
            firstPos = entity.position(),
            firstYaw = entity.yRot,
            seenAt = now,
            playerDistance = entity.distanceTo(self).toDouble(),
            invisibleAtFirst = entity.isInvisible,
            playerName = (entity as? Player)?.gameProfile?.name,
            skin = skin,
        )
    }

    private fun update(mob: Mob, entity: LivingEntity, now: Long, self: Player) {
        mob.lastPos = entity.position()
        mob.health = entity.health
        mob.maxHealth = entity.maxHealth
        entity.customName?.let { mob.ownName = DebugText.legacy(it) }
        if (entity.isInvisible != mob.invisible) {
            mob.invisible = entity.isInvisible
            mob.events.add("${if (entity.isInvisible) "invisible" else "visible"} ${rel(now)} player %.1f".format(entity.distanceTo(self)))
        }
        for (slot in EquipmentSlot.entries) {
            val stack = runCatching { entity.getItemBySlot(slot) }.getOrNull() ?: continue
            if (stack.isEmpty) continue
            mob.equipment[slot.name.lowercase()] = DebugText.item(stack)
        }
        if (mob.diedAt == null && entity.isDeadOrDying) {
            mob.diedAt = now
            mob.events.add("died ${rel(now)}")
        }
    }

    private fun isKey(name: String): Boolean {
        val plain = name.replace(Regex("§."), "")
        return plain.contains("Wither Key") || plain.contains("Blood Key")
    }

    private fun noteKey(entity: Entity, name: String, now: Long, self: Player) {
        if (!seenKeys.add(entity.id)) return
        val pos = entity.position()
        val killer = mobs.values.filter { mob ->
            val died = mob.diedAt ?: return@filter false
            now - died <= KEY_DROP_MS && mob.lastPos.distanceTo(pos) <= KEY_DROP_RANGE
        }.minByOrNull { it.lastPos.distanceTo(pos) }
        keys.add(KeyDrop(name, pos, now, killer, entity.distanceTo(self).toDouble()))
    }

    private fun inGrid(x: Double, z: Double): Boolean = x >= GRID && x < GRID + GRID_SPAN && z >= GRID && z < GRID + GRID_SPAN

    private fun rel(now: Long): String = "+${now - runStart}ms"

    // Levers

    private class LeverJob(
        val room: String,
        val shape: String,
        val facing: DungeonRoom.Facing,
        val corner: Vector2i,
        val segments: Set<Pair<Int, Int>>,
        val clicked: BlockPos,
        val secret: SecretRoomData.SecretEntry?,
        val minX: Int,
        val minZ: Int,
        val sizeX: Int,
        val sizeZ: Int,
        val states: Array<BlockState?>,
        val startTick: Int,
        val floor: String?,
    ) {
        var changes: List<Pair<BlockPos, BlockState>>? = null
        var revertedAfterTicks: Int? = null
        var saved = false
        /** Another lever went within [TOO_CLOSE_TICKS]; what changed can't be split between them. */
        var tooClose = false

        fun index(dx: Int, dy: Int, dz: Int) = (dy * sizeZ + dz) * sizeX + dx
    }

    private const val TOO_CLOSE_TICKS = 10
    private var lastLeverClick = 0L
    private var lastLeverPos: BlockPos? = null

    private fun onUse(pos: BlockPos) {
        if (!enabled || !SkyBlockData.inDungeons) return
        val level = McCompat.mc.level ?: return
        val room = DungeonScanner.current ?: return
        val name = room.name ?: return
        val facing = room.facing ?: return
        val corner = room.corner?.let { Vector2i(it.first, it.second) } ?: return
        if (!room.matched) return
        val relPos = DungeonMapUtils.actualToRelative(facing.vanilla(), corner, pos)
        val secret = leverSecrets(name).minByOrNull { relPos.distSqr(BlockPos(it.x, it.y, it.z)) }
            ?.takeIf { relPos.distSqr(BlockPos(it.x, it.y, it.z)) <= 4.0 }
        if (level.getBlockState(pos).block != Blocks.LEVER && secret == null) return
        // Puzzle levers (the water board's) are puzzle workings, not secret passages
        if (room.type == DungeonRoom.Type.PUZZLE && secret == null) return
        val now = System.currentTimeMillis()
        // The same click can come through for both hands
        if (pos == lastLeverPos && now - lastLeverClick < 250) return
        lastLeverPos = pos.immutable()
        lastLeverClick = now

        // Recordings still in their window end here, so they only hold what happened before this lever
        for (open in levers) {
            if (open.changes != null) continue
            open.changes = diff(level, open)
            if (ticks - open.startTick < TOO_CLOSE_TICKS) {
                open.tooClose = true
                ChatUtils.chat("§dRecord §8» §cTwo levers too close together§7, neither is saved. Flick them again a few seconds apart.")
            } else {
                ChatUtils.chat("§dRecord §8» §f${open.changes!!.size} §7blocks changed for the last lever (cut short by this one).")
            }
        }
        val tooClose = levers.any { it.tooClose && ticks - it.startTick < TOO_CLOSE_TICKS }

        val minX = room.segments.minOf { it.first }
        val minZ = room.segments.minOf { it.second }
        val sizeX = room.segments.maxOf { it.first } + 31 - minX
        val sizeZ = room.segments.maxOf { it.second } + 31 - minZ
        val sizeY = SNAPSHOT_MAX_Y - SNAPSHOT_MIN_Y + 1
        val states = arrayOfNulls<BlockState>(sizeX * sizeY * sizeZ)
        val job = LeverJob(name, room.shape, facing, corner, room.segments, relPos, secret, minX, minZ, sizeX, sizeZ, states, ticks, SkyBlockData.dungeonFloor)
        val at = BlockPos.MutableBlockPos()
        for (dy in 0 until sizeY) for (dz in 0 until sizeZ) for (dx in 0 until sizeX) {
            states[job.index(dx, dy, dz)] = level.getBlockState(at.set(minX + dx, SNAPSHOT_MIN_Y + dy, minZ + dz))
        }
        job.tooClose = tooClose
        levers.add(job)
        if (!tooClose) ChatUtils.chat("§dRecord §8» §7Watching the room for what that ${if (secret != null) "§f${secret.secretName.ifBlank { "lever" }}" else "lever"} §7opens...")
    }

    private fun stepLevers(level: ClientLevel) {
        if (levers.isEmpty()) return
        val iterator = levers.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            val age = ticks - job.startTick
            if (job.tooClose) {
                if (job.changes != null || age >= LEVER_DIFF_TICKS) iterator.remove()
                continue
            }
            if (job.changes == null && age >= LEVER_DIFF_TICKS) {
                job.changes = diff(level, job)
                val count = job.changes!!.size
                ChatUtils.chat("§dRecord §8» §f$count §7blocks changed in §f${SecretRoomData.displayName(job.room)}§7. Watching ${LEVER_WATCH_TICKS / 20}s in case it closes again.")
            }
            val changes = job.changes ?: continue
            if (job.revertedAfterTicks == null && changes.isNotEmpty() && age % 20 == 0) {
                val back = changes.all { (pos, _) ->
                    val dy = pos.y - SNAPSHOT_MIN_Y
                    level.getBlockState(pos) == job.states[job.index(pos.x - job.minX, dy, pos.z - job.minZ)]
                }
                if (back) job.revertedAfterTicks = age
            }
            if (age >= LEVER_WATCH_TICKS || job.revertedAfterTicks != null) {
                iterator.remove()
                saveLever(job)
            }
        }
    }

    /** Blocks in the room that differ from the snapshot, ignoring the lever itself. */
    private fun diff(level: ClientLevel, job: LeverJob): List<Pair<BlockPos, BlockState>> {
        val out = ArrayList<Pair<BlockPos, BlockState>>()
        val at = BlockPos.MutableBlockPos()
        val clickedWorld = DungeonMapUtils.relativeToActual(job.facing.vanilla(), job.corner, job.clicked)
        for (dy in 0..(SNAPSHOT_MAX_Y - SNAPSHOT_MIN_Y)) for (dz in 0 until job.sizeZ) for (dx in 0 until job.sizeX) {
            val x = job.minX + dx
            val z = job.minZ + dz
            if ((Math.floorDiv(x - GRID, CELL) * CELL + GRID to Math.floorDiv(z - GRID, CELL) * CELL + GRID) !in job.segments) continue
            at.set(x, SNAPSHOT_MIN_Y + dy, z)
            if (at == clickedWorld) continue
            val before = job.states[job.index(dx, dy, dz)] ?: continue
            val now = level.getBlockState(at)
            if (now != before) out.add(at.immutable() to now)
        }
        return out
    }

    private fun saveLever(job: LeverJob) {
        if (job.saved || job.tooClose || job.changes == null) return
        job.saved = true
        val rotation = rotationFor(job.facing)
        val changes = (job.changes ?: emptyList()).map { (pos, after) ->
            val before = job.states[job.index(pos.x - job.minX, pos.y - SNAPSHOT_MIN_Y, pos.z - job.minZ)] ?: Blocks.AIR.defaultBlockState()
            val rel = DungeonMapUtils.actualToRelative(job.facing.vanilla(), job.corner, pos)
            Triple(rel, BlockStateParser.serialize(before.rotate(rotation)), BlockStateParser.serialize(after.rotate(rotation)))
        }
        val reverted = job.revertedAfterTicks
        worker.execute {
            runCatching {
                val file = dir.resolve("levers").resolve(job.shape).resolve("${job.room}.json")
                val root = read(file)?.asJsonObject ?: JsonObject()
                root.addProperty("room", job.room)
                root.addProperty("name", SecretRoomData.displayName(job.room))
                root.addProperty("shape", job.shape)
                val all = root.getAsJsonObject("levers") ?: JsonObject().also { root.add("levers", it) }
                val key = job.secret?.let { leverKey(it.x, it.y, it.z) } ?: leverKey(job.clicked.x, job.clicked.y, job.clicked.z)
                // Every recording is kept; DungeonSim goes with what most of them agree on
                val lever = all.getAsJsonObject(key) ?: JsonObject().also { all.add(key, it) }
                lever.addProperty("secretName", job.secret?.secretName)
                val recordings = lever.getAsJsonArray("recordings") ?: JsonArray().also { lever.add("recordings", it) }
                val entry = JsonObject()
                entry.add("clicked", ints(job.clicked.x, job.clicked.y, job.clicked.z))
                entry.addProperty("facing", job.facing.name)
                entry.addProperty("floor", job.floor)
                entry.addProperty("recordedAt", Instant.now().toString())
                entry.addProperty("closesAgainAfterMs", reverted?.let { it * 50L })
                val list = JsonArray()
                changes.forEach { (rel, before, after) ->
                    list.add(JsonObject().apply {
                        add("pos", ints(rel.x, rel.y, rel.z))
                        addProperty("from", before)
                        addProperty("to", after)
                    })
                }
                entry.add("changes", list)
                recordings.add(entry)
                while (recordings.size() > MAX_SESSIONS) recordings.remove(0)
                write(file, root)
                logger.info("Lever {} in {}: {} blocks changed, closes again {}", key, job.room, changes.size, reverted?.let { "${it * 50}ms" } ?: "no")
            }.onFailure { logger.error("Saving lever recording failed", it) }
        }
        McCompat.mc.execute {
            ChatUtils.chat("§dRecord §8» §7Saved that lever in §f${SecretRoomData.displayName(job.room)}§7: §f${changes.size} §7blocks${if (reverted != null) ", closed again after §f${reverted / 20}s" else ""}.")
        }
    }

    private fun rotationFor(facing: DungeonRoom.Facing): Rotation = when (facing) {
        DungeonRoom.Facing.NW -> Rotation.NONE
        DungeonRoom.Facing.NE -> Rotation.COUNTERCLOCKWISE_90
        DungeonRoom.Facing.SW -> Rotation.CLOCKWISE_90
        DungeonRoom.Facing.SE -> Rotation.CLOCKWISE_180
    }

    // Saving mobs at the end of the run

    private fun finishRun() {
        if (!inRun) return
        inRun = false
        absentTicks = 0
        levers.forEach { saveLever(it) }
        levers.clear()
        val all = mobs.values.toList()
        val drops = keys.toList()
        val rooms = known.values.toList()
        val enteredCopy = HashMap(entered)
        val start = runStart
        val floor = SkyBlockData.dungeonFloor
        tracked.clear()
        mobs.clear()
        known.clear()
        keys.clear()
        seenKeys.clear()
        entered.clear()
        notified.clear()
        lastRoom = null
        worker.execute { runCatching { saveMobs(all, drops, rooms, enteredCopy, start, floor) }.onFailure { logger.error("Saving mob recording failed", it) } }
    }

    private fun cellOf(pos: Vec3): Pair<Int, Int> =
        Math.floorDiv(Math.floor(pos.x).toInt() - GRID, CELL) * CELL + GRID to Math.floorDiv(Math.floor(pos.z).toInt() - GRID, CELL) * CELL + GRID

    private fun saveMobs(
        mobs: List<Mob>, drops: List<KeyDrop>, rooms: List<KnownRoom>, entered: Map<Pair<Int, Int>, Entered>, start: Long, floor: String?,
    ) {
        var saved = 0
        val names = ArrayList<String>()
        for (room in rooms) {
            val name = room.name
            if (room.shape !in MOB_SHAPES) continue
            val inRoom = mobs.filter { cellOf(it.firstPos) in room.segments }
            val roomKeys = drops.filter { cellOf(it.pos) in room.segments }
            if (inRoom.isEmpty() && roomKeys.isEmpty()) continue
            val facing = room.facing.vanilla()
            val corner = room.corner
            val came = entered[firstCell(room.segments)]

            val session = JsonObject()
            session.addProperty("floor", floor)
            session.addProperty("facing", room.facing.name)
            session.add("corner", ints(corner.x, 0, corner.y))
            session.addProperty("recordedAt", Instant.now().toString())
            session.addProperty("enteredMs", came?.let { it.at - start })
            session.addProperty("clearedBeforeEntering", came?.clearedAlready)
            val list = JsonArray()
            for (mob in inRoom) {
                val o = JsonObject()
                o.addProperty("type", mob.type)
                o.addProperty("uuid", mob.uuid.toString())
                o.addProperty("uuidVersion", mob.uuid.version())
                o.addProperty("name", mob.firstName)
                o.addProperty("lastName", mob.name)
                o.addProperty("customName", mob.ownName)
                o.addProperty("starred", (mob.firstName ?: mob.name ?: "").contains("✯"))
                o.addProperty("playerName", mob.playerName)
                o.addProperty("skin", mob.skin)
                o.add("pos", vec(DungeonMapUtils.actualToRelative(facing, corner, mob.firstPos)))
                o.addProperty("yaw", relYaw(facing, corner, mob.firstPos, mob.firstYaw))
                o.add("lastPos", vec(DungeonMapUtils.actualToRelative(facing, corner, mob.lastPos)))
                o.addProperty("firstSeenMs", mob.seenAt - start)
                o.addProperty("firstSeenAfterEnteringMs", came?.let { mob.seenAt - it.at })
                o.addProperty("playerDistanceAtFirstSeen", round(mob.playerDistance))
                o.addProperty("invisibleAtFirst", mob.invisibleAtFirst)
                o.addProperty("maxHealth", mob.maxHealth)
                o.addProperty("diedMs", mob.diedAt?.let { it - start })
                val gear = JsonObject()
                mob.equipment.forEach { (slot, item) -> gear.addProperty(slot, item) }
                o.add("equipment", gear)
                o.add("events", JsonArray().apply { mob.events.forEach { add(it) } })
                list.add(o)
            }
            session.add("mobs", list)
            val keyList = JsonArray()
            for (drop in roomKeys) {
                keyList.add(JsonObject().apply {
                    addProperty("name", drop.name)
                    add("pos", vec(DungeonMapUtils.actualToRelative(facing, corner, drop.pos)))
                    addProperty("atMs", drop.at - start)
                    addProperty("playerDistance", round(drop.playerDistance))
                    addProperty("droppedByType", drop.droppedBy?.type)
                    addProperty("droppedByName", drop.droppedBy?.let { it.firstName ?: it.name ?: it.playerName })
                    addProperty("droppedByUuid", drop.droppedBy?.uuid?.toString())
                })
            }
            session.add("keys", keyList)

            val file = dir.resolve("mobs").resolve(room.shape).resolve("$name.json")
            val root = read(file)?.asJsonObject ?: JsonObject()
            root.addProperty("room", name)
            root.addProperty("name", SecretRoomData.displayName(name))
            root.addProperty("shape", room.shape)
            val sessions = root.getAsJsonArray("sessions") ?: JsonArray().also { root.add("sessions", it) }
            sessions.add(session)
            while (sessions.size() > MAX_SESSIONS) sessions.remove(0)
            write(file, root)
            saved++
            names.add(SecretRoomData.displayName(name))
        }
        val unplaced = mobs.count { mob -> rooms.none { cellOf(mob.firstPos) in it.segments } }
        McCompat.mc.execute {
            ChatUtils.chat("§dRecord §8» §7Mobs saved for §f$saved §7rooms${if (names.isEmpty()) "" else ": §f${names.joinToString(", ")}"}§7. §f${drops.size} §7keys seen, §f$unplaced §7mobs in rooms PA never matched.")
            status(shortForm = true)
        }
    }

    private fun relYaw(facing: dev.pawfect.addons.features.dungeon.rooms.Room.Direction, corner: Vector2i, pos: Vec3, yaw: Float): Float {
        val rad = Math.toRadians(yaw.toDouble())
        val ahead = pos.add(-Math.sin(rad), 0.0, Math.cos(rad))
        val a = DungeonMapUtils.actualToRelative(facing, corner, pos)
        val b = DungeonMapUtils.actualToRelative(facing, corner, ahead)
        return Math.toDegrees(atan2(-(b.x - a.x), b.z - a.z)).toFloat()
    }

    // What's left

    private fun leverSecrets(room: String): List<SecretRoomData.SecretEntry> =
        SecretRoomData.secretsFor(room).filter { it.category == "lever" && !it.secretName.contains("Pressure Plate", ignoreCase = true) }

    private fun leverKey(x: Int, y: Int, z: Int) = "$x,$y,$z"

    private fun mobsDone(shape: String, room: String): Boolean {
        val json = read(dir.resolve("mobs").resolve(shape).resolve("$room.json"))?.asJsonObject ?: return false
        return json.getAsJsonArray("sessions")?.any { session ->
            val s = session.asJsonObject
            s.get("enteredMs")?.isJsonNull == false && s.get("clearedBeforeEntering")?.asBoolean != true
        } == true
    }

    private fun recordedLevers(shape: String, room: String): Set<String> {
        val json = read(dir.resolve("levers").resolve(shape).resolve("$room.json"))?.asJsonObject ?: return emptySet()
        return json.getAsJsonObject("levers")?.entrySet()
            ?.filter { lever ->
                lever.value.asJsonObject.getAsJsonArray("recordings")?.any { (it.asJsonObject.getAsJsonArray("changes")?.size() ?: 0) > 0 } == true
            }
            ?.map { it.key }?.toSet() ?: emptySet()
    }

    /** /pa dev record: what's recorded and what's still missing, for every room, not just this run's. */
    fun status(shortForm: Boolean = false) {
        SecretRoomData.load()
        worker.execute {
            val mobRooms = MOB_SHAPES.flatMap { shape -> SecretRoomData.roomsForShape(shape).keys.map { shape to it } }
            val mobsLeft = mobRooms.filter { (shape, room) -> !mobsDone(shape, room) }
            val leverRooms = SecretRoomData.shapes.flatMap { shape ->
                SecretRoomData.roomsForShape(shape).keys.filter { leverSecrets(it).isNotEmpty() }.map { shape to it }
            }
            val leverLeft = leverRooms.mapNotNull { (shape, room) ->
                val wanted = leverSecrets(room)
                val have = recordedLevers(shape, room)
                val left = wanted.count { leverKey(it.x, it.y, it.z) !in have }
                if (left == 0) null else Triple(shape, room, left to wanted.size)
            }
            McCompat.mc.execute {
                ChatUtils.chat("§dRoom recording §7is ${if (enabled) "§aon" else "§coff"}§7. Mobs: §f${mobRooms.size - mobsLeft.size}§7/§f${mobRooms.size} §7rooms, levers: §f${leverRooms.size - leverLeft.size}§7/§f${leverRooms.size} §7rooms.")
                if (shortForm) return@execute
                mobsLeft.groupBy({ it.first }, { it.second }).forEach { (shape, rooms) ->
                    ChatUtils.chat("§7Mobs missing §f$shape §7(${rooms.size}): §f${rooms.map(SecretRoomData::displayName).sorted().joinToString(", ")}")
                }
                if (leverLeft.isNotEmpty()) {
                    ChatUtils.chat("§7Levers missing: §f" + leverLeft.sortedBy { SecretRoomData.displayName(it.second) }
                        .joinToString(", ") { (_, room, count) -> "${SecretRoomData.displayName(room)}${if (count.second > 1) " ${count.first}/${count.second}" else ""}" })
                }
                ChatUtils.chat("§7Files in §f$dir")
            }
        }
    }

    // Files

    private fun read(file: Path): JsonElement? =
        if (!Files.exists(file)) null else runCatching { Files.newBufferedReader(file).use { JsonParser.parseReader(it) } }.getOrNull()

    private fun write(file: Path, json: JsonElement) {
        Files.createDirectories(file.parent)
        Files.writeString(file, gson.toJson(json))
    }

    private fun ints(vararg values: Int) = JsonArray().apply { values.forEach { add(it) } }

    private fun vec(v: Vec3) = JsonArray().apply {
        add(round(v.x))
        add(round(v.y))
        add(round(v.z))
    }

    private fun round(d: Double) = Math.round(d * 100) / 100.0
}
