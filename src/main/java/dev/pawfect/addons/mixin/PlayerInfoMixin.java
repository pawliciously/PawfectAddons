package dev.pawfect.addons.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.chat.LevelPrestige;

/**
 * Tab entries are restyled where they're read, not where they're drawn, so vanilla's tab list,
 * Skyblocker's tab HUD and anything else that shows them all get it. Only colours change, so
 * mods parsing the text see the same words.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void pawfectaddons$levelPrestige(CallbackInfoReturnable<Component> callback) {
        Component original = callback.getReturnValue();
        if (original == null) return;
        Component styled = LevelPrestige.tab(original);
        if (styled != original) callback.setReturnValue(styled);
    }
}
