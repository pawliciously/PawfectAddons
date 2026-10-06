package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.handchams.HandChams;
import dev.pawfect.addons.features.visual.menu.hub.HubWalker;
import dev.pawfect.addons.features.visual.motionblur.MotionBlur;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void pawfectaddons$afterHands(DeltaTracker deltaTracker, CallbackInfo ci) {
        HandChams.renderEffect();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void pawfectaddons$beforeHandDepthClear(DeltaTracker deltaTracker, CallbackInfo ci) {
        MotionBlur.render();
    }

    @Inject(method = "lightmap", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$walkerLightmap(CallbackInfoReturnable<GpuTextureView> cir) {
        GpuTextureView override = HubWalker.lightmapOverride();
        if (override != null) cir.setReturnValue(override);
    }
}
