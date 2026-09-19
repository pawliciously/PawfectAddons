package dev.pawfect.addons.features.recipetracker

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.recipetracker.RecipeTracker.Ingredient
import dev.pawfect.addons.utils.NumberUtil.addSeparators
import dev.pawfect.addons.utils.NumberUtil.shortFormat
import dev.pawfect.addons.utils.renderables.ColumnRenderable
import dev.pawfect.addons.utils.renderables.HorizontalAlignment
import dev.pawfect.addons.utils.renderables.ItemRenderable
import dev.pawfect.addons.utils.renderables.Renderable
import dev.pawfect.addons.utils.renderables.SpacerRenderable
import dev.pawfect.addons.utils.renderables.TableRenderable
import dev.pawfect.addons.utils.renderables.TextRenderable

object RecipeTrackerOverlay {

    private const val REBUILD_INTERVAL_TICKS = 20

    private const val COMPLETE_MARK = "§a§l✔"

    private val config get() = ConfigManager.features.recipeTracker

    private var display: Renderable? = null
    private var tickCounter = 0

    fun onTick() {
        if (++tickCounter < REBUILD_INTERVAL_TICKS) return
        tickCounter = 0
        rebuild()
    }

    fun invalidate() = rebuild()

    fun render() {
        if (!config.enabled) return
        if (!SkyBlockData.onSkyBlock) return
        if (!RecipeTracker.overlayVisible) return
        val current = display ?: return
        config.position.render(current, "Recipe Tracker")
    }

    private fun rebuild() {
        if (!config.enabled || !SkyBlockData.onSkyBlock || !RecipeTracker.overlayVisible) {
            display = null
            return
        }

        if (RecipeTracker.goals().isEmpty()) {
            display = null
            return
        }

        if (!NeuRepo.isLoaded) {
            display = ColumnRenderable(
                listOf(
                    TextRenderable("§dRecipe Tracker"),
                    TextRenderable("§7Loading item data..."),
                ),
                spacing = config.lineSpacing,
            )
            return
        }

        val view = RecipeTracker.buildView()
        val sections = ArrayList<Renderable>()

        sections.add(TextRenderable(buildTitle(view)))
        for (goal in view.goals) {
            sections.add(
                TextRenderable(
                    " §8- ${RecipeTracker.displayName(goal)}§8 x${goal.amount.addSeparators()}",
                ),
            )
        }

        val rows = view.ingredients
            .filterNot { config.hideCompleted && it.isComplete }
            .map(::buildRow)

        if (rows.isEmpty()) {
            sections.add(TextRenderable("§a§lEverything is ready to craft!"))
        } else {
            sections.add(
                TableRenderable(
                    rows = rows,
                    columnSpacing = 4,
                    rowSpacing = config.lineSpacing,
                    columnAlignments = listOf(
                        HorizontalAlignment.LEFT,
                        HorizontalAlignment.LEFT,
                        HorizontalAlignment.RIGHT,
                        HorizontalAlignment.RIGHT,
                        HorizontalAlignment.RIGHT,
                    ),
                ),
            )
        }

        if (view.anyWithoutRecipe) {
            sections.add(TextRenderable("§8Some tracked items have no known crafting recipe."))
        }

        display = ColumnRenderable(sections, spacing = config.lineSpacing + 1)
    }

    private fun buildTitle(view: RecipeTracker.View): String {
        val title = StringBuilder("§d§lRecipe Tracker")
        if (view.ingredients.isNotEmpty()) {
            title.append(" §8(").append(view.completeCount).append('/').append(view.ingredients.size).append(')')
        }
        if (config.showCost) {
            val cost = view.totalCostToFinish
            if (cost > 0) title.append(" §7[§6").append(cost.shortFormat()).append("§7]")
        }
        return title.toString()
    }

    private fun buildRow(ingredient: Ingredient): List<Renderable> {
        val color = colorFor(ingredient)
        val name = NeuRepo.displayName(ingredient.itemId)

        val icon: Renderable = if (config.showIcons) {
            val stack = NeuRepo.itemStack(ingredient.itemId)
            if (stack.isEmpty) SpacerRenderable(ICON_SIZE, ICON_SIZE)
            else ItemRenderable(stack, ICON_SCALE)
        } else {
            SpacerRenderable(0, LINE_HEIGHT)
        }

        val ownedText = if (ingredient.isUnknown) "?" else ingredient.owned!!.addSeparators()
        val amounts = "$color$ownedText§7/§b${ingredient.needed.addSeparators()}"

        val progress = when {
            !config.showPercentage -> ""
            ingredient.isUnknown -> "§8?"
            ingredient.isComplete -> COMPLETE_MARK
            else -> "$color${ingredient.percent.toInt()}%"
        }

        val cost = when {
            !config.showCost -> ""
            ingredient.costToFinish == null || ingredient.costToFinish <= 0.0 -> ""
            else -> "§7(§6${ingredient.costToFinish.shortFormat()}§7)"
        }

        return listOf(
            icon,
            TextRenderable("  $color$name"),
            TextRenderable(amounts),
            TextRenderable(progress),
            TextRenderable(cost),
        )
    }

    private fun colorFor(ingredient: Ingredient): String = when {
        ingredient.isUnknown -> "§8"
        ingredient.isOverfilled -> "§a§l"
        ingredient.isComplete -> "§a"
        ingredient.percent < 50.0 -> "§c"
        else -> "§e"
    }

    private const val ICON_SIZE = 10
    private const val ICON_SCALE = 0.625
    private const val LINE_HEIGHT = 9
}
