package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.skybox.Skybox;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(method = "addSkyPass", at = @At("TAIL"))
    private void pawfectaddons$sky(
            FrameGraphBuilder builder,
            CameraRenderState cameraRenderState,
            GpuBufferSlice fog,
            CallbackInfo callback) {
        if (!Skybox.isActive()) return;

        FramePass pass = builder.addPass("pawfectaddons_sky");
        this.targets.main = pass.readsAndWrites(this.targets.main);
        pass.executes(Skybox::renderSky);
    }
}
