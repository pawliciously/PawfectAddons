package dev.pawfect.addons.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.handchams.HandChams;
import dev.pawfect.addons.features.visual.motionblur.MotionBlur;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
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
}
