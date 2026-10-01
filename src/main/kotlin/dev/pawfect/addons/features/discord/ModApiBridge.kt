package dev.pawfect.addons.features.discord

import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket

internal object ModApiBridge {

    fun subscribe() {
        val api = HypixelModAPI.getInstance()
        api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
        api.createHandler(ClientboundLocationPacket::class.java) { packet ->
            val type = packet.serverType.orElse(null)
            HypixelLocation.update(
                type?.name(),
                type?.name,
                packet.lobbyName.orElse(null),
                packet.mode.orElse(null),
            )
        }
    }
}
