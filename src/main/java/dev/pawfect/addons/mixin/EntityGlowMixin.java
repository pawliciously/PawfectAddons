package dev.pawfect.addons.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.visual.DungeonBats;
import dev.pawfect.addons.features.visual.StarMobGlow;

@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$forceGlow(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        if (DungeonBats.shouldHighlight(entity) || StarMobGlow.shouldGlow(entity)) cir.setReturnValue(true);
    }
}
