package dev.pawfect.addons.features.dungeon.secrets

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.Expose
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.utils.McCompat
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.io.ObjectInputStream
import java.util.zip.InflaterInputStream

object SecretRoomData {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Secrets")

    val NUMERIC_ID: Map<String, Byte> = mapOf(
        "minecraft:stone" to 1,
        "minecraft:diorite" to 2,
        "minecraft:polished_diorite" to 3,
        "minecraft:andesite" to 4,
        "minecraft:polished_andesite" to 5,
        "minecraft:grass_block" to 6,
        "minecraft:dirt" to 7,
        "minecraft:coarse_dirt" to 8,
        "minecraft:cobblestone" to 9,
        "minecraft:bedrock" to 10,
        "minecraft:oak_leaves" to 11,
        "minecraft:gray_wool" to 12,
        "minecraft:double_stone_slab" to 13,
        "minecraft:mossy_cobblestone" to 14,
        "minecraft:clay" to 15,
        "minecraft:stone_bricks" to 16,
        "minecraft:mossy_stone_bricks" to 17,
        "minecraft:chiseled_stone_bricks" to 18,
        "minecraft:gray_terracotta" to 19,
        "minecraft:cyan_terracotta" to 20,
        "minecraft:black_terracotta" to 21,
    )

    class SecretEntry(
        @field:Expose val secretName: String = "",
        @field:Expose val category: String = "item",
        @field:Expose val x: Int = 0,
        @field:Expose val y: Int = 0,
        @field:Expose val z: Int = 0,
    )

    private class RoomInfo(
        @field:Expose val name: String = "",
    )

    private class RoomFile(
        @field:Expose val info: RoomInfo? = null,
        @field:Expose val secrets: List<SecretEntry>? = null,
    )

    private val gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()

    private val skeletons = HashMap<String, HashMap<String, IntArray>>()
    private val secrets = HashMap<String, List<SecretEntry>>()

    @Volatile
    var loaded = false
        private set

    var lastLoadReport: String = "not attempted"
        private set

    val shapes: Set<String> get() = skeletons.keys

    fun roomsForShape(shape: String): Map<String, IntArray> = skeletons[shape] ?: emptyMap()

    fun secretsFor(room: String): List<SecretEntry> = secrets[room] ?: emptyList()

    fun roomCount(): Int = skeletons.values.sumOf { it.size }

    fun load() {
        if (loaded) return
        val manager = McCompat.mc.resourceManager
        val prefix = "dungeons/catacombs"

        val resources = manager.listResources(prefix) { id ->
            id.namespace == PawfectAddons.MOD_ID && (id.path.endsWith(".skeleton") || id.path.endsWith(".json"))
        }

        resources.forEach { (id, resource) ->
            val parts = id.path.split("/")
            if (parts.size != 4) return@forEach
            val shape = parts[2]
            val file = parts[3]
            runCatching {
                if (file.endsWith(".skeleton")) {
                    val room = file.removeSuffix(".skeleton")
                    val data = ObjectInputStream(InflaterInputStream(resource.open())).use { it.readObject() as IntArray }
                    skeletons.getOrPut(shape) { HashMap() }[room] = data
                } else {
                    val room = file.removeSuffix(".json")
                    val parsed = resource.openAsReader().use { gson.fromJson(it, RoomFile::class.java) }
                    parsed?.secrets?.let { secrets[room] = it }
                }
            }.onFailure { logger.error("Could not read dungeon room data {}", id, it) }
        }

        loaded = skeletons.isNotEmpty()
        lastLoadReport = "resources=${resources.size} skeletons=${roomCount()} shapes=${skeletons.size} secrets=${secrets.size}"
        logger.info("Dungeon room data: {}", lastLoadReport)
    }

    fun identifier(path: String): Identifier =
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)
}
