package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {

    @Accessor("hoveredSlot")
    Slot pawfectaddons$hoveredSlot();

    @Accessor("leftPos")
    int pawfectaddons$leftPos();

    @Accessor("topPos")
    int pawfectaddons$topPos();

    @Accessor("imageWidth")
    int pawfectaddons$imageWidth();

    @Accessor("imageHeight")
    int pawfectaddons$imageHeight();
}
