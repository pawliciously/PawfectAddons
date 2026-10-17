package dev.pawfect.addons.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.dev.DevClickSounds;

/**
 * Dev click sounds for the UI clicks that don't go through a widget: tab bars, the world list, option keys, the
 * crafter, and other mods playing the vanilla click themselves.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$devClick(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
        if (instance.getSource() != SoundSource.UI) return;
        if (!SoundEvents.UI_BUTTON_CLICK.value().location().equals(instance.getIdentifier())) return;
        if (DevClickSounds.uiClick()) callback.setReturnValue(SoundEngine.PlayResult.STARTED);
    }
}
