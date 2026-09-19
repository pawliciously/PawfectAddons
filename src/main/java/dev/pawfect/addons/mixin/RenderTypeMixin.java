package dev.pawfect.addons.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.pawfect.addons.features.visual.playerchams.PlayerChams;

@Mixin(RenderType.class)
public abstract class RenderTypeMixin {

    private static final String WRITE_TRANSFORM = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";

    @ModifyArg(method = "draw", at = @At(value = "INVOKE", target = WRITE_TRANSFORM), index = 1)
    private Vector4fc pawfectaddons$chamsColor(Vector4fc original) {
        return PlayerChams.isChams((RenderType) (Object) this) ? PlayerChams.color(original) : original;
    }

    @ModifyArg(method = "draw", at = @At(value = "INVOKE", target = WRITE_TRANSFORM), index = 2)
    private Vector3fc pawfectaddons$chamsOffset(Vector3fc original) {
        return PlayerChams.isChams((RenderType) (Object) this) ? PlayerChams.offset(original) : original;
    }

    @ModifyArg(method = "draw", at = @At(value = "INVOKE", target = WRITE_TRANSFORM), index = 3)
    private Matrix4fc pawfectaddons$chamsParams(Matrix4fc original) {
        return PlayerChams.isChams((RenderType) (Object) this) ? PlayerChams.params(original) : original;
    }
}
