package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class StatsConfig {

    @Expose
    var hideActionBar: Boolean = false

    @Expose
    var hideReplacedStats: Boolean = true

    @Expose
    var useNativeColors: Boolean = true

    @Expose
    var showLabels: Boolean = true

    @Expose
    var showMax: Boolean = true

    @Expose
    var healthEnabled: Boolean = false

    @Expose
    val healthPosition: Position = HudDefaults.position(HudDefaults.STAT_HEALTH)

    @Expose
    var defenseEnabled: Boolean = false

    @Expose
    val defensePosition: Position = HudDefaults.position(HudDefaults.STAT_DEFENSE)

    @Expose
    var manaEnabled: Boolean = false

    @Expose
    val manaPosition: Position = HudDefaults.position(HudDefaults.STAT_MANA)

    @Expose
    var overflowEnabled: Boolean = false

    @Expose
    val overflowPosition: Position = HudDefaults.position(HudDefaults.STAT_OVERFLOW)

    @Expose
    var vitalityEnabled: Boolean = false

    @Expose
    val vitalityPosition: Position = HudDefaults.position(HudDefaults.STAT_VITALITY)

    @Expose
    var lowHealthAlert: Boolean = false

    @Expose
    var lowHealthThreshold: Int = 15

    @Expose
    var lowHealthOpacity: Float = 0.55f

    @Expose
    var lowHealthSpeed: Float = 1.6f

    @Expose
    var lowHealthThickness: Int = 60

    @Expose
    var lowHealthColor: Int = 0xFF2020
}
