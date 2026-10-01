package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.pawfect.addons.features.chat.LevelPrestige;

/** All GUI text and nametags pass through here; it animates the 640+ level shine. */
@Mixin(Font.class)
public abstract class FontMixin {

    @ModifyVariable(
        method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
        at = @At("HEAD"),
        argsOnly = true
    )
    private FormattedCharSequence pawfectaddons$shine(FormattedCharSequence text) {
        return LevelPrestige.animate(text);
    }
}
