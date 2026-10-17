package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.dev.DevClickSounds;

/** Dev click sounds on vanilla widgets: buttons, checkboxes, cycle buttons and sliders (on release). */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {

    @Inject(method = "playDownSound", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$devClick(SoundManager soundManager, CallbackInfo callback) {
        if (DevClickSounds.widgetClick(this)) callback.cancel();
    }
}
