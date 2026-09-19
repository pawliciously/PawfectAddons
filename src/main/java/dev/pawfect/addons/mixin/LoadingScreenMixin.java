package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuStyle;

@Mixin({ GenericMessageScreen.class, LevelLoadingScreen.class })
public abstract class LoadingScreenMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$loadingBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        if (!MenuStyle.stylesLoadingScreens()) return;
        try {
            MenuStyle.drawBackground(graphics);
            callback.cancel();
        } catch (Throwable ignored) {
        }
    }
}
