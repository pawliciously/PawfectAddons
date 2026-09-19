package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractTexture.class)
public interface AbstractTextureAccessor {

    @Accessor("sampler")
    void pawfectaddons$setSampler(GpuSampler sampler);
}
