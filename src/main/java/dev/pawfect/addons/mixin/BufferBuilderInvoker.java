package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BufferBuilder.class)
public interface BufferBuilderInvoker {

    @Invoker("beginElement")
    long pawfectaddons$beginElement(VertexFormatElement element);
}
