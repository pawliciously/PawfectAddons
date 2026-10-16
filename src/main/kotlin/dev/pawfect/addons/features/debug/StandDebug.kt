package dev.pawfect.addons.features.debug

import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.features.slayer.NukekubiGlow
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.TagValueOutput
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object StandDebug {

    private const val RADIUS = 24.0
    private const val LIMIT = 30
    private const val FILE_RADIUS = 48.0
    private val skinUrl = Regex("texture/([0-9a-f]+)")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun dump(): List<String> {
        val player = McCompat.player ?: return listOf("No player.")
        val level = McCompat.mc.level ?: return listOf("No level.")

        val stands = level.getEntitiesOfClass(
            ArmorStand::class.java,
            player.boundingBox.inflate(RADIUS),
        ) { it.hasCustomName() }

        if (stands.isEmpty()) return listOf("Nothing named within $RADIUS blocks.")

        return stands
            .sortedBy { player.distanceToSqr(it) }
            .take(LIMIT)
            .map { "§8${"%.1f".format(player.distanceTo(it))}m §f${it.name.string.replace('§', '&')}" }
    }

    /**
     * Writes every armor stand within [FILE_RADIUS] blocks to a file, nearest first: position, name, flags,
     * what each slot holds (with the head texture value and skin URL, which is what a head ID is) and the
     * full NBT. Unnamed stands are included since key and decoration heads usually have no name.
     */
    fun save(): Pair<Int, Path>? {
        val player = McCompat.player ?: return null
        val level = McCompat.mc.level ?: return null
        val stands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(FILE_RADIUS))
            .sortedBy { player.distanceToSqr(it) }
        val area = HypixelLocation.mode ?: "unknown"
        val lines = buildList {
            add("# ${stands.size} armor stands within ${FILE_RADIUS.toInt()} blocks")
            add("# area: $area, server: ${HypixelLocation.lobby ?: "unknown"}, from ${player.blockX} ${player.blockY} ${player.blockZ}")
            for (stand in stands) {
                add("")
                add("== #${stand.id}  %.2fm  at %.2f %.2f %.2f  yaw %.1f".format(player.distanceTo(stand), stand.x, stand.y, stand.z, stand.yRot))
                stand.customName?.string?.takeIf { it.isNotBlank() }?.let { add("name: $it") }
                add("marker=${stand.isMarker} invisible=${stand.isInvisible} small=${stand.isSmall} uuid=${stand.uuid}")
                for (slot in EquipmentSlot.entries) {
                    val stack = stand.getItemBySlot(slot)
                    if (stack.isEmpty) continue
                    add("${slot.name.lowercase()}: ${BuiltInRegistries.ITEM.getKey(stack.item)} ${stack.hoverName.string}")
                    val texture = stack.get(DataComponents.PROFILE)?.partialProfile()?.properties()?.get("textures")?.firstOrNull()
                    if (texture != null) {
                        add("  texture: ${texture.value()}")
                        NukekubiGlow.skinOf(stack)?.let { json -> skinUrl.find(json)?.let { add("  skin: http://textures.minecraft.net/${it.value}") } }
                    }
                }
                add(nbt(stand))
            }
        }
        val dir = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev")
        Files.createDirectories(dir)
        val file = dir.resolve("stands_${area}_${LocalDateTime.now().format(stamp)}.txt")
        Files.write(file, lines)
        return stands.size to file
    }

    private fun nbt(stand: ArmorStand): String = runCatching {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, stand.registryAccess())
        stand.saveWithoutId(output)
        NbtUtils.prettyPrint(output.buildResult(), false)
    }.getOrElse { "(could not save: ${it.javaClass.simpleName}: ${it.message})" }

    /** Armor stands carrying player heads nearby: which slot, the skin, and whether it counts as a Nukekubi. */
    fun heads(): List<String> {
        val player = McCompat.player ?: return listOf("No player.")
        val level = McCompat.mc.level ?: return listOf("No level.")
        val out = mutableListOf("§7Area: §f${HypixelLocation.mode ?: "unknown"}")

        val stands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(RADIUS)) { stand ->
            EquipmentSlot.entries.any { stand.getItemBySlot(it).`is`(Items.PLAYER_HEAD) }
        }
        if (stands.isEmpty()) return out + "No armor stands with heads within $RADIUS blocks."

        for (stand in stands.sortedBy { player.distanceToSqr(it) }.take(LIMIT)) {
            val slots = EquipmentSlot.entries.filter { stand.getItemBySlot(it).`is`(Items.PLAYER_HEAD) }
            val skin = NukekubiGlow.skinOf(stand.getItemBySlot(slots.first()))
            val hash = skin?.let { skinUrl.find(it)?.groupValues?.get(1)?.take(12) } ?: "no texture"
            val verdict = if (NukekubiGlow.isNukekubi(stand)) "§aNukekubi" else "§7other"
            out += "§8${"%.1f".format(player.distanceTo(stand))}m $verdict §7${slots.joinToString { it.name.lowercase() }}" +
                " marker=${stand.isMarker} invisible=${stand.isInvisible} skin=§f$hash"
            if (NukekubiGlow.isNukekubi(stand)) out += "  " + glowStages(stand)
        }
        out += "§7Shader pack in use: §f${shaderPackInUse() ?: "no Iris"}"
        return out
    }

    // Each step a glow passes through, so whichever one comes back false or 0 is where it's lost.
    private fun glowStages(stand: ArmorStand): String {
        val mc = McCompat.mc
        val ours = NukekubiGlow.shouldGlow(stand)
        val glowing = stand.isCurrentlyGlowing
        val appears = mc.shouldEntityAppearGlowing(stand)
        val outline = runCatching {
            "%08X".format(mc.entityRenderDispatcher.getRenderer(stand).createRenderState(stand, 1f).outlineColor)
        }.getOrElse { "error ${it.javaClass.simpleName}" }
        return "§7ours=§f$ours §7glowing=§f$glowing §7appears=§f$appears §7outline=§f$outline"
    }

    private fun shaderPackInUse(): Boolean? = runCatching {
        val api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null)
        api.javaClass.getMethod("isShaderPackInUse").invoke(api) as Boolean
    }.getOrNull()
}
