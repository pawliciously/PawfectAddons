package dev.pawfect.addons.utils

import dev.pawfect.addons.features.combat.HitsoundLibrary
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource

object SoundCue {

    fun play(soundId: String, volume: Float, pitch: Float) {
        val client = McCompat.mc ?: return
        val id = Identifier.tryParse(soundId) ?: return
        val level = volume.coerceIn(0f, 1f)
        if (level <= 0f) return
        val speed = pitch.coerceIn(0.5f, 2f)
        if (!client.isSameThread) {
            client.execute { playNow(id, level, speed) }
            return
        }
        playNow(id, level, speed)
    }

    private fun playNow(id: Identifier, volume: Float, pitch: Float) {
        val manager = McCompat.mc.soundManager ?: return
        HitsoundLibrary.ensureInstalled()
        manager.play(
            SimpleSoundInstance(
                id,
                SoundSource.MASTER,
                volume,
                pitch,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0,
                0.0,
                0.0,
                true,
            ),
        )
    }
}
