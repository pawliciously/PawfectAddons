package dev.pawfect.addons.features.discord

import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket

/** The only place that touches the Hypixel Mod API. Load it only when that mod is present. */
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
