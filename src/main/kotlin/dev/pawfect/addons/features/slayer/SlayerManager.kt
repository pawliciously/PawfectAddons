package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.SlayersConfig.SlayerTarget
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SoundCue
import dev.pawfect.addons.utils.NumberUtil.parseHypixelInt
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

object SlayerManager {

    private const val SEARCH_RADIUS = 32.0
    private const val OWNER_SEARCH_Y = 2.0

    private val bossRegex = Regex(
        "(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood|Sven Packmaster|" +
            "Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend|Bloodfiend)(?:\\s+(V|IV|III|II|I))?",
    )

    private val healthRegex = Regex("(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?[kKmM]?)(?=❤)")

    enum class SlayerType(val displayName: String, private val tierHealth: List<Int>) {
        REVENANT("Revenant Horror", listOf(500, 20_000, 400_000, 1_500_000, 10_000_000)),
        TARANTULA("Tarantula Broodfather", listOf(750, 30_000, 900_000, 2_400_000, 10_000_000)),
        SVEN("Sven Packmaster", listOf(2_000, 40_000, 750_000, 2_000_000)),
        VOIDGLOOM("Voidgloom Seraph", listOf(300_000, 12_000_000, 50_000_000, 210_000_000)),
        DEMONLORD("Inferno Demonlord", listOf(2_500_000, 10_000_000, 45_000_000, 150_000_000)),
        VAMPIRE("Riftstalker Bloodfiend", listOf(625, 1_100, 1_800, 2_400, 3_000)),
        ;

        fun healthFor(bossName: String, tier: SlayerTier): Int {
            if (bossName.contains("Conjoined Brood")) return 20_000_000
            return tierHealth.getOrNull(tier.ordinal) ?: tierHealth.last()
        }

        companion object {
            fun fromBossName(bossName: String): SlayerType? = when (bossName) {
                "Revenant Horror", "Atoned Horror" -> REVENANT
                "Tarantula Broodfather", "Conjoined Brood" -> TARANTULA
                "Sven Packmaster" -> SVEN
                "Voidgloom Seraph" -> VOIDGLOOM
                "Inferno Demonlord" -> DEMONLORD
                "Riftstalker Bloodfiend", "Bloodfiend" -> VAMPIRE
                else -> null
            }
        }
    }

    enum class SlayerTier(val label: String) {
        I("I"),
        II("II"),
        III("III"),
        IV("IV"),
        V("V"),
        ;

        companion object {
            fun parse(tier: String?, bossName: String): SlayerTier {
                if (bossName.contains("Conjoined Brood")) return V
                if (bossName.contains("Atoned Horror")) return V
                if (bossName.contains("Bloodfiend") && tier.isNullOrEmpty()) return V
                if (tier.isNullOrEmpty()) return I
                return entries.firstOrNull { it.label == tier } ?: I
            }
        }
    }

    class Boss(
        val stand: ArmorStand,
        val type: SlayerType,
        val tier: SlayerTier,
        val owned: Boolean,
        maxHealth: Int,
    ) {
        var maxHealth: Int = maxHealth
            private set

        val currentHealth: Int?
            get() {
                // TODO verify on tier 5
                val health = parseHealth(stand.name.string) ?: return null
                if (health > maxHealth) maxHealth = health
                return health
            }

        val percent: Double
            get() {
                val health = currentHealth ?: return 0.0
                if (maxHealth <= 0) return 100.0
                return (health.toDouble() / maxHealth * 100.0).coerceIn(0.0, 100.0)
            }

        val position: Vec3 get() = stand.position()

        val alive: Boolean
            get() = stand.isAlive && (currentHealth ?: 0) > 0
    }

    private val config get() = ConfigManager.features.slayers

    var boss: Boss? = null
        private set

    private var alertedStand = -1
    private var lowAlerted = false

    fun onTick() {
        if (!config.enabled || !SkyBlockData.onSkyBlock) {
            if (boss != null) onBossGone()
            boss = null
            return
        }

        val current = boss
        if (current != null && current.alive && matchesTarget(current)) {
            checkLowHealth(current)
            return
        }

        if (current != null) onBossGone()
        boss = findBoss()

        val found = boss ?: return
        if (found.stand.id == alertedStand) return
        alertedStand = found.stand.id
        lowAlerted = false
        if (config.alertSpawn) SoundCue.play(config.spawnSoundId, config.alertVolume, config.alertPitch)
    }

    private fun onBossGone() {
        val gone = boss ?: return
        val wasTracked = gone.stand.id == alertedStand
        alertedStand = -1
        lowAlerted = false
        if (!wasTracked || !config.alertSlain) return
        val player = McCompat.player ?: return
        if (player.distanceToSqr(gone.position) > SLAIN_RANGE_SQ) return
        SoundCue.play(config.slainSoundId, config.alertVolume, config.alertPitch)
    }

    private fun checkLowHealth(current: Boss) {
        if (!config.alertLowHealth || lowAlerted) return
        if (current.currentHealth == null) return
        if (current.percent > config.lowHealthPercent) return
        lowAlerted = true
        SoundCue.play(config.lowHealthSoundId, config.alertVolume, config.alertPitch)
    }

    private fun matchesTarget(candidate: Boss): Boolean =
        config.target != SlayerTarget.YOUR_BOSS || candidate.owned

    private fun findBoss(): Boss? {
        val player = McCompat.player ?: return null
        val level = McCompat.mc.level ?: return null
        val username = McCompat.mc.user.name

        val stands = level.getEntitiesOfClass(
            ArmorStand::class.java,
            player.boundingBox.inflate(SEARCH_RADIUS),
        ) { it.hasCustomName() }

        var best: Boss? = null
        var bestScore = Double.MAX_VALUE

        for (stand in stands) {
            val name = stand.name.string.removeColor()
            val match = bossRegex.find(name) ?: continue
            if (parseHealth(stand.name.string) == null) continue

            val bossName = match.groupValues[1]
            val type = SlayerType.fromBossName(bossName) ?: continue
            val tier = SlayerTier.parse(match.groupValues.getOrNull(2), bossName)
            val owned = isOwnedBy(stand, username)

            if (config.target == SlayerTarget.YOUR_BOSS && !owned) continue

            val distance = player.distanceToSqr(stand)
            val score = if (owned) distance else distance + OWNED_PREFERENCE
            if (score >= bestScore) continue

            bestScore = score
            best = Boss(stand, type, tier, owned, type.healthFor(bossName, tier))
        }

        return best
    }

    private fun isOwnedBy(stand: ArmorStand, username: String): Boolean {
        val level = McCompat.mc.level ?: return false
        val neighbours = level.getEntitiesOfClass(
            ArmorStand::class.java,
            stand.boundingBox.inflate(0.5, OWNER_SEARCH_Y, 0.5),
        ) { it.hasCustomName() }

        return neighbours.any { it.name.string.removeColor().contains(username) }
    }

    private fun parseHealth(name: String): Int? {
        val match = healthRegex.find(name) ?: return null
        return match.groupValues[1].parseHypixelInt()
    }

    fun colorFor(percent: Double): String = when {
        percent > 75.0 -> "§c"
        percent > 50.0 -> "§6"
        percent > 25.0 -> "§e"
        else -> "§a"
    }

    private const val OWNED_PREFERENCE = 100_000.0

    private const val SLAIN_RANGE_SQ = 900.0
}
