package dev.pawfect.addons.features.cosmetics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Notifications
import dev.pawfect.addons.utils.McCompat
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

object CosmeticEnroll {

    private val logger = LoggerFactory.getLogger("PawfectAddons/CosmeticEnroll")

    private const val BASE = "https://me.pawfectaddons.net"
    private const val STARTUP_DELAY_MS = 30_000L
    private const val RETRY_MS = 600_000L

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    private val config get() = ConfigManager.features.cosmetics

    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var running = false

    private var nextAttempt = 0L

    fun onTick() {
        if (config.enrolledAt != 0L) return
        if (!config.enabled) return
        if (running) return

        val now = System.currentTimeMillis()
        if (now - startedAt < STARTUP_DELAY_MS) return
        if (now < nextAttempt) return

        val mc = McCompat.mc
        val user = mc.user ?: return
        if (user.accessToken.isEmpty()) return
        if (mc.connection != null && mc.player == null) return

        nextAttempt = now + RETRY_MS
        running = true

        Thread({
            try {
                enroll(user.profileId, user.accessToken, user.name)
            } catch (error: Exception) {
                logger.warn("Could not enroll for cosmetics.", error)
            } finally {
                running = false
            }
        }, "PawfectAddons Cosmetic Enroll").apply { isDaemon = true }.start()
    }

    private fun enroll(uuid: UUID, accessToken: String, username: String) {
        val nonce = begin() ?: return

        try {
            McCompat.mc.services().sessionService().joinServer(uuid, accessToken, nonce)
        } catch (error: Exception) {
            logger.warn("Mojang refused the session handshake.", error)
            return
        }

        val payload = JsonObject().apply {
            addProperty("username", username)
            addProperty("nonce", nonce)
        }

        val request = HttpRequest.newBuilder(URI.create("$BASE/api/enroll"))
            .header("User-Agent", "PawfectAddons")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warn("Enroll returned HTTP {}", response.statusCode())
            return
        }

        val body = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull() ?: return

        config.enrolledAt = System.currentTimeMillis()
        ConfigManager.save(ConfigFileType.FEATURES, "cosmetic enrollment")

        val awarded = runCatching { body.get("awarded").asBoolean }.getOrDefault(false)
        if (!awarded) return

        val slot = runCatching { body.get("slot").asInt }.getOrNull()
        Cosmetics.reload()
        Notifications.push(
            "Founder",
            if (slot != null) "You are founder number $slot. The paw is yours." else "The founder paw is yours.",
            Icons.USER,
        )
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
            return null
        }

        return runCatching {
            JsonParser.parseString(response.body()).asJsonObject.get("nonce").asString
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
