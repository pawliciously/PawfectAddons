package dev.pawfect.addons.features.debug

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.discord.HypixelLocation
import dev.pawfect.addons.mixin.PlayerTabOverlayAccessor
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.scores.DisplaySlot
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DungeonDump {

    private const val ENTITY_RADIUS = 64.0
    private const val HEAD_RADIUS = 48
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val skinUrl = Regex("texture/([0-9a-f]+)")

    fun save(label: String?): Path? {
        val mc = McCompat.mc
        val player = McCompat.player ?: return null
        val level = mc.level ?: return null
        val connection = mc.connection ?: return null
        val out = ArrayList<String>()

        out += "# PA dungeon dump ${label ?: ""} ${LocalDateTime.now()}"
        out += ""
        out += "## Where"
        out += "address: ${mc.currentServer?.ip ?: "singleplayer"}"
        out += "brand: ${connection.serverBrand()}"
        out += "mod api: serverName=${HypixelLocation.serverName} serverType=${HypixelLocation.serverType} lobby=${HypixelLocation.lobby} mode=${HypixelLocation.mode} map=${HypixelLocation.map}"
        out += "PA sees: onSkyBlock=${SkyBlockData.onSkyBlock} inDungeons=${SkyBlockData.inDungeons} floor=${SkyBlockData.dungeonFloor}"
        out += "player: ${player.name.string} at %.2f %.2f %.2f yaw %.1f pitch %.1f, %s, slot %d".format(
            player.x, player.y, player.z, player.yRot, player.xRot, level.dimension().identifier(), player.inventory.selectedSlot,
        )
        out += "health %.1f/%.1f food %d xp level %d".format(player.health, player.maxHealth, player.foodData.foodLevel, player.experienceLevel)

        sidebar(out)
        tabList(out)

        out += ""
        out += "## Action bar (last ${ChatRecorder.recentActionBars.size}, changes only)"
        ChatRecorder.recentActionBars.forEach { out += it }
        out += ""
        out += "## Titles (last ${ChatRecorder.recentTitles.size})"
        ChatRecorder.recentTitles.forEach { out += it }

        out += ""
        out += "## Inventory (slot: item | name | components)"
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            out += "$slot: ${BuiltInRegistries.ITEM.getKey(stack.item)} x${stack.count} | ${DebugText.legacy(stack.hoverName)} | ${DebugText.item(stack)}"
        }

        map(out)
        entities(out)
        heads(out)

        val dir = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev")
        Files.createDirectories(dir)
        val tag = label?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() }
        val file = dir.resolve("dungeondump_${tag?.let { "${it}_" } ?: ""}${LocalDateTime.now().format(stamp)}.txt")
        Files.write(file, out)
        return file
    }

    private fun sidebar(out: MutableList<String>) {
        val scoreboard = McCompat.mc.level?.scoreboard ?: return
        out += ""
        out += "## Scoreboard objectives (name | display | render type | number format)"
        for (objective in scoreboard.objectives) {
            out += "${objective.name} | ${DebugText.legacy(objective.displayName)} | ${objective.renderType} | ${objective.numberFormat()} | ${DebugText.json(objective.displayName)}"
        }
        for (slot in DisplaySlot.entries) {
            scoreboard.getDisplayObjective(slot)?.let { out += "slot ${slot.serializedName}: ${it.name}" }
        }

        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return
        out += ""
        out += "## Sidebar lines, top first (holder | score | team | prefix + suffix | display | json of prefix, suffix)"
        val entries = scoreboard.listPlayerScores(objective).filter { !it.isHidden }.sortedByDescending { it.value }
        for (entry in entries) {
            val team = scoreboard.getPlayersTeam(entry.owner)
            out += "${entry.owner.replace('§', '&')} | ${entry.value} | ${team?.name} | " +
                "${DebugText.legacy(team?.playerPrefix)}${DebugText.legacy(team?.playerSuffix)} | ${DebugText.legacy(entry.display)} | " +
                "${DebugText.json(team?.playerPrefix)} ${DebugText.json(team?.playerSuffix)}"
        }
    }

    private fun tabList(out: MutableList<String>) {
        val mc = McCompat.mc
        val connection = mc.connection ?: return
        val overlay = mc.gui.hud.tabList as PlayerTabOverlayAccessor
        out += ""
        out += "## Tab list"
        out += "header: ${DebugText.legacy(overlay.header)}"
        out += "header json: ${DebugText.json(overlay.header)}"
        out += "footer: ${DebugText.legacy(overlay.footer)}"
        out += "footer json: ${DebugText.json(overlay.footer)}"
        out += ""
        out += "## Tab entries in display order (# | profile name | uuid | order | mode | ping | team | display | skin | display json)"
        val sorted = connection.listedOnlinePlayers.sortedWith(
            compareBy<PlayerInfo>({ -it.tabListOrder }, { if (it.gameMode == GameType.SPECTATOR) 1 else 0 }, { it.team?.name ?: "" })
                .thenComparator { a, b -> a.profile.name().compareTo(b.profile.name(), ignoreCase = true) },
        )
        sorted.forEachIndexed { i, info ->
            val texture = info.profile.properties().get("textures").firstOrNull()?.value()
            val skin = texture?.let { runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
                ?.let { skinUrl.find(it)?.groupValues?.get(1)?.take(16) }
            out += "$i | ${info.profile.name()} | ${info.profile.id()} | ${info.tabListOrder} | ${info.gameMode} | ${info.latency} | ${info.team?.name} | " +
                "${DebugText.legacy(info.tabListDisplayName)} | ${skin ?: "-"} | ${DebugText.json(info.tabListDisplayName)}"
        }
    }

    private fun map(out: MutableList<String>) {
        val player = McCompat.player ?: return
        val level = McCompat.mc.level ?: return
        out += ""
        out += "## Dungeon map"
        val slot = (0 until 9).firstOrNull { player.inventory.getItem(it).`is`(Items.FILLED_MAP) }
        if (slot == null) {
            out += "no filled map in the hotbar"
            return
        }
        val stack = player.inventory.getItem(slot)
        val data = MapItem.getSavedData(stack, level)
        out += "slot $slot, map id ${stack.get(DataComponents.MAP_ID)}"
        if (data == null) {
            out += "no map data yet"
            return
        }
        out += "scale ${data.scale} locked ${data.locked} dimension ${data.dimension.identifier()} centre ${data.centerX} ${data.centerZ}"
        out += "decorations (type | x | y | rot | name), x/y are -128..127:"
        for (decoration in data.decorations) {
            val type = decoration.type().unwrapKey().map { it.identifier().toString() }.orElse("?")
            out += "  $type | ${decoration.x()} | ${decoration.y()} | ${decoration.rot()} | ${decoration.name().map { DebugText.legacy(it) }.orElse("")}"
        }
        out += "colors, 128 rows of 128 bytes as hex (row = map y):"
        for (y in 0 until 128) {
            val row = StringBuilder(256)
            for (x in 0 until 128) row.append("%02x".format(data.colors[y * 128 + x].toInt() and 0xFF))
            out += row.toString()
        }
    }

    private fun entities(out: MutableList<String>) {
        val player = McCompat.player ?: return
        val level = McCompat.mc.level ?: return
        out += ""
        out += "## Entities within ${ENTITY_RADIUS.toInt()} blocks, nearest first"
        val nearby = level.entitiesForRendering()
            .filter { it !== player && it.distanceToSqr(player) <= ENTITY_RADIUS * ENTITY_RADIUS }
            .sortedBy { it.distanceToSqr(player) }
        for (entity in nearby) {
            val sb = StringBuilder()
            sb.append("${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)} #${entity.id} at %.2f %.2f %.2f".format(entity.x, entity.y, entity.z))
            entity.customName?.let { sb.append(" name=\"${DebugText.legacy(it)}\" visible=${entity.isCustomNameVisible}") }
            if (entity.isCurrentlyGlowing) sb.append(" glowing")
            if (entity.isInvisible) sb.append(" invisible")
            if (entity is LivingEntity) sb.append(" hp %.1f/%.1f".format(entity.health, entity.maxHealth))
            if (entity is ArmorStand) {
                val head = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                if (!head.isEmpty) sb.append(" head=${DebugText.item(head)}")
            }
            if (entity is ItemEntity) sb.append(" item=${DebugText.item(entity.item)}")
            out += sb.toString()
        }
    }

    private fun heads(out: MutableList<String>) {
        val player = McCompat.player ?: return
        val level = McCompat.mc.level ?: return
        out += ""
        out += "## Player head blocks within $HEAD_RADIUS blocks (pos | profile id | skin)"
        val cx = player.blockX shr 4
        val cz = player.blockZ shr 4
        val chunks = HEAD_RADIUS / 16 + 1
        for (x in cx - chunks..cx + chunks) {
            for (z in cz - chunks..cz + chunks) {
                val chunk = level.chunkSource.getChunk(x, z, false) ?: continue
                for ((pos, blockEntity) in chunk.blockEntities) {
                    if (blockEntity !is SkullBlockEntity) continue
                    val profile = blockEntity.ownerProfile ?: continue
                    val texture = profile.partialProfile().properties().get("textures").firstOrNull()?.value()
                    val skin = texture?.let { runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
                        ?.let { skinUrl.find(it)?.groupValues?.get(1) }
                    out += "${pos.x} ${pos.y} ${pos.z} | ${profile.partialProfile().id()} | ${skin ?: "-"}"
                }
            }
        }
    }
}
