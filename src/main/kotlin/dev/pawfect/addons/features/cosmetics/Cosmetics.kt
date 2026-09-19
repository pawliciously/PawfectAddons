package dev.pawfect.addons.features.cosmetics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.utils.McCompat
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.UUID

object Cosmetics {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Cosmetics")

    private const val ENDPOINT = "https://cdn.pawfectaddons.net/v1/cosmetics.json"
    private const val SELF_KEY = "self"
    private const val RELOAD_COOLDOWN_MS = 15_000L
    private const val LEGACY = '§'
    private const val RETRY_BASE_MS = 20_000L
    private const val RETRY_MAX_DOUBLINGS = 6
    private val REFRESH_INTERVAL = Duration.ofMinutes(30)

    private val config get() = ConfigManager.features.cosmetics

    private val gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    private val cacheFile: Path get() = ConfigManager.configDirectory.resolve("cosmetics.cache.json")
    private val overrideFile: Path get() = ConfigManager.configDirectory.resolve("cosmetics.local.json")

    private val startedAt = System.nanoTime()

    @Volatile
    private var entries: Map<UUID, ResolvedCosmetic> = emptyMap()

    @Volatile
    private var selfEntry: ResolvedCosmetic? = null

    @Volatile
    private var refreshing = false

    private var etag: String? = null
    private var nextRefreshAt = 0L
    private var failures = 0
    private var lastReload = 0L
    private var started = false

    val count: Int get() = entries.values.distinct().size + if (selfEntry != null) 1 else 0

    val owned: Boolean
        get() {
            if (selfEntry != null) return true
            val uuid = McCompat.mc.user?.profileId ?: return false
            return entries.containsKey(uuid)
        }

    fun onTick() {
        if (!started) {
            started = true
            loadFromDisk()
        }

        if (!config.enabled) return
        if (refreshing) return
        if (System.currentTimeMillis() < nextRefreshAt) return
        nextRefreshAt = System.currentTimeMillis() + REFRESH_INTERVAL.toMillis()
        refreshAsync()
    }

    fun reload(): String {
        val remaining = RELOAD_COOLDOWN_MS - (System.currentTimeMillis() - lastReload)
        if (lastReload != 0L && remaining > 0) {
            return "Wait ${(remaining / 1000L) + 1}s before reloading again."
        }

        lastReload = System.currentTimeMillis()
        nextRefreshAt = System.currentTimeMillis() + REFRESH_INTERVAL.toMillis()
        failures = 0
        etag = null
        loadFromDisk()
        refreshAsync()
        return "Refreshing cosmetics."
    }

    @JvmStatic
    fun styleNameTag(uuid: UUID, ownerName: String, nameTag: Component?): Component? {
        if (!config.enabled) return null
        if (nameTag == null) return null

        val entry = lookup(uuid) ?: return null
        val name = if (config.nameColors) entry.name else null
        val badges = if (badgeVisible(uuid)) entry.badges else emptyList()
        if (name == null && badges.isEmpty()) return null

        val base = name?.let { recolor(nameTag, ownerName, it) } ?: nameTag
        if (badges.isEmpty()) return base

        val styled = Component.empty()
        badges.forEach { styled.append(it.component) }
        return styled.append(Component.literal(" ")).append(base)
    }

    private fun badgeVisible(uuid: UUID): Boolean = when (config.badges) {
        Visibility.ALL -> true
        Visibility.SELF -> uuid == McCompat.mc.player?.uuid
        Visibility.NONE -> false
    }

    @JvmStatic
    fun capeFor(uuid: UUID): String? {
        if (!config.enabled) return null
        return lookup(uuid)?.cape
    }

    @JvmStatic
    fun trailFor(uuid: UUID): ResolvedTrail? {
        if (!config.enabled) return null
        return lookup(uuid)?.trail
    }

    @JvmStatic
    fun motesFor(uuid: UUID): ResolvedMotes? {
        if (!config.enabled) return null
        return lookup(uuid)?.motes
    }

    private fun lookup(uuid: UUID): ResolvedCosmetic? {
        entries[uuid]?.let { return it }
        val self = selfEntry ?: return null
        return if (uuid == McCompat.mc.player?.uuid) self else null
    }

    private fun recolor(source: Component, ownerName: String, name: ResolvedName): Component? {
        if (ownerName.isEmpty()) return null

        val runs = ArrayList<Run>()
        source.visit(
            object : FormattedText.StyledContentConsumer<Unit> {
                override fun accept(style: Style, text: String): Optional<Unit> {
                    runs.add(Run(style, text))
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        )

        if (runs.isEmpty()) return null

        val result = Component.empty()
        var replaced = false

        for (run in runs) {
            val at = if (replaced) -1 else run.text.indexOf(ownerName)
            if (at < 0) {
                result.append(Component.literal(run.text).setStyle(run.style))
                continue
            }

            replaced = true
            if (at > 0) {
                result.append(Component.literal(run.text.substring(0, at)).setStyle(run.style))
            }

            appendName(result, ownerName, run.style, name)

            val tail = at + ownerName.length
            if (tail < run.text.length) {
                result.append(Component.literal(run.text.substring(tail)).setStyle(carry(run.text, at, run.style)))
            }
        }

        return if (replaced) result else null
    }

    private fun appendName(into: MutableComponent, ownerName: String, base: Style, name: ResolvedName) {
        val time = if (config.animate && name.isAnimated) seconds() else 0f
        val span = (ownerName.length - 1).coerceAtLeast(1)

        for (index in ownerName.indices) {
            var style = base.withColor(name.colorAt(index.toFloat() / span, time))
            if (name.bold) style = style.withBold(true)
            val character = if (name.uppercase) ownerName[index].uppercaseChar() else ownerName[index]
            into.append(Component.literal(character.toString()).setStyle(style))
        }
    }

    private fun carry(text: String, upTo: Int, base: Style): Style {
        var style = base
        var index = 0
        while (index < upTo - 1) {
            if (text[index] == LEGACY) {
                val format = ChatFormatting.getByCode(text[index + 1])
                if (format != null) {
                    style = if (format == ChatFormatting.RESET) base else style.applyFormat(format)
                }
                index += 2
                continue
            }
            index++
        }
        return style
    }

    private class Run(val style: Style, val text: String)

    private fun seconds(): Float = (System.nanoTime() - startedAt) / 1_000_000_000f

    private fun refreshAsync() {
        if (Files.exists(overrideFile)) return

        refreshing = true
        Thread({
            val delivered = try {
                refresh()
            } catch (error: Exception) {
                logger.warn("Could not refresh cosmetics; keeping the cached list.", error)
                false
            } finally {
                refreshing = false
            }
            schedule(delivered)
        }, "PawfectAddons Cosmetics").apply { isDaemon = true }.start()
    }

    private fun schedule(delivered: Boolean) {
        if (delivered) {
            failures = 0
            nextRefreshAt = System.currentTimeMillis() + REFRESH_INTERVAL.toMillis()
            return
        }

        failures++
        val doublings = (failures - 1).coerceAtMost(RETRY_MAX_DOUBLINGS)
        val backoff = (RETRY_BASE_MS shl doublings).coerceAtMost(REFRESH_INTERVAL.toMillis())
        nextRefreshAt = System.currentTimeMillis() + backoff
        logger.info("Retrying cosmetics in {}s.", backoff / 1000L)
    }

    private fun refresh(): Boolean {
        val builder = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .header("User-Agent", "PawfectAddons")
            .timeout(Duration.ofSeconds(20))
        etag?.let { builder.header("If-None-Match", it) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 304) return true
        if (response.statusCode() != 200) {
            logger.warn("Cosmetics request returned HTTP {}", response.statusCode())
            return false
        }

        val body = response.body()
        if (!apply(body, "network")) return false

        etag = response.headers().firstValue("etag").orElse(null)
        runCatching { Files.writeString(cacheFile, body) }
            .onFailure { logger.warn("Could not cache the cosmetics list.", it) }
        return true
    }

    private fun loadFromDisk() {
        val override = overrideFile
        if (Files.exists(override)) {
            runCatching { apply(Files.readString(override), "local override") }
                .onFailure { logger.warn("Could not read the local cosmetics override.", it) }
            return
        }

        val cache = cacheFile
        if (!Files.exists(cache)) return
        runCatching { apply(Files.readString(cache), "cache") }
            .onFailure { logger.warn("Could not read the cached cosmetics list.", it) }
    }

    private fun apply(json: String, source: String): Boolean {
        val parsed = runCatching { parse(json) }.getOrElse {
            logger.warn("Could not parse the cosmetics list from {}.", source, it)
            return false
        }

        entries = parsed.first
        selfEntry = parsed.second
        Capes.retain(activeCapeUrls())
        logger.info("Loaded {} cosmetics from {}.", count, source)
        return true
    }

    private fun parse(json: String): Pair<Map<UUID, ResolvedCosmetic>, ResolvedCosmetic?> {
        val root = JsonParser.parseString(json).asJsonObject
        val people = root.getAsJsonObject("cosmetics") ?: return emptyMap<UUID, ResolvedCosmetic>() to null

        val resolved = HashMap<UUID, ResolvedCosmetic>(people.size())
        var self: ResolvedCosmetic? = null

        for ((key, element) in people.entrySet()) {
            val entry = runCatching { gson.fromJson(element, CosmeticEntry::class.java) }.getOrNull() ?: continue
            val cosmetic = entry.resolve() ?: continue

            if (key.equals(SELF_KEY, ignoreCase = true)) {
                self = cosmetic
                continue
            }

            val uuid = parseUuid(key) ?: continue
            resolved[uuid] = cosmetic

            val ign = entry.ign?.trim()
            if (!ign.isNullOrEmpty()) resolved.putIfAbsent(offlineUuid(ign), cosmetic)
        }

        return resolved to self
    }

    private fun activeCapeUrls(): Set<String> {
        val urls = HashSet<String>()
        entries.values.forEach { it.cape?.let(urls::add) }
        selfEntry?.cape?.let(urls::add)
        return urls
    }

    private fun offlineUuid(ign: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$ign".toByteArray(Charsets.UTF_8))

    private fun parseUuid(raw: String): UUID? {
        val text = raw.trim()
        if (text.length == 32) {
            val dashed = "${text.substring(0, 8)}-${text.substring(8, 12)}-${text.substring(12, 16)}-" +
                "${text.substring(16, 20)}-${text.substring(20)}"
            return runCatching { UUID.fromString(dashed) }.getOrNull()
        }
        return runCatching { UUID.fromString(text) }.getOrNull()
    }
}
