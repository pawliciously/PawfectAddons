package dev.pawfect.addons.features.dungeon.secrets

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SoundCue
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks

object SecretTracker {

    private val logger = org.slf4j.LoggerFactory.getLogger("PawfectAddons/Secrets")

    var lastClaimReport: String = "nothing yet"
        private set

    private val SECRET_ITEMS = listOf(
        "Decoy", "Defuse Kit", "Dungeon Chest Key", "Healing VIII", "Inflatable Jerry",
        "Spirit Leap", "Training Weights", "Trap", "Treasure Talisman", "Candycomb",
    )

    private const val ITEM_RANGE_SQ = 25.0
    private const val BAT_RANGE_SQ = 144.0
    private const val PICKUP_RANGE_SQ = 36.0

    private val config get() = ConfigManager.features.secretWaypoints

    private var lastInteract: BlockPos? = null

    private val SECRET_INDEX = Regex("""^\s*([0-9]+(?:\s*/\s*[0-9]+)*)""")

    private val ROUTE_CATEGORIES = setOf("entrance", "lever", "superboom", "stonk", "aotv", "pearl", "key")

    private fun indicesOf(secret: SecretRoomData.SecretEntry): Set<Int> =
        SECRET_INDEX.find(secret.secretName)
            ?.groupValues
            ?.get(1)
            ?.split('/')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

    fun register() {
        UseBlockCallback.EVENT.register { _, _, _, hit ->
            lastInteract = hit.blockPos
            runCatching { onInteract(hit.blockPos) }
            InteractionResult.PASS
        }
    }

    private fun onInteract(pos: BlockPos) {
        if (!active()) return
        val level = McCompat.mc.level ?: return
        val categories = when (level.getBlockState(pos).block) {
            Blocks.CHEST, Blocks.TRAPPED_CHEST -> setOf("chest")
            Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD -> setOf("wither", "key")
            else -> return
        }
        claimAt(pos, categories)
    }

    private fun key(secret: SecretRoomData.SecretEntry): String =
        "${secret.category}:${secret.x},${secret.y},${secret.z}"

    fun syncRoom() = Unit

    fun isClaimed(secret: SecretRoomData.SecretEntry): Boolean {
        val room = DungeonScanner.matchedRoom() ?: return false
        return claimed(room, secret)
    }

    private fun claimed(room: DungeonRoom, secret: SecretRoomData.SecretEntry): Boolean {
        if (room.claimedSecrets.contains(key(secret))) return true
        val indices = indicesOf(secret)
        if (indices.isEmpty()) return false
        return room.claimedIndices.containsAll(indices)
    }

    fun claimedCount(): Int {
        val room = DungeonScanner.matchedRoom() ?: return 0
        val name = room.name ?: return 0
        return SecretRoomData.secretsFor(name).count { claimed(room, it) }
    }

    fun reset() {
        lastInteract = null
    }

    private fun active(): Boolean =
        config.enabled && config.hideClaimed && SkyBlockData.inDungeons

    private fun claim(secret: SecretRoomData.SecretEntry) {
        val room = DungeonScanner.matchedRoom() ?: return
        val indices = indicesOf(secret)
        val added = if (indices.isEmpty() || secret.category in ROUTE_CATEGORIES) {
            room.claimedSecrets.add(key(secret))
        } else {
            room.claimedIndices.addAll(indices)
        }
        if (!added) return
        playSound()
    }

    private fun secretsHere(): List<SecretRoomData.SecretEntry> {
        val match = DungeonScanner.matchedRoom() ?: return emptyList()
        return SecretRoomData.secretsFor(match.name!!).filter { !isClaimed(it) }
    }

    private fun actual(secret: SecretRoomData.SecretEntry): BlockPos? {
        val match = DungeonScanner.matchedRoom() ?: return null
        return DungeonScanner.relativeToActual(match, secret.x, secret.y, secret.z)
    }

    private fun claimNearest(category: String, pos: BlockPos, maxDistanceSq: Double) {
        secretsHere()
            .filter { it.category == category }
            .mapNotNull { secret -> actual(secret)?.let { secret to it.distSqr(pos) } }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= maxDistanceSq }
            ?.let { claim(it.first) }
    }

    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!active()) return
        if (packet.sound.value() != SoundEvents.BAT_DEATH) return
        claimNearest("bat", BlockPos(packet.x.toInt(), packet.y.toInt(), packet.z.toInt()), BAT_RANGE_SQ)
    }

    @JvmStatic
    fun onItemTaken(entityId: Int) {
        if (!active()) return
        val level = McCompat.mc.level ?: return
        val player = McCompat.player ?: return
        val entity = level.getEntity(entityId) as? ItemEntity ?: return
        if (SECRET_ITEMS.none { entity.item.hoverName.string.contains(it) }) return
        if (player.distanceToSqr(entity) > PICKUP_RANGE_SQ) return
        claimNearest("item", entity.blockPosition(), ITEM_RANGE_SQ)
    }

    @JvmStatic
    fun onChestOpened(pos: BlockPos) {
        if (!active()) return
        claimAt(pos, setOf("chest", "wither", "key"))
    }

    private fun claimAt(pos: BlockPos, categories: Set<String>) {
        val here = secretsHere().filter { it.category in categories }
        val hit = here.firstOrNull { actual(it) == pos }
        lastClaimReport = if (hit != null) {
            "claimed ${hit.secretName} at $pos"
        } else {
            "no $categories secret at $pos (${here.size} unclaimed of those types: " +
                here.joinToString { "${it.secretName}@${actual(it)}" } + ")"
        }
        logger.info("Secret claim: {}", lastClaimReport)
        hit?.let { claim(it) }
    }

    fun markAllClaimed() {
        val match = DungeonScanner.matchedRoom() ?: return
        SecretRoomData.secretsFor(match.name!!).forEach {
            match.claimedSecrets.add(key(it))
            match.claimedIndices.addAll(indicesOf(it))
        }
    }

    @JvmStatic
    fun onScreenOpened() {
        if (!active()) return
        val pos = lastInteract ?: return
        val level = McCompat.mc.level ?: return
        val category = when (level.getBlockState(pos).block) {
            Blocks.CHEST, Blocks.TRAPPED_CHEST -> "chest"
            Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD -> "wither"
            else -> return
        }
        claimAt(pos, if (category == "wither") setOf("wither", "key") else setOf(category))
    }

    private fun playSound() {
        if (!config.playSound) return
        SoundCue.play(config.soundId, config.soundVolume, config.soundPitch)
    }

    fun previewSound() = playSound()
}
