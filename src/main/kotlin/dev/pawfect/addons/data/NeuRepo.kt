package dev.pawfect.addons.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.Expose
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.PropertyMap
import com.mojang.serialization.JsonOps
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.StringUtil.internalNameToDisplay
import dev.pawfect.addons.utils.StringUtil.normalizeItemName
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.ExtraCodecs
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

object NeuRepo {

    private val logger = LoggerFactory.getLogger("PawfectAddons/NeuRepo")

    private const val REPO_ZIP =
        "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip"
    private const val CACHE_FILE = "neu-cache.json"
    private const val CACHE_VERSION = 3
    private val CACHE_MAX_AGE: Duration = Duration.ofDays(7)

    private val gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()

    class RepoItem(
        @field:Expose val internalName: String,
        @field:Expose val displayName: String,
        @field:Expose val itemId: String?,
        @field:Expose val recipe: Map<String, Int>,
        @field:Expose val skullTexture: String? = null,
    )

    private class RepoCache(
        @field:Expose val version: Int,
        @field:Expose val items: List<RepoItem>,
        @field:Expose val sackContents: Map<String, List<String>>,
        @field:Expose val armorSets: Map<String, List<String>> = emptyMap(),
    )

    class ArmorSet(
        val key: String,
        val displayName: String,
        val pieces: List<String>,
    )

    @Volatile
    var isLoaded: Boolean = false
        private set

    private var items: Map<String, RepoItem> = emptyMap()
    private var byNormalizedName: Map<String, String> = emptyMap()
    private var sackContents: Map<String, List<String>> = emptyMap()
    private var sackItemIds: Set<String> = emptySet()
    private var armorSets: Map<String, ArmorSet> = emptyMap()
    private var armorSetAliases: Map<String, String> = emptyMap()
    private var enchantBookNames: Map<String, String> = emptyMap()

    private val stackCache = HashMap<String, ItemStack>()

    fun item(internalName: String): RepoItem? = items[internalName]

    fun isSackItem(internalName: String): Boolean = internalName in sackItemIds

    fun sackItemIds(): Set<String> = sackItemIds

    fun allItemIds(): Set<String> = items.keys

    fun resolve(query: String): RepoItem? {
        val direct = query.trim().uppercase().replace(' ', '_')
        items[direct]?.let { return it }
        byNormalizedName[query.normalizeItemName()]?.let { return items[it] }
        return null
    }

    fun displayName(internalName: String): String {
        enchantBookNames[internalName]?.let { return it }
        return items[internalName]?.displayName ?: "§f" + internalName.internalNameToDisplay()
    }

    fun craftableDisplayNames(): List<String> = items.values
        .filter { it.recipe.isNotEmpty() }
        .map { displayName(it.internalName).removeColor().trim() }
        .filter { it.isNotBlank() }

    fun resolveArmorSet(query: String): ArmorSet? {
        val key = armorSetAliases[normalizeSetQuery(query)] ?: return null
        return armorSets[key]
    }

    fun armorSetNames(): List<String> = armorSets.values.map { it.displayName + " Armor" }.sorted()

    private fun normalizeSetQuery(query: String): String {
        var text = query.removeColor()
            .uppercase()
            .replace('_', ' ')
            .replace(Regex("[^A-Z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        for (suffix in listOf(" ARMOR SET", " ARMOUR SET", " ARMOR", " ARMOUR", " SET")) {
            if (text.endsWith(suffix)) {
                text = text.removeSuffix(suffix).trim()
                break
            }
        }
        return text
    }

    private const val ENCHANTED_BOOK_ITEM_ID = "minecraft:enchanted_book"
    private const val ULTIMATE_PREFIX = "ULTIMATE_"

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    private val ARMOR_PIECE_SUFFIXES = listOf("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")

    private fun buildArmorSets(raw: Map<String, List<String>>): Map<String, ArmorSet> {
        val result = LinkedHashMap<String, ArmorSet>()
        for ((key, entries) in raw) {
            val pieces = entries.filter { id -> ARMOR_PIECE_SUFFIXES.any { id.endsWith(it) } }
            if (pieces.size < 2) continue
            result[key] = ArmorSet(key, setDisplayName(key, pieces), pieces.sorted())
        }
        return result
    }

    private fun setDisplayName(key: String, pieces: List<String>): String {
        val sample = pieces.firstOrNull { it.endsWith("_HELMET") } ?: pieces.first()
        val name = items[sample]?.displayName?.removeColor()?.trim()
        if (name != null) {
            for (suffix in listOf(" Helmet", " Chestplate", " Leggings", " Boots")) {
                if (name.endsWith(suffix)) return name.removeSuffix(suffix).trim()
            }
        }
        return key.internalNameToDisplay()
    }

    private fun buildArmorSetAliases(sets: Map<String, ArmorSet>): Map<String, String> {
        val aliases = HashMap<String, String>()
        for (set in sets.values) {
            aliases.putIfAbsent(normalizeSetQuery(set.key), set.key)
            val display = normalizeSetQuery(set.displayName)
            aliases.putIfAbsent(display, set.key)
            if (display.endsWith("S")) aliases.putIfAbsent(display.dropLast(1), set.key)
        }
        return aliases
    }

    fun itemStack(internalName: String): ItemStack = stackCache.getOrPut(internalName) {
        val item = items[internalName] ?: return@getOrPut ItemStack.EMPTY

        item.skullTexture?.let { texture ->
            return@getOrPut runCatching { headStack(texture) }
                .onFailure { logger.debug("Bad skull texture for {}", internalName, it) }
                .getOrDefault(ItemStack(Items.PLAYER_HEAD))
        }

        val identifier = Identifier.tryParse(item.itemId ?: "") ?: return@getOrPut ItemStack.EMPTY
        ItemStack(BuiltInRegistries.ITEM.getValue(identifier))
    }

    private fun headStack(texture: String): ItemStack {
        val stack = ItemStack(Items.PLAYER_HEAD)
        stack.set(DataComponents.PROFILE, profileFor(texture))
        return stack
    }

    private fun profileFor(texture: String): ResolvableProfile = ResolvableProfile.createResolved(
        GameProfile(
            UUID.nameUUIDFromBytes(texture.toByteArray(StandardCharsets.UTF_8)),
            "custom",
            propertyMapWithTexture(texture),
        ),
    )

    private fun propertyMapWithTexture(texture: String): PropertyMap = ExtraCodecs.PROPERTY_MAP.parse(
        JsonOps.INSTANCE,
        JsonParser.parseString("""[{"name":"textures","value":"$texture"}]"""),
    ).getOrThrow()

    fun loadAsync() {
        Thread({ load() }, "PawfectAddons NEU Repo").apply { isDaemon = true }.start()
    }

    private fun load() {
        try {
            val cachePath = ConfigManager.configDirectory.resolve(CACHE_FILE)
            val cache = readCache(cachePath) ?: downloadAndBuildCache(cachePath)
            if (cache == null) {
                logger.error("Could not load NEU data; recipe tracking will be unavailable.")
                return
            }
            apply(cache)
            logger.info("Loaded {} items and {} sacks from the NEU repo.", items.size, sackContents.size)
        } catch (e: Exception) {
            logger.error("Failed to load the NEU repo", e)
        }
    }

    private fun apply(cache: RepoCache) {
        items = cache.items.associateBy { it.internalName }
        enchantBookNames = buildEnchantBookNames(cache.items)

        val names = HashMap<String, String>(cache.items.size)
        for (item in cache.items) {
            names.putIfAbsent(displayName(item.internalName).normalizeItemName(), item.internalName)
        }
        for ((id, name) in enchantBookNames) {
            val level = id.substringAfter(';').toIntOrNull() ?: continue
            val base = name.removeColor().trim().substringBeforeLast(' ')
            if (base.isNotBlank()) names.putIfAbsent("$base $level".normalizeItemName(), id)
        }
        byNormalizedName = names

        sackContents = cache.sackContents
        sackItemIds = cache.sackContents.values.flatten().toSet()
        armorSets = buildArmorSets(cache.armorSets)
        armorSetAliases = buildArmorSetAliases(armorSets)
        isLoaded = true
    }

    private fun buildEnchantBookNames(all: List<RepoItem>): Map<String, String> {
        val books = all.filter { it.itemId == ENCHANTED_BOOK_ITEM_ID && it.internalName.contains(';') }
        if (books.isEmpty()) return emptyMap()

        val plainBases = books.map { it.internalName.substringBefore(';') }
            .filterNot { it.startsWith(ULTIMATE_PREFIX) }
            .toSet()

        val result = HashMap<String, String>(books.size)
        for (book in books) {
            val base = book.internalName.substringBefore(';')
            val stripped = base.removePrefix(ULTIMATE_PREFIX)
            val useStripped = base.startsWith(ULTIMATE_PREFIX) && stripped !in plainBases
            val label = (if (useStripped) stripped else base).internalNameToDisplay()
            val level = book.internalName.substringAfter(';').toIntOrNull()
            result[book.internalName] = leadingColor(book.displayName) + label +
                if (level != null) " " + roman(level) else ""
        }
        return result
    }

    private fun leadingColor(text: String): String =
        if (text.length >= 2 && text[0] == '§') text.substring(0, 2) else "§f"

    private fun roman(value: Int): String {
        if (value !in 1..ROMAN.size) return value.toString()
        return ROMAN[value - 1]
    }

    private fun readCache(path: Path): RepoCache? {
        if (!path.exists()) return null
        val age = Duration.ofMillis(System.currentTimeMillis() - path.getLastModifiedTime().toMillis())
        if (age > CACHE_MAX_AGE) {
            logger.info("NEU cache is {} days old; refreshing.", age.toDays())
            return null
        }
        return try {
            gson.fromJson(path.readText(), RepoCache::class.java)?.takeIf { it.version == CACHE_VERSION }
        } catch (e: Exception) {
            logger.warn("NEU cache was unreadable; re-downloading.", e)
            null
        }
    }

    private fun downloadAndBuildCache(path: Path): RepoCache? {
        logger.info("Downloading the NEU repository (the first run may take a minute)...")

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val request = HttpRequest.newBuilder(URI.create(REPO_ZIP))
            .header("User-Agent", "PawfectAddons")
            .timeout(Duration.ofMinutes(5))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            logger.error("NEU repo download failed with HTTP {}", response.statusCode())
            return null
        }

        val parsedItems = ArrayList<RepoItem>(6000)
        var sacks: Map<String, List<String>> = emptyMap()
        var sets: Map<String, List<String>> = emptyMap()

        ZipInputStream(response.body().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name

                when {
                    name.contains("/items/") && name.endsWith(".json") ->
                        parseItem(zip)?.let { parsedItems.add(it) }

                    name.endsWith("/constants/sacks.json") -> sacks = parseSacks(zip)

                    name.endsWith("/constants/museum.json") -> sets = parseArmorSets(zip)
                }
            }
        }

        if (parsedItems.isEmpty()) {
            logger.error("NEU repo download contained no items.")
            return null
        }

        val cache = RepoCache(CACHE_VERSION, parsedItems, sacks, sets)
        runCatching {
            path.parent.createDirectories()
            path.writeText(gson.toJson(cache))
        }.onFailure { logger.warn("Could not write the NEU cache; it will be rebuilt next launch.", it) }

        return cache
    }

    private fun parseItem(zip: ZipInputStream): RepoItem? = try {
        val json = JsonParser.parseReader(InputStreamReader(NonClosingStream(zip))).asJsonObject
        val internalName = json.get("internalname")?.asString
        if (internalName.isNullOrBlank()) {
            null
        } else {
            RepoItem(
                internalName = internalName,
                displayName = json.get("displayname")?.asString ?: internalName.internalNameToDisplay(),
                itemId = json.get("itemid")?.asString,
                recipe = parseRecipe(json.getAsJsonObject("recipe")),
                skullTexture = parseSkullTexture(json.get("nbttag")?.asString),
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun parseSkullTexture(nbt: String?): String? {
        if (nbt == null || !nbt.contains("SkullOwner")) return null
        return skullTexturePattern.find(nbt)?.groupValues?.get(1)
    }

    private val skullTexturePattern = Regex("""Value:\s*"([A-Za-z0-9+/=]+)"""")

    private fun parseRecipe(recipe: JsonObject?): Map<String, Int> {
        if (recipe == null) return emptyMap()
        val result = HashMap<String, Int>()
        for (row in listOf("A", "B", "C")) {
            for (column in 1..3) {
                val raw = recipe.get(row + column)?.asString ?: continue
                if (raw.isBlank()) continue
                val separator = raw.lastIndexOf(':')
                val ingredient = if (separator > 0) raw.substring(0, separator) else raw
                val count = if (separator > 0) raw.substring(separator + 1).toIntOrNull() ?: 1 else 1
                if (ingredient.isBlank()) continue
                result[ingredient] = (result[ingredient] ?: 0) + count
            }
        }
        return result
    }

    private fun parseArmorSets(zip: ZipInputStream): Map<String, List<String>> = try {
        val json = JsonParser.parseReader(InputStreamReader(NonClosingStream(zip))).asJsonObject
        val sets = json.getAsJsonObject("sets_to_items") ?: JsonObject()
        sets.entrySet().associate { entry ->
            entry.key to entry.value.asJsonArray.map { it.asString }
        }
    } catch (e: Exception) {
        logger.warn("Could not parse constants/museum.json", e)
        emptyMap()
    }

    private fun parseSacks(zip: ZipInputStream): Map<String, List<String>> = try {
        val json = JsonParser.parseReader(InputStreamReader(NonClosingStream(zip))).asJsonObject
        val sacks = json.getAsJsonObject("sacks") ?: JsonObject()
        sacks.entrySet().associate { entry ->
            val contents = entry.value.asJsonObject.getAsJsonArray("contents")
                ?.map { it.asString }
                .orEmpty()
            entry.key to contents
        }
    } catch (e: Exception) {
        logger.warn("Could not parse constants/sacks.json", e)
        emptyMap()
    }

    private class NonClosingStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() = Unit
    }
}
