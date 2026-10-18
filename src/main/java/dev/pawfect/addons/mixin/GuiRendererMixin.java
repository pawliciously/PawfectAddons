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
        if (right <= left || bottom <= top) {
            original.call(pass, 0, 0, 1, 1);
            return;
        }
        original.call(pass, left, top, right - left, bottom - top);
    }
}
