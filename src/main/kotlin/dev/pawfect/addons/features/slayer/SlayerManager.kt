package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.SlayersConfig.SlayerTarget
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SoundCue
import dev.pawfect.addons.utils.NumberUtil.parseHypixelInt
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ln

object SlayerManager {

    private const val SEARCH_RADIUS = 32.0
    private const val OWNER_SEARCH_Y = 2.0
    private const val HITS_SEARCH_XZ = 1.0
    private const val HITS_SEARCH_Y = 3.0

    private val bossRegex = Regex(
        "(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood|Sven Packmaster|" +
            "Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend|Bloodfiend)(?:\\s+(V|IV|III|II|I))?",
    )

    // Plates read "4.2M❤", or now "4.2M/10M❤": the first number is what's left, the second the max.
    private const val AMOUNT = "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?[kKmMbB]?)"
    private val healthRegex = Regex("$AMOUNT(?:/$AMOUNT)?\\s*❤")

    // "12 Hits", or "12/60 Hits" in the same style as health.
    private val hitsRegex = Regex("(\\d+)(?:/(\\d+))?\\s*Hits?", RegexOption.IGNORE_CASE)

    private val hitsPerTier = listOf(15, 30, 60, 100)

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

        /** The tier whose health is nearest [maxHealth], for plates that don't name one. */
        fun tierFor(bossName: String, maxHealth: Int): SlayerTier? {
            if (bossName.contains("Conjoined Brood") || bossName.contains("Atoned Horror")) return SlayerTier.V
            if (maxHealth <= 0) return null
            val index = tierHealth.indices.minByOrNull { abs(ln(tierHealth[it].toDouble() / maxHealth)) } ?: return null
            return SlayerTier.entries.getOrNull(index)
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
                val name = stand.name.string
                val health = parseHealth(name) ?: return null
                // The plate's own max beats the tier table when it shows one.
                parseMaxHealth(name)?.let { maxHealth = it }
                if (health > maxHealth) maxHealth = health
                return health
            }

        val percent: Double
            get() {
                val health = currentHealth ?: return 0.0
                if (maxHealth <= 0) return 100.0
                return (health.toDouble() / maxHealth * 100.0).coerceIn(0.0, 100.0)
            }

        var hits: Int? = null
            internal set

        /** The plate's own hit count to break the shield, when it shows one. */
        var shownMaxHits: Int? = null
            internal set

        val maxHits: Int
            get() = shownMaxHits ?: if (type == SlayerType.VOIDGLOOM) hitsPerTier.getOrNull(tier.ordinal) ?: hitsPerTier.last() else 0

        val position: Vec3 get() = stand.position()

        val alive: Boolean
            get() = stand.isAlive && ((currentHealth ?: 0) > 0 || hits != null)
    }

    private val config get() = ConfigManager.features.slayers

    var boss: Boss? = null
        private set

    private var alertedStand = -1
    private var lowAlerted = false
    private var inHitPhase = false

    // Hypixel no longer always names the owner over a boss, so the boss that appears next to you
    // as your quest turns to "Slay the boss!" is taken as yours, until the quest moves on.
    private var questBossUp = false
    private var questSpawnedAt = 0L
    private var claimedStand = -1

    private fun trackQuest() {
        val up = SkyBlockData.sidebarLines.any { it.contains(SLAY_LINE) }
        if (up && !questBossUp) {
            questSpawnedAt = System.currentTimeMillis()
            claimedStand = -1
        }
        if (!up) claimedStand = -1
        questBossUp = up
    }

    private val claimOpen: Boolean
        get() = questBossUp && claimedStand == -1 && System.currentTimeMillis() - questSpawnedAt <= CLAIM_WINDOW_MS

    fun onTick() {
        if (!config.enabled || !SkyBlockData.onSkyBlock) {
            if (boss != null) onBossGone()
            boss = null
            return
        }
        trackQuest()

        val current = boss
        if (current != null) updateHits(current)
        // A boss of someone else's is let go once yours spawns.
        if (current != null && current.alive && matchesTarget(current) && (current.owned || !claimOpen)) {
            checkLowHealth(current)
            checkHitPhase(current)
            return
        }

        if (current != null) onBossGone()
        boss = findBoss()

        val found = boss ?: return
        updateHits(found)
        if (found.stand.id == alertedStand) return
        alertedStand = found.stand.id
        lowAlerted = false
        inHitPhase = false
        if (config.alertSpawn) SoundCue.play(config.spawnSoundId, config.alertVolume, config.alertPitch)
        checkHitPhase(found)
    }

    // The Voidgloom's shield is up whenever its plate counts hits instead of health.
    private fun checkHitPhase(current: Boss) {
        val hits = current.hits
        if (hits == null) {
            inHitPhase = false
            return
        }
        if (inHitPhase) return
        inHitPhase = true
        if (!current.owned) return
        if (config.hitPhaseNotify) {
            val gui = McCompat.mc.gui.hud
            gui.setTimes(HIT_PHASE_FADE_IN, HIT_PHASE_STAY, HIT_PHASE_FADE_OUT)
            gui.setSubtitle(Component.literal("§f$hits hits to break the shield"))
            gui.setTitle(Component.literal("§d§lHITS PHASE"))
        }
        if (config.hitPhaseSound) SoundCue.play(config.hitPhaseSoundId, config.alertVolume, config.alertPitch)
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

        for (stand in stands.sortedBy { player.distanceToSqr(it) }) {
            val name = stand.name.string.removeColor()
            val match = bossRegex.find(name) ?: continue
            if (parseHealth(stand.name.string) == null && parseHits(stand.name.string) == null) continue

            val bossName = match.groupValues[1]
            val type = SlayerType.fromBossName(bossName) ?: continue
            val numeral = match.groupValues.getOrNull(2)
            // New plates show "[Lv377] Name cur/max❤" with no tier, so the max picks it instead.
            val tier = parseMaxHealth(stand.name.string)?.takeIf { numeral.isNullOrEmpty() }
                ?.let { type.tierFor(bossName, it) }
                ?: SlayerTier.parse(numeral, bossName)
            val owned = isOwnedBy(stand, username) || stand.id == claimedStand || claim(stand)

            if (config.target == SlayerTarget.YOUR_BOSS && !owned) continue

            val distance = player.distanceToSqr(stand)
            val score = if (owned) distance else distance + OWNED_PREFERENCE
            if (score >= bestScore) continue

            bestScore = score
            best = Boss(stand, type, tier, owned, type.healthFor(bossName, tier))
        }

        return best
    }

    /** The first boss within reach that is new when your quest's boss spawns. */
    private fun claim(stand: ArmorStand): Boolean {
        if (!claimOpen) return false
        val player = McCompat.player ?: return false
        if (stand.tickCount > CLAIM_MAX_AGE_TICKS || player.distanceToSqr(stand) > CLAIM_RANGE_SQ) return false
        claimedStand = stand.id
        return true
    }

    private fun updateHits(boss: Boss) {
        val match = findHits(boss)
        boss.hits = match?.groupValues?.get(1)?.toIntOrNull()
        boss.shownMaxHits = match?.groupValues?.get(2)?.toIntOrNull()
    }

    private fun findHits(boss: Boss): MatchResult? {
        if (boss.type != SlayerType.VOIDGLOOM) return null
        val level = McCompat.mc.level ?: return null

        hitsMatch(boss.stand.name.string)?.let { return it }

        val stands = level.getEntitiesOfClass(
            ArmorStand::class.java,
            boss.stand.boundingBox.inflate(HITS_SEARCH_XZ, HITS_SEARCH_Y, HITS_SEARCH_XZ),
        ) { it.hasCustomName() }

        for (stand in stands) {
            hitsMatch(stand.name.string)?.let { return it }
        }

        return null
    }

    private fun parseHits(name: String): Int? = hitsMatch(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun hitsMatch(name: String): MatchResult? {
        val plain = name.removeColor()
        if (!plain.contains("hit", ignoreCase = true)) return null
        return hitsRegex.find(plain)
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
        val match = healthRegex.find(name.removeColor()) ?: return null
        return match.groupValues[1].parseHypixelInt()
    }

    private fun parseMaxHealth(name: String): Int? {
        val match = healthRegex.find(name.removeColor()) ?: return null
        return match.groupValues[2].takeIf { it.isNotEmpty() }?.parseHypixelInt()
    }

    fun colorFor(percent: Double): String = when {
        percent > 75.0 -> "§c"
        percent > 50.0 -> "§6"
        percent > 25.0 -> "§e"
        else -> "§a"
    }

    private const val OWNED_PREFERENCE = 100_000.0

    private const val SLAY_LINE = "Slay the boss!"
    private const val CLAIM_WINDOW_MS = 5_000L
    private const val CLAIM_MAX_AGE_TICKS = 120
    private const val CLAIM_RANGE_SQ = 20.0 * 20.0

    private const val SLAIN_RANGE_SQ = 900.0

    // Title timings in ticks.
    private const val HIT_PHASE_FADE_IN = 3
    private const val HIT_PHASE_STAY = 30
    private const val HIT_PHASE_FADE_OUT = 8
}
