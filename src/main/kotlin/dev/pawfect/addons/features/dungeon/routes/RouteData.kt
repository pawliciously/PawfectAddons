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
        val ours: SecretRoomData.SecretEntry? = null,
        val extra: Boolean = false,
        val teleports: List<IntArray> = emptyList(),
    ) {
        fun copy(
            locations: List<IntArray> = this.locations,
            etherwarps: List<IntArray> = this.etherwarps,
            mines: List<IntArray> = this.mines,
            interacts: List<IntArray> = this.interacts,
            teleports: List<IntArray> = this.teleports,
        ) = Step(locations, etherwarps, mines, interacts, tnts, pearls, pearlAngles, secretType, secretAt, ours, extra, teleports)

        val walkOnly: Boolean get() = secretType == null || secretType in WALK_TYPES

        val target: IntArray? get() = secretAt ?: locations.lastOrNull()

        val start: IntArray? get() = locations.firstOrNull() ?: etherwarps.firstOrNull() ?: secretAt
    }

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

    private var routes: Map<Kind, Map<String, List<List<Step>>>> = emptyMap()

    var report: String = "not loaded"
        private set

    val folder: Path get() = ConfigManager.configDirectory.resolve("routes")

    class Picked(val routes: List<List<Step>>, val from: Source, val ours: Boolean, val note: String?)

    private fun order(source: Source): List<Source> = when (source) {
        Source.DEFAULT, Source.RECORDED -> listOf(Source.DEFAULT, Source.FLAMEOFWAR, Source.STELLA)
        Source.FLAMEOFWAR -> listOf(Source.FLAMEOFWAR, Source.DEFAULT, Source.STELLA)
        Source.STELLA -> listOf(Source.STELLA, Source.DEFAULT, Source.FLAMEOFWAR)
    }

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

    private fun isStella(json: JsonObject): Boolean = json.entrySet().any { (name, value) ->
        !name.startsWith("#") && value.isJsonArray &&
            (value.asJsonArray.firstOrNull() as? JsonObject)?.has("waypoints") == true
    }

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

    private const val SAME_START_SQ = 36

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
