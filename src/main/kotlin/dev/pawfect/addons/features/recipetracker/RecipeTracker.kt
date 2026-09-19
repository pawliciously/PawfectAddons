package dev.pawfect.addons.features.recipetracker

import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.TrackedGoal
import dev.pawfect.addons.config.TrackerStorage
import dev.pawfect.addons.data.BazaarApi
import dev.pawfect.addons.data.ItemSources
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.data.RecipeApi
import dev.pawfect.addons.data.SkyBlockData

object RecipeTracker {

    class Ingredient(
        val itemId: String,
        val needed: Int,
        val owned: Int?,
        val fromSacks: Int,
        val fromInventory: Int,
        val fromStorage: Int,
        val costToFinish: Double?,
    ) {
        val isComplete: Boolean get() = owned != null && owned >= needed
        val isUnknown: Boolean get() = owned == null
        val isOverfilled: Boolean get() = owned != null && owned > needed
        val percent: Double get() = if (needed <= 0) 100.0 else (owned ?: 0) * 100.0 / needed
        val remaining: Int get() = (needed - (owned ?: 0)).coerceAtLeast(0)
    }

    class View(
        val goals: List<TrackedGoal>,
        val ingredients: List<Ingredient>,
        val anyWithoutRecipe: Boolean,
    ) {
        val totalCostToFinish: Double get() = ingredients.sumOf { it.costToFinish ?: 0.0 }
        val completeCount: Int get() = ingredients.count { it.isComplete }
    }

    private fun profileGoals(): TrackerStorage.ProfileGoals = ConfigManager.tracker.players
        .getOrPut(SkyBlockData.playerUuid) { TrackerStorage.PlayerGoals() }
        .profiles
        .getOrPut(SkyBlockData.currentProfile) { TrackerStorage.ProfileGoals() }

    fun goals(): List<TrackedGoal> = profileGoals().goals.toList()

    var overlayVisible: Boolean
        get() = profileGoals().overlayVisible
        set(value) {
            profileGoals().overlayVisible = value
            save()
        }

    fun add(itemId: String, amount: Int): Int {
        val profile = profileGoals()
        profile.overlayVisible = true
        val goals = profile.goals
        val existing = goals.firstOrNull { it.itemId == itemId }
        val result = if (existing != null) {
            existing.amount += amount
            existing.amount
        } else {
            goals.add(TrackedGoal(itemId, amount))
            amount
        }
        save()
        return result
    }

    fun remove(itemId: String): Boolean {
        val removed = profileGoals().goals.removeIf { it.itemId == itemId }
        if (removed) save()
        return removed
    }

    fun clear(): Int {
        val goals = profileGoals().goals
        val count = goals.size
        goals.clear()
        save()
        return count
    }

    private fun save() = ConfigManager.save(ConfigFileType.TRACKER, "recipe tracker changed")

    fun buildView(): View {
        val goals = goals()
        val totals = LinkedHashMap<String, Int>()
        var anyWithoutRecipe = false

        for (goal in goals) {
            val recipe = RecipeApi.directIngredients(goal.itemId, goal.amount)
            if (!recipe.hasRecipe) anyWithoutRecipe = true
            for ((itemId, needed) in recipe.ingredients) {
                totals[itemId] = (totals[itemId] ?: 0) + needed
            }
        }

        val ingredients = totals.map { (itemId, needed) ->
            val owned = ItemSources.owned(itemId)
            val amount = owned.amountOrNull
            val remaining = (needed - (amount ?: 0)).coerceAtLeast(0)
            val unitPrice = BazaarApi.instantBuyPrice(itemId)
            Ingredient(
                itemId = itemId,
                needed = needed,
                owned = amount,
                fromSacks = owned.sacks ?: 0,
                fromInventory = owned.inventory,
                fromStorage = owned.storage,
                costToFinish = if (unitPrice != null && remaining > 0) unitPrice * remaining else null,
            )
        }.sortedWith(compareBy<Ingredient> { it.isComplete }.thenBy { it.percent })

        return View(goals, ingredients, anyWithoutRecipe)
    }

    fun displayName(goal: TrackedGoal): String = NeuRepo.displayName(goal.itemId)
}
