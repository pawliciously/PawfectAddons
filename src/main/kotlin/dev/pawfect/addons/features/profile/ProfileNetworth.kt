package dev.pawfect.addons.features.profile

import com.google.gson.JsonParser
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.objects.Object2ObjectMap
import net.azureaaron.networth.ItemCalculator
import net.azureaaron.networth.PetCalculator
import net.azureaaron.networth.data.ModifierValues
import net.azureaaron.networth.data.SkyblockItemData
import net.azureaaron.networth.item.ItemMetadataRetriever
import net.azureaaron.networth.item.PetInfo
import net.azureaaron.networth.item.SkyblockItemStack
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.IdentityHashMap
import java.util.Optional

/**
 * Networth the way Skyblocker and Aaron's Mod do it: AzureAaron's networth-calculator
 * (a port of SkyHelper's maths) valuing each item, fed by SkyHelper's public price list
 * and Hypixel's public item metadata. Neither source needs an API key.
 */
object ProfileNetworth {

    private val logger = LoggerFactory.getLogger("PawfectAddons/Networth")

    private const val PRICES = "https://raw.githubusercontent.com/SkyHelperBot/Prices/main/pricesV2.json"
    private const val ITEMS = "https://api.hypixel.net/v2/resources/skyblock/items"
    private val TTL = Duration.ofMinutes(15).toMillis()

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    private class Data(val prices: Map<String, Double>, val items: Object2ObjectMap<String, SkyblockItemData>, val at: Long)

    @Volatile
    private var data: Data? = null

    class Category(val label: String, val value: Double)

    class Result(
        val total: Double,
        val categories: List<Category>,
        /** Value of each decoded stack, keyed by identity, for tooltips. */
        val values: IdentityHashMap<ItemStack, Double>,
        val petValues: Map<Int, Double>,
    )

    /** Blocking; run it on a worker thread. */
    fun compute(loaded: LoadedProfile): Result {
        val d = ensureData()
        val price: (String) -> Double = { id -> d.prices[id] ?: 0.0 }
        val values = IdentityHashMap<ItemStack, Double>()

        fun sum(stacks: List<ItemStack>): Double = stacks.sumOf { stack ->
            if (stack.isEmpty) return@sumOf 0.0
            val value = itemValue(stack, price, d.items)
            if (value > 0) values[stack] = value
            value
        }

        val p = loaded.profile
        val categories = mutableListOf<Category>()
        categories += Category("Purse", p.purse)
        p.bank?.let { categories += Category("Bank", it) }
        categories += Category("Inventory", sum(loaded.container("inventory")) + sum(loaded.container("armor")) + sum(loaded.container("equipment")))
        categories += Category("Ender chest", sum(loaded.container("ender_chest")))
        categories += Category("Backpacks", loaded.backpacks.sumOf { sum(it.second) })
        categories += Category("Personal vault", sum(loaded.container("vault")))
        categories += Category("Accessories", sum(loaded.container("accessories")))
        categories += Category("Bags", sum(loaded.container("potions")) + sum(loaded.container("fishing_bag")) + sum(loaded.container("quiver")))

        val petValues = HashMap<Int, Double>()
        val pets = p.pets.withIndex().sumOf { (index, pet) ->
            val info = PetInfo(pet.type, pet.exp, pet.tier, 0, Optional.ofNullable(pet.heldItem), Optional.ofNullable(pet.skin))
            val value = runCatching { PetCalculator.calculate(info, price, ModifierValues.DEFAULT).price() }.getOrDefault(0.0)
            if (value > 0) petValues[index] = value
            value
        }
        categories += Category("Pets", pets)

        if (p.sacks.isNotEmpty()) categories += Category("Sacks", p.sacks.entries.sumOf { (id, count) -> price(id) * count })
        if (p.essence.isNotEmpty()) categories += Category("Essence", p.essence.entries.sumOf { (type, count) -> price("ESSENCE_$type") * count })

        val shown = categories.filter { it.value > 0 }.sortedByDescending { it.value }
        return Result(shown.sumOf { it.value }, shown, values, petValues)
    }

    private fun itemValue(stack: ItemStack, price: (String) -> Double, items: Object2ObjectMap<String, SkyblockItemData>): Double {
        val id = ProfileItems.skyblockId(stack).ifEmpty { return 0.0 }
        return try {
            val custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            val sbStack = SkyblockItemStack.of(id, stack.count, Dynamic<Tag>(NbtOps.INSTANCE, custom), Retriever.of(custom, id))
            ItemCalculator.calculate(sbStack, price, items).price()
        } catch (_: Exception) {
            0.0
        }
    }

    private fun ensureData(): Data {
        data?.let { if (System.currentTimeMillis() - it.at < TTL) return it }
        return try {
            val prices = HashMap<String, Double>()
            JsonParser.parseString(get(PRICES)).asJsonObject.entrySet().forEach { (k, v) ->
                runCatching { prices[k] = v.asDouble }
            }
            val items = SkyblockItemData.MAP_CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(get(ITEMS)).asJsonObject.getAsJsonArray("items"))
                .getOrThrow()
            Data(prices, items, System.currentTimeMillis()).also { data = it }
        } catch (error: Exception) {
            logger.warn("Could not load networth prices.", error)
            data ?: throw error
        }
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "PawfectAddons")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("$url returned HTTP ${response.statusCode()}")
        return response.body()
    }

    /** New Year Cake Bags are worth whatever cake years they hold, which lives in nested NBT. */
    private class Retriever(private val years: IntList) : ItemMetadataRetriever {
        override fun cakeBagCakeYears(): IntList = years

        companion object {
            fun of(custom: CompoundTag, id: String): Retriever {
                if (id != "NEW_YEAR_CAKE_BAG" || !custom.contains("new_year_cake_bag_data")) return Retriever(IntList.of())
                return runCatching {
                    val bytes = custom.getByteArray("new_year_cake_bag_data").orElse(ByteArray(0))
                    val inner = NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap())
                    val list = inner.getListOrEmpty("i")
                    val years = IntArrayList()
                    for (i in 0 until list.size) {
                        val extra = list.getCompoundOrEmpty(i).getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes")
                        if (!extra.isEmpty) years.add(extra.getIntOr("new_years_cake", 0))
                    }
                    Retriever(years)
                }.getOrDefault(Retriever(IntList.of()))
            }
        }
    }
}
