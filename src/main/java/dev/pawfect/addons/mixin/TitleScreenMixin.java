package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.config.ConfigGuiManager;
import dev.pawfect.addons.features.visual.menu.hub.HubSceneRenderer;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void pawfectaddons$addConfigButton(CallbackInfo callback) {
        addRenderableWidget(
            Button.builder(Component.literal("PawfectAddons"), button -> ConfigGuiManager.INSTANCE.open("theme"))
                .bounds(4, this.height - 30, 98, 20)
                .build()
        );
        if (HubSceneRenderer.canSwitch()) {
            addRenderableWidget(
                Button.builder(backgroundLabel(), button -> {
                    HubSceneRenderer.next();
                    button.setMessage(backgroundLabel());
                })
                    .bounds(106, this.height - 30, 140, 20)
                    .build()
            );
        }
    }

    private static Component backgroundLabel() {
        return Component.literal("Background: " + HubSceneRenderer.sceneTitle());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void pawfectaddons$hubCredit(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if (!HubSceneRenderer.active()) return;
        String credit = HubSceneRenderer.sceneTitle() + " by Hypixel";
        graphics.text(this.font, credit, this.width - this.font.width(credit) - 2, this.height - 20, 0xB3FFFFFF, true);
    }
}
