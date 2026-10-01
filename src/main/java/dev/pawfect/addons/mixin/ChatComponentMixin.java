package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.chat.ChatStyle;

/**
 * Vanilla draws chat in two passes: every line's background, then every line's text. The
 * background pass is recorded instead of drawn, and the styled panel goes in right after it,
 * so it still sits under the text. Text, layout, clicks and hover are left to vanilla and to
 * the mods that patch them (Chat Patches, Chat Heads, SkyHanni's chat peek and so on).
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    private static final String DRAW =
        "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V";
    private static final String FOR_EACH_LINE =
        "Lnet/minecraft/client/gui/components/ChatComponent;forEachLine(Lnet/minecraft/client/gui/components/ChatComponent$AlphaCalculator;Lnet/minecraft/client/gui/components/ChatComponent$LineConsumer;)I";

    @Inject(method = DRAW, at = @At(value = "INVOKE", target = FOR_EACH_LINE, ordinal = 0))
    private void pawfectaddons$beginBackgrounds(
        ChatComponent.ChatGraphicsAccess access,
        int a,
        int b,
        ChatComponent.DisplayMode mode,
        CallbackInfo callback
    ) {
        ChatStyle.beginLines(access, mode.foreground);
    }

    @Inject(method = DRAW, at = @At(value = "INVOKE", target = FOR_EACH_LINE, ordinal = 0, shift = At.Shift.AFTER))
    private void pawfectaddons$endBackgrounds(
        ChatComponent.ChatGraphicsAccess access,
        int a,
        int b,
        ChatComponent.DisplayMode mode,
        CallbackInfo callback
    ) {
        ChatStyle.endLines(access);
    }

    /** The background pass's per-line callback: fills one black strip behind a line. */
    @Inject(method = "lambda$extractRenderState$1", at = @At("HEAD"), cancellable = true)
    private static void pawfectaddons$recordLine(
        int baseY,
        int lineHeight,
        ChatComponent.ChatGraphicsAccess access,
        int width,
        float backgroundOpacity,
        GuiMessage.Line line,
        int index,
        float alpha,
        CallbackInfo callback
    ) {
        if (ChatStyle.line(access, baseY, lineHeight, width, line, index, alpha)) callback.cancel();
    }
}
