package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.chat.ChatGraphicsHolder;
import dev.pawfect.addons.features.chat.ChatStyle;

/**
 * The two chat passes that actually draw: unfocused (fading lines) and focused (chat open).
 * Line backgrounds are handled in ChatComponentMixin; what reaches fill here is the scrollbar
 * and the pending-message line.
 */
// Two targets means nothing in here may be remappable; 26.1 is unobfuscated, so none of it needs to be.
@Mixin(
    targets = {
        "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess",
        "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess"
    },
    remap = false
)
public abstract class ChatDrawingAccessMixin implements ChatGraphicsHolder {

    @Shadow(remap = false)
    @Final
    private GuiGraphicsExtractor graphics;

    @Override
    public GuiGraphicsExtractor pawfectaddons$graphics() {
        return graphics;
    }

    @Inject(method = "fill(IIIII)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void pawfectaddons$restyleFill(int x0, int y0, int x1, int y1, int color, CallbackInfo callback) {
        if (ChatStyle.fill(graphics, x0, y0, x1, y1, color)) callback.cancel();
    }
}
