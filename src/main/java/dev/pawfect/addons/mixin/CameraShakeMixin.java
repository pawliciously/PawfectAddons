package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.pawfect.addons.features.drops.DropCelebration;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class CameraShakeMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"))
    private void pawfectaddons$dropShake(CameraRenderState camera, PoseStack poseStack, CallbackInfo callback) {
        try {
            DropCelebration.shake(poseStack);
        } catch (Throwable ignored) {
        }
    }
}
