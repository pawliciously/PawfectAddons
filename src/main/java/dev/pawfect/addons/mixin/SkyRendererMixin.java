package dev.pawfect.addons.mixin;

import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.skybox.Skybox;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$hideCelestialBodies(CallbackInfo callback) {
        if (Skybox.isActive()) callback.cancel();
    }
}
