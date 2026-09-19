package dev.pawfect.addons.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object BazaarApi {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Bazaar")

    private const val ENDPOINT = "https://api.hypixel.net/v2/skyblock/bazaar"
    private val REFRESH_INTERVAL = Duration.ofMinutes(5)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    @Volatile
    private var instantBuyPrices: Map<String, Double> = emptyMap()

    @Volatile
    private var refreshing = false

    private var lastRefresh = 0L

    val isLoaded: Boolean get() = instantBuyPrices.isNotEmpty()

    fun instantBuyPrice(internalName: String): Double? {
        val prices = instantBuyPrices
        prices[internalName]?.let { return it }
        return prices[internalName.replace(';', '_')]
    }

    fun onTick() {
        if (refreshing) return
        if (System.currentTimeMillis() - lastRefresh < REFRESH_INTERVAL.toMillis()) return
        lastRefresh = System.currentTimeMillis()
        refreshAsync()
    }

    private fun refreshAsync() {
        refreshing = true
        Thread({
            try {
                refresh()
            } catch (e: Exception) {
                logger.warn("Could not refresh bazaar prices; keeping the previous snapshot.", e)
            } finally {
                refreshing = false
            }
        }, "PawfectAddons Bazaar").apply { isDaemon = true }.start()
    }

    private fun refresh() {
        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .header("User-Agent", "PawfectAddons")
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warn("Bazaar request returned HTTP {}", response.statusCode())
            return
        }

        val root = JsonParser.parseString(response.body()).asJsonObject
        if (root.get("success")?.asBoolean != true) {
            logger.warn("Bazaar request was unsuccessful.")
            return
        }

        val products: JsonObject = root.getAsJsonObject("products") ?: return
        val parsed = HashMap<String, Double>(products.size())

        for ((productId, element) in products.entrySet()) {
            val quickStatus = element.asJsonObject.getAsJsonObject("quick_status") ?: continue
            val buyPrice = quickStatus.get("buyPrice")?.asDouble ?: continue
            if (buyPrice > 0) parsed[productId] = buyPrice
        }

        instantBuyPrices = parsed
        logger.debug("Loaded {} bazaar prices.", parsed.size)
    }
}
