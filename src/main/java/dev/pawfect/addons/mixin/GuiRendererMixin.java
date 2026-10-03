package dev.pawfect.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.CubeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import dev.pawfect.addons.features.visual.menu.hub.HubSceneRenderer;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CubeMap;render(FF)V"))
    private void pawfectaddons$hubScene(CubeMap cubeMap, float alpha, float spin, Operation<Void> original) {
        if (!HubSceneRenderer.draw()) original.call(cubeMap, alpha, spin);
    }
}
