package dev.pawfect.addons.features.debug

import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Counts every block in the chunks the client has loaded, so standing on an island lists what
 * it's built from. Islands fit inside a normal render distance, so standing near the middle
 * covers all of one. Counts come from each chunk section's palette, so a whole island takes
 * well under a second.
 */
object IslandBlocks {

    class Result(val counts: List<Pair<Block, Long>>, val chunks: Int, val file: Path)

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun scan(): Result? {
        val mc = McCompat.mc
        val level = mc.level ?: return null
        val player = McCompat.player ?: return null
        val radius = mc.options.effectiveRenderDistance + 1
        val centreX = player.blockX shr 4
        val centreZ = player.blockZ shr 4

        val totals = HashMap<Block, Long>()
        var chunks = 0
        for (cx in centreX - radius..centreX + radius) {
            for (cz in centreZ - radius..centreZ + radius) {
                val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) ?: continue
                chunks++
                for (section in chunk.sections) {
                    if (section.hasOnlyAir()) continue
                    section.states.count { state, count -> totals.merge(state.block, count.toLong(), Long::plus) }
                }
            }
        }
        listOf(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR).forEach(totals::remove)

        val sorted = totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return Result(sorted, chunks, write(sorted, chunks))
    }

    private fun write(counts: List<Pair<Block, Long>>, chunks: Int): Path {
        val area = HypixelLocation.mode ?: "unknown"
        val dir = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev")
        Files.createDirectories(dir)
        val file = dir.resolve("blocks_${area}_${LocalDateTime.now().format(stamp)}.txt")
        val player = McCompat.player
        val lines = buildList {
            add("# ${counts.size} unique blocks in $chunks loaded chunks")
            add("# area: $area, server: ${HypixelLocation.lobby ?: "unknown"}, from ${player?.blockX} ${player?.blockY} ${player?.blockZ}")
            counts.forEach { (block, count) -> add("${BuiltInRegistries.BLOCK.getKey(block)}\t$count") }
        }
        Files.write(file, lines)
        return file
    }
}
