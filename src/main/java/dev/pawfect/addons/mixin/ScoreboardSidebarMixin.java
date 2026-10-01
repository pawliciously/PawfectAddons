package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.ScoreboardRenderer;

@Mixin(value = Gui.class, priority = 1100)
public abstract class ScoreboardSidebarMixin {

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$drawSidebar(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo callback) {
        if (ScoreboardRenderer.render(graphics, objective)) callback.cancel();
    }
}
