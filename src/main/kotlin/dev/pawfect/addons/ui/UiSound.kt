package dev.pawfect.addons.ui

import dev.pawfect.addons.features.dev.DevClickSounds
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * Menu sounds. Everything is the vanilla button click, unless the dev click sounds are on: then each kind of
 * click plays its own sound from the dev sound pack.
 */
object UiSound {

    fun click() = play("tap")

    fun toggle(on: Boolean) = play(if (on) "on" else "off")

    fun open() = play("open")

    fun close() = play("close")

    /** Small adjustments: sliders, dropdown picks. */
    fun tick() = play("tick")

    /** Folding and unfolding groups. */
    fun pluck() = play("pluck")

    private fun play(kind: String) {
        if (!Theme.clickSoundsEnabled) return
        if (DevClickSounds.active && runCatching { DevClickSounds.play(kind) }.getOrDefault(false)) return
        McCompat.mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
    }
}
