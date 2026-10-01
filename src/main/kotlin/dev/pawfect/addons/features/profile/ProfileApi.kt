package dev.pawfect.addons.features.profile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pawfect.addons.utils.McCompat
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Fetches SkyBlock profiles through me.pawfectaddons.net. The Hypixel key lives on our
 * server, so the mod first proves who it is with the same handshake cosmetics use and
 * gets a session token back.
 */
object ProfileApi {

    private val logger = LoggerFactory.getLogger("PawfectAddons/ProfileApi")

    private const val BASE = "https://me.pawfectaddons.net"

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PawfectAddons Profile Viewer").apply { isDaemon = true }
    }

    class ProfileException(message: String) : Exception(message)

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpires = 0L

    /** Completes on the client thread. */
    fun fetch(name: String): CompletableFuture<ProfileData> =
        CompletableFuture.supplyAsync({ load(name) }, executor)
            .thenApplyAsync({ it }, McCompat.mc)

    private fun load(name: String): ProfileData {
        var response = request(name, session())
        if (response.statusCode() == 401) {
            // Our session ran out or was cleared on the server. Sign in again once.
            token = null
            response = request(name, session())
        }
        val body = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
        if (response.statusCode() != 200 || body == null) {
            val message = body?.get("message")?.asString ?: "The profile service returned HTTP ${response.statusCode()}."
            throw ProfileException(message)
        }
        return ProfileData.parse(body)
    }

    private fun request(name: String, session: String): HttpResponse<String> {
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("$BASE/api/profile/$encoded"))
            .header("User-Agent", "PawfectAddons")
            .header("Authorization", "Bearer $session")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun session(): String {
        token?.let { if (System.currentTimeMillis() < tokenExpires) return it }

        val mc = McCompat.mc
        val user = mc.user ?: throw ProfileException("Not signed in to Minecraft.")
        if (user.accessToken.isEmpty()) throw ProfileException("Sign in with a Microsoft account to use the profile viewer.")

        val nonce = post("/api/link/begin", null).get("nonce")?.asString
            ?: throw ProfileException("The profile service did not answer.")

        // Same as joining any server: the token goes to Mojang only, never to us.
        try {
            mc.services().sessionService().joinServer(user.profileId, user.accessToken, nonce)
        } catch (error: Exception) {
            logger.warn("Mojang refused the session handshake.", error)
            throw ProfileException("Mojang could not confirm your account. Try again in a moment.")
        }

        val payload = JsonObject().apply {
            addProperty("username", user.name)
            addProperty("nonce", nonce)
        }
        val reply = post("/api/session", payload)
        val issued = reply.get("token")?.asString ?: throw ProfileException("Sign in to the profile service failed.")
        token = issued
        // Renew well before the server's 7 day expiry.
        tokenExpires = System.currentTimeMillis() + Duration.ofDays(6).toMillis()
        return issued
    }

    private fun post(path: String, body: JsonObject?): JsonObject {
        val builder = HttpRequest.newBuilder(URI.create(BASE + path))
            .header("User-Agent", "PawfectAddons")
            .timeout(Duration.ofSeconds(20))
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody())
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
        if (response.statusCode() != 200 || json == null) {
            throw ProfileException(json?.get("message")?.asString ?: "The profile service returned HTTP ${response.statusCode()}.")
        }
        return json
    }
}
