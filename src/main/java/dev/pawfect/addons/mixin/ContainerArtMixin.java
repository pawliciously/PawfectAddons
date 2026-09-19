package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.InventoryStyle;

@Mixin(GuiGraphicsExtractor.class)
public abstract class ContainerArtMixin {

    @Inject(
        method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void pawfectaddons$hidePanel(
        RenderPipeline pipeline,
        Identifier texture,
        int x,
        int y,
        float u,
        float v,
        int width,
        int height,
        int textureWidth,
        int textureHeight,
        CallbackInfo callback
    ) {
        if (InventoryStyle.replacePanel((GuiGraphicsExtractor) (Object) this, texture, x, y, width, height)) {
            callback.cancel();
        }
    }

    @Inject(
        method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void pawfectaddons$hideSlotIcon(
        RenderPipeline pipeline,
        Identifier sprite,
        int x,
        int y,
        int width,
        int height,
        CallbackInfo callback
    ) {
        if (InventoryStyle.hidesSprite(sprite)) callback.cancel();
    }
}
