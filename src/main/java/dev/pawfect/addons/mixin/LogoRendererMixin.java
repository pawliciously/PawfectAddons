package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuStyle;

@Mixin(LogoRenderer.class)
public abstract class LogoRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$replaceLogo(GuiGraphicsExtractor graphics, int screenWidth, float alpha, int height, CallbackInfo callback) {
        if (!MenuStyle.replacesLogo()) return;
        try {
            MenuStyle.drawTitle(graphics, screenWidth);
            callback.cancel();
        } catch (Throwable ignored) {
        }
    }
}
