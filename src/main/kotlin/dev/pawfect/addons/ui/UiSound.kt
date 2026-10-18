package dev.pawfect.addons.ui

import dev.pawfect.addons.features.dev.DevClickSounds
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

object UiSound {

    fun click() = play("tap")

    fun toggle(on: Boolean) = play(if (on) "on" else "off")

    fun open() = play("open")

    fun close() = play("close")

    fun tick() = play("tick")

    fun pluck() = play("pluck")

    private fun play(kind: String) {
        if (!Theme.clickSoundsEnabled) return
        if (DevClickSounds.active && runCatching { DevClickSounds.play(kind) }.getOrDefault(false)) return
        McCompat.mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
    }
}
