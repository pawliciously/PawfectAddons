package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuStyle;

@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin {

    @Shadow
    protected double value;

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$styleSlider(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        if (!MenuStyle.stylesWidget((AbstractWidget) (Object) this)) return;
        try {
            MenuStyle.drawSlider(graphics, (AbstractWidget) (Object) this, this.value);
            callback.cancel();
        } catch (Throwable ignored) {
        }
    }
}
