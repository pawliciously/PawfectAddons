package dev.pawfect.addons.features.debug

import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.storage.TagValueOutput
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object EntityInfo {

    data class Found(val entity: Entity, val distance: Double)

    class Result(val found: List<Found>, val file: Path)

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun scan(radius: Int): Result? {
        val level = McCompat.mc.level ?: return null
        val player = McCompat.player ?: return null
        val found = level.entitiesForRendering()
            .filter { it !== player }
            .map { Found(it, it.distanceTo(player).toDouble()) }
            .filter { it.distance <= radius }
            .sortedBy { it.distance }
        return Result(found, write(found, radius))
    }

    fun describe(found: Found): String {
        val entity = found.entity
        val name = entity.customName?.string?.takeIf { it.isNotBlank() }
        return "§f%.1fm §7%s".format(found.distance, type(entity)) + (name?.let { " §f$it" } ?: "")
    }

    private fun type(entity: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()

    private fun nbt(entity: Entity): String = runCatching {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess())
        entity.saveWithoutId(output)
        NbtUtils.prettyPrint(output.buildResult(), false)
    }.getOrElse { "(could not save: ${it.javaClass.simpleName}: ${it.message})" }

    private fun write(found: List<Found>, radius: Int): Path {
        val area = HypixelLocation.mode ?: "unknown"
        val dir = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev")
        Files.createDirectories(dir)
        val file = dir.resolve("entities_${area}_${LocalDateTime.now().format(stamp)}.txt")
        val player = McCompat.player
        val lines = buildList {
            add("# ${found.size} entities within $radius blocks")
            add("# area: $area, server: ${HypixelLocation.lobby ?: "unknown"}, from ${player?.blockX} ${player?.blockY} ${player?.blockZ}")
            found.forEach { (entity, distance) ->
                add("")
                add("== ${type(entity)} #${entity.id}  %.2fm  at %.2f %.2f %.2f".format(distance, entity.x, entity.y, entity.z))
                entity.customName?.string?.takeIf { it.isNotBlank() }?.let { add("name: $it") }
                add("uuid: ${entity.uuid}")
                add(nbt(entity))
            }
        }
        Files.write(file, lines)
        return file
    }
}
