package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.visual.tooltip.TooltipStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class TooltipItemMixin {

    @Inject(method = "extractTooltip", at = @At("HEAD"))
    private void pawfectaddons$captureItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo callback) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        ItemStack carried = screen.getMenu().getCarried();
        if (!carried.isEmpty()) {
            TooltipStyle.setItem(carried);
            return;
        }
        Slot hovered = ((ContainerScreenAccessor) screen).pawfectaddons$hoveredSlot();
        TooltipStyle.setItem(hovered == null ? ItemStack.EMPTY : hovered.getItem());
    }
}
