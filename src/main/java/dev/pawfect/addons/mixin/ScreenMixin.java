package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.dev.PacketOverlay;
import dev.pawfect.addons.features.media.MediaOverlay;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void pawfectaddons$mediaOnTop(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
        try {
            MediaOverlay.renderOverScreen(graphics);
            PacketOverlay.renderOverScreen(graphics);
        } catch (Throwable ignored) {
        }
    }
}
