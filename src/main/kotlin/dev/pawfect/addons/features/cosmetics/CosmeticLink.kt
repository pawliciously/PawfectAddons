package dev.pawfect.addons.features.cosmetics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Notifications
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

object CosmeticLink {

    private val logger = LoggerFactory.getLogger("PawfectAddons/CosmeticLink")

    private const val BASE = "https://me.pawfectaddons.net"
    private const val COOLDOWN_MS = 5_000L

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    @Volatile
    private var running = false

    private var lastStart = 0L

    val busy: Boolean get() = running

    fun start() {
        if (running) return

        val now = System.currentTimeMillis()
        if (now - lastStart < COOLDOWN_MS) return

        val mc = McCompat.mc
        val user = mc.user
        if (user == null || user.accessToken.isEmpty()) {
            notify("Sign in to Minecraft before linking your account.")
            return
        }

        if (mc.connection != null && mc.player == null) {
            notify("Finish joining the server first, then try again.")
            return
        }

        lastStart = now
        running = true

        Thread({
            try {
                link(user.profileId, user.accessToken, user.name)
            } catch (error: Exception) {
                logger.warn("Could not link the cosmetics account.", error)
                notify("Could not reach pawfectaddons.net. Check your connection.")
            } finally {
                running = false
            }
        }, "PawfectAddons Cosmetic Link").apply { isDaemon = true }.start()
    }

    private fun link(uuid: UUID, accessToken: String, username: String) {
        val nonce = begin() ?: return

        try {
            McCompat.mc.services().sessionService().joinServer(uuid, accessToken, nonce)
        } catch (error: Exception) {
            logger.warn("Mojang refused the session handshake.", error)
            notify("Minecraft could not verify your session. Try again.")
            return
        }

        val url = complete(username, nonce) ?: return
        open(url)
    }

    private fun begin(): String? {
        val request = HttpRequest.newBuilder(URI.create("$BASE/api/link/begin"))
            .header("User-Agent", "PawfectAddons")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warn("Link begin returned HTTP {}", response.statusCode())
            notify("The cosmetics site is not answering. Try again shortly.")
            return null
        }

        val nonce = runCatching {
            JsonParser.parseString(response.body()).asJsonObject.get("nonce").asString
        }.getOrNull()

        if (nonce.isNullOrEmpty()) {
            notify("The cosmetics site sent an unexpected reply.")
            return null
        }

        return nonce
    }

    private fun complete(username: String, nonce: String): String? {
        val payload = JsonObject().apply {
            addProperty("username", username)
            addProperty("nonce", nonce)
        }

        val request = HttpRequest.newBuilder(URI.create("$BASE/api/link/complete"))
            .header("User-Agent", "PawfectAddons")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        when (response.statusCode()) {
            200 -> Unit
            401 -> {
                notify("Mojang did not confirm that account. Start again.")
                return null
            }
            503 -> {
                notify("Mojang is unavailable right now. Try again in a minute.")
                return null
            }
            else -> {
                logger.warn("Link complete returned HTTP {}", response.statusCode())
                notify("The cosmetics site is not answering. Try again shortly.")
                return null
            }
        }

        val url = runCatching {
            JsonParser.parseString(response.body()).asJsonObject.get("url").asString
        }.getOrNull()

        if (url.isNullOrEmpty()) {
            notify("The cosmetics site sent an unexpected reply.")
            return null
        }

        return url
    }

    private fun open(url: String) {
        val mc = McCompat.mc
        mc.execute {
            ConfirmLinkScreen.confirmLinkNow(mc.screen, URI.create(url))
        }
    }

    private fun notify(message: String) {
        Notifications.push("Cosmetics", message, Icons.USER)
    }
}
