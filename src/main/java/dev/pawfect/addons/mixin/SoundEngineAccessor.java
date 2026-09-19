package dev.pawfect.addons.mixin;

import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {

    @Accessor("soundBuffers")
    SoundBufferLibrary pawfectaddonsSoundBuffers();
}
