package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.chat.LevelPrestige;

@Mixin(Font.class)
public abstract class FontMixin {

    @ModifyVariable(
        method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedCharSequence pawfectaddons$levelSymbol(FormattedCharSequence text) {
        return LevelPrestige.decorate(text);
    }

    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("RETURN"), cancellable = true)
    private void pawfectaddons$levelWidth(FormattedText text, CallbackInfoReturnable<Integer> callback) {
        int extra = LevelPrestige.extraWidth(text);
        if (extra != 0) callback.setReturnValue(callback.getReturnValue() + extra);
    }

    @Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("RETURN"), cancellable = true)
    private void pawfectaddons$levelSequenceWidth(FormattedCharSequence text, CallbackInfoReturnable<Integer> callback) {
        int extra = LevelPrestige.extraWidth(text);
        if (extra != 0) callback.setReturnValue(callback.getReturnValue() + extra);
    }
}
