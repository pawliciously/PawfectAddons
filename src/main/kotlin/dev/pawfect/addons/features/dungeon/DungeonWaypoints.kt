package dev.pawfect.addons.features.dungeon

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.Expose
import com.google.gson.reflect.TypeToken
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object DungeonWaypoints {

    private val logger = LoggerFactory.getLogger("PawfectAddons/DungeonWaypoints")

    private const val FILE_NAME = "pa-dw.json"

    private val gson: Gson = GsonBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .setPrettyPrinting()
        .create()

    class Waypoint(
        @field:Expose val room: String,
        @field:Expose var name: String,
        @field:Expose var color: Int,
        @field:Expose val x: Int,
        @field:Expose val y: Int,
        @field:Expose val z: Int,
        @field:Expose var showName: Boolean = true,
        @field:Expose var visible: Boolean = true,
    )

    private val waypoints = ArrayList<Waypoint>()
    private var loaded = false

    private val config get() = ConfigManager.features.dungeonWaypoints

    fun path(): Path = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve(FILE_NAME)

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        load()
    }

    fun all(): List<Waypoint> {
        ensureLoaded()
        return waypoints
    }

    fun forRoom(room: String): List<Waypoint> {
        ensureLoaded()
        return waypoints.filter { it.room == room }
    }

    fun add(): String {
        if (!SkyBlockData.inDungeons) return "You are not in a dungeon."
        val match = DungeonScanner.matchedRoom() ?: return "Room not identified yet, wait a moment."
        val target = targetBlock() ?: return "Look at a block first."

        val relative = DungeonScanner.actualToRelative(match, target)
        val existing = waypoints.firstOrNull {
            it.room == match.name!! && it.x == relative.x && it.y == relative.y && it.z == relative.z
        }
        if (existing != null) return "That block already has a waypoint (${existing.name})."

        val index = forRoom(match.name!!).size + 1
        waypoints.add(
            Waypoint(match.name!!, "Waypoint $index", config.defaultColor, relative.x, relative.y, relative.z),
        )
        save()
        return "Added waypoint in ${match.name} at ${target.x}, ${target.y}, ${target.z}."
    }

    fun remove(waypoint: Waypoint) {
        ensureLoaded()
        waypoints.remove(waypoint)
        save()
    }

    fun clearRoom(): String {
        val match = DungeonScanner.matchedRoom() ?: return "Room not identified yet."
        val removed = waypoints.removeAll { it.room == match.name!! }
        save()
        return if (removed) "Cleared waypoints in this room." else "No waypoints in this room."
    }

    private fun targetBlock(): net.minecraft.core.BlockPos? {
        val hit = McCompat.mc.hitResult ?: return null
        if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK) return null
        if (hit !is net.minecraft.world.phys.BlockHitResult) return null
        val level = McCompat.mc.level ?: return null
        if (level.getBlockState(hit.blockPos).isAir) return null
        return hit.blockPos
    }

    fun load() {
        waypoints.clear()
        val file = path()
        if (!Files.exists(file)) return
        runCatching {
            val type = object : TypeToken<List<Waypoint>>() {}.type
            val parsed: List<Waypoint>? = gson.fromJson(Files.readString(file), type)
            parsed?.let { waypoints.addAll(it) }
        }.onFailure { logger.error("Could not read $FILE_NAME", it) }
    }

    fun save() {
        val file = path()
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(waypoints))
        }.onFailure { logger.error("Could not write $FILE_NAME", it) }
    }

    fun exportToClipboard(): String {
        ensureLoaded()
        if (waypoints.isEmpty()) return "No waypoints to export."
        McCompat.mc.keyboardHandler.clipboard = gson.toJson(waypoints)
        return "Copied ${waypoints.size} waypoint(s) to your clipboard."
    }

    fun importFromClipboard(): String {
        val text = runCatching { McCompat.mc.keyboardHandler.clipboard }.getOrNull()
        if (text.isNullOrBlank()) return "Your clipboard is empty."
        return importJson(text)
    }

    fun importFromFile(): String {
        val source = path().parent.resolve("pa-dw-import.json")
        if (!Files.exists(source)) return "Put a file at $source first."
        return runCatching { importJson(Files.readString(source)) }.getOrElse { "Could not read that file: ${it.message}" }
    }

    private fun importJson(text: String): String = runCatching {
        val type = object : TypeToken<List<Waypoint>>() {}.type
        val parsed: List<Waypoint> = gson.fromJson(text, type) ?: emptyList()
        ensureLoaded()
        var added = 0
        parsed.forEach { incoming ->
            val duplicate = waypoints.any {
                it.room == incoming.room && it.x == incoming.x && it.y == incoming.y && it.z == incoming.z
            }
            if (!duplicate) {
                waypoints.add(incoming)
                added++
            }
        }
        save()
        "Imported $added waypoint(s)."
    }.getOrElse { "That did not look like waypoint JSON." }

    fun importFrom(source: Path): String {
        if (!Files.exists(source)) return "No file at $source"
        return runCatching {
            val type = object : TypeToken<List<Waypoint>>() {}.type
            val parsed: List<Waypoint> = gson.fromJson(Files.readString(source), type) ?: emptyList()
            ensureLoaded()
            var added = 0
            parsed.forEach { incoming ->
                val duplicate = waypoints.any {
                    it.room == incoming.room && it.x == incoming.x && it.y == incoming.y && it.z == incoming.z
                }
                if (!duplicate) {
                    waypoints.add(incoming)
                    added++
                }
            }
            save()
            "Imported $added waypoint(s)."
        }.getOrElse { "Could not read that file: ${it.message}" }
    }
}
