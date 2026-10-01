package dev.pawfect.addons.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.menu.InventoryStyle;

@Mixin(Slot.class)
public abstract class SlotMixin {

    @Shadow
    @Final
    public Container container;

    @Shadow
    public abstract int getContainerSlot();

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$hideOffhand(CallbackInfoReturnable<Boolean> callback) {
        if (container instanceof Inventory && getContainerSlot() == Inventory.SLOT_OFFHAND && InventoryStyle.active()) {
            callback.setReturnValue(false);
        }
    }
}
