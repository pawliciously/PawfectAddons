package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.chat.ChatStyle;
import dev.pawfect.addons.features.dev.RecordingMode;

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

    /** Recording mode keeps everything but our own messages out of chat. */
    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void pawfectaddons$recordingFilter(CallbackInfo callback) {
        if (RecordingMode.hidesChat()) callback.cancel();
    }

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
