package dev.pawfect.addons.features.combat

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.level.entity.EntityTypeTest

object Hitsound {

    private const val ARROW_MARGIN = 0.6
    private const val ARROW_PAIR = 2
    private const val VANILLA_PING_TICKS = 20
    private const val PRUNE_AGE = 40
    private const val SEARCH_RADIUS = 48.0
    private const val SWEEP_MARGIN = 1.1
    private const val ABILITY_GAP = 2
    private const val NEVER = -1_000_000

    private val ABILITY = Regex("""^Your .{1,64} hit [\d,]+ enem(?:y|ies) for [\d,.]+ damage""")

    private val arrows = HashMap<Long, Int>()
    private val last = HashMap<Int, Int>()

    private var gameTick = 0
    private var lastSuppressTick = NEVER
    private var levelKey = 0
    private var lastAbilityTick = NEVER

    private val config get() = ConfigManager.features.hitsounds

    fun reset() {
        arrows.clear()
        last.clear()
        gameTick = 0
        lastSuppressTick = NEVER
        lastAbilityTick = NEVER
        AttackSpeed.reset()
    }

    fun onTick() {
        val client = McCompat.mc
        val key = System.identityHashCode(client.level)
        if (key != levelKey) {
            levelKey = key
            arrows.clear()
            last.clear()
            lastSuppressTick = NEVER
            AttackSpeed.reset()
            Hitmarker.reset()
        }

        gameTick++
        AttackSpeed.onTick()
        if (gameTick and 31 == 0) prune()

        config.sanitize()
        if (config.enabled && gameTick and 63 == 0) HitsoundLibrary.ensureInstalled()
        if (!config.enabled && !config.markerEnabled) return
        if (!config.arrows && !config.markerEnabled) return
        if (!config.predictArrows) return

        val player = client.player ?: return
        if (client.level == null || client.isPaused) return

        val box = player.boundingBox.inflate(SEARCH_RADIUS)
        val candidates = player.level().getEntities(
            EntityTypeTest.forClass(AbstractArrow::class.java),
            box,
        ) { !it.isRemoved && shotByLocalPlayer(it, player) }

        candidates.forEach { predictArrow(player, it) }
    }

    @JvmStatic
    fun onMelee(attacker: Entity?, target: Entity?) {
        config.sanitize()
        val wantSound = config.enabled && config.melee
        val wantMarker = config.markerEnabled
        if (!wantSound && !wantMarker) return

        val player = McCompat.mc.player ?: return
        if (attacker !== player || target == null || player.isSpectator) return
        if (!isMeleeTarget(target, player)) return
        if (!entityReady(target.id, AttackSpeed.meleeDelay())) return

        stampEntity(target.id)
        land(wantSound, wantMarker)
    }

    @JvmStatic
    fun onArrowHit(arrow: AbstractArrow, target: Entity?) {
        config.sanitize()
        if (!wantArrow()) return
        val player = McCompat.mc.player ?: return
        if (!shotByLocalPlayer(arrow, player)) return
        if (target == null || !isArrowTarget(target, player)) return
        if (!markArrow(arrow.id, target.id)) return
        land(config.enabled && config.arrows, config.markerEnabled)
    }

    fun onChat(message: Component) {
        config.sanitize()
        if (!config.enabled && !config.markerEnabled) return
        if (!config.abilities && !config.markerEnabled) return
        if (!ABILITY.containsMatchIn(message.string.removeColor().trim())) return
        if (gameTick - lastAbilityTick < ABILITY_GAP) return
        lastAbilityTick = gameTick
        land(config.enabled && config.abilities, config.markerEnabled)
    }

    @JvmStatic
    fun muteExplosion(packet: ClientboundSoundPacket): Boolean {
        if (!ConfigManager.features.hitsounds.muteExplosions) return false
        return packet.sound.value() === SoundEvents.GENERIC_EXPLODE.value()
    }

    @JvmStatic
    fun suppressVanillaArrowPing(): Boolean {
        val settings = ConfigManager.features.hitsounds
        if (!settings.enabled || !settings.arrows || !settings.suppressVanilla) return false
        return gameTick - lastSuppressTick <= VANILLA_PING_TICKS
    }

    fun preview() = play()

    private fun wantArrow(): Boolean =
        config.markerEnabled || (config.enabled && config.arrows)

    private fun predictArrow(player: LocalPlayer, arrow: AbstractArrow) {
        if (arrow.isRemoved || arrow.tickCount < 1) return

        val from = arrow.oldPosition()
        var to = arrow.position()
        if (from.distanceToSqr(to) < 1.0e-6) to = from.add(arrow.deltaMovement)
        if (from.distanceToSqr(to) < 1.0e-4 && arrow.deltaMovement.lengthSqr() < 0.002) return

        val swept = arrow.boundingBox.expandTowards(to.subtract(from)).inflate(SWEEP_MARGIN)
        val targets = player.level().getEntities(arrow, swept) { isArrowTarget(it, player) }

        for (target in targets) {
            val box = target.boundingBox.inflate(ARROW_MARGIN)
            val crossed = box.contains(from) || box.contains(to) || box.clip(from, to).isPresent
            if (!crossed) continue
            if (!markArrow(arrow.id, target.id)) continue
            land(config.enabled && config.arrows, config.markerEnabled)
        }
    }

    private fun land(wantSound: Boolean, wantMarker: Boolean) {
        if (wantSound) play()
        if (wantMarker) Hitmarker.flash()
    }

    private fun isMeleeTarget(target: Entity, player: LocalPlayer): Boolean {
        if (target === player || target.isRemoved) return false
        if (target is ItemEntity || target is ExperienceOrb) return false
        if (target is Player && target.uuid.version() == 4) return false
        return target is LivingEntity || target is Interaction || target is Display
    }

    private fun isArrowTarget(target: Entity, player: LocalPlayer): Boolean {
        if (target !is LivingEntity || target === player || target.isRemoved) return false
        if (target is ArmorStand) return false
        return !(target is Player && target.uuid.version() == 4)
    }

    private fun shotByLocalPlayer(arrow: AbstractArrow, player: LocalPlayer): Boolean {
        val owner = arrow.owner
        if (owner === player) return true
        if (owner != null) return owner.uuid == player.uuid
        if (arrow.tickCount > 4 || player.distanceToSqr(arrow) > 6.25) return false
        val motion = arrow.deltaMovement
        if (motion.lengthSqr() < 0.04) return false
        return motion.normalize().dot(player.lookAngle) > 0.75
    }

    private fun entityReady(entityId: Int, delay: Int): Boolean {
        val stamp = last[entityId] ?: return true
        return gameTick - stamp >= delay
    }

    private fun stampEntity(entityId: Int) {
        last[entityId] = gameTick
        lastSuppressTick = gameTick
    }

    private fun markArrow(arrowId: Int, entityId: Int): Boolean {
        if (!entityReady(entityId, AttackSpeed.arrowDelay())) return false
        val key = (arrowId.toLong() shl 32) xor (entityId.toLong() and 0xFFFFFFFFL)
        val stamp = arrows[key]
        if (stamp != null && gameTick - stamp < ARROW_PAIR) return false
        arrows[key] = gameTick
        stampEntity(entityId)
        return true
    }

    private fun prune() {
        arrows.values.removeAll { gameTick - it > PRUNE_AGE }
        last.values.removeAll { gameTick - it > PRUNE_AGE }
    }

    private fun play() {
        val client = McCompat.mc ?: return
        val id = Identifier.tryParse(config.soundId) ?: return
        val volume = config.volume.coerceIn(0f, 1f)
        val pitch = config.pitch.coerceIn(0.5f, 2f)
        if (volume <= 0f) return
        if (!client.isSameThread) {
            client.execute { playNow(client, id, volume, pitch) }
            return
        }
        playNow(client, id, volume, pitch)
    }

    private fun playNow(client: Minecraft, id: Identifier, volume: Float, pitch: Float) {
        val manager = client.soundManager ?: return
        HitsoundLibrary.ensureInstalled()
        val instance = SimpleSoundInstance(
            id,
            SoundSource.MASTER,
            volume,
            pitch,
            SoundInstance.createUnseededRandom(),
            false,
            0,
            SoundInstance.Attenuation.NONE,
            0.0,
            0.0,
            0.0,
            true,
        )
        val result = manager.play(instance)
        if (result == SoundEngine.PlayResult.NOT_STARTED || result == SoundEngine.PlayResult.STARTED_SILENTLY) {
            manager.play(SimpleSoundInstance.forUI(SoundEvents.ARROW_HIT_PLAYER, pitch, volume))
        }
    }
}
