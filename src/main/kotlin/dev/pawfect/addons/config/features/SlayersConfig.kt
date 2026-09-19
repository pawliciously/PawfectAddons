package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class SlayersConfig {

    @Expose
    var enabled: Boolean = true

    @Expose
    var display: SlayerDisplay = SlayerDisplay.BOTH

    @Expose
    var target: SlayerTarget = SlayerTarget.YOUR_BOSS

    @Expose
    var showName: Boolean = true

    @Expose
    var showPercent: Boolean = true

    @Expose
    var worldScale: Float = 1f

    @Expose
    var worldOffset: Float = -0.5f

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.SLAYER_HEALTH)

    @Expose
    var alertSpawn: Boolean = false

    @Expose
    var spawnSoundId: String = "minecraft:entity.experience_orb.pickup"

    @Expose
    var alertSlain: Boolean = false

    @Expose
    var slainSoundId: String = "minecraft:entity.player.levelup"

    @Expose
    var alertLowHealth: Boolean = false

    @Expose
    var lowHealthSoundId: String = "minecraft:block.note_block.pling"

    @Expose
    var lowHealthPercent: Float = 20f

    @Expose
    var alertVolume: Float = 0.7f

    @Expose
    var alertPitch: Float = 1f

    enum class SlayerDisplay(private val label: String) {
        HUD("Screen Overlay"),
        WORLD("Below the Boss"),
        BOTH("Both"),
        ;

        override fun toString(): String = label
    }

    enum class SlayerTarget(private val label: String) {
        YOUR_BOSS("Your Boss"),
        NEAREST("Nearest"),
        ;

        override fun toString(): String = label
    }
}
