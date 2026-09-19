package dev.pawfect.addons.features.experiments

import dev.pawfect.addons.config.ConfigManager
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack

object UltrasequencerSolver : ExperimentSolver(Regex("^Ultrasequencer \\(\\w+\\)$")) {

    private val config get() = ConfigManager.features.experiments

    private val NUMERIC = Regex("^\\d+$")

    private var nextSlot = 0

    override val displayName = "Ultrasequencer"

    override val maxRounds get() = config.ultrasequencerRounds

    override val enabled get() = config.ultrasequencer

    override fun reset() {
        nextSlot = 0
        super.reset()
    }

    override fun tick(container: Container) {
        when (state) {
            State.REMEMBER -> {
                if (nameOf(container, 49) != "Remember the pattern!") return
                for (index in 9 until 45) {
                    val stack = container.getItem(index)
                    val name = stack.hoverName.string
                    if (!NUMERIC.matches(name)) continue
                    if (name == "1") nextSlot = index
                    slots[index] = stack.copy()
                }
                if (slots.isNotEmpty()) {
                    round++
                    state = State.WAIT
                }
            }

            State.WAIT -> {
                if (nameOf(container, 49).startsWith("Timer: ")) state = State.SHOW
            }

            State.SHOW -> {
                if (!nameOf(container, 49).startsWith("Timer: ")) state = State.END
            }

            State.END -> {
                val name = nameOf(container, 49)
                if (name.startsWith("Timer: ")) return
                if (name == "Remember the pattern!") {
                    slots.clear()
                    nextSlot = 0
                    state = State.REMEMBER
                } else {
                    reset()
                }
            }
        }
    }

    override fun onClickSlot(slotId: Int, stack: ItemStack): Boolean {
        if (state != State.SHOW) return config.blockIncorrectClicks
        if (slotId != nextSlot) return config.blockIncorrectClicks
        val current = slots[nextSlot] ?: return false
        val wanted = current.count + 1
        slots.entries.firstOrNull { it.value.count == wanted }?.let { nextSlot = it.key }
        return false
    }

    override fun highlights(): List<Highlight> {
        if (state != State.SHOW || nextSlot == 0) return emptyList()
        val current = slots[nextSlot] ?: return emptyList()
        val result = ArrayList<Highlight>()
        result.add(Highlight(nextSlot, ExperimentPalette.NEXT, current.count, 1f))

        if (config.ghostUpcoming) {
            val depth = config.ghostCount.coerceIn(0, 5)
            for (step in 1..depth) {
                val wanted = current.count + step
                val entry = slots.entries.firstOrNull { it.value.count == wanted } ?: break
                result.add(
                    Highlight(
                        entry.key,
                        ExperimentPalette.UPCOMING,
                        wanted,
                        (0.45f - step * 0.1f).coerceAtLeast(0.15f),
                    ),
                )
            }
        }
        return result
    }

    override fun status(): String = when (state) {
        State.REMEMBER, State.WAIT -> "Watch the pattern"
        State.SHOW -> "Click ${slots[nextSlot]?.count ?: 1} of ${slots.size}"
        State.END -> "Round complete"
    }
}
