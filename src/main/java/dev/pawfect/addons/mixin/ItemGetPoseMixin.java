package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.drops.ItemGet;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Raises your arms over your head for the item get, after the model's own pose. */
@Mixin(PlayerModel.class)
public class ItemGetPoseMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void pawfectaddons$itemGetPose(AvatarRenderState state, CallbackInfo callback) {
        try {
            HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
            ItemGet.pose(state.id, model.rightArm, model.leftArm);
        } catch (Throwable ignored) {
        }
    }
}
