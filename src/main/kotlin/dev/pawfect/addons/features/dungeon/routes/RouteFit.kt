package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import org.slf4j.LoggerFactory
import kotlin.math.max

/**
 * Fits a route to our own data for its room before it's followed:
 *
 * - Some routes were recorded with the room turned around from ours (Slime is half a turn out).
 *   Whichever turn puts the route's chests and items exactly on our secrets is used.
 * - Each step is tied to the secret it collects, so a step completes from our exact secret
 *   positions rather than wherever the route's author happened to be standing.
 * - Secrets the route never gets are added before it leaves the room, with the lever, stonk,
 *   superboom and entrance hints our data has for them.
 */
object RouteFit {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Routes")

    private enum class Turn { NONE, HALF, QUARTER, THREE_QUARTERS }

    /** Our secret categories a route's secret type can be. */
    val CATEGORIES: Map<String, Set<String>> = mapOf(
        "item" to setOf("item"),
        "bat" to setOf("bat"),
        "interact" to setOf("chest", "wither", "lever", "redstone_key"),
    )

    /** How far a route's secret can be from ours and still be the same one, squared. */
    private val MATCH_RANGE_SQ = mapOf("item" to 100, "bat" to 256, "interact" to 4)

    /** Our secrets worth collecting, and the route secret type each one is. */
    private val COLLECTABLE = mapOf("chest" to "interact", "wither" to "interact", "item" to "item", "bat" to "bat")

    private val INDEX = Regex("""^\s*([0-9]+(?:\s*/\s*[0-9]+)*)""")

    fun prepare(room: String, route: List<RouteData.Step>, fill: Boolean): List<RouteData.Step> {
        val secrets = SecretRoomData.secretsFor(room)
        if (secrets.isEmpty() || route.isEmpty()) return route
        val turned = turn(room, route, secrets)
        val assigned = assign(turned, secrets)
        return if (fill) fillMissing(assigned, secrets) else assigned
    }

    // ------------------------------------------------------------------ turning

    private fun turn(room: String, route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val box = box(room) ?: return route
        val (mx, mz) = box
        val turns = if (mx == mz) Turn.entries else listOf(Turn.NONE, Turn.HALF)
        val scores = turns.associateWith { turn -> score(route, secrets) { turnBlock(it, turn, mx, mz) } }
        val none = scores.getValue(Turn.NONE)
        val (best, bestScore) = scores.entries.maxBy { it.value.first * 2 + it.value.second }
        // Only turned when it clearly fits better: at least two secrets land exactly on ours.
        if (best == Turn.NONE || bestScore.first < 2 || bestScore.first <= none.first) return route
        logger.info("Route for {} is turned {} from our room data, lining it up ({} exact before, {} after)", room, best, none.first, bestScore.first)
        return route.map { step -> turnStep(step, best, mx, mz) }
    }

    /** Route secrets landing exactly on one of ours, and within a few blocks of one. */
    private fun score(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>, turn: (IntArray) -> IntArray): Pair<Int, Int> {
        var exact = 0
        var near = 0
        route.forEach { step ->
            val categories = CATEGORIES[step.secretType] ?: return@forEach
            val at = turn(step.secretAt ?: return@forEach)
            val best = secrets.filter { it.category in categories }.minOfOrNull { distanceSq(it, at) } ?: return@forEach
            if (best <= 1) exact++ else if (best <= 64) near++
        }
        return exact to near
    }

    /** The room's size in blocks along x and z, less one, from its block layout. */
    private fun box(room: String): Pair<Int, Int>? {
        val blocks = SecretRoomData.shapes.firstNotNullOfOrNull { SecretRoomData.roomsForShape(it)[room] } ?: return null
        var mx = 0
        var mz = 0
        for (v in blocks) {
            mx = max(mx, (v ushr 24) and 0xFF)
            mz = max(mz, (v ushr 8) and 0xFF)
        }
        // Rooms are whole 32 block cells less the gap between them.
        fun snap(m: Int) = ((m + 32) / 32) * 32 - 2
        return snap(mx) to snap(mz)
    }

    private fun turnBlock(p: IntArray, turn: Turn, mx: Int, mz: Int): IntArray = when (turn) {
        Turn.NONE -> p
        Turn.HALF -> intArrayOf(mx - p[0], p[1], mz - p[2])
        Turn.QUARTER -> intArrayOf(mx - p[2], p[1], p[0])
        Turn.THREE_QUARTERS -> intArrayOf(p[2], p[1], mz - p[0])
    }

    /** The same turn for a point inside a block, like where a pearl was thrown from. */
    private fun turnPoint(p: DoubleArray, turn: Turn, mx: Int, mz: Int): DoubleArray = when (turn) {
        Turn.NONE -> p
        Turn.HALF -> doubleArrayOf(mx + 1 - p[0], p[1], mz + 1 - p[2])
        Turn.QUARTER -> doubleArrayOf(mx + 1 - p[2], p[1], p[0])
        Turn.THREE_QUARTERS -> doubleArrayOf(p[2], p[1], mz + 1 - p[0])
    }

    private fun turnYaw(yaw: Float, turn: Turn): Float = when (turn) {
        Turn.NONE -> yaw
        Turn.HALF -> yaw + 180f
        Turn.QUARTER -> yaw + 90f
        Turn.THREE_QUARTERS -> yaw - 90f
    }

    private fun turnStep(step: RouteData.Step, turn: Turn, mx: Int, mz: Int): RouteData.Step {
        fun blocks(list: List<IntArray>) = list.map { turnBlock(it, turn, mx, mz) }
        return RouteData.Step(
            blocks(step.locations), blocks(step.etherwarps), blocks(step.mines), blocks(step.interacts), blocks(step.tnts),
            step.pearls.map { turnPoint(it, turn, mx, mz) },
            step.pearlAngles.map { floatArrayOf(it[0], turnYaw(it.getOrElse(1) { 0f }, turn)) },
            step.secretType, step.secretAt?.let { turnBlock(it, turn, mx, mz) }, step.ours, step.extra,
        )
    }

    // ------------------------------------------------------------------ tying steps to our secrets

    private class Pairing(val step: Int, val secret: SecretRoomData.SecretEntry, val distance: Int)

    private fun assign(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val pairings = ArrayList<Pairing>()
        route.forEachIndexed { i, step ->
            val categories = CATEGORIES[step.secretType] ?: return@forEachIndexed
            val at = step.secretAt ?: return@forEachIndexed
            val range = MATCH_RANGE_SQ.getValue(step.secretType!!)
            secrets.filter { it.category in categories }.forEach { secret ->
                val d = distanceSq(secret, at)
                if (d <= range) pairings += Pairing(i, secret, d)
            }
        }
        // Closest pairs first, each secret once, except a step listing the very same secret again.
        val chosen = HashMap<Int, SecretRoomData.SecretEntry>()
        val taken = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SecretRoomData.SecretEntry, Boolean>())
        pairings.sortedBy { it.distance }.forEach { p ->
            if (p.step in chosen) return@forEach
            if (p.secret in taken && p.distance > 1) return@forEach
            chosen[p.step] = p.secret
            taken += p.secret
        }
        return route.mapIndexed { i, step ->
            val ours = chosen[i] ?: return@mapIndexed step
            RouteData.Step(step.locations, step.etherwarps, step.mines, step.interacts, step.tnts, step.pearls, step.pearlAngles, step.secretType, step.secretAt, ours, step.extra)
        }
    }

    // ------------------------------------------------------------------ secrets the route skips

    private fun fillMissing(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val covered = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SecretRoomData.SecretEntry, Boolean>())
        route.forEach { step -> step.ours?.let { covered += it } }
        val missing = secrets.filter { it.category in COLLECTABLE && it !in covered }.toMutableList()
        if (missing.isEmpty()) return route

        // A route that mostly doesn't line up with our data can't say what it skips.
        val untied = route.filter { it.secretType in CATEGORIES && it.ours == null }
        val secretSteps = route.count { it.secretType in CATEGORIES }
        if (secretSteps > 0 && (secretSteps - untied.size) * 2 < secretSteps) return route
        // A step we couldn't tie to one of ours is most likely still one of the missing ones.
        untied.forEach { step ->
            val categories = CATEGORIES.getValue(step.secretType!!)
            val at = step.secretAt ?: return@forEach
            missing.filter { it.category in categories }.minByOrNull { distanceSq(it, at) }?.let { missing.remove(it) }
        }
        if (missing.isEmpty()) return route

        // Walking steps at the very end leave the room; the missing secrets go before them.
        var split = route.size
        while (split > 0 && route[split - 1].walkOnly) split--
        var from = route.subList(0, split).lastOrNull { it.target != null }?.target ?: route.firstOrNull()?.start ?: intArrayOf(0, 0, 0)

        val added = ArrayList<RouteData.Step>()
        while (missing.isNotEmpty()) {
            val here = from
            val next = missing.minBy { distanceSq(it, here) }
            missing.remove(next)
            added += hintedStep(next, secrets, here)
            from = intArrayOf(next.x, next.y, next.z)
        }
        logger.info("Route skips {} secret(s), added from our room data", added.size)
        return route.subList(0, split) + added + route.subList(split, route.size)
    }

    private fun indices(secret: SecretRoomData.SecretEntry): Set<Int> =
        INDEX.find(secret.secretName)?.groupValues?.get(1)?.split('/')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()

    /** A step to [secret] with the ways in our data lists for it. */
    private fun hintedStep(secret: SecretRoomData.SecretEntry, secrets: List<SecretRoomData.SecretEntry>, from: IntArray): RouteData.Step {
        val numbers = indices(secret)
        val hints = if (numbers.isEmpty()) emptyList() else secrets.filter { it !== secret && indices(it).any { n -> n in numbers } }
        fun of(category: String) = hints.filter { it.category == category }.map { intArrayOf(it.x, it.y, it.z) }
        // Of several ways in, the one nearest to where you're coming from.
        val entrance = hints.filter { it.category == "entrance" }.minByOrNull { distanceSq(it, from) }
        return RouteData.Step(
            locations = listOfNotNull(entrance?.let { intArrayOf(it.x, it.y, it.z) }),
            etherwarps = emptyList(),
            mines = of("stonk"),
            interacts = of("lever"),
            tnts = of("superboom"),
            pearls = emptyList(),
            pearlAngles = emptyList(),
            secretType = COLLECTABLE.getValue(secret.category),
            secretAt = intArrayOf(secret.x, secret.y, secret.z),
            ours = secret,
            extra = true,
        )
    }

    private fun distanceSq(secret: SecretRoomData.SecretEntry, p: IntArray): Int {
        val dx = secret.x - p[0]
        val dy = secret.y - p[1]
        val dz = secret.z - p[2]
        return dx * dx + dy * dy + dz * dz
    }
}
