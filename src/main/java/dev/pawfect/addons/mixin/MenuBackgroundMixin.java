package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuStyle;
import dev.pawfect.addons.features.visual.menu.hub.HubSceneRenderer;

@Mixin(Screen.class)
public abstract class MenuBackgroundMixin {

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$panorama(GuiGraphicsExtractor graphics, float alpha, CallbackInfo callback) {
        if (HubSceneRenderer.active()) return;
        if (!MenuStyle.stylesBackground()) return;
        MenuStyle.drawBackground(graphics);
        callback.cancel();
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$menuBackground(GuiGraphicsExtractor graphics, CallbackInfo callback) {
        if (HubSceneRenderer.active()) return;
        if (!MenuStyle.stylesBackground()) return;
        MenuStyle.drawBackground(graphics);
        callback.cancel();
    }
}
