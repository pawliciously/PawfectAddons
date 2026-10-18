package dev.pawfect.addons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import dev.pawfect.addons.features.visual.playerchams.PlayerChams;

@Mixin(RenderType.class)
public abstract class RenderTypeMixin {

    @Unique
    private static final Vector4f pawfectaddons$WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);

    @Unique
    private static final Vector3f pawfectaddons$NO_OFFSET = new Vector3f();

    @WrapOperation(
            method = "writeDynamicTransforms",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
            )
    )
    private GpuBufferSlice pawfectaddons$chamsTransform(DynamicUniforms uniforms, Matrix4f modelView, Matrix4f textureMatrix, Operation<GpuBufferSlice> original) {
        if (!PlayerChams.isChams((RenderType) (Object) this)) return original.call(uniforms, modelView, textureMatrix);
        return uniforms.writeTransform(new DynamicUniforms.Transform(
                modelView,
                PlayerChams.color(pawfectaddons$WHITE),
                PlayerChams.offset(pawfectaddons$NO_OFFSET),
                PlayerChams.params(textureMatrix)
        ));
    }
}
