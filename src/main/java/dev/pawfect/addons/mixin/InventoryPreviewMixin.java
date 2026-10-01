package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.playerchams.PlayerScale;

@Mixin(InventoryScreen.class)
public abstract class InventoryPreviewMixin {

    private static final String BUILD =
        "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;";

    @Inject(method = BUILD, at = @At("HEAD"))
    private static void pawfectaddons$previewStart(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> callback) {
        PlayerScale.setInPreview(true);
    }

    @Inject(method = BUILD, at = @At("RETURN"))
    private static void pawfectaddons$previewEnd(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> callback) {
        PlayerScale.setInPreview(false);
    }
}
