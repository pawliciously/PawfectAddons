package dev.pawfect.addons.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(SoundManager.class)
public interface SoundManagerAccessor {

    @Accessor("registry")
    Map<Identifier, WeighedSoundEvents> pawfectaddonsRegistry();

    @Accessor("soundEngine")
    SoundEngine pawfectaddonsSoundEngine();
}
