package dev.pawfect.addons.ui

import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

object UiSound {

    fun click() {
        if (!Theme.clickSoundsEnabled) return
        McCompat.mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
    }
}
