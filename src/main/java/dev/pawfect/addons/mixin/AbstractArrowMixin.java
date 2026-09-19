package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.combat.Hitsound;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void pawfectaddons$hitsoundArrow(EntityHitResult result, CallbackInfo callback) {
        try {
            Hitsound.onArrowHit((AbstractArrow) (Object) this, result.getEntity());
        } catch (Throwable ignored) {
        }
    }
}
