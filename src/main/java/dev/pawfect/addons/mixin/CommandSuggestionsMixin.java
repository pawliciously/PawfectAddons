package dev.pawfect.addons.mixin;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.chat.Emojis;

@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    @Shadow
    @Final
    private EditBox input;

    @Shadow
    @Final
    private boolean commandsOnly;

    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    public abstract void showSuggestions(boolean tabCycles);

    @Inject(method = "updateCommandInfo", at = @At("RETURN"))
    private void pawfectaddons$emojiSuggestions(CallbackInfo callback) {
        if (commandsOnly) return;
        CompletableFuture<Suggestions> emoji = Emojis.INSTANCE.suggest(input.getValue(), input.getCursorPosition());
        if (emoji == null) return;
        pendingSuggestions = emoji;
        showSuggestions(false);
    }
}
