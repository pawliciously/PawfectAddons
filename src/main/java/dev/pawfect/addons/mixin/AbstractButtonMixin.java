package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuStyle;

@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$styleButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        if (!MenuStyle.stylesWidget((AbstractWidget) (Object) this)) return;
        try {
            MenuStyle.drawButton(graphics, (AbstractWidget) (Object) this);
            callback.cancel();
        } catch (Throwable ignored) {
        }
    }
}
