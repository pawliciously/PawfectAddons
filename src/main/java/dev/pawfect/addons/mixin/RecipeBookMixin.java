package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.pawfect.addons.features.visual.menu.InventoryStyle;

@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookMixin<T extends RecipeBookMenu> extends AbstractContainerScreen<T> {

    @Shadow
    @Final
    private RecipeBookComponent<?> recipeBookComponent;

    public RecipeBookMixin(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "initButton", at = @At("HEAD"), cancellable = true)
    private void pawfectaddons$hideRecipeBook(CallbackInfo callback) {
        if (!InventoryStyle.hidesRecipeBook()) return;
        if (recipeBookComponent.isVisible()) {
            recipeBookComponent.toggleVisibility();
            this.leftPos = recipeBookComponent.updateScreenPosition(this.width, this.imageWidth);
        }
        callback.cancel();
    }
}
