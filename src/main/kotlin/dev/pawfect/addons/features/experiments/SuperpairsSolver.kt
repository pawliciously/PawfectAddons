package dev.pawfect.addons.features.experiments

import dev.pawfect.addons.config.ConfigManager
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object SuperpairsSolver : ExperimentSolver(Regex("^Superpairs \\(\\w+\\)$")) {

    private val config get() = ConfigManager.features.experiments

    private var prevClickedSlot = 0
    private var currentStack: ItemStack = ItemStack.EMPTY
    private val duplicated = HashSet<Int>()

    override val displayName = "Superpairs"

    override val maxRounds = 0

    override val enabled get() = config.superpairs

    override fun start(title: String) {
        super.start(title)
        state = State.SHOW
    }

    override fun reset() {
        prevClickedSlot = 0
        currentStack = ItemStack.EMPTY
        duplicated.clear()
        super.reset()
        state = State.SHOW
    }

    override fun tick(container: Container) {
        if (state != State.SHOW) return
        if (slots[prevClickedSlot] != null) return
        val stack = container.getItem(prevClickedSlot)
        if (stack.isEmpty) return
        if (stack.`is`(Items.CYAN_STAINED_GLASS) || stack.`is`(Items.BLACK_STAINED_GLASS_PANE)) return

        slots.entries.firstOrNull { ItemStack.matches(it.value, stack) }?.let {
            duplicated.add(it.key)
            duplicated.add(prevClickedSlot)
        }
        slots[prevClickedSlot] = stack.copy()
        currentStack = stack.copy()
    }

    override fun onClickSlot(slotId: Int, stack: ItemStack): Boolean {
        if (state == State.SHOW) {
            prevClickedSlot = slotId
            currentStack = ItemStack.EMPTY
        }
        return false
    }

    fun remembered(slotId: Int): ItemStack? = slots[slotId]

    override fun highlights(): List<Highlight> {
        if (state != State.SHOW) return emptyList()
        val container = ExperimentManager.container() ?: return emptyList()
        val result = ArrayList<Highlight>()
        val waiting = container.getItem(49).hoverName.string == "Click a second button!"

        for ((slotId, known) in slots) {
            val shown = container.getItem(slotId)
            if (ItemStack.matches(known, shown)) continue
            val color = when {
                waiting && ItemStack.matches(currentStack, known) -> ExperimentPalette.NEXT
                duplicated.contains(slotId) -> ExperimentPalette.DUPLICATE
                else -> ExperimentPalette.KNOWN
            }
            val strength = if (color == ExperimentPalette.NEXT) 1f else 0.7f
            result.add(Highlight(slotId, color, -1, strength))
        }
        return result
    }

    override fun status(): String = "${slots.size} remembered"
}
