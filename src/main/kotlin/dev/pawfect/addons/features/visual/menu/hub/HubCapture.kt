package dev.pawfect.addons.features.visual.menu.hub

import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.MenuSpot
import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.math.floor

object HubScene {

    const val VERSION = 2
    const val RADIUS_CHUNKS = 8
    const val HUB = "hub"

    fun file(area: String): Path = ConfigManager.configDirectory.resolve(
        if (area == HUB) "hub_scene.nbt" else "menu_scene_${area.lowercase().replace(Regex("[^a-z0-9_]"), "_")}.nbt",
    )

    fun configSpots(): MutableList<MenuSpot> {
        val config = ConfigManager.features.menu
        if (config.anchorSet && config.spots.none { it.area == HUB }) {
            config.spots += MenuSpot().apply {
                area = HUB
                x = config.anchorX
                y = config.anchorY
                z = config.anchorZ
                yaw = config.anchorYaw
                pitch = config.anchorPitch
            }
            config.anchorSet = false
            ConfigManager.save(ConfigFileType.FEATURES, "menu spots")
        }
        return config.spots
    }

    fun spots(): List<MenuSpot> {
        val scripted = listOf(HUB, "combat_3").mapNotNull { area -> MenuScenes.forArea(area)?.spot?.also { it.area = area } }
        return scripted + configSpots().filter { spot -> scripted.none { it.area == spot.area } }
    }

    fun spot(area: String?): MenuSpot? = area?.let { name -> spots().firstOrNull { it.area == name } }
}

object HubCapture {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HubCapture")

    private val config get() = ConfigManager.features.menu

    private const val SCAN_EVERY_TICKS = 10
    private const val CHUNKS_PER_SCAN = 6
    private const val SAVE_EVERY_MS = 30_000L
    private const val MOVED_FAR_BLOCKS = 32.0

    private val sections = LinkedHashMap<Long, CompoundTag>()
    private val columns = LinkedHashMap<Long, CompoundTag>()
    private val doneThisVisit = HashSet<Long>()
    private var loadedFromDisk = false
    private var dirty = false
    private var lastSave = 0L
    private var ticker = 0

    private var area: String? = null

    @Volatile
    private var saving = false

    val sectionCount: Int get() = sections.size

    val columnCount: Int
        get() = sections.keys.mapTo(HashSet()) { SectionPos.x(it).toLong() shl 32 or (SectionPos.z(it).toLong() and 0xFFFFFFFFL) }.size

    val currentArea: String? get() = HypixelLocation.mode

    fun onTick() {
        if (++ticker < SCAN_EVERY_TICKS) return
        ticker = 0
        val here = currentArea
        val spot = HubScene.spot(here)
        val capturing = config.hubScene && config.hubCapture && spot != null
        if (area != null && (!capturing || area != here)) leave()
        if (!capturing || spot == null) return
        if (area == null) {
            area = here
            loadedFromDisk = false
        }
        val level = McCompat.mc.level ?: return
        loadOnce()
        scan(level, spot)
        if (dirty && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) save(async = true)
    }

    fun shutdown() {
        save(async = false, force = true)
    }

    private fun leave() {
        save(async = false, force = true)
        sections.clear()
        columns.clear()
        doneThisVisit.clear()
        dirty = false
        area = null
    }

    fun setAnchor(area: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean {
        val spots = HubScene.configSpots()
        val old = spots.firstOrNull { it.area == area }
        val moved = old != null && (abs(old.x - x) > MOVED_FAR_BLOCKS || abs(old.z - z) > MOVED_FAR_BLOCKS)
        if (moved) clear(area)
        val spot = old ?: MenuSpot().also {
            it.area = area
            spots += it
        }
        spot.x = x
        spot.y = y
        spot.z = z
        spot.yaw = yaw
        spot.pitch = pitch
        doneThisVisit.clear()
        ConfigManager.save(ConfigFileType.FEATURES, "menu anchor")
        return moved
    }

    fun clear(area: String) {
        if (this.area == area) {
            sections.clear()
            columns.clear()
            doneThisVisit.clear()
            loadedFromDisk = true
            dirty = false
        }
        runCatching { Files.deleteIfExists(HubScene.file(area)) }
    }

    fun remove(area: String): Boolean {
        clear(area)
        val removed = HubScene.configSpots().removeIf { it.area == area }
        if (removed) ConfigManager.save(ConfigFileType.FEATURES, "menu spot removed")
        return removed
    }

    private fun scan(level: ClientLevel, spot: MenuSpot) {
        val centreX = floor(spot.x).toInt() shr 4
        val centreZ = floor(spot.z).toInt() shr 4
        var budget = CHUNKS_PER_SCAN
        for (ring in 0..HubScene.RADIUS_CHUNKS) {
            for (dx in -ring..ring) for (dz in -ring..ring) {
                if (maxOf(abs(dx), abs(dz)) != ring) continue
                val cx = centreX + dx
                val cz = centreZ + dz
                val key = SectionPos.asLong(cx, 0, cz)
                if (key in doneThisVisit) continue
                val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) ?: continue
                if (!level.lightEngine.lightOnInColumn(SectionPos.getZeroNode(cx, cz))) continue
                capture(level, chunk, cx, cz, spot)
                doneThisVisit += key
                dirty = true
                if (--budget == 0) return
            }
        }
    }

    private fun capture(level: ClientLevel, chunk: LevelChunk, cx: Int, cz: Int, spot: MenuSpot) {
        val sky = level.lightEngine.getLayerListener(LightLayer.SKY)
        val block = level.lightEngine.getLayerListener(LightLayer.BLOCK)
        chunk.sections.forEachIndexed { index, section ->
            val sy = chunk.getSectionYFromSectionIndex(index)
            val key = SectionPos.asLong(cx, sy, cz)
            if (section.hasOnlyAir()) {
                sections.remove(key)
                return@forEachIndexed
            }
            val where = SectionPos.of(cx, sy, cz)
            sections[key] = encode(section, cx, sy, cz, sky.getDataLayerData(where), block.getDataLayerData(where))
        }
        columns[SectionPos.asLong(cx, 0, cz)] = tints(level, cx, cz, spot.y)
    }

    private fun tints(level: ClientLevel, cx: Int, cz: Int, height: Double): CompoundTag {
        val y = floor(height).toInt()
        val pos = BlockPos.MutableBlockPos()
        val grass = IntArray(256)
        val foliage = IntArray(256)
        val dry = IntArray(256)
        val water = IntArray(256)
        for (z in 0 until 16) for (x in 0 until 16) {
            pos.set((cx shl 4) + x, y, (cz shl 4) + z)
            val at = (z shl 4) or x
            grass[at] = level.getBlockTint(pos, BiomeColors.GRASS_COLOR_RESOLVER)
            foliage[at] = level.getBlockTint(pos, BiomeColors.FOLIAGE_COLOR_RESOLVER)
            dry[at] = level.getBlockTint(pos, BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER)
            water[at] = level.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER)
        }
        return CompoundTag().apply {
            putInt("x", cx)
            putInt("z", cz)
            putIntArray("grass", grass)
            putIntArray("foliage", foliage)
            putIntArray("dry_foliage", dry)
            putIntArray("water", water)
        }
    }

    private fun encode(section: LevelChunkSection, x: Int, y: Int, z: Int, sky: DataLayer?, block: DataLayer?): CompoundTag {
        val palette = ArrayList<BlockState>()
        val lookup = HashMap<BlockState, Int>()
        val ids = IntArray(4096)
        for (by in 0 until 16) for (bz in 0 until 16) for (bx in 0 until 16) {
            val state = section.getBlockState(bx, by, bz)
            ids[(by shl 8) or (bz shl 4) or bx] = lookup.getOrPut(state) {
                palette += state
                palette.size - 1
            }
        }

        val tag = CompoundTag()
        tag.putInt("x", x)
        tag.putInt("y", y)
        tag.putInt("z", z)
        tag.put("palette", ListTag().apply { palette.forEach { add(NbtUtils.writeBlockState(it)) } })
        if (palette.size <= 256) {
            tag.putByteArray("blocks", ByteArray(4096) { ids[it].toByte() })
        } else {
            tag.putIntArray("blocks", ids)
        }
        if (sky != null && !sky.isEmpty) tag.putByteArray("sky", sky.data.copyOf())
        if (block != null && !block.isEmpty) tag.putByteArray("light", block.data.copyOf())

        val biomes = ArrayList<String>()
        val biomeIds = ByteArray(64)
        for (qy in 0 until 4) for (qz in 0 until 4) for (qx in 0 until 4) {
            val name = section.getNoiseBiome(qx, qy, qz).unwrapKey().map { it.identifier().toString() }.orElse("minecraft:plains")
            var at = biomes.indexOf(name)
            if (at < 0) {
                biomes += name
                at = biomes.size - 1
            }
            biomeIds[(qy shl 4) or (qz shl 2) or qx] = at.toByte()
        }
        tag.put("biome_palette", ListTag().apply { biomes.forEach { add(StringTag.valueOf(it)) } })
        tag.putByteArray("biomes", biomeIds)
        return tag
    }

    private fun loadOnce() {
        if (loadedFromDisk) return
        loadedFromDisk = true
        val path = HubScene.file(area ?: return)
        if (!path.exists()) return
        runCatching {
            val root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
            if (root.getIntOr("version", 0) != HubScene.VERSION) return
            root.getListOrEmpty("sections").forEach { element ->
                val tag = element as? CompoundTag ?: return@forEach
                sections[SectionPos.asLong(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0))] = tag
            }
            root.getListOrEmpty("columns").forEach { element ->
                val tag = element as? CompoundTag ?: return@forEach
                columns[SectionPos.asLong(tag.getIntOr("x", 0), 0, tag.getIntOr("z", 0))] = tag
            }
            logger.info("Loaded {} {} sections from disk.", sections.size, area)
        }.onFailure { logger.warn("Could not read the saved hub scene; starting fresh.", it) }
    }

    private fun save(async: Boolean, force: Boolean = false) {
        if (!dirty || (saving && !force)) return
        val target = HubScene.file(area ?: return)
        val root = CompoundTag()
        root.putInt("version", HubScene.VERSION)
        root.putString("source", "local")
        root.putLong("captured", System.currentTimeMillis())
        root.put("sections", ListTag().apply { sections.values.forEach { add(it) } })
        root.put("columns", ListTag().apply { columns.values.forEach { add(it) } })
        dirty = false
        lastSave = System.currentTimeMillis()

        val write = {
            try {
                val temp = target.resolveSibling("${target.fileName}.${System.nanoTime()}.tmp")
                NbtIo.writeCompressed(root, temp)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                HubSceneRenderer.invalidate()
            } catch (error: Exception) {
                logger.warn("Could not save the hub scene.", error)
                dirty = true
            } finally {
                saving = false
            }
        }
        saving = true
        if (async) Thread({ write() }, "PawfectAddons Hub Scene Save").apply { isDaemon = true }.start() else write()
    }
}
