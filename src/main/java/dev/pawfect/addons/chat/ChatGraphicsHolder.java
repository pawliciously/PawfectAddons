package dev.pawfect.addons.chat;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Exposes the graphics a chat drawing pass is going to; see ChatDrawingAccessMixin. */
public interface ChatGraphicsHolder {

    GuiGraphicsExtractor pawfectaddons$graphics();
}
