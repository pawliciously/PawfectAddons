package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.combat.Hitsound;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void pawfectaddons$hitsoundMelee(Player attacker, Entity target, CallbackInfo callback) {
        try {
            Hitsound.onMelee(attacker, target);
        } catch (Throwable ignored) {
        }
    }
}
