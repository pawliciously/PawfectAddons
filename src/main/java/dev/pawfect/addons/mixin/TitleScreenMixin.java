package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.config.ConfigGuiManager;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void pawfectaddons$addConfigButton(CallbackInfo callback) {
        addRenderableWidget(
            Button.builder(Component.literal("PawfectAddons"), button -> ConfigGuiManager.INSTANCE.open("menu"))
                .bounds(4, this.height - 24, 98, 20)
                .build()
        );
    }
}
