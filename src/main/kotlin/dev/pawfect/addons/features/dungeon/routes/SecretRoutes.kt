package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import dev.pawfect.addons.features.dungeon.secrets.SecretTracker
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.core.BlockPos
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Follows a route through each room. A step is done when you collect its secret (an item you
 * pick up, a chest, essence or lever you click, a bat that dies near it) or, for a step that
 * only walks somewhere, when you get there. Collecting a later step's secret first jumps ahead
 * to it. Secrets our tracker has already marked as claimed count too.
 */
object SecretRoutes {

    class Progress(val route: List<RouteData.Step>) {
        var index = 0
        val done: Boolean get() = index >= route.size
    }

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.secretRoutes)

    /** Each room of this run, to how far along its route you are. */
    private val progress: MutableMap<DungeonRoom, Progress> = Collections.synchronizedMap(IdentityHashMap())

    private var loaded = false

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
        val routes = RouteData.routesFor(name, config.usePearlRoutes)
        if (routes.isEmpty()) return null
        val here = here(room) ?: return null
        // With more than one route for the room, the one starting nearest to you.
        val route = routes.minBy { route -> route.firstNotNullOfOrNull { it.start }?.let { distanceSq(it, here) } ?: Double.MAX_VALUE }
        return room to Progress(route).also { progress[room] = it }
    }

    private fun tick() {
        if (!loaded) {
            loaded = true
            RouteData.load()
        }
        RouteRecorder.tick()
        if (!SkyBlockData.inDungeons) {
            if (progress.isNotEmpty()) progress.clear()
            return
        }
        val (room, state) = current() ?: return
        if (state.done) return
        val here = here(room) ?: return

        // Secrets our own tracker has already marked as claimed.
        val name = room.name ?: return
        val claimedAt = SecretRoomData.secretsFor(name)
            .filter { SecretTracker.isClaimed(it) }
            .map { intArrayOf(it.x, it.y, it.z) }
        for (k in state.index until state.route.size) {
            val at = state.route[k].secretAt ?: continue
            if (claimedAt.any { it.contentEquals(at) }) state.index = k + 1
        }
        if (state.done) return

        // A step with nothing to collect is done once you reach it.
        val step = state.route[state.index]
        val type = step.secretType
        if (type == null || type == "exit" || type == "exitroute" || type == "dummy") {
            val target = step.target ?: run { state.index++; return }
            if (reached(here, target)) state.index++
        }
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

    /** Moves past the first step from here on whose secret is [type] within [range] of [pos]. */
    private fun collected(pos: BlockPos, types: Set<String>, range: Double) {
        val (room, state) = current() ?: return
        if (state.done) return
        val rel = DungeonScanner.actualToRelative(room, pos)
        val at = intArrayOf(rel.x, rel.y, rel.z)
        for (k in state.index until state.route.size) {
            val step = state.route[k]
            val secret = step.secretAt ?: continue
            if (step.secretType !in types) continue
            if (distanceSq(secret, at) <= range * range) {
                state.index = k + 1
                return
            }
        }
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
            if (active()) collected(pos, setOf("interact"), 1.75)
        }
    }

    @JvmStatic
    fun onItemPicked(pos: BlockPos) {
        runCatching {
            RouteRecorder.onItem(pos)
            if (active()) collected(pos, setOf("item"), 4.0)
        }
    }

    @JvmStatic
    fun onBatDeath(pos: BlockPos) {
        runCatching {
            RouteRecorder.onBat(pos)
            if (active()) collected(pos, setOf("bat"), 12.0)
        }
    }

    // ------------------------------------------------------------------ manual control

    fun next(): String {
        val (_, state) = current() ?: return "No route for this room."
        if (state.done) return "This room's route is done."
        state.index++
        return "Step ${state.index + 1} of ${state.route.size}."
    }

    fun back(): String {
        val (_, state) = current() ?: return "No route for this room."
        state.index = (state.index - 1).coerceAtLeast(0)
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
        return "Routes: ${RouteData.report}. Room: ${room?.name ?: "unknown"}" +
            (state?.let { ", step ${(it.index + 1).coerceAtMost(it.route.size)} of ${it.route.size}" } ?: "")
    }
}
