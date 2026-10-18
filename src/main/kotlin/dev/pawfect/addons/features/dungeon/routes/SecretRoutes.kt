package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.SecretRoutesConfig
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import dev.pawfect.addons.features.dungeon.secrets.SecretTracker
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

object SecretRoutes {

    class Progress(val route: List<RouteData.Step>, val note: String? = null) {
        var index = 0

        var complete = false

        val done: Boolean get() = complete || index >= route.size

        val collected: MutableSet<SecretRoomData.SecretEntry> = Collections.newSetFromMap(IdentityHashMap())

        val passed = HashSet<Int>()

        var hold = false

        val seen: MutableSet<SecretRoomData.SecretEntry> = Collections.newSetFromMap(IdentityHashMap())

        var nextRescan = 0L
    }

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.secretRoutes)

    private val logger = LoggerFactory.getLogger("PawfectAddons/Routes")

    private val progress: MutableMap<DungeonRoom, Progress> = Collections.synchronizedMap(IdentityHashMap())

    private var loaded = false

    private var lastSource: SecretRoutesConfig.Source? = null

    private const val ITEM_RANGE_SQ = 25
    private const val BAT_RANGE_SQ = 144
    private const val INTERACT_RANGE_SQ = 2

    fun active(): Boolean = config.enabled && SkyBlockData.inDungeons

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { tick() } }
    }

    fun reload() {
        RouteData.load()
        progress.clear()
    }

    fun current(): Pair<DungeonRoom, Progress>? {
        if (!active()) return null
        val room = DungeonScanner.matchedRoom() ?: return null
        val name = room.name ?: return null
        val existing = progress[room]
        if (existing != null) return room to existing
        @Suppress("SENSELESS_COMPARISON")
        val source = config.source ?: SecretRoutesConfig.Source.DEFAULT
        val picked = RouteData.routesFor(name, source) ?: return null
        val fill = config.fillMissing && picked.from != SecretRoutesConfig.Source.RECORDED
        val levers = config.useLevers && !picked.ours
        val routes = picked.routes.map { RouteFit.prepare(name, it, fill, levers) }
        if (routes.isEmpty()) return null
        val here = here(room) ?: return null
        val route = routes.minBy { route -> route.firstNotNullOfOrNull { it.start }?.let { distanceSq(it, here) } ?: Double.MAX_VALUE }
        logger.info(
            "Route for {} from {}: {} steps, {} added from our room data, {} levers",
            name, picked.from, route.size, route.count { it.extra }, route.count { it.secretType == "lever" || it.secretType == "plate" },
        )
        return room to Progress(route, picked.note).also { progress[room] = it }
    }

    private fun tick() {
        if (!loaded) {
            loaded = true
            RouteData.load()
        }
        if (config.source != lastSource) {
            lastSource = config.source
            progress.clear()
        }
        RouteRecorder.tick()
        if (!SkyBlockData.inDungeons) {
            if (progress.isNotEmpty()) progress.clear()
            return
        }
        val (room, state) = current() ?: return
        if (room !== lastRoom) {
            lastRoom = room
            enteredAt = System.currentTimeMillis()
        }
        if (state.done) return
        roomComplete(room, state)?.let { why ->
            state.complete = true
            logger.info("Route for {} finished: {}", room.name, why)
            return
        }
        val here = here(room) ?: return
        val now = System.currentTimeMillis()
        if (unexplained > 0 && now - unexplainedAt >= BAR_SETTLE_MS) {
            if (lastCollectAt < unexplainedAt - BAR_EXPLAIN_MS) creditBat(room, state)
            unexplained = 0
        }
        if (now >= state.nextRescan) {
            state.nextRescan = now + RESCAN_MS
            rescan(room, state)
        }

        val step = state.route[state.index]
        if (step.walkOnly) {
            val target = step.target
            if (target == null || reached(here, target)) {
                state.passed += state.index
                state.hold = false
            }
        }
        if (!state.hold) advance(state)
    }

    private var lastRoom: DungeonRoom? = null
    private var enteredAt = 0L

    private var barFound = -1
    private var barTotal = -1
    private var barAt = 0L

    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")

    fun onActionBar(message: net.minecraft.network.chat.Component) {
        val match = SECRETS.find(message.string.replace(Regex("§."), "")) ?: return
        barFound = match.groupValues[1].toInt()
        barTotal = match.groupValues[2].toInt()
        barAt = System.currentTimeMillis()
        val room = DungeonScanner.matchedRoom()
        if (room == null || room !== barRoom || barAt - enteredAt < 2_000L || barFound < barBaseline) {
            barRoom = room
            barBaseline = barFound
            return
        }
        if (barFound > barBaseline) {
            unexplained += barFound - barBaseline
            unexplainedAt = barAt
            barBaseline = barFound
        }
    }

    private var barRoom: DungeonRoom? = null
    private var barBaseline = -1

    private var unexplained = 0
    private var unexplainedAt = 0L

    private var lastCollectAt = 0L

    private const val BAR_SETTLE_MS = 750L
    private const val BAR_EXPLAIN_MS = 1_500L

    private fun roomComplete(room: DungeonRoom, state: Progress): String? {
        if (room.greenChecked) return "green checkmark"
        val now = System.currentTimeMillis()
        if (barTotal > 0 && barFound >= barTotal && now - barAt < 1_500L && barAt - enteredAt > 2_000L) return "action bar says $barFound/$barTotal secrets"
        val all = state.route.indices.all { k -> state.route[k].walkOnly || stepDone(state, k) } && state.route.any { !it.walkOnly }
        return if (all) "every secret step done" else null
    }

    private const val RESCAN_MS = 3_000L

    private const val RESCAN_RANGE_SQ = 48.0 * 48.0

    private fun rescan(room: DungeonRoom, state: Progress) {
        val level = McCompat.mc.level ?: return
        val player = McCompat.player ?: return
        var changed = false
        state.route.forEach { step ->
            val secret = step.ours ?: return@forEach
            if (secret in state.collected) return@forEach
            val pos = DungeonScanner.relativeToActual(room, secret.x, secret.y, secret.z)
            if (player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > RESCAN_RANGE_SQ) return@forEach
            if (!level.isLoaded(pos)) return@forEach
            val present = when (secret.category) {
                "item" -> level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity::class.java,
                    net.minecraft.world.phys.AABB(pos).inflate(2.0),
                ).isNotEmpty()
                "wither" -> level.getBlockState(pos).block.let {
                    it == net.minecraft.world.level.block.Blocks.PLAYER_HEAD || it == net.minecraft.world.level.block.Blocks.PLAYER_WALL_HEAD
                }
                "bat" -> batNear(pos)
                else -> return@forEach
            }
            if (present) state.seen += secret
            else if (secret in state.seen) {
                state.collected += secret
                changed = true
                if (secret.category == "bat") logger.info("Bat for {} is gone, killed by someone", secret.secretName)
            }
        }
        if (changed) lastCollectAt = System.currentTimeMillis()
        if (changed && !state.hold) advance(state)
    }

    private const val BAT_ALIVE_SQ = 24.0 * 24.0

    private fun batNear(pos: BlockPos): Boolean {
        val level = McCompat.mc.level ?: return true
        return level.getEntitiesOfClass(
            net.minecraft.world.entity.ambient.Bat::class.java,
            net.minecraft.world.phys.AABB(pos).inflate(24.0),
        ).any { it.isAlive && it.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= BAT_ALIVE_SQ }
    }

    private fun creditBat(room: DungeonRoom, state: Progress) {
        val k = (state.index until state.route.size).firstOrNull { !state.route[it].walkOnly && !stepDone(state, it) } ?: return
        val step = state.route[k]
        if (step.secretType != "bat") return
        val at = step.ours?.let { intArrayOf(it.x, it.y, it.z) } ?: step.secretAt ?: return
        if (batNear(DungeonScanner.relativeToActual(room, at[0], at[1], at[2]))) return
        step.ours?.let { state.collected += it }
        state.passed += k
        state.hold = false
        logger.info("Secret count went up with no bat seen dying, counting step {} (bat) as done", k + 1)
        advance(state)
    }

    private fun secretDone(state: Progress, step: RouteData.Step): Boolean {
        val ours = step.ours ?: return false
        return ours in state.collected || SecretTracker.isClaimed(ours)
    }

    private fun stepDone(state: Progress, k: Int): Boolean {
        if (k in state.passed) return true
        val step = state.route[k]
        if (step.secretType == "lever") {
            if (secretDone(state, step)) return true
            val opens = (k + 1 until state.route.size).firstOrNull { state.route[it].secretType != "lever" } ?: return false
            return stepDone(state, opens)
        }
        if (!step.walkOnly) return secretDone(state, step)
        return (k + 1 until state.route.size).any { later ->
            val next = state.route[later]
            !next.walkOnly && (later in state.passed || secretDone(state, next))
        }
    }

    private fun advance(state: Progress) {
        while (!state.done && stepDone(state, state.index)) state.index++
    }

    private fun here(room: DungeonRoom): IntArray? {
        val player = McCompat.player ?: return null
        val rel = DungeonScanner.actualToRelative(room, player.blockPosition())
        return intArrayOf(rel.x, rel.y, rel.z)
    }

    private fun reached(here: IntArray, target: IntArray): Boolean {
        val dx = here[0] - target[0]
        val dz = here[2] - target[2]
        return dx * dx + dz * dz <= 4 && abs(here[1] - target[1]) <= 2
    }

    private fun distanceSq(a: IntArray, b: IntArray): Double {
        val dx = (a[0] - b[0]).toDouble()
        val dy = (a[1] - b[1]).toDouble()
        val dz = (a[2] - b[2]).toDouble()
        return dx * dx + dy * dy + dz * dz
    }

    private fun collected(pos: BlockPos, type: String, ourRangeSq: Int, routeRange: Double) {
        val (room, state) = current() ?: return
        lastCollectAt = System.currentTimeMillis()
        val name = room.name ?: return
        val rel = DungeonScanner.actualToRelative(room, pos)
        val at = intArrayOf(rel.x, rel.y, rel.z)
        val categories = RouteFit.CATEGORIES.getValue(type)

        val ours = SecretRoomData.secretsFor(name)
            .filter { it.category in categories }
            .map { it to distanceSq(intArrayOf(it.x, it.y, it.z), at) }
            .filter { it.second <= ourRangeSq }
            .minByOrNull { it.second }
            ?.first
        if (ours != null && state.route.any { it.ours === ours }) {
            state.collected += ours
        } else {
            val k = (state.index until state.route.size).firstOrNull { k ->
                val step = state.route[k]
                step.ours == null && (step.secretType == type || type == "interact" && step.secretType == "lever") && k !in state.passed &&
                    step.secretAt?.let { distanceSq(it, at) <= routeRange * routeRange } == true
            } ?: return
            state.passed += k
        }
        state.hold = false
        advance(state)
    }

    private var lastInteract: BlockPos? = null
    private var lastInteractAt = 0L

    @JvmStatic
    fun onInteract(pos: BlockPos) {
        val now = System.currentTimeMillis()
        if (pos == lastInteract && now - lastInteractAt < 1_000L) return
        lastInteract = pos
        lastInteractAt = now
        runCatching {
            RouteRecorder.onInteract(pos)
            if (active()) collected(pos, "interact", INTERACT_RANGE_SQ, 1.75)
        }
    }

    @JvmStatic
    fun onItemPicked(pos: BlockPos) {
        runCatching {
            RouteRecorder.onItem(pos)
            if (active()) collected(pos, "item", ITEM_RANGE_SQ, 4.0)
        }
    }

    @JvmStatic
    fun onBatDeath(pos: BlockPos) {
        runCatching {
            RouteRecorder.onBat(pos)
            if (active()) collected(pos, "bat", BAT_RANGE_SQ, 12.0)
        }
    }

    fun next(): String {
        val (_, state) = current() ?: return "No route for this room."
        if (state.done) return "This room's route is done."
        state.passed += state.index
        state.hold = false
        advance(state)
        return if (state.done) "Route done." else "Step ${state.index + 1} of ${state.route.size}."
    }

    fun back(): String {
        val (_, state) = current() ?: return "No route for this room."
        state.index = (state.index - 1).coerceAtLeast(0)
        state.passed -= state.index
        state.hold = true
        return "Step ${state.index + 1} of ${state.route.size}."
    }

    fun restart(): String {
        val room = DungeonScanner.matchedRoom() ?: return "This room isn't recognised yet."
        progress.remove(room)
        return if (current() != null) "Route restarted." else "No route for this room."
    }

    fun status(): String {
        val room = DungeonScanner.matchedRoom()
        val state = room?.let { progress[it] }
        val extra = state?.route?.count { it.extra } ?: 0
        return "Routes: ${RouteData.report}. Room: ${room?.name ?: "unknown"}" +
            (state?.let { ", step ${(it.index + 1).coerceAtMost(it.route.size)} of ${it.route.size}" } ?: "") +
            (state?.note?.let { ". $it" } ?: "") +
            (if (extra > 0) ", $extra added from our room data" else "")
    }
}
