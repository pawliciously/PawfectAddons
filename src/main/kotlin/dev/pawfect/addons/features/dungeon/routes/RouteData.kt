package dev.pawfect.addons.features.dungeon.routes

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.SecretRoutesConfig.Source
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Secret routes. Two file formats are read, both mapping a room to its steps in order with
 * every position relative to the room's corner the same way our room data is:
 *
 * - The Secret Routes Mod's, keyed by room id ("Sewer-7"). A room can have more than one route
 *   ("Name", "Name:1"); the one starting closest to you is used.
 * - Stella's, keyed by display name ("Pressure Plates"), each step a list of waypoints and the
 *   line to walk.
 *
 * The route sets ship with the mod (assets/pawfectaddons/routes, credits in CREDITS.txt):
 * - Default: the Secret Routes Mod's routes (3ppopka's set), by yourboykyle, zyra and the
 *   Secret Routes team, https://github.com/yourboykyle/SecretRoutes (GPL-3.0).
 * - FlameOfWar SRM: FlameOfWar's routes from the same mod, by FlameOfWar, Itplays, wyannnnn and
 *   yourboykyle (GPL-3.0).
 * - Stella: Stella's default routes, recorded by Luckkytigger for Stella by Eclipse (NEXD_),
 *   https://github.com/Eclipse-5214/ether (LGPL-3.0).
 * - Our own routes for rooms the community ones get wrong, used over Default and Stella.
 *
 * Your recordings are saved in config/pawfectaddons/routes/recorded.json and used when you pick
 * Recorded. A room without one falls back to Default, and so on down for a set that lacks it.
 */
object RouteData {

    class Step(
        val locations: List<IntArray>,
        val etherwarps: List<IntArray>,
        val mines: List<IntArray>,
        val interacts: List<IntArray>,
        val tnts: List<IntArray>,
        val pearls: List<DoubleArray>,
        val pearlAngles: List<FloatArray>,
        val secretType: String?,
        val secretAt: IntArray?,
        /** The secret from our own room data this step collects, once the route is fitted to the room. */
        val ours: SecretRoomData.SecretEntry? = null,
        /** Added from our room data because the route skipped it. */
        val extra: Boolean = false,
        /** Where a teleport the route doesn't list as an etherwarp lands you: the block you end up in. */
        val teleports: List<IntArray> = emptyList(),
    ) {
        fun copy(
            locations: List<IntArray> = this.locations,
            etherwarps: List<IntArray> = this.etherwarps,
            mines: List<IntArray> = this.mines,
            interacts: List<IntArray> = this.interacts,
            teleports: List<IntArray> = this.teleports,
        ) = Step(locations, etherwarps, mines, interacts, tnts, pearls, pearlAngles, secretType, secretAt, ours, extra, teleports)

        /** A step with nothing to collect, done once you get there. */
        val walkOnly: Boolean get() = secretType == null || secretType in WALK_TYPES

        /** Where the step ends: its secret, or the last place it walks to. */
        val target: IntArray? get() = secretAt ?: locations.lastOrNull()

        /** The first place the step goes, for picking which route to follow. */
        val start: IntArray? get() = locations.firstOrNull() ?: etherwarps.firstOrNull() ?: secretAt
    }

    /**
     * Step types with nothing to collect: done once you get there. FlameOfWar's Sloth ends on
     * the Prince, a mob to kill rather than a secret.
     */
    private val WALK_TYPES = setOf("exit", "exitroute", "dummy", "plate", "prince")

    private enum class Kind(val resource: String?) {
        RECORDED(null),
        PAWFECT("pawfect.json"),
        DEFAULT("default.json"),
        FLAMEOFWAR("flameofwar.json"),
        STELLA("stella.json"),
    }

    private val logger = LoggerFactory.getLogger("PawfectAddons/Routes")

    const val RECORDED_FILE = "recorded.json"


    /**
     * Room to its routes, per kind of file: the room id in lower case, or for Stella's files
     * the display name as a [SecretRoomData.nameKey].
     */
    private var routes: Map<Kind, Map<String, List<List<Step>>>> = emptyMap()

    var report: String = "not loaded"
        private set

    val folder: Path get() = ConfigManager.configDirectory.resolve("routes")

    /**
     * A room's routes and the set they came from. [note] says why that isn't the set you picked,
     * for showing in game; null when it is.
     */
    class Picked(val routes: List<List<Step>>, val from: Source, val ours: Boolean, val note: String?)

    /** The sets tried for a room, in order, with [source] picked. */
    private fun order(source: Source): List<Source> = when (source) {
        Source.DEFAULT, Source.RECORDED -> listOf(Source.DEFAULT, Source.FLAMEOFWAR, Source.STELLA)
        Source.FLAMEOFWAR -> listOf(Source.FLAMEOFWAR, Source.DEFAULT, Source.STELLA)
        Source.STELLA -> listOf(Source.STELLA, Source.DEFAULT, Source.FLAMEOFWAR)
    }

    /** [room]'s routes from [set], ours over Default and Stella where we have them. */
    private fun from(set: Source, key: String): Pair<List<List<Step>>, Boolean>? {
        if (set == Source.DEFAULT || set == Source.STELLA) routes[Kind.PAWFECT]?.get(key)?.let { return it to true }
        val found = when (set) {
            Source.DEFAULT -> routes[Kind.DEFAULT]?.get(key)
            Source.FLAMEOFWAR -> routes[Kind.FLAMEOFWAR]?.get(key)
            Source.STELLA -> if (key in STELLA_BROKEN) null else SecretRoomData.displayKey(key)?.let { routes[Kind.STELLA]?.get(it) }
            Source.RECORDED -> routes[Kind.RECORDED]?.get(key)
        }
        return found?.let { it to false }
    }

    fun routesFor(room: String, source: Source): Picked? {
        val key = room.lowercase()
        if (source == Source.RECORDED) from(Source.RECORDED, key)?.let { (list, _) -> return Picked(list, Source.RECORDED, false, null) }
        for (set in order(source)) {
            val (list, ours) = from(set, key) ?: continue
            val note = when {
                set == source -> null
                source == Source.RECORDED -> "No recording for this room, using $set"
                source == Source.STELLA && key in STELLA_BROKEN -> "Stella's route here doesn't work, using $set"
                else -> "No $source route for this room, using $set"
            }
            return Picked(list, set, ours, note)
        }
        return null
    }

    /**
     * Rooms whose Stella route doesn't work, by our room id; the Secret Routes Mod's is used
     * there instead. Grass Ruin's warps up through slabs from the lower chest and mines through
     * the upper one instead of opening it; Sewer's runs better as the Secret Routes Mod has it.
     */
    private val STELLA_BROKEN = setOf("grass-ruin-3", "sewer-7")

    private const val RESOURCES = "/assets/pawfectaddons/routes/"

    private fun readRooms(json: JsonObject, stella: Boolean, target: HashMap<String, MutableList<List<Step>>>) {
        json.entrySet().forEach { (name, value) ->
            if (name.startsWith("#") || !value.isJsonArray) return@forEach
            val room = if (stella) stellaKey(name) else name.substringBefore(':').lowercase()
            val steps = value.asJsonArray.mapNotNull { step ->
                runCatching { step.asJsonObject.let { if (stella) parseStellaStep(it) else parseStep(it) } }.getOrNull()
            }
            if (steps.isNotEmpty()) target.getOrPut(room) { mutableListOf() } += steps
        }
    }

    fun load() {
        val loaded = HashMap<Kind, HashMap<String, MutableList<List<Step>>>>()
        Kind.entries.forEach { kind ->
            val resource = kind.resource ?: return@forEach
            runCatching {
                RouteData::class.java.getResourceAsStream(RESOURCES + resource)?.bufferedReader()?.use { reader ->
                    val json = JsonParser.parseReader(reader).asJsonObject
                    readRooms(json, isStella(json), loaded.getOrPut(kind) { HashMap() })
                }
            }.onFailure { logger.warn("Could not read the bundled routes {}: {}", resource, it.message) }
        }
        runCatching {
            val file = folder.resolve(RECORDED_FILE)
            if (Files.exists(file)) {
                val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject
                readRooms(json, false, loaded.getOrPut(Kind.RECORDED) { HashMap() })
            }
        }.onFailure { logger.warn("Could not read your recorded routes: {}", it.message) }
        routes = loaded
        fun rooms(kind: Kind) = loaded[kind]?.size ?: 0
        report = "Default ${rooms(Kind.DEFAULT)} rooms, FlameOfWar SRM ${rooms(Kind.FLAMEOFWAR)}, Stella ${rooms(Kind.STELLA)}, " +
            "${rooms(Kind.PAWFECT)} of our own, ${rooms(Kind.RECORDED)} recorded"
        logger.info("Secret routes: {}", report)
    }

    private fun ints(json: JsonObject, key: String): List<IntArray> =
        (json.get(key) as? JsonArray)?.mapNotNull { point ->
            (point as? JsonArray)?.takeIf { it.size() >= 3 }?.let { intArrayOf(it[0].asInt, it[1].asInt, it[2].asInt) }
        }.orEmpty()

    private fun parseStep(json: JsonObject): Step {
        val secret = json.get("secret")?.takeIf { it.isJsonObject }?.asJsonObject
        val at = (secret?.get("location") as? JsonArray)?.takeIf { it.size() >= 3 }?.let { intArrayOf(it[0].asInt, it[1].asInt, it[2].asInt) }
        return Step(
            locations = ints(json, "locations"),
            etherwarps = ints(json, "etherwarps"),
            teleports = ints(json, "teleports"),
            mines = ints(json, "mines"),
            interacts = ints(json, "interacts"),
            tnts = ints(json, "tnts"),
            pearls = (json.get("enderpearls") as? JsonArray)?.mapNotNull { point ->
                (point as? JsonArray)?.takeIf { it.size() >= 3 }?.let { doubleArrayOf(it[0].asDouble, it[1].asDouble, it[2].asDouble) }
            }.orEmpty(),
            pearlAngles = (json.get("enderpearlangles") as? JsonArray)?.mapNotNull { angle ->
                (angle as? JsonArray)?.takeIf { it.size() >= 2 }?.let { floatArrayOf(it[0].asFloat, it[1].asFloat) }
            }.orEmpty(),
            secretType = secret?.get("type")?.takeIf { !it.isJsonNull }?.asString?.lowercase(),
            secretAt = at,
        )
    }

    // ------------------------------------------------------------------ Stella's format

    /** A Stella file's steps are objects with a "waypoints" list. */
    private fun isStella(json: JsonObject): Boolean = json.entrySet().any { (name, value) ->
        !name.startsWith("#") && value.isJsonArray &&
            (value.asJsonArray.firstOrNull() as? JsonObject)?.has("waypoints") == true
    }

    /** Stella's names for rooms our data names differently, as [SecretRoomData.nameKey]s. */
    private val STELLA_NAMES = mapOf(
        "withermancer" to "withermancers",
        "dinosite" to "dinodigsite",
        "silversword" to "silverssword",
        "rails" to "railtrack",
        "pipes" to "sewer",
        "crisscross" to "crypts",
        "redstonecrypt" to "redstoneskull",
        "smallwaterfall" to "miniwaterfall",
        "pirate" to "drawbridge",
        "staircase" to "doublestair",
    )

    private fun stellaKey(name: String): String = SecretRoomData.nameKey(name).let { STELLA_NAMES[it] ?: it }

    /**
     * One of Stella's steps: its line is where to walk, its waypoints what to do on the way, and
     * the chest, essence, item or bat at the end is the secret it collects.
     */
    private fun parseStellaStep(json: JsonObject): Step {
        val etherwarps = ArrayList<IntArray>()
        val mines = ArrayList<IntArray>()
        val interacts = ArrayList<IntArray>()
        val tnts = ArrayList<IntArray>()
        val pearls = ArrayList<DoubleArray>()
        var secretType: String? = null
        var secretAt: IntArray? = null
        (json.get("waypoints") as? JsonArray)?.forEach { element ->
            val waypoint = element as? JsonObject ?: return@forEach
            val pos = (waypoint.get("pos") as? JsonArray)?.takeIf { it.size() >= 3 }?.let { intArrayOf(it[0].asInt, it[1].asInt, it[2].asInt) } ?: return@forEach
            val type = waypoint.get("type")?.takeIf { !it.isJsonNull }?.asString?.uppercase()
            val name = waypoint.get("name")?.takeIf { !it.isJsonNull }?.asString
            when (type) {
                "ETHERWARP" -> etherwarps += pos
                "MINE" -> mines += pos
                "LEVER" -> interacts += pos
                "SUPERBOOM" -> tnts += pos
                "PEARL" -> pearls += doubleArrayOf(pos[0] + 0.5, pos[1].toDouble(), pos[2] + 0.5)
                "CHEST", "ESSENCE" -> { secretType = "interact"; secretAt = pos }
                "ITEM" -> { secretType = "item"; secretAt = pos }
                "BAT" -> { secretType = "bat"; secretAt = pos }
                // Custom marks are notes like crypts, apart from the odd bat.
                "CUSTOM" -> if (name.equals("bat", ignoreCase = true)) { secretType = "bat"; secretAt = pos }
            }
        }
        return Step(
            locations = ints(json, "line"),
            etherwarps = etherwarps,
            mines = mines,
            interacts = interacts,
            tnts = tnts,
            pearls = pearls,
            pearlAngles = emptyList(),
            secretType = secretType,
            secretAt = secretAt,
        )
    }

    // ------------------------------------------------------------------ writing

    private fun points(list: List<IntArray>): JsonArray = JsonArray().also { array ->
        list.forEach { p -> array.add(JsonArray().also { it.add(p[0]); it.add(p[1]); it.add(p[2]) }) }
    }

    fun toJson(step: Step): JsonObject = JsonObject().also { json ->
        json.add("locations", points(step.locations))
        json.add("etherwarps", points(step.etherwarps))
        if (step.teleports.isNotEmpty()) json.add("teleports", points(step.teleports))
        json.add("mines", points(step.mines))
        json.add("interacts", points(step.interacts))
        json.add("tnts", points(step.tnts))
        json.add("enderpearls", JsonArray().also { array ->
            step.pearls.forEach { p -> array.add(JsonArray().also { it.add(p[0]); it.add(p[1]); it.add(p[2]) }) }
        })
        json.add("enderpearlangles", JsonArray().also { array ->
            step.pearlAngles.forEach { a -> array.add(JsonArray().also { it.add(a[0]); it.add(a[1]) }) }
        })
        val type = step.secretType
        val at = step.secretAt
        if (type != null) {
            json.add("secret", JsonObject().also { secret ->
                secret.addProperty("type", type)
                if (at != null) secret.add("location", JsonArray().also { it.add(at[0]); it.add(at[1]); it.add(at[2]) })
            })
        }
    }

    /** Two recordings starting this close together, squared, are the same way into the room. */
    private const val SAME_START_SQ = 36

    /**
     * Saves [steps] as one of [room]'s routes in your recordings. A recorded route that starts in
     * the same place is replaced; otherwise it's kept alongside as "room:1", "room:2" and so on.
     * Returns whether one was replaced.
     */
    fun saveRecorded(room: String, steps: List<Step>): Boolean {
        Files.createDirectories(folder)
        val file = folder.resolve(RECORDED_FILE)
        val json: JsonObject = runCatching {
            if (Files.exists(file)) Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject else null
        }.getOrNull() ?: JsonObject()
        val start = steps.firstNotNullOfOrNull { it.start }
        val keys = json.keySet().filter { it.substringBefore(':').equals(room, ignoreCase = true) }
        val same = keys.firstOrNull { key ->
            val first = runCatching { (json.get(key) as? JsonArray)?.firstOrNull()?.asJsonObject?.let { parseStep(it) }?.start }.getOrNull()
            start != null && first != null &&
                (first[0] - start[0]) * (first[0] - start[0]) + (first[1] - start[1]) * (first[1] - start[1]) + (first[2] - start[2]) * (first[2] - start[2]) <= SAME_START_SQ
        }
        val key = same ?: if (keys.isEmpty()) room else generateSequence(1) { it + 1 }.map { "$room:$it" }.first { it !in keys }
        json.add(key, JsonArray().also { array -> steps.forEach { array.add(toJson(it)) } })
        Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(json as JsonElement))
        load()
        return same != null
    }

}
