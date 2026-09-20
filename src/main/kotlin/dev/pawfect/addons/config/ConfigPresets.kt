package dev.pawfect.addons.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.Expose
import dev.pawfect.addons.features.recipetracker.RecipeTrackerOverlay
import dev.pawfect.addons.features.visual.LavaChanger
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

object ConfigPresets {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Presets")

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .excludeFieldsWithoutExposeAnnotation()
        .serializeNulls()
        .create()

    private val LOCAL = listOf(
        "theme" to "windowX",
        "theme" to "windowY",
        "theme" to "lastCategory",
        "theme" to "collapsedSections",
        "theme" to "collapsedGroups",
        "cosmetics" to "enrolledAt",
    )

    private const val MAX_NAME = 32

    var draftName: String = ""

    val directory: Path get() = ConfigManager.configDirectory.resolve("presets")

    fun clean(raw: String): String = raw.trim()
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .take(MAX_NAME)
        .trim()

    fun names(): List<String> {
        val dir = directory
        if (!dir.exists()) return emptyList()
        return runCatching {
            Files.list(dir).use { stream -> stream.toList() }
                .filter { it.extension.equals("json", ignoreCase = true) }
                .map { it.nameWithoutExtension }
                .sorted()
        }.getOrElse {
            logger.warn("Could not list the presets folder", it)
            emptyList()
        }
    }

    fun save(rawName: String): Result<String> {
        val name = clean(rawName)
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Give the config a name first."))

        return runCatching {
            val dir = directory
            dir.createDirectories()

            val tree = gson.toJsonTree(ConfigManager.features).asJsonObject
            strip(tree)

            val target = dir.resolve("$name.json")
            val temp = dir.resolve("$name.json.tmp")
            Files.writeString(temp, gson.toJson(tree))
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            name
        }.onFailure { logger.warn("Could not save the config {}", name, it) }
    }

    fun load(name: String): Result<String> = runCatching {
        val file = directory.resolve("$name.json")
        if (!file.exists()) throw IllegalArgumentException("There is no config called $name.")

        val parsed = JsonParser.parseString(file.readText())
        if (!parsed.isJsonObject) throw IllegalArgumentException("$name is not a config file.")

        val tree = parsed.asJsonObject
        strip(tree)

        val incoming = gson.fromJson(tree, PawfectConfig::class.java)
            ?: throw IllegalArgumentException("$name could not be read.")

        val live = ConfigManager.features
        val backup = gson.toJsonTree(live).asJsonObject

        try {
            apply(live, incoming, tree)
        } catch (e: Exception) {
            runCatching { apply(live, gson.fromJson(backup, PawfectConfig::class.java), backup) }
            throw e
        }

        settle()
        ConfigManager.save(ConfigFileType.FEATURES, "loaded config $name")
        name
    }.onFailure { logger.warn("Could not load the config {}", name, it) }

    fun delete(name: String): Result<String> = runCatching {
        directory.resolve("$name.json").deleteIfExists()
        name
    }.onFailure { logger.warn("Could not delete the config {}", name, it) }

    fun openFolder(): Result<Unit> = runCatching {
        val dir = directory
        dir.createDirectories()
        Util.getPlatform().openPath(dir)
    }.onFailure { logger.warn("Could not open the presets folder", it) }

    private fun strip(tree: JsonObject) {
        for ((branchName, fieldName) in LOCAL) {
            val branch = tree.get(branchName) ?: continue
            if (!branch.isJsonObject) continue
            branch.asJsonObject.remove(fieldName)
        }
    }

    private fun apply(target: Any, incoming: Any, json: JsonObject) {
        for (field in exposed(target.javaClass)) {
            if (!json.has(field.name)) continue

            val fresh = field.get(incoming) ?: continue
            val current = field.get(target)

            if (current is MutableCollection<*> && fresh is Collection<*>) {
                @Suppress("UNCHECKED_CAST")
                val sink = current as MutableCollection<Any?>
                sink.clear()
                sink.addAll(fresh)
                continue
            }

            if (current is MutableMap<*, *> && fresh is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val sink = current as MutableMap<Any?, Any?>
                sink.clear()
                sink.putAll(fresh)
                continue
            }

            val branch = json.get(field.name)
            if (current != null && own(field.type) && branch.isJsonObject) {
                apply(current, fresh, branch.asJsonObject)
                continue
            }

            if (Modifier.isFinal(field.modifiers)) continue
            field.set(target, fresh)
        }
    }

    private fun settle() {
        sanitize(ConfigManager.features)
        runCatching { RecipeTrackerOverlay.invalidate() }
        runCatching { LavaChanger.invalidate() }
    }

    private fun sanitize(holder: Any) {
        for (field in exposed(holder.javaClass)) {
            if (!own(field.type)) continue
            val child = field.get(holder) ?: continue
            val method = child.javaClass.methods.firstOrNull { it.name == "sanitize" && it.parameterCount == 0 }
            if (method != null) runCatching { method.invoke(child) }
            sanitize(child)
        }
    }

    private fun exposed(clazz: Class<*>): List<Field> = clazz.declaredFields
        .filter {
            !Modifier.isStatic(it.modifiers) &&
                !Modifier.isTransient(it.modifiers) &&
                it.isAnnotationPresent(Expose::class.java)
        }
        .onEach { it.isAccessible = true }

    private fun own(type: Class<*>): Boolean =
        !type.isEnum && !type.isPrimitive && type.name.startsWith("dev.pawfect.addons.config.")
}
