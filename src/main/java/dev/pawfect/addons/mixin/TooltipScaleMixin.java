package dev.pawfect.addons.mixin;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.tooltip.TooltipStyle;

@Mixin(GuiGraphicsExtractor.class)
public class TooltipScaleMixin {

    @Unique
    private boolean pawfectaddons$scaled;

    @Inject(method = "tooltip", at = @At("HEAD"))
    private void pawfectaddons$pushScale(
        Font font,
        List<ClientTooltipComponent> lines,
        int x,
        int y,
        ClientTooltipPositioner positioner,
        Identifier style,
        CallbackInfo callback
    ) {
        pawfectaddons$scaled = TooltipStyle.pushScale((GuiGraphicsExtractor) (Object) this, x, y);
    }

    @Inject(method = "tooltip", at = @At("RETURN"))
    private void pawfectaddons$popScale(
        Font font,
        List<ClientTooltipComponent> lines,
        int x,
        int y,
        ClientTooltipPositioner positioner,
        Identifier style,
        CallbackInfo callback
    ) {
        TooltipStyle.popScale((GuiGraphicsExtractor) (Object) this, pawfectaddons$scaled);
        pawfectaddons$scaled = false;
    }
}
