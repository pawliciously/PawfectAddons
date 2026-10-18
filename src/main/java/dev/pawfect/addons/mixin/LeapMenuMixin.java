package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.dungeon.LeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class LeapMenuMixin {

    private AbstractContainerScreen<?> pawfectaddons$self() {
        return (AbstractContainerScreen<?>) (Object) this;
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (LeapMenu.active(pawfectaddons$self()) && LeapMenu.render(pawfectaddons$self(), graphics, mouseX, mouseY)) ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!LeapMenu.active(pawfectaddons$self())) return;
        LeapMenu.mouseClicked(pawfectaddons$self(), event.x(), event.y(), event.button());
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (LeapMenu.active(pawfectaddons$self())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapDrag(MouseButtonEvent event, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (LeapMenu.active(pawfectaddons$self())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapScroll(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (LeapMenu.active(pawfectaddons$self())) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!LeapMenu.active(pawfectaddons$self())) return;
        if (LeapMenu.closesMenu(event)) return;
        LeapMenu.keyPressed(pawfectaddons$self(), event.key());
        cir.setReturnValue(true);
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$leapNoSlotClicks(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (LeapMenu.active(pawfectaddons$self())) ci.cancel();
    }
}
