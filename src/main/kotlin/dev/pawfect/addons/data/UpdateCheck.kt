package dev.pawfect.addons.data

import com.google.gson.JsonParser
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Notifications
import dev.pawfect.addons.utils.McCompat
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object UpdateCheck {

    private val logger = LoggerFactory.getLogger("PawfectAddons/UpdateCheck")

    private const val ENDPOINT = "https://pawfectaddons.net/v1/version.json"
    private const val NOTICE_MS = 30_000L
    private const val RECHECK_MS = 30 * 60 * 1000L

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    @Volatile
    private var lastCheck = 0L

    @Volatile
    private var pending: String? = null

    fun onTick() {
        flush()

        val now = System.currentTimeMillis()
        if (now - lastCheck < RECHECK_MS) return
        lastCheck = now

        Thread({
            try {
                check()
            } catch (e: Exception) {
                logger.warn("Could not check for a newer version.", e)
            }
        }, "PawfectAddons Update").apply { isDaemon = true }.start()
    }

    private fun flush() {
        val latest = pending ?: return
        if (McCompat.hideGui || McCompat.mc.screen != null) return
        pending = null

        Notifications.push(
            "PawfectAddons " + latest,
            "You are on " + PawfectAddons.VERSION + ". Update at pawfectaddons.net",
            Icons.PACKAGE,
            lifetime = NOTICE_MS,
        )
    }

    private fun check() {
        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .header("User-Agent", "PawfectAddons/" + PawfectAddons.VERSION)
            .timeout(Duration.ofSeconds(20))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warn("Version request returned HTTP {}", response.statusCode())
            return
        }

        val latest = JsonParser.parseString(response.body())
            .asJsonObject
            .get("version")
            ?.asString

        if (latest == null) {
            logger.warn("Version reply carried no version field.")
            return
        }

        val current = PawfectAddons.VERSION
        if (!isNewer(latest, current)) {
            logger.info("Running {}, which is the latest.", current)
            return
        }

        logger.info("Running {}, but {} is out.", current, latest)
        pending = latest
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val theirs = parts(remote)
        val ours = parts(local)

        for (i in 0 until maxOf(theirs.size, ours.size)) {
            val a = theirs.getOrNull(i) ?: 0
            val b = ours.getOrNull(i) ?: 0
            if (a != b) return a > b
        }

        return false
    }

    private fun parts(version: String): List<Int> =
        version.split(".").map { chunk ->
            chunk.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
}
