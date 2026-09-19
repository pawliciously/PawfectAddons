package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Mixin(SoundBufferLibrary.class)
public interface SoundBufferLibraryAccessor {

    @Accessor("cache")
    Map<Identifier, CompletableFuture<SoundBuffer>> pawfectaddonsCache();
}
