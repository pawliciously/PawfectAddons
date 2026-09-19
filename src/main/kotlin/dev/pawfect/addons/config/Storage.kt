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
    }
}
