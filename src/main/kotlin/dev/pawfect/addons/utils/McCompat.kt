package dev.pawfect.addons.utils

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.player.LocalPlayer

object McCompat {

    val mc: Minecraft get() = Minecraft.getInstance()

    val font: Font get() = mc.font

    val player: LocalPlayer? get() = mc.player

    var screen: Screen?
        get() = mc.gui.screen()
        set(value) = mc.gui.setScreen(value)

    val hideGui: Boolean get() = mc.gui.hud.isHidden

    val scaledWidth: Int get() = mc.window.guiScaledWidth
    val scaledHeight: Int get() = mc.window.guiScaledHeight

    val mouseX: Int get() = mc.mouseHandler.getScaledXPos(mc.window).toInt()
    val mouseY: Int get() = mc.mouseHandler.getScaledYPos(mc.window).toInt()
}
