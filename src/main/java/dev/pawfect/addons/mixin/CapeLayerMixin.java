package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.playerchams.PlayerChams;

/** Marks the cape while it's submitted, so chams can leave it alone (and PA capes with it). */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {

    private static final String SUBMIT =
        "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V";

    @Inject(method = SUBMIT, at = @At("HEAD"))
    private void pawfectaddons$capeStart(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yaw, float pitch, CallbackInfo callback) {
        PlayerChams.setInCape(true);
    }

    @Inject(method = SUBMIT, at = @At("RETURN"))
    private void pawfectaddons$capeEnd(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yaw, float pitch, CallbackInfo callback) {
        PlayerChams.setInCape(false);
    }
}
