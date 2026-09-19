package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SplashRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuStyle;

@Mixin(SplashRenderer.class)
public abstract class SplashRendererMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$hideSplash(GuiGraphicsExtractor graphics, int screenWidth, Font font, float alpha, CallbackInfo callback) {
        if (MenuStyle.hidesSplash()) callback.cancel();
    }
}
