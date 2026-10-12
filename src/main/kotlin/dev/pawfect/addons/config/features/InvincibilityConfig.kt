package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.core.Position

class InvincibilityConfig {

    enum class ShowWhen(private val label: String) {
        ALWAYS("Always"),
        COOLING("While Used");

        override fun toString(): String = label
    }

    @Expose
    var enabled: Boolean = false

    @Expose
    var showWhen: ShowWhen = ShowWhen.ALWAYS

    @Expose
    var onlyInDungeons: Boolean = true

    @Expose
    var bonzo: Boolean = true

    @Expose
    var spirit: Boolean = true

    @Expose
    var phoenix: Boolean = true

    @Expose
    var onItem: Boolean = true

    @Expose
    var alert: Boolean = true

    /** The alert's small line under the title; the big line stays empty. */
    @Expose
    var alertText: String = "&6{item} &fsaved you! &e{time}s &finvincible"

    @Expose
    var background: Boolean = true

    @Expose
    var sound: Boolean = true

    @Expose
    var soundId: String = "minecraft:block.note_block.pling"

    @Expose
    var announce: Boolean = false

    @Expose
    val position: Position = HudDefaults.position(HudDefaults.INVINCIBILITY)
}
