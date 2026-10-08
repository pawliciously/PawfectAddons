package dev.pawfect.addons.features.dungeon.routes

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Secret routes, in the Secret Routes Mod's file format: each room's name maps to its steps in
 * order, every position relative to the room's corner the same way our room data is. A room
 * can have more than one route ("Name", "Name:1"); the one starting closest to you is used.
 *
 * Route files live in config/pawfectaddons/routes. Your own recordings go in recorded.json and
 * win over everything else; pearl routes are used where you turn them on.
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
    ) {
        /** A step with nothing to collect, done once you get there. */
        val walkOnly: Boolean get() = secretType == null || secretType == "exit" || secretType == "exitroute" || secretType == "dummy"

        /** Where the step ends: its secret, or the last place it walks to. */
        val target: IntArray? get() = secretAt ?: locations.lastOrNull()

        /** The first place the step goes, for picking which route to follow. */
        val start: IntArray? get() = locations.firstOrNull() ?: etherwarps.firstOrNull() ?: secretAt
    }

    private enum class Kind { RECORDED, PEARL, STANDARD }

    private val logger = LoggerFactory.getLogger("PawfectAddons/Routes")

    const val RECORDED_FILE = "recorded.json"

    /** Room name in lower case to its routes, per kind of file. */
    private var routes: Map<Kind, Map<String, List<List<Step>>>> = emptyMap()

    var report: String = "not loaded"
        private set

    val folder: Path get() = ConfigManager.configDirectory.resolve("routes")

    fun routesFor(room: String, pearls: Boolean): List<List<Step>> {
        val key = room.lowercase()
        routes[Kind.RECORDED]?.get(key)?.let { return it }
        if (pearls) routes[Kind.PEARL]?.get(key)?.let { return it }
        return routes[Kind.STANDARD]?.get(key) ?: routes[Kind.PEARL]?.get(key).orEmpty()
    }

    fun isRecorded(room: String): Boolean = routes[Kind.RECORDED]?.containsKey(room.lowercase()) == true

    fun roomCount(): Int = routes.values.flatMap { it.keys }.toSet().size

    fun load() {
        val loaded = HashMap<Kind, HashMap<String, MutableList<List<Step>>>>()
        var files = 0
        runCatching {
            Files.createDirectories(folder)
            folder.listDirectoryEntries().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
                val kind = when {
                    file.name.equals(RECORDED_FILE, ignoreCase = true) -> Kind.RECORDED
                    file.name.contains("pearl", ignoreCase = true) -> Kind.PEARL
                    else -> Kind.STANDARD
                }
                runCatching {
                    val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject
                    val target = loaded.getOrPut(kind) { HashMap() }
                    json.entrySet().forEach { (name, value) ->
                        if (name.startsWith("#") || !value.isJsonArray) return@forEach
                        val room = name.substringBefore(':').lowercase()
                        val steps = value.asJsonArray.mapNotNull { runCatching { parseStep(it.asJsonObject) }.getOrNull() }
                        if (steps.isNotEmpty()) target.getOrPut(room) { mutableListOf() } += steps
                    }
                    files++
                }.onFailure { logger.warn("Could not read route file {}: {}", file.name, it.message) }
            }
        }.onFailure { logger.warn("Could not read the routes folder: {}", it.message) }
        routes = loaded
        report = "$files files, ${roomCount()} rooms"
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

    // ------------------------------------------------------------------ writing

    private fun points(list: List<IntArray>): JsonArray = JsonArray().also { array ->
        list.forEach { p -> array.add(JsonArray().also { it.add(p[0]); it.add(p[1]); it.add(p[2]) }) }
    }

    fun toJson(step: Step): JsonObject = JsonObject().also { json ->
        json.add("locations", points(step.locations))
        json.add("etherwarps", points(step.etherwarps))
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

    /** Saves [steps] as [room]'s route in your recordings, replacing any route it had there. */
    fun saveRecorded(room: String, steps: List<Step>): Path {
        Files.createDirectories(folder)
        val file = folder.resolve(RECORDED_FILE)
        val json: JsonObject = runCatching {
            if (Files.exists(file)) Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject else null
        }.getOrNull() ?: JsonObject()
        json.add(room, JsonArray().also { array -> steps.forEach { array.add(toJson(it)) } })
        Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(json as JsonElement))
        load()
        return file
    }

    // ------------------------------------------------------------------ community routes

    private const val SOURCE = "https://raw.githubusercontent.com/yourboykyle/SecretRoutes/main/"

    /** The Secret Routes Mod's routes, fetched from its GitHub when you ask for them. */
    private val COMMUNITY = listOf("routes.json", "pearlroutes.json")

    fun downloadCommunity(done: (String) -> Unit) {
        thread(name = "PawfectAddons Route Download", isDaemon = true) {
            val message = runCatching {
                Files.createDirectories(folder)
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
                COMMUNITY.forEach { name ->
                    val request = HttpRequest.newBuilder(URI.create(SOURCE + name)).timeout(Duration.ofSeconds(30)).GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    check(response.statusCode() == 200) { "$name: HTTP ${response.statusCode()}" }
                    // Only kept if it reads as a route file.
                    JsonParser.parseString(response.body()).asJsonObject
                    Files.writeString(folder.resolve(name), response.body())
                }
                load()
                "Downloaded the Secret Routes Mod's routes: ${roomCount()} rooms. Routes by yourboykyle, zyra and the Secret Routes team (GPL-3.0)."
            }.getOrElse { "Could not download routes: ${it.message}" }
            done(message)
        }
    }
}
