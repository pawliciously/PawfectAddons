package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.InventoryStyle;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerPlateMixin {

    @Inject(method = "extractContents", at = @At("HEAD"))
    private void pawfectaddons$endPlateFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        InventoryStyle.endFrame();
    }

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$highlightBack(GuiGraphicsExtractor graphics, CallbackInfo callback) {
        if (InventoryStyle.hidesSlotHighlight()) callback.cancel();
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$highlightFront(GuiGraphicsExtractor graphics, CallbackInfo callback) {
        if (InventoryStyle.hidesSlotHighlight()) callback.cancel();
    }
}
