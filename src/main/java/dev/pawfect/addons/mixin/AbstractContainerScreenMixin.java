package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.experiments.ExperimentManager;
import dev.pawfect.addons.features.experiments.ExperimentOverlay;
import dev.pawfect.addons.features.visual.TooltipHider;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$hideTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (TooltipHider.isHidingTooltips()) ci.cancel();
    }

    @Inject(method = "extractLabels", at = @At("TAIL"))
    private void pawfectaddons$experimentsUnder(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            ExperimentOverlay.renderUnder(graphics, (AbstractContainerScreen<?>) (Object) this);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "extractSlots", at = @At("TAIL"))
    private void pawfectaddons$experimentsOver(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            ExperimentOverlay.renderOver(graphics, (AbstractContainerScreen<?>) (Object) this);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$experimentClick(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (slot == null || slotId < 0) return;
        try {
            if (ExperimentManager.onClickSlot(slotId, slot.getItem())) ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
