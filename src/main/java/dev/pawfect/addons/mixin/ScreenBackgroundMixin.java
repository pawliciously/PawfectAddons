package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.MenuImage;

@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void pawfectaddons$behindWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        try {
            MenuImage.renderBehind((Screen) (Object) this, graphics);
        } catch (Throwable ignored) {
        }
    }
}
