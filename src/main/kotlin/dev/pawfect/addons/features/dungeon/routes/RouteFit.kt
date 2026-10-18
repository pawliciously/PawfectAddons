package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.max

object RouteFit {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Routes")

    private enum class Turn { NONE, HALF, QUARTER, THREE_QUARTERS }

    val CATEGORIES: Map<String, Set<String>> = mapOf(
        "item" to setOf("item"),
        "bat" to setOf("bat"),
        "interact" to setOf("chest", "wither", "lever", "redstone_key"),
        "lever" to setOf("lever"),
    )

    private val MATCH_RANGE_SQ = mapOf("item" to 100, "bat" to 256, "interact" to 4, "lever" to 4)

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

    private const val TELEPORT_SQ = 64

    private fun markTeleports(route: List<RouteData.Step>): List<RouteData.Step> = route.map { step ->
        val added = ArrayList<IntArray>()
        step.locations.zipWithNext { from, to ->
            val dx = to[0] - from[0]
            val dy = to[1] - from[1]
            val dz = to[2] - from[2]
            if (dx * dx + dy * dy + dz * dz <= TELEPORT_SQ) return@zipWithNext
            if (dx * dx + dz * dz <= 4 && dy < 0) return@zipWithNext
            if (dy > 0 && dy * dy >= dx * dx + dz * dz) return@zipWithNext
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
            val flippedBy = if (step.extra) out else out + step
            val pending = leverEntries.filter { lever ->
                val at = intArrayOf(lever.x, lever.y, lever.z)
                flippedBy.none { s -> s.interacts.any { near(it, at, 2) } || (s.ours === lever) }
            }
            if (pending.isEmpty()) {
                out += step
                return@forEach
            }
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

    private const val OWN_WAY_SQ = 36

    private const val LEVER_DETOUR = 16.0

    private const val MAX_SHIFT = 16

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
                if (key > bestKey || key == bestKey && solid >= bestSolid + SOLID_MARGIN) {
                    best = Triple(turn, tx, tz)
                    bestScore = score
                    bestSolid = solid
                }
            }
        }
        val anchors = route.count { it.secretType in CATEGORIES && it.secretAt != null } + route.sumOf { it.interacts.size }
        val (turn, tx, tz) = best
        val bySecrets = bestScore.first >= max(2, (anchors + 1) / 2)
        val byBlocks = bestScore.first >= 1 && bestSolid >= noneSolid + SOLID_MARGIN && bestSolid * 2 >= total
        if (best == Triple(Turn.NONE, 0, 0) || bestScore.first <= none.first || !(bySecrets || byBlocks)) return route
        logger.info("Route for {} is turned {} and shifted ({}, {}) from our room data, lining it up ({} exact before, {} after)", room, turn, tx, tz, none.first, bestScore.first)
        return route.map { step -> turnStep(step, turn, mx, mz, tx, tz) }
    }

    private fun shift(p: IntArray, tx: Int, tz: Int): IntArray = if (tx == 0 && tz == 0) p else intArrayOf(p[0] + tx, p[1], p[2] + tz)

    private const val SOLID_MARGIN = 2

    private fun layout(room: String): IntArray? = SecretRoomData.shapes.firstNotNullOfOrNull { SecretRoomData.roomsForShape(it)[room] }

    private fun solidAt(layout: IntArray, p: IntArray): Boolean {
        if (p[0] !in 0..255 || p[1] !in 0..255 || p[2] !in 0..255) return false
        val base = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8)
        val found = java.util.Arrays.binarySearch(layout, base)
        if (found >= 0) return true
        val at = -found - 1
        return at < layout.size && (layout[at] and 0xFFFFFF00.toInt()) == base
    }

    private fun solid(route: List<RouteData.Step>, layout: IntArray?, frame: (IntArray) -> IntArray): Int {
        if (layout == null) return 0
        return route.sumOf { step -> (step.mines + step.tnts + step.etherwarps).count { solidAt(layout, frame(it)) } }
    }

    private fun inside(route: List<RouteData.Step>, mx: Int, mz: Int, frame: (IntArray) -> IntArray): Boolean = route.all { step ->
        (step.mines + step.tnts + step.etherwarps + step.interacts + listOfNotNull(step.secretAt)).all { p ->
            val q = frame(p)
            q[0] in -1..mx + 1 && q[2] in -1..mz + 1
        }
    }

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

    private fun box(room: String): Pair<Int, Int>? {
        val blocks = SecretRoomData.shapes.firstNotNullOfOrNull { SecretRoomData.roomsForShape(it)[room] } ?: return null
        var mx = 0
        var mz = 0
        for (v in blocks) {
            mx = max(mx, (v ushr 24) and 0xFF)
            mz = max(mz, (v ushr 8) and 0xFF)
        }
        fun snap(m: Int) = ((m + 32) / 32) * 32 - 2
        return snap(mx) to snap(mz)
    }

    private fun turnBlock(p: IntArray, turn: Turn, mx: Int, mz: Int): IntArray = when (turn) {
        Turn.NONE -> p
        Turn.HALF -> intArrayOf(mx - p[0], p[1], mz - p[2])
        Turn.QUARTER -> intArrayOf(mx - p[2], p[1], p[0])
        Turn.THREE_QUARTERS -> intArrayOf(p[2], p[1], mz - p[0])
    }

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

    private fun fillMissing(route: List<RouteData.Step>, secrets: List<SecretRoomData.SecretEntry>): List<RouteData.Step> {
        val covered = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SecretRoomData.SecretEntry, Boolean>())
        route.forEach { step -> step.ours?.let { covered += it } }
        val missing = secrets.filter { it.category in COLLECTABLE && it !in covered }.toMutableList()
        if (missing.isEmpty()) return route

        val untied = route.filter { it.secretType in CATEGORIES && it.ours == null }
        val secretSteps = route.count { it.secretType in CATEGORIES }
        if (secretSteps > 0 && (secretSteps - untied.size) * 2 < secretSteps) return route
        untied.forEach { step ->
            val categories = CATEGORIES.getValue(step.secretType!!)
            val at = step.secretAt ?: return@forEach
            missing.filter { it.category in categories }.minByOrNull { distanceSq(it, at) }?.let { missing.remove(it) }
        }
        if (missing.isEmpty()) return route

        var trailing = 0
        while (trailing < route.size && route[route.size - 1 - trailing].walkOnly) trailing++
        val out = ArrayList(route)
        var added = 0
        val origin = route.firstOrNull()?.start ?: intArrayOf(0, 0, 0)
        while (missing.isNotEmpty()) {
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

    private const val PASSING_SQ = 9

    private const val DIGGING_SQ = 2

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

    private fun hintedStep(secret: SecretRoomData.SecretEntry, secrets: List<SecretRoomData.SecretEntry>, from: IntArray): RouteData.Step {
        val numbers = indices(secret)
        val hints = if (numbers.isEmpty()) emptyList() else secrets.filter { it !== secret && indices(it).any { n -> n in numbers } }
        fun of(category: String) = hints.filter { it.category == category }.map { intArrayOf(it.x, it.y, it.z) }
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
