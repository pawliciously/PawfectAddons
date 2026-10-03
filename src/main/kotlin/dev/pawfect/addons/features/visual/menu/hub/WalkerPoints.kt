package dev.pawfect.addons.features.visual.menu.hub

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.utils.McCompat
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Spots recorded in game with /pa menu point, kept in a plain JSON file so they can be turned
 * into new walks for the menu scene. Each one notes where it was taken, since a hub spot only
 * makes sense in the hub.
 */
object WalkerPoints {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    val file: Path get() = ConfigManager.configDirectory.resolve("walker_points.json")

    /** Adds the player's spot to the list and returns how many points it now holds. */
    fun add(): Int? {
        val player = McCompat.player ?: return null
        val points = read()
        points.add(JsonObject().apply {
            addProperty("x", round(player.x))
            addProperty("y", round(player.y))
            addProperty("z", round(player.z))
            addProperty("yaw", round(player.yRot.toDouble()))
            addProperty("pitch", round(player.xRot.toDouble()))
            addProperty("world", HypixelLocation.mode ?: "unknown")
            addProperty("server", HypixelLocation.lobby ?: "unknown")
            addProperty("dimension", McCompat.mc.level?.dimension()?.identifier()?.toString() ?: "unknown")
            addProperty("recorded", Instant.now().toString())
        })
        write(points)
        return points.size()
    }

    /** Drops the most recent point, returning how many are left, or null if there were none. */
    fun undo(): Int? {
        val points = read()
        if (points.isEmpty) return null
        points.remove(points.size() - 1)
        write(points)
        return points.size()
    }

    fun clear() = write(JsonArray())

    fun count(): Int = read().size()

    private fun read(): JsonArray {
        if (!Files.exists(file)) return JsonArray()
        return runCatching { JsonParser.parseString(Files.readString(file)).asJsonArray }.getOrElse { JsonArray() }
    }

    private fun write(points: JsonArray) {
        Files.createDirectories(file.parent)
        Files.writeString(file, gson.toJson(points))
    }

    private fun round(value: Double): Double = Math.round(value * 100.0) / 100.0
}
