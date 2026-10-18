package dev.pawfect.addons.features.visual

import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.rooms.DungeonMapUtils
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.utils.McCompat
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.minecraft.client.multiplayer.ClientLevel
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
import net.minecraft.world.phys.AABB
import java.util.Optional

object StarMobGlow {

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.visuals)

    private const val HEART = "❤"
    private const val STAR = "✯"
    private const val MAX_RANGE_SQ = 96.0 * 96.0
    private const val RECHECK_EVERY = 5
    private val DEAD_PLATE = Regex("\\s0$HEART$")
    private val MINIBOSSES = setOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")

    private val BOUND = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }

    private val PENDING = IntOpenHashSet()

    private val STARRED = IntOpenHashSet()
    private val MINIBOSS_IDS = IntOpenHashSet()

    private var ticks = 0

    @JvmStatic
    fun reset() {
        BOUND.clear()
        PENDING.clear()
        STARRED.clear()
        MINIBOSS_IDS.clear()
    }

    @JvmStatic
    fun onRemoveEntities(ids: IntList) {
        if (!McCompat.mc.isSameThread) return
        for (i in 0 until ids.size) {
            val id = ids.getInt(i)
            PENDING.remove(id)
            MINIBOSS_IDS.remove(id)
            val mob = BOUND.remove(id)
            if (mob >= 0) STARRED.remove(mob)
            if (STARRED.remove(id)) {
                val stands = BOUND.int2IntEntrySet().filter { it.intValue == id }.map { it.intKey }
                stands.forEach {
                    BOUND.remove(it)
                    PENDING.add(it)
                }
            }
        }
        rebuild()
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
            if (starredPlate(name)) {
                if (BOUND.get(entity.id) < 0) tryBind(level, entity)
            } else if (name.isNotEmpty() && (BOUND.containsKey(entity.id) || PENDING.contains(entity.id))) {
                BOUND.remove(entity.id)
                PENDING.remove(entity.id)
                rebuild()
            }
            return
        }
        if (entity is Player && config.starMinibosses && miniboss(entity) && MINIBOSS_IDS.add(entity.id)) rebuild()
    }

    @JvmStatic
    fun onTick() {
        if (!config.starGlow || !SkyBlockData.inDungeons) return
        if (ticks++ % RECHECK_EVERY != 0) return
        val level = McCompat.mc.level ?: return
        var changed = false
        val bound = BOUND.int2IntEntrySet().map { it.intKey to it.intValue }
        for ((standId, mobId) in bound) {
            val stand = level.getEntity(standId)
            if (stand == null) {
                BOUND.remove(standId)
                changed = true
                continue
            }
            val mob = level.getEntity(mobId)
            if (mob == null || !mob.isAlive || !under(stand, mob)) {
                BOUND.remove(standId)
                PENDING.add(standId)
                changed = true
            }
        }
        if (PENDING.isNotEmpty()) {
            for (standId in PENDING.toIntArray()) {
                val stand = level.getEntity(standId)
                if (stand == null) {
                    PENDING.remove(standId)
                    continue
                }
                tryBind(level, stand, rebuildAfter = false)
            }
            changed = true
        }
        if (changed) rebuild()
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

    private fun tryBind(level: ClientLevel, stand: Entity, rebuildAfter: Boolean = true) {
        val plain = (stand.customName?.string ?: "").replace(Regex("§."), "")
        if (DEAD_PLATE.containsMatchIn(plain)) {
            PENDING.remove(stand.id)
            return
        }
        val mob = findMob(level, stand)
        if (mob < 0) {
            PENDING.add(stand.id)
            return
        }
        PENDING.remove(stand.id)
        BOUND.put(stand.id, mob)
        if (rebuildAfter) rebuild()
    }

    private fun findMob(level: ClientLevel, stand: Entity): Int {
        val self = McCompat.player
        val taken = IntOpenHashSet(BOUND.values)
        val plain = (stand.customName?.string ?: "").replace(Regex("§."), "").uppercase()
        val offset = if (plain.contains("WITHERMANCER")) 3 else 1
        val guess = level.getEntity(stand.id - offset)
        if (guess != null && guess !is ArmorStand && guess.id !in taken && realMob(guess, self) && under(stand, guess)) return guess.id
        val box = AABB(stand.x - 1.2, stand.y - 4.0, stand.z - 1.2, stand.x + 1.2, stand.y + 0.5, stand.z + 1.2)
        return level.getEntities(stand, box) { it !is ArmorStand && it !is ExperienceOrb }
            .filter { it.id !in taken && it.isAlive && realMob(it, self) && under(stand, it) }
            .minByOrNull { (it.x - stand.x) * (it.x - stand.x) + (it.z - stand.z) * (it.z - stand.z) }
            ?.id ?: -1
    }

    private fun under(stand: Entity, mob: Entity): Boolean {
        val dx = stand.x - mob.x
        val dz = stand.z - mob.z
        val dy = stand.y - mob.y
        return dx * dx + dz * dz <= 2.25 && dy in -0.5..4.5
    }

    private fun rebuild() {
        STARRED.clear()
        STARRED.addAll(BOUND.values)
        STARRED.addAll(MINIBOSS_IDS)
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
