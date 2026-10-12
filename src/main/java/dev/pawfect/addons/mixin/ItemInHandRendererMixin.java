package dev.pawfect.addons.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.pawfect.addons.features.visual.HandInspect;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Plays the inspect animation on the held item, after the swing and just before it's drawn. */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"
        )
    )
    private void pawfectaddons$inspect(
        AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
        ItemStack stack, float equipProgress, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo callback
    ) {
        HandInspect.apply(poseStack, player, hand, swingProgress);
    }
}
