package dev.pawfect.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import dev.pawfect.addons.features.visual.menu.hub.HubSceneRenderer;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CubeMap;render(FF)V"))
    private void pawfectaddons$hubScene(CubeMap cubeMap, float alpha, float spin, Operation<Void> original) {
        if (!HubSceneRenderer.draw()) original.call(cubeMap, alpha, spin);
    }

    // The render pass throws on a scissor past the left or top edge, or one with no size, but the
    // GUI only trims the right and bottom. Anything clipped against those edges (chat backdrops
    // at x 0, a list scrolled past the top) would crash the game, so the rest is trimmed here.
    @WrapOperation(
            method = "enableScissor(Lnet/minecraft/client/gui/navigation/ScreenRectangle;Lcom/mojang/blaze3d/systems/RenderPass;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;enableScissor(IIII)V")
    )
    private void pawfectaddons$trimScissor(RenderPass pass, int x, int y, int width, int height, Operation<Void> original) {
        WindowRenderState window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
        int left = Math.max(x, 0);
        int top = Math.max(y, 0);
        int right = Math.min(x + width, window.width);
        int bottom = Math.min(y + height, window.height);
        // Nothing is left on screen: a single corner pixel stands in, as nothing drawn there is visible.
        if (right <= left || bottom <= top) {
            original.call(pass, 0, 0, 1, 1);
            return;
        }
        original.call(pass, left, top, right - left, bottom - top);
    }
}
