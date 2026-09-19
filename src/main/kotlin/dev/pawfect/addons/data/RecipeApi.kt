package dev.pawfect.addons.data

object RecipeApi {

    class Recipe(
        val ingredients: Map<String, Int>,
        val hasRecipe: Boolean,
    )

    fun directIngredients(itemId: String, amount: Int): Recipe {
        val recipe = NeuRepo.item(itemId)?.recipe.orEmpty()
        if (recipe.isEmpty()) return Recipe(mapOf(itemId to amount), hasRecipe = false)
        return Recipe(recipe.mapValues { (_, perCraft) -> perCraft * amount }, hasRecipe = true)
    }
}
