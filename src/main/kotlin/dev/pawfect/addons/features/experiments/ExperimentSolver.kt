package dev.pawfect.addons.features.experiments

import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack

class Highlight(val slot: Int, val color: Int, val order: Int = -1, val strength: Float = 1f)

abstract class ExperimentSolver(val titlePattern: Regex) {

    enum class State { REMEMBER, WAIT, SHOW, END }

    var state: State = State.REMEMBER

    val slots = HashMap<Int, ItemStack>()

    var round: Int = 0
        protected set

    abstract val displayName: String

    abstract val maxRounds: Int

    open val enabled: Boolean get() = true

    open fun start(title: String) {
        state = State.REMEMBER
    }

    open fun reset() {
        state = State.REMEMBER
        slots.clear()
        round = 0
    }

    open fun tick(container: Container) {}

    open fun slotChanged(slotId: Int, stack: ItemStack) {}

    open fun onClickSlot(slotId: Int, stack: ItemStack): Boolean = false

    abstract fun highlights(): List<Highlight>

    open fun status(): String = ""

    protected fun nameOf(container: Container, slot: Int): String =
        container.getItem(slot).hoverName.string
}
