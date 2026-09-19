package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.visual.tooltip.TooltipStyle;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiGraphicsExtractor.class)
public class TooltipTextMixin {

    @ModifyVariable(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        argsOnly = true,
        index = 2
    )
    private List<Component> pawfectaddons$restyle(List<Component> lines) {
        return TooltipStyle.restyle(lines);
    }
}
