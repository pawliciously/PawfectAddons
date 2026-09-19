package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.cosmetics.Capes;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void pawfectaddons$applyCape(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        try {
            Capes.apply(entity.getUUID(), state);
        } catch (Throwable error) {
        }
    }
}
