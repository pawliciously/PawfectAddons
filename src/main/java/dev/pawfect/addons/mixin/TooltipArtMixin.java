package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.visual.tooltip.TooltipStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TooltipRenderUtil.class)
public class TooltipArtMixin {

    @Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
    private static void pawfectaddons$frame(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int width,
        int height,
        Identifier style,
        CallbackInfo callback
    ) {
        if (TooltipStyle.drawBackground(graphics, x, y, width, height)) callback.cancel();
    }
}
