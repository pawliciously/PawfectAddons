package dev.pawfect.addons.features.experiments

import dev.pawfect.addons.config.ConfigManager
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object ChronomatronSolver : ExperimentSolver(Regex("^Chronomatron \\(\\w+\\)$")) {

    private val TERRACOTTA_TO_GLASS: Map<Item, Item> = mapOf(
        Items.RED_TERRACOTTA to Items.RED_STAINED_GLASS,
        Items.ORANGE_TERRACOTTA to Items.ORANGE_STAINED_GLASS,
        Items.YELLOW_TERRACOTTA to Items.YELLOW_STAINED_GLASS,
        Items.LIME_TERRACOTTA to Items.LIME_STAINED_GLASS,
        Items.GREEN_TERRACOTTA to Items.GREEN_STAINED_GLASS,
        Items.CYAN_TERRACOTTA to Items.CYAN_STAINED_GLASS,
        Items.LIGHT_BLUE_TERRACOTTA to Items.LIGHT_BLUE_STAINED_GLASS,
        Items.BLUE_TERRACOTTA to Items.BLUE_STAINED_GLASS,
        Items.PURPLE_TERRACOTTA to Items.PURPLE_STAINED_GLASS,
        Items.PINK_TERRACOTTA to Items.PINK_STAINED_GLASS,
    )

    // TODO only tested on the first few tiers, check the later ones
    private const val INSTRUCTION_SLOT = 49
    private const val LAST_CHEST_SLOT = 53

    private val PANES: Set<Item> = TERRACOTTA_TO_GLASS.keys + TERRACOTTA_TO_GLASS.values

    private val config get() = ConfigManager.features.experiments

    private val sequence = ArrayList<Item>()
    private var chainLengthCount = 0
    private var currentSlot = -1
    private var currentOrdinal = 0

    private val recent = ArrayDeque<String>()

    override val displayName = "Chronomatron"

    override val maxRounds get() = config.chronomatronRounds

    override val enabled get() = config.chronomatron

    override fun reset() {
        sequence.clear()
        chainLengthCount = 0
        currentSlot = -1
        currentOrdinal = 0
        super.reset()
    }

    private fun glinting(stack: ItemStack): Boolean =
        stack.hasFoil() ||
            stack.isEnchanted ||
            stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == true

    private fun isPane(stack: ItemStack): Boolean = PANES.contains(stack.item)

    private fun note(line: String) {
        recent.addLast(line)
        while (recent.size > 12) recent.removeFirst()
    }

    override fun slotChanged(slotId: Int, stack: ItemStack) {
        if (slotId > LAST_CHEST_SLOT) return
        if (slotId != INSTRUCTION_SLOT && !isPane(stack)) return

        if (slotId == INSTRUCTION_SLOT) {
            note("49 '${stack.hoverName.string}' state=$state")
        } else if (glinting(stack)) {
            note("$slotId ${stack.item} GLINT state=$state")
        }

        when (state) {
            State.REMEMBER -> {
                if (slotId == INSTRUCTION_SLOT) return
                if (currentSlot < 0) {
                    if (glinting(stack)) {
                        if (sequence.size <= chainLengthCount) {
                            sequence.add(TERRACOTTA_TO_GLASS[stack.item] ?: stack.item)
                            state = State.WAIT
                        } else {
                            chainLengthCount++
                        }
                        currentSlot = slotId
                    }
                } else if (currentSlot == slotId && !glinting(stack)) {
                    currentSlot = -1
                }
                round = sequence.size
            }

            State.WAIT -> {
                if (slotId == INSTRUCTION_SLOT && stack.hoverName.string.startsWith("Timer: ")) {
                    state = State.SHOW
                }
            }

            State.END -> {
                if (slotId != INSTRUCTION_SLOT) return
                val name = stack.hoverName.string
                if (name.startsWith("Timer: ")) return
                if (name == "Remember the pattern!") {
                    chainLengthCount = 0
                    currentOrdinal = 0
                    state = State.REMEMBER
                } else {
                    reset()
                }
            }

            else -> {}
        }
    }

    override fun onClickSlot(slotId: Int, stack: ItemStack): Boolean {
        if (state != State.SHOW) return false
        if (currentOrdinal >= sequence.size) return false
        return if (matches(stack, sequence[currentOrdinal])) {
            if (++currentOrdinal >= sequence.size) state = State.END
            false
        } else {
            config.blockIncorrectClicks
        }
    }

    private fun matches(stack: ItemStack, item: Item): Boolean =
        stack.`is`(item) || TERRACOTTA_TO_GLASS[stack.item] == item

    override fun highlights(): List<Highlight> {
        if (state != State.SHOW || currentOrdinal >= sequence.size) return emptyList()
        val menu = ExperimentManager.container() ?: return emptyList()
        val result = ArrayList<Highlight>()
        val depth = if (config.ghostUpcoming) config.ghostCount.coerceIn(0, 5) else 0

        for (step in 0..depth) {
            val index = currentOrdinal + step
            if (index >= sequence.size) break
            val item = sequence[index]
            val color = if (step == 0) ExperimentPalette.NEXT else ExperimentPalette.UPCOMING
            val strength = (if (step == 0) 1f else 0.45f - step * 0.1f).coerceAtLeast(0.15f)

            for (slotId in 0..LAST_CHEST_SLOT) {
                if (slotId == INSTRUCTION_SLOT) continue
                val stack = menu.getItem(slotId)
                if (stack.isEmpty || !isPane(stack) || !matches(stack, item)) continue
                result.add(Highlight(slotId, color, index + 1, strength))
            }
        }
        return result
    }

    fun debugLines(): List<String> = listOf(
        "sequence=${sequence.size} ordinal=$currentOrdinal chain=$chainLengthCount lit=$currentSlot",
    ) + recent.toList()

    override fun status(): String = when (state) {
        State.REMEMBER, State.WAIT -> "Watch the pattern"
        State.SHOW -> "Click ${currentOrdinal + 1} of ${sequence.size}"
        State.END -> "Round complete"
    }
}
