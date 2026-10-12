package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.dungeon.InvincibilityTimers;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the invincibility masks' cooldown over them, in the inventory and the hotbar alike. */
@Mixin(GuiGraphicsExtractor.class)
public class ItemDecorationsMixin {

    @Inject(
        method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
        at = @At("TAIL")
    )
    private void pawfectaddons$maskCooldown(Font font, ItemStack stack, int x, int y, String text, CallbackInfo callback) {
        InvincibilityTimers.decorate((GuiGraphicsExtractor) (Object) this, stack, x, y);
    }
}
