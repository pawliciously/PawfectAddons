package dev.pawfect.addons

import com.mojang.blaze3d.platform.InputConstants
import dev.pawfect.addons.features.media.MediaBridge
import dev.pawfect.addons.features.visual.TooltipHider
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.components.EditBox
import net.minecraft.resources.Identifier

object PawfectKeybinds {

    private lateinit var toggleTooltips: KeyMapping
    private lateinit var mediaToggle: KeyMapping
    private lateinit var mediaNext: KeyMapping
    private lateinit var mediaPrevious: KeyMapping

    fun register() {
        val category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "main"),
        )

        toggleTooltips = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.pawfectaddons.toggleTooltips",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_H,
                category,
            ),
        )

        mediaToggle = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.pawfectaddons.mediaToggle",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_HOME,
                category,
            ),
        )

        mediaNext = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.pawfectaddons.mediaNext",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_PAGEDOWN,
                category,
            ),
        )

        mediaPrevious = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.pawfectaddons.mediaPrevious",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_PAGEUP,
                category,
            ),
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            while (toggleTooltips.consumeClick()) TooltipHider.toggle()
            while (mediaToggle.consumeClick()) MediaBridge.togglePlayback()
            while (mediaNext.consumeClick()) MediaBridge.next()
            while (mediaPrevious.consumeClick()) MediaBridge.previous()
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenKeyboardEvents.afterKeyPress(screen).register { current, keyEvent ->
                if (current.focused is EditBox) return@register
                if (toggleTooltips.matches(keyEvent)) TooltipHider.toggle()
            }
        }
    }
}
