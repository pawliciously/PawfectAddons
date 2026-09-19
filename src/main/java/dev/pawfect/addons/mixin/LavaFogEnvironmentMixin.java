package dev.pawfect.addons.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.LavaChanger;

@Mixin(LavaFogEnvironment.class)
public abstract class LavaFogEnvironmentMixin {

    @Unique
    private static final WaterFogEnvironment pawfectaddons$waterFog = new WaterFogEnvironment();

    @Inject(method = "setupFog", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$clearFog(
            FogData fog,
            Camera camera,
            ClientLevel level,
            float renderDistance,
            DeltaTracker deltaTracker,
            CallbackInfo ci
    ) {
        if (!LavaChanger.getHideFog()) return;
        fog.color.set(fog.color.x, fog.color.y, fog.color.z, 0f);
        fog.environmentalStart = renderDistance;
        fog.environmentalEnd = renderDistance;
        ci.cancel();
    }

    @Inject(method = "getBaseColor", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$waterFogColor(
            ClientLevel level,
            Camera camera,
            int renderDistance,
            float partialTicks,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!LavaChanger.isEnabled()) return;
        cir.setReturnValue(pawfectaddons$waterFog.getBaseColor(level, camera, renderDistance, partialTicks));
    }
}
