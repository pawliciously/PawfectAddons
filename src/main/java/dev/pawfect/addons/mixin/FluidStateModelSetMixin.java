package dev.pawfect.addons.mixin;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.LavaChanger;

@Mixin(FluidStateModelSet.class)
public abstract class FluidStateModelSetMixin {

    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void pawfectaddons$replaceLavaModel(FluidState state, CallbackInfoReturnable<FluidModel> cir) {
        FluidModel replacement = LavaChanger.modelFor((FluidStateModelSet) (Object) this, state);
        if (replacement != null) cir.setReturnValue(replacement);
    }
}
