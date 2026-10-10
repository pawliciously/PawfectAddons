package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import org.slf4j.LoggerFactory
import kotlin.math.abs
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
        // A step that's only flipping a lever, as our own routes have.
        "lever" to setOf("lever"),
    )

    /** How far a route's secret can be from ours and still be the same one, squared. */
    private val MATCH_RANGE_SQ = mapOf("item" to 100, "bat" to 256, "interact" to 4, "lever" to 4)

    /** Our secrets worth collecting, and the route secret type each one is. */
    private val COLLECTABLE = mapOf("chest" to "interact", "wither" to "interact", "item" to "item", "bat" to "bat")

    private val INDEX = Regex("""^\s*([0-9]+(?:\s*/\s*[0-9]+)*)""")

    fun prepare(room: String, route: List<RouteData.Step>, fill: Boolean, levers: Boolean): List<RouteData.Step> {
        val secrets = SecretRoomData.secretsFor(room)
        if (route.isEmpty()) return route
        if (secrets.isEmpty()) return markTeleports(route)
        val turned = turn(room, route, secrets)
        val assigned = assign(turned, secrets)
        val filled = if (fill) fillMissing(pickUpOnTheWay(assigned, secrets), secrets) else assigned
        return markTeleports(if (levers) leverFirst(filled, secrets) else filled)
    }

    // ------------------------------------------------------------------ teleports

    /** A jump this far between two walking points, squared, is a teleport. */
    private const val TELEPORT_SQ = 64

    /**
     * Routes only list etherwarps; other teleports (Aspect of the Void, a warp you didn't
     * crouch for) show up as a jump between walking points. Those get a mark where you land.
     */
    private fun markTeleports(route: List<RouteData.Step>): List<RouteData.Step> = route.map { step ->
        val added = ArrayList<IntArray>()
        step.locations.zipWithNext { from, to ->
            val dx = to[0] - from[0]
            val dy = to[1] - from[1]
            val dz = to[2] - from[2]
            if (dx * dx + dy * dy + dz * dz <= TELEPORT_SQ) return@zipWithNext
            // Dropping straight down a hole isn't a teleport.
            if (dx * dx + dz * dz <= 4 && dy < 0) return@zipWithNext
            // Nor is landing a pearl thrown from here.
            if (step.pearls.any { p -> (p[0] - from[0]).let { it * it } + (p[1] - from[1]).let { it * it } + (p[2] - from[2]).let { it * it } <= 9.0 }) return@zipWithNext
            val below = intArrayOf(to[0], to[1] - 1, to[2])
            val marked = step.etherwarps.any { e -> near(e, to, 4) || near(e, below, 4) } || added.any { near(it, to, 4) }
            if (!marked) added += to
        }
        if (added.isEmpty()) step else step.copy(teleports = added)
    }

    private fun near(a: IntArray, b: IntArray, rangeSq: Int): Boolean {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz <= rangeSq
    }

    // ------------------------------------------------------------------ lever doors

    /**
     * Routes stonk through the door of a chest that a lever opens. Where our data has the lever,
     * the route goes there first and the door's stonk is left out.
     */
    private fun leverFirst(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val out = ArrayList<RouteData.Step>()
        route.forEach { step ->
            val ours = step.ours
            val numbers = if (ours != null && ours.category in LEVER_LOCKED) indices(ours) else emptySet()
            if (numbers.isEmpty()) {
                out += step
                return@forEach
            }
            fun related(category: String) = secrets.filter { it.category == category && indices(it).any { n -> n in numbers } }.map { intArrayOf(it.x, it.y, it.z) }
            val leverEntries = secrets.filter { it.category == "lever" && indices(it).any { n -> n in numbers } }
            // Levers the route doesn't flip itself, before or on the way to this chest. A step we
            // added only lists its lever as a hint, so that one still gets its own step.
            val flippedBy = if (step.extra) out else out + step
            val pending = leverEntries.filter { lever ->
                val at = intArrayOf(lever.x, lever.y, lever.z)
                flippedBy.none { s -> s.interacts.any { near(it, at, 2) } || (s.ours === lever) }
            }
            if (pending.isEmpty()) {
                out += step
                return@forEach
            }
            // A route that digs or blows its own way in keeps it when the lever is out of the way:
            // a lever across the room (Sewer's, Mossy's behind a crypt) is a trip there and back.
            val chest = intArrayOf(ours!!.x, ours.y, ours.z)
            val ownWay = (step.mines + step.tnts).any { near(it, chest, OWN_WAY_SQ) } || step.pearls.isNotEmpty()
            val from = out.lastOrNull { it.target != null }?.target ?: step.start ?: chest
            val detour = pending.sumOf { lever ->
                val at = intArrayOf(lever.x, lever.y, lever.z)
                length(from, at) + length(at, chest) - length(from, chest)
            }
            if (ownWay && detour > LEVER_DETOUR) {
                logger.info("Leaving {}'s lever out, {} blocks out of the way; the route has its own way in", ours.secretName, detour.toInt())
                out += step
                return@forEach
            }
            pending.forEach { lever ->
                // A pressure plate is stood on, not clicked: done once you get there.
                val plate = lever.secretName.contains("plate", ignoreCase = true)
                out += RouteData.Step(
                    emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                    if (plate) "plate" else "lever", intArrayOf(lever.x, lever.y, lever.z), lever, step.extra,
                )
            }
            val stonks = related("stonk")
            val levers = related("lever")
            out += step.copy(
                mines = step.mines.filter { m -> stonks.none { near(m, it, 2) } },
                interacts = step.interacts.filter { m -> levers.none { near(m, it, 2) } },
            )
        }
        return out
    }

    private val LEVER_LOCKED = setOf("chest", "wither", "item")

    /** How close, squared, a mined or blown block has to be to a chest to be the route's way in. */
    private const val OWN_WAY_SQ = 36

    /** How many extra blocks a lever can add to the way to its chest before the route's own way in is kept. */
    private const val LEVER_DETOUR = 16.0

    // ------------------------------------------------------------------ turning

    /** How far a route's frame can sit from ours, in blocks along x or z. */
    private const val MAX_SHIFT = 16

    /**
     * Lines the route up with our room: turned (Slime is half a turn out) and shifted (Super
     * Tall sits 8 blocks off), whichever puts the most of its secrets exactly on ours.
     *
     * A route with a single secret (the Secret Routes Mod's Crypts) can't be told apart by
     * secrets alone, so the blocks it mines, blows up and warps onto settle it: in the right
     * frame they're really there in our block layout. A frame that puts any of the route
     * outside the room is never the right one.
     */
    private fun turn(room: String, route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val box = box(room) ?: return route
        val (mx, mz) = box
        val layout = layout(room)
        val turns = if (mx == mz) Turn.entries else listOf(Turn.NONE, Turn.HALF)
        val none = score(route, secrets) { it }
        val noneSolid = solid(route, layout) { it }
        val total = route.sumOf { it.mines.size + it.tnts.size + it.etherwarps.size }
        var best = Triple(Turn.NONE, 0, 0)
        var bestScore = none
        var bestSolid = noneSolid
        turns.forEach { turn ->
            // Shifts worth trying: the ones that put some route secret right on one of ours.
            val shifts = LinkedHashSet<Pair<Int, Int>>()
            shifts += 0 to 0
            route.forEach { step ->
                val categories = CATEGORIES[step.secretType] ?: return@forEach
                val at = turnBlock(step.secretAt ?: return@forEach, turn, mx, mz)
                secrets.filter { it.category in categories && it.y == at[1] }.forEach { secret ->
                    val tx = secret.x - at[0]
                    val tz = secret.z - at[2]
                    if (abs(tx) <= MAX_SHIFT && abs(tz) <= MAX_SHIFT) shifts += tx to tz
                }
            }
            shifts.forEach { (tx, tz) ->
                val frame = { p: IntArray -> shift(turnBlock(p, turn, mx, mz), tx, tz) }
                if (!inside(route, mx, mz, frame)) return@forEach
                val score = score(route, secrets, frame)
                val solid = solid(route, layout, frame)
                val key = score.first * 2 + score.second
                val bestKey = bestScore.first * 2 + bestScore.second
                // Secrets first; between frames they fit equally, clearly more real blocks.
                if (key > bestKey || key == bestKey && solid >= bestSolid + SOLID_MARGIN) {
                    best = Triple(turn, tx, tz)
                    bestScore = score
                    bestSolid = solid
                }
            }
        }
        // Only moved when it clearly fits better: most of its secrets and levers, and at least two,
        // land exactly on ours; or its one secret does and the blocks it works on are really there.
        val anchors = route.count { it.secretType in CATEGORIES && it.secretAt != null } + route.sumOf { it.interacts.size }
        val (turn, tx, tz) = best
        val bySecrets = bestScore.first >= max(2, (anchors + 1) / 2)
        val byBlocks = bestScore.first >= 1 && bestSolid >= noneSolid + SOLID_MARGIN && bestSolid * 2 >= total
        if (best == Triple(Turn.NONE, 0, 0) || bestScore.first <= none.first || !(bySecrets || byBlocks)) return route
        logger.info("Route for {} is turned {} and shifted ({}, {}) from our room data, lining it up ({} exact before, {} after)", room, turn, tx, tz, none.first, bestScore.first)
        return route.map { step -> turnStep(step, turn, mx, mz, tx, tz) }
    }

    private fun shift(p: IntArray, tx: Int, tz: Int): IntArray = if (tx == 0 && tz == 0) p else intArrayOf(p[0] + tx, p[1], p[2] + tz)

    /** How many more real blocks one frame needs than another to count as fitting better. */
    private const val SOLID_MARGIN = 2

    /** The room's block layout, sorted, each block as x, y, z and its id packed in one int. */
    private fun layout(room: String): IntArray? = SecretRoomData.shapes.firstNotNullOfOrNull { SecretRoomData.roomsForShape(it)[room] }

    private fun solidAt(layout: IntArray, p: IntArray): Boolean {
        if (p[0] !in 0..255 || p[1] !in 0..255 || p[2] !in 0..255) return false
        val base = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8)
        val found = java.util.Arrays.binarySearch(layout, base)
        if (found >= 0) return true
        val at = -found - 1
        return at < layout.size && (layout[at] and 0xFFFFFF00.toInt()) == base
    }

    /** The route's mined, blown up and warped onto blocks that are really there in this frame. */
    private fun solid(route: List<RouteData.Step>, layout: IntArray?, frame: (IntArray) -> IntArray): Int {
        if (layout == null) return 0
        return route.sumOf { step -> (step.mines + step.tnts + step.etherwarps).count { solidAt(layout, frame(it)) } }
    }

    /** Every block the route works on and every secret it gets falls inside the room. */
    private fun inside(route: List<RouteData.Step>, mx: Int, mz: Int, frame: (IntArray) -> IntArray): Boolean = route.all { step ->
        (step.mines + step.tnts + step.etherwarps + step.interacts + listOfNotNull(step.secretAt)).all { p ->
            val q = frame(p)
            q[0] in -1..mx + 1 && q[2] in -1..mz + 1
        }
    }

    /**
     * Route secrets landing exactly on one of ours, and within a few blocks of one. Levers the
     * route flips count too, so a room with one chest behind a lever (Crypts) can still be lined up.
     */
    private fun score(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>, turn: (IntArray) -> IntArray): Pair<Int, Int> {
        var exact = 0
        var near = 0
        fun count(at: IntArray, categories: Set<String>) {
            val best = secrets.filter { it.category in categories }.minOfOrNull { distanceSq(it, at) } ?: return
            if (best <= 1) exact++ else if (best <= 64) near++
        }
        val levers = setOf("lever")
        route.forEach { step ->
            CATEGORIES[step.secretType]?.let { categories -> step.secretAt?.let { count(turn(it), categories) } }
            step.interacts.forEach { count(turn(it), levers) }
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

    private fun turnStep(step: RouteData.Step, turn: Turn, mx: Int, mz: Int, tx: Int, tz: Int): RouteData.Step {
        fun block(p: IntArray) = shift(turnBlock(p, turn, mx, mz), tx, tz)
        fun blocks(list: List<IntArray>) = list.map { block(it) }
        return RouteData.Step(
            blocks(step.locations), blocks(step.etherwarps), blocks(step.mines), blocks(step.interacts), blocks(step.tnts),
            step.pearls.map { turnPoint(it, turn, mx, mz).let { p -> doubleArrayOf(p[0] + tx, p[1], p[2] + tz) } },
            step.pearlAngles.map { floatArrayOf(it[0], turnYaw(it.getOrElse(1) { 0f }, turn)) },
            step.secretType, step.secretAt?.let { block(it) }, step.ours, step.extra,
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
        var trailing = 0
        while (trailing < route.size && route[route.size - 1 - trailing].walkOnly) trailing++
        val out = ArrayList(route)
        var added = 0
        val origin = route.firstOrNull()?.start ?: intArrayOf(0, 0, 0)
        while (missing.isNotEmpty()) {
            // Whichever secret and place in the route costs the least extra walking.
            var best: Triple<SecretRoomData.SecretEntry, Int, Double>? = null
            missing.forEach { secret ->
                for (at in 1..out.size - trailing) {
                    val before = out.subList(0, at).lastOrNull { it.target != null }?.target ?: origin
                    val after = out.getOrNull(at)?.start
                    val detour = distance(secret, before) + (after?.let { distance(secret, it) - length(before, it) } ?: 0.0)
                    if (best == null || detour < best!!.third) best = Triple(secret, at, detour)
                }
            }
            val (secret, at, _) = best ?: break
            missing.remove(secret)
            val before = out.subList(0, at).lastOrNull { it.target != null }?.target ?: origin
            out.add(at, hintedStep(secret, secrets, before))
            added++
        }
        logger.info("Route skips {} secret(s), added from our room data", added)
        return out
    }

    // ------------------------------------------------------------------ secrets picked up on the way

    /** How close, squared, a walking point has to pass a secret for the route to be taking it. */
    private const val PASSING_SQ = 9

    /** How close, squared, a mined block has to be to a secret for the route to be digging it out. */
    private const val DIGGING_SQ = 2

    /**
     * Some routes grab a secret partway through a step without marking it, recording an item
     * as a block to mine on the way (Stella's Archway does). A walking step that passes right
     * by a secret nothing else collects is split there, the first half collecting it.
     */
    private fun pickUpOnTheWay(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val covered = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SecretRoomData.SecretEntry, Boolean>())
        route.forEach { step -> step.ours?.let { covered += it } }
        val out = ArrayList<RouteData.Step>()
        val pending = ArrayDeque(route)
        while (pending.isNotEmpty()) {
            val step = pending.removeFirst()
            if (!step.walkOnly || step.secretType == "plate" || step.locations.size < 2) {
                out += step
                continue
            }
            // The earliest point on the step that passes an uncollected secret.
            var hit: Pair<Int, SecretRoomData.SecretEntry>? = null
            secrets.filter { it.category in COLLECTABLE && it !in covered && it.category != "bat" }.forEach { secret ->
                val byWalk = step.locations.indices.filter { distanceSq(secret, step.locations[it]) <= PASSING_SQ }.minOrNull()
                val byDig = step.mines.firstOrNull { distanceSq(secret, it) <= DIGGING_SQ }?.let { nearestIndex(step.locations, it) }
                val index = listOfNotNull(byWalk, byDig).minOrNull() ?: return@forEach
                if (hit == null || index < hit!!.first) hit = index to secret
            }
            val found = hit
            if (found == null) {
                out += step
                continue
            }
            val (index, secret) = found
            covered += secret
            val first = step.locations.subList(0, index + 1)
            val rest = step.locations.subList(index, step.locations.size)
            fun firstHalf(list: List<IntArray>) = list.filter { nearestIndex(step.locations, it) <= index }
            fun secondHalf(list: List<IntArray>) = list.filter { nearestIndex(step.locations, it) > index }
            out += RouteData.Step(
                first, firstHalf(step.etherwarps), firstHalf(step.mines), firstHalf(step.interacts), firstHalf(step.tnts),
                step.pearls, step.pearlAngles, COLLECTABLE.getValue(secret.category), intArrayOf(secret.x, secret.y, secret.z), secret,
            )
            logger.info("Route passes {} on the way, collecting it there", secret.secretName)
            // What's left of the step goes on, and may pass another one.
            if (rest.size > 1) pending.addFirst(
                RouteData.Step(rest, secondHalf(step.etherwarps), secondHalf(step.mines), secondHalf(step.interacts), secondHalf(step.tnts),
                    emptyList(), emptyList(), step.secretType, step.secretAt)
            )
        }
        return out
    }

    private fun nearestIndex(points: List<IntArray>, p: IntArray): Int =
        points.indices.minBy { i -> points[i].let { (it[0] - p[0]) * (it[0] - p[0]) + (it[1] - p[1]) * (it[1] - p[1]) + (it[2] - p[2]) * (it[2] - p[2]) } }

    private fun distance(secret: SecretRoomData.SecretEntry, p: IntArray): Double = kotlin.math.sqrt(distanceSq(secret, p).toDouble())

    private fun length(a: IntArray, b: IntArray): Double =
        kotlin.math.sqrt(((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])).toDouble())

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
