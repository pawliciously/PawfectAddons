package dev.pawfect.addons.features.profile

import com.google.gson.JsonArray
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

    fun fetch(name: String): CompletableFuture<ProfileData> =
        CompletableFuture.supplyAsync({ load(name) }, executor)
            .thenApplyAsync({ it }, McCompat.mc)

    fun saveDescription(lines: List<String>): CompletableFuture<List<String>> =
        CompletableFuture.supplyAsync({ storeDescription(lines) }, executor)
            .thenApplyAsync({ it }, McCompat.mc)

    private fun load(name: String): ProfileData {
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
        val body = authorized { session ->
            HttpRequest.newBuilder(URI.create("$BASE/api/profile/$encoded"))
                .header("Authorization", "Bearer $session")
                .timeout(Duration.ofSeconds(30))
                .GET()
        }
        return ProfileData.parse(body)
    }

    private fun storeDescription(lines: List<String>): List<String> {
        val payload = JsonObject().apply {
            add("lines", JsonArray().apply { lines.forEach(::add) })
        }
        val body = authorized { session ->
            HttpRequest.newBuilder(URI.create("$BASE/api/description"))
                .header("Authorization", "Bearer $session")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .PUT(HttpRequest.BodyPublishers.ofString(payload.toString()))
        }
        return body.getAsJsonArray("lines")?.map { it.asString } ?: emptyList()
    }

    private fun authorized(build: (String) -> HttpRequest.Builder): JsonObject {
        fun send(): HttpResponse<String> {
            val request = build(session())
                .header("User-Agent", "PawfectAddons")
                .header("Accept", "application/json")
                .build()
            return client.send(request, HttpResponse.BodyHandlers.ofString())
        }

        var response = send()
        if (response.statusCode() == 401) {
            token = null
            response = send()
        }
        val body = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
        if (response.statusCode() != 200 || body == null) {
            val message = body?.get("message")?.asString ?: "The profile service returned HTTP ${response.statusCode()}."
            throw ProfileException(message)
        }
        return body
    }

    private fun session(): String {
        token?.let { if (System.currentTimeMillis() < tokenExpires) return it }

        val mc = McCompat.mc
        val user = mc.user ?: throw ProfileException("Not signed in to Minecraft.")
        if (user.accessToken.isEmpty()) throw ProfileException("Sign in with a Microsoft account to use the profile viewer.")

        val nonce = post("/api/link/begin", null).get("nonce")?.asString
            ?: throw ProfileException("The profile service did not answer.")

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
