package dev.pawfect.addons.mixin;

import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.pawfect.addons.features.visual.playerchams.PlayerChams;

@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {

    @ModifyVariable(method = "submitModel", at = @At("HEAD"), argsOnly = true)
    private RenderType pawfectaddons$chamsModel(RenderType renderType) {
        return PlayerChams.wrap(renderType);
    }

    @ModifyVariable(method = "submitModelPart", at = @At("HEAD"), argsOnly = true)
    private RenderType pawfectaddons$chamsModelPart(RenderType renderType) {
        return PlayerChams.wrap(renderType);
    }
}
