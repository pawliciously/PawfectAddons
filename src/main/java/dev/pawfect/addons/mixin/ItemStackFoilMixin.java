package dev.pawfect.addons.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.handchams.HandChams;

@Mixin(ItemStack.class)
public abstract class ItemStackFoilMixin {

    @Inject(method = "hasFoil", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$hideGlint(CallbackInfoReturnable<Boolean> callback) {
        if (HandChams.hideEnchantGlint()) callback.setReturnValue(false);
    }
}
