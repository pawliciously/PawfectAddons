package dev.pawfect.addons.config

import com.google.gson.annotations.Expose
import dev.pawfect.addons.data.SackStatus

class SackStorage {
    @Expose
    @JvmField
    val players: MutableMap<String, PlayerSacks> = mutableMapOf()

    class PlayerSacks {
        @Expose
        @JvmField
        val profiles: MutableMap<String, ProfileSacks> = mutableMapOf()
    }

    class ProfileSacks {
        @Expose
        @JvmField
        val contents: MutableMap<String, SackEntry> = mutableMapOf()
    }
}

data class SackEntry(
    @field:Expose val amount: Int,
    @field:Expose val status: SackStatus,
)

class TrackerStorage {
    @Expose
    @JvmField
    val players: MutableMap<String, PlayerGoals> = mutableMapOf()

    class PlayerGoals {
        @Expose
        @JvmField
        val profiles: MutableMap<String, ProfileGoals> = mutableMapOf()
    }

    class ProfileGoals {
        @Expose
        @JvmField
        var overlayVisible: Boolean = true

        @Expose
        @JvmField
        val goals: MutableList<TrackedGoal> = mutableListOf()
    }
}

data class TrackedGoal(
    @field:Expose val itemId: String,
    @field:Expose var amount: Int,
)

class StorageData {
    @Expose
    @JvmField
    val players: MutableMap<String, PlayerStorage> = mutableMapOf()

    class PlayerStorage {
        @Expose
        @JvmField
        val profiles: MutableMap<String, ProfileStorage> = mutableMapOf()
    }

    class ProfileStorage {
        @Expose
        @JvmField
        val pages: MutableMap<String, Map<String, Int>> = mutableMapOf()

        /** RNG Meters by floor ("F7", "M3") or slayer boss ("Voidgloom Seraph"). */
        @Expose
        @JvmField
        val rngMeters: MutableMap<String, RngMeterEntry> = mutableMapOf()
    }
}

class RngMeterEntry {
    /** Dungeon score or slayer XP in the meter. */
    @Expose
    var stored: Long = 0

    /** The selected drop's id, or null with nothing selected. */
    @Expose
    var item: String? = null

    /** What the selected drop needs. */
    @Expose
    var goal: Long? = null

    /** The last run's or boss's gain, for how many are left. */
    @Expose
    var lastGain: Long? = null

    /** Read from the meter's menu or the slayer's chat, not added up from runs. */
    @Expose
    var exact: Boolean = false
}
