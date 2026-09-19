package dev.pawfect.addons.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.cosmetics.Cosmetics;
import dev.pawfect.addons.features.visual.DungeonBats;
import dev.pawfect.addons.features.visual.StarMobGlow;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void pawfectaddons$batGlowColour(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        if (DungeonBats.shouldHighlight(entity)) {
            state.outlineColor = ARGB.opaque(DungeonBats.getGlowColor());
        } else if (StarMobGlow.shouldGlow(entity)) {
            state.outlineColor = StarMobGlow.outlineColor();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void pawfectaddons$cosmeticNameTag(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        if (state.nameTag == null) return;
        if (!(entity instanceof AbstractClientPlayer player)) return;

        Component styled = Cosmetics.styleNameTag(
            player.getUUID(),
            player.getGameProfile().name(),
            state.nameTag
        );
        if (styled != null) state.nameTag = styled;
    }
}
