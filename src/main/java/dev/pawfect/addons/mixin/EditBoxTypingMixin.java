package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.pawfect.addons.features.dev.DevClickSounds;

/** Dev typing sounds: a sound for every character that lands in the chat box, and for backspace. */
@Mixin(EditBox.class)
public abstract class EditBoxTypingMixin {

    @Inject(method = "charTyped", at = @At("RETURN"))
    private void pawfectaddons$typed(CharacterEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ()) return;
        try {
            DevClickSounds.chatKey((EditBox) (Object) this, event.codepoint());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "keyPressed", at = @At("RETURN"))
    private void pawfectaddons$backspace(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() || (event.key() != 259 && event.key() != 261)) return;
        try {
            DevClickSounds.chatKey((EditBox) (Object) this, '\b');
        } catch (Throwable ignored) {
        }
    }
}
