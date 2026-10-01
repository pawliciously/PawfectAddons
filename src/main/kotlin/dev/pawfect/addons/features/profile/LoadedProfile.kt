package dev.pawfect.addons.features.profile

import dev.pawfect.addons.utils.McCompat
import net.minecraft.world.item.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** One profile with every container decoded, plus its networth once that has been worked out. */
class LoadedProfile private constructor(
    val profile: ProfileData.Profile,
    private val containers: Map<String, List<ItemStack>>,
    /** Backpack slot to contents, in slot order. */
    val backpacks: List<Pair<Int, List<ItemStack>>>,
    val backpackIcons: Map<Int, ItemStack>,
) {

    fun container(key: String): List<ItemStack> = containers[key] ?: emptyList()

    /** Pet items need NeuRepo, which lives on the render thread, so they are built on first use there. */
    val pets: List<ItemStack> by lazy { profile.pets.map(ProfileItems::pet) }

    @Volatile
    var networth: ProfileNetworth.Result? = null
        private set

    @Volatile
    var networthFailed = false
        private set

    companion object {
        private val worker = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "PawfectAddons Profile Decode").apply { isDaemon = true }
        }

        private val CONTAINERS = listOf("inventory", "armor", "equipment", "ender_chest", "vault", "accessories", "potions", "fishing_bag", "quiver")

        /** Decodes off-thread and completes on the render thread. Networth follows on its own. */
        fun load(profile: ProfileData.Profile): CompletableFuture<LoadedProfile> =
            CompletableFuture.supplyAsync({
                LoadedProfile(
                    profile,
                    CONTAINERS.associateWith { ProfileItems.decode(profile.inventories[it]) },
                    profile.backpacks.map { (slot, data) -> slot to ProfileItems.decode(data) },
                    profile.backpackIcons.mapNotNull { (slot, data) -> ProfileItems.decode(data).firstOrNull()?.let { slot to it } }.toMap(),
                )
            }, worker).thenApplyAsync({ loaded ->
                loaded.pets.size // build pet items now, on the render thread
                CompletableFuture.supplyAsync({ ProfileNetworth.compute(loaded) }, worker).whenComplete { result, error ->
                    if (result != null) loaded.networth = result else loaded.networthFailed = error != null
                }
                loaded
            }, McCompat.mc)
    }
}
