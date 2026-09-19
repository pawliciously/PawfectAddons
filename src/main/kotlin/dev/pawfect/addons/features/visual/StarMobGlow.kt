package dev.pawfect.addons.features.visual

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.utils.McCompat
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import java.util.Optional

object StarMobGlow {

    private val config get() = ConfigManager.features.visuals

    private const val HEART = "❤"
    private const val STAR = "✯"
    private const val MAX_RANGE_SQ = 96.0 * 96.0
    private val MINIBOSSES = setOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")

    private val STARRED = IntOpenHashSet()
    private val CHECKED = IntOpenHashSet()

    @JvmStatic
    fun reset() {
        STARRED.clear()
        CHECKED.clear()
    }

    @JvmStatic
    fun onRemoveEntities(ids: IntList) {
        for (i in 0 until ids.size) {
            val id = ids.getInt(i)
            STARRED.remove(id)
            CHECKED.remove(id)
        }
    }

    @JvmStatic
    fun onEntityData(packet: ClientboundSetEntityDataPacket) {
        if (!config.starGlow || !SkyBlockData.inDungeons) return
        val level = McCompat.mc.level ?: return
        val entity = level.getEntity(packet.id()) ?: return
        if (entity is ArmorStand) {
            var name = packetName(packet)
            val custom = entity.customName
            if (custom != null) {
                val current = custom.string
                if (name.isEmpty() || starredPlate(current)) name = current
            }
            if (starredPlate(name)) bindStand(entity, name)
            return
        }
        if (entity is Player && config.starMinibosses && miniboss(entity)) STARRED.add(entity.id)
    }

    @JvmStatic
    fun shouldGlow(entity: Entity): Boolean {
        if (!config.starGlow || !entity.level().isClientSide) return false
        if (!STARRED.contains(entity.id) && !extra(entity)) return false
        if (!entity.isAlive || !SkyBlockData.inDungeons) return false
        val player = McCompat.player ?: return false
        if (player.distanceToSqr(entity) > MAX_RANGE_SQ) return false
        return sameRoom(player, entity)
    }

    private fun sameRoom(player: Entity, entity: Entity): Boolean {
        val own = cell(player)
        val mob = cell(entity)
        val room = DungeonScanner.current
        if (room != null && own in room.segments) return mob in room.segments
        return own == mob
    }

    private fun cell(entity: Entity): Pair<Int, Int> {
        val pos = DungeonMapUtils.getPhysicalRoomPos(entity.x, entity.z)
        return pos.x() to pos.y()
    }

    @JvmStatic
    fun outlineColor(): Int {
        val alpha = (config.starOpacity.coerceIn(0.15f, 0.9f) * 255f).toInt()
        return ARGB.color(alpha, config.starColor and 0xFFFFFF)
    }

    private fun extra(entity: Entity): Boolean {
        if (entity !is EnderMan || !config.starFels) return false
        val name = entity.customName ?: return false
        return "Dinnerbone" == name.string
    }

    private fun starredPlate(name: String): Boolean =
        name.contains(STAR) && (name.endsWith(HEART) || name.endsWith("§c$HEART"))

    private fun bindStand(stand: Entity, name: String) {
        if (!CHECKED.add(stand.id)) return
        val plain = name.replace(Regex("§."), "").uppercase()
        val offset = if (plain.contains("WITHERMANCER")) 3 else 1
        val guessId = stand.id - offset
        val guess = stand.level().getEntity(guessId)
        if (guess != null && guess !is ArmorStand && STARRED.add(guessId)) return
        val box = stand.boundingBox.move(0.0, -1.0, 0.0)
        val nearby = stand.level().getEntities(stand, box) { it !is ArmorStand && it !is ExperienceOrb }
        val self = McCompat.player
        for (mob in nearby) {
            if (STARRED.contains(mob.id) || !realMob(mob, self)) continue
            STARRED.add(mob.id)
            return
        }
    }

    private fun realMob(entity: Entity, self: Player?): Boolean {
        if (entity is Player) return !entity.isInvisible && entity.uuid.version() == 2 && entity !== self
        return entity !is WitherBoss && entity !is AbstractArrow
    }

    private fun miniboss(player: Player): Boolean {
        val connection = McCompat.mc.connection ?: return false
        val info = connection.getPlayerInfo(player.uuid) ?: return false
        val name = info.profile.name ?: return false
        return name in MINIBOSSES
    }

    private fun packetName(packet: ClientboundSetEntityDataPacket): String {
        val out = StringBuilder()
        for (value in packet.packedItems()) append(out, value.value())
        return out.toString()
    }

    private fun append(out: StringBuilder, value: Any?) {
        if (value is Component) {
            out.append(value.string)
            return
        }
        if (value is Optional<*> && value.isPresent) append(out, value.get())
    }
}
