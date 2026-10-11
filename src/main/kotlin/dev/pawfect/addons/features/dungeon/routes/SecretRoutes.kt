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

/**
 * Follows a route through each room. A step is done when you collect its secret (an item you
 * pick up, a chest, essence or lever you click, a bat that dies near it) or, for a step that
 * only walks somewhere, when you get there or collect something after it.
 *
 * Collecting secrets out of order never skips the ones you haven't got: the route waits on
 * the first secret still left, so every secret in the room gets done.
 */
object SecretRoutes {

    /** [note] says why the route isn't from the set you picked, shown with it; null when it is. */
    class Progress(val route: List<RouteData.Step>, val note: String? = null) {
        /** The step being shown. */
        var index = 0

        /** Every secret in the room is collected, so nothing is left to show. */
        var complete = false

        val done: Boolean get() = complete || index >= route.size

        /** Our secrets collected in this room. */
        val collected: MutableSet<SecretRoomData.SecretEntry> = Collections.newSetFromMap(IdentityHashMap())

        /** Steps done some other way: reached, skipped by hand, or collected near the route's own spot. */
        val passed = HashSet<Int>()

        /** Set by going back a step, so it stays shown until something happens. */
        var hold = false

        /** Items and essences seen in place, so one that's gone later was taken by someone. */
        val seen: MutableSet<SecretRoomData.SecretEntry> = Collections.newSetFromMap(IdentityHashMap())

        var nextRescan = 0L
    }

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.secretRoutes)

    private val logger = LoggerFactory.getLogger("PawfectAddons/Routes")

    /** Each room of this run, to how far along its route you are. */
    private val progress: MutableMap<DungeonRoom, Progress> = Collections.synchronizedMap(IdentityHashMap())

    private var loaded = false

    /** The route source rooms were last set up from, so switching it takes effect straight away. */
    private var lastSource: SecretRoutesConfig.Source? = null

    /** Our own ranges for telling which secret an event was, matching the secret tracker's. */
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

    /** The room you are in and how far along its route you are, once the room is known. */
    fun current(): Pair<DungeonRoom, Progress>? {
        if (!active()) return null
        val room = DungeonScanner.matchedRoom() ?: return null
        val name = room.name ?: return null
        val existing = progress[room]
        if (existing != null) return room to existing
        // An old config can hold a source that's gone; Gson leaves it null.
        @Suppress("SENSELESS_COMPARISON")
        val source = config.source ?: SecretRoutesConfig.Source.DEFAULT
        val picked = RouteData.routesFor(name, source) ?: return null
        val fill = config.fillMissing && picked.from != SecretRoutesConfig.Source.RECORDED
        // Our own routes already go to their levers where it makes sense.
        val levers = config.useLevers && !picked.ours
        val routes = picked.routes.map { RouteFit.prepare(name, it, fill, levers) }
        if (routes.isEmpty()) return null
        val here = here(room) ?: return null
        // With more than one route for the room, the one starting nearest to you.
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

        // Reaching a walking step's end.
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

    /** The action bar's "found/total Secrets" for the room you're in, and when it last said so. */
    private var barFound = -1
    private var barTotal = -1
    private var barAt = 0L

    private val SECRETS = Regex("""(\d+)/(\d+) Secrets""")

    fun onActionBar(message: net.minecraft.network.chat.Component) {
        val match = SECRETS.find(message.string.replace(Regex("§."), "")) ?: return
        barFound = match.groupValues[1].toInt()
        barTotal = match.groupValues[2].toInt()
        barAt = System.currentTimeMillis()
        // The count is the room's, whoever collects; a rise is a secret found. Fresh in a room it's
        // still the last room's for a moment, so that's only taken as where the count starts.
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

    /** The room the action bar's count was last read in, and the count then. */
    private var barRoom: DungeonRoom? = null
    private var barBaseline = -1

    /** Secrets the action bar counted that no event of ours explained yet, and when it counted them. */
    private var unexplained = 0
    private var unexplainedAt = 0L

    /** When something was last collected by the route's own events, to explain a rise in the count. */
    private var lastCollectAt = 0L

    /** How long a rise in the count waits for our own event, and how far back one still explains it. */
    private const val BAR_SETTLE_MS = 750L
    private const val BAR_EXPLAIN_MS = 1_500L

    /**
     * Every secret here is collected: the room's checkmark is green, the action bar says all
     * of them are found, or every step that collects something is done.
     */
    private fun roomComplete(room: DungeonRoom, state: Progress): String? {
        if (room.greenChecked) return "green checkmark"
        val now = System.currentTimeMillis()
        // The action bar lags a moment behind walking into a room, so it only counts once you've been here a bit.
        if (barTotal > 0 && barFound >= barTotal && now - barAt < 1_500L && barAt - enteredAt > 2_000L) return "action bar says $barFound/$barTotal secrets"
        val all = state.route.indices.all { k -> state.route[k].walkOnly || stepDone(state, k) } && state.route.any { !it.walkOnly }
        return if (all) "every secret step done" else null
    }

    private const val RESCAN_MS = 3_000L

    /** How close, squared, a secret has to be for its item or essence to be checked. */
    private const val RESCAN_RANGE_SQ = 48.0 * 48.0

    /**
     * Looks over the room's items and essences that are still to collect. One that was there
     * earlier and is gone now was taken, by you or a teammate, so the route moves past it.
     * Chests and bats can't be told apart this way and are left to their own events.
     */
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

    /** How far, squared, a secret bat flutters from its spot while it's still alive. */
    private const val BAT_ALIVE_SQ = 24.0 * 24.0

    private fun batNear(pos: BlockPos): Boolean {
        val level = McCompat.mc.level ?: return true
        return level.getEntitiesOfClass(
            net.minecraft.world.entity.ambient.Bat::class.java,
            net.minecraft.world.phys.AABB(pos).inflate(24.0),
        ).any { it.isAlive && it.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= BAT_ALIVE_SQ }
    }

    /**
     * The room's secret count went up and nothing we saw explains it: a bat killed out of sight,
     * or by a teammate. When the next secret the route waits on is a bat that isn't around any
     * more, that was it.
     */
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
        // A lever is done once flipped, or once the chest it opens is collected anyway.
        if (step.secretType == "lever") {
            if (secretDone(state, step)) return true
            val opens = (k + 1 until state.route.size).firstOrNull { state.route[it].secretType != "lever" } ?: return false
            return stepDone(state, opens)
        }
        if (!step.walkOnly) return secretDone(state, step)
        // A walk is behind you once anything after it is collected.
        return (k + 1 until state.route.size).any { later ->
            val next = state.route[later]
            !next.walkOnly && (later in state.passed || secretDone(state, next))
        }
    }

    /** Moves past every step that's done, stopping at the first one that isn't. */
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

    /**
     * Something of [type] was collected at [pos]. It counts as our nearest secret of that kind
     * when there is one in range; otherwise as the route's own step whose spot is near it.
     */
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
            // A step not tied to any of our secrets: the first one from here whose spot is close.
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

    // ------------------------------------------------------------------ events, from the secret tracker

    private var lastInteract: BlockPos? = null
    private var lastInteractAt = 0L

    @JvmStatic
    fun onInteract(pos: BlockPos) {
        // A chest reports both the click and its opening; one is enough.
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

    // ------------------------------------------------------------------ manual control

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
