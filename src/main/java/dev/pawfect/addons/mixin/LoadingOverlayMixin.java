package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.pawfect.addons.features.visual.menu.LoadingScene;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.IntSupplier;

/**
 * Swaps Mojang's logo and progress bar for the PawfectAddons loading scene, on startup and on
 * every resource reload. Vanilla still decides when to fade and when it's finished.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Unique
    private boolean pawfectaddons$drewScene;

    @Shadow
    private void extractProgressBar(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, float alpha) {
        throw new AssertionError();
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
    private int pawfectaddons$background(IntSupplier brand) {
        return LoadingScene.active() ? LoadingScene.BACKGROUND : brand.getAsInt();
    }

    @Redirect(
        method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", ordinal = 0)
    )
    private void pawfectaddons$scene(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
                                     int width, int height, int uWidth, int vHeight, int textureWidth, int textureHeight, int color) {
        LoadingOverlayAccessor self = (LoadingOverlayAccessor) this;
        pawfectaddons$drewScene = LoadingScene.draw(graphics, this, (color >>> 24) / 255f, self.pawfectaddons$currentProgress(), self.pawfectaddons$fadeOutStart());
        if (!pawfectaddons$drewScene) graphics.blit(pipeline, texture, x, y, u, v, width, height, uWidth, vHeight, textureWidth, textureHeight, color);
    }

    @Redirect(
        method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", ordinal = 1)
    )
    private void pawfectaddons$secondLogoHalf(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
                                              int width, int height, int uWidth, int vHeight, int textureWidth, int textureHeight, int color) {
        if (!pawfectaddons$drewScene) graphics.blit(pipeline, texture, x, y, u, v, width, height, uWidth, vHeight, textureWidth, textureHeight, color);
    }

    @Redirect(
        method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;extractProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V")
    )
    private void pawfectaddons$progressBar(LoadingOverlay overlay, GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, float alpha) {
        // The scene draws its own bar.
        if (!pawfectaddons$drewScene) extractProgressBar(graphics, left, top, right, bottom, alpha);
    }
}
