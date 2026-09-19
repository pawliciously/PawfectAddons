package dev.pawfect.addons.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class ConfigFileType(val fileName: String) {
    FEATURES("config"),
    SACKS("sacks"),
    TRACKER("tracker"),
    STORAGE("storage"),
}

object ConfigManager {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Config")

    val configDirectory: Path = FabricLoader.getInstance().configDir.resolve("pawfectaddons")

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .excludeFieldsWithoutExposeAnnotation()
        .serializeNulls()
        .create()

    lateinit var features: PawfectConfig
        private set
    lateinit var sacks: SackStorage
        private set
    lateinit var tracker: TrackerStorage
        private set
    lateinit var storage: StorageData
        private set

    fun load() {
        configDirectory.createDirectories()
        features = read(ConfigFileType.FEATURES, PawfectConfig::class.java) { PawfectConfig() }
        sacks = read(ConfigFileType.SACKS, SackStorage::class.java) { SackStorage() }
        tracker = read(ConfigFileType.TRACKER, TrackerStorage::class.java) { TrackerStorage() }
        storage = read(ConfigFileType.STORAGE, StorageData::class.java) { StorageData() }
    }

    private fun <T : Any> read(type: ConfigFileType, clazz: Class<T>, fallback: () -> T): T {
        val file = pathOf(type)
        if (!file.exists()) return fallback()

        return try {
            gson.fromJson(file.readText(), clazz) ?: fallback()
        } catch (e: Exception) {
            val backup = configDirectory.resolve("${type.fileName}-${System.currentTimeMillis()}-corrupt.json")
            runCatching { Files.copy(file, backup) }
                .onFailure { logger.error("Could not back up corrupt {}", type.fileName, it) }
            logger.error("Could not read {}; starting fresh. Broken file saved to {}", type.fileName, backup, e)
            fallback()
        }
    }

    fun save(type: ConfigFileType, reason: String) {
        val data: Any = when (type) {
            ConfigFileType.FEATURES -> features
            ConfigFileType.SACKS -> sacks
            ConfigFileType.TRACKER -> tracker
            ConfigFileType.STORAGE -> storage
        }
        try {
            configDirectory.createDirectories()
            pathOf(type).writeText(gson.toJson(data))
            logger.debug("Saved {} ({})", type.fileName, reason)
        } catch (e: Exception) {
            logger.error("Could not save {} ({})", type.fileName, reason, e)
        }
    }

    fun saveAll(reason: String) = ConfigFileType.entries.forEach { save(it, reason) }

    private fun pathOf(type: ConfigFileType): Path = configDirectory.resolve("${type.fileName}.json")
}
