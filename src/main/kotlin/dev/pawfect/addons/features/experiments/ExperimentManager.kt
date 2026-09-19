package dev.pawfect.addons.features.experiments

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack

object ExperimentManager {

    private val config get() = ConfigManager.features.experiments

    private val solvers = listOf(ChronomatronSolver, UltrasequencerSolver, SuperpairsSolver)

    var current: ExperimentSolver? = null
        private set

    var tier: String = ""
        private set

    private var lastTitle: String = ""

    private val TITLE = Regex("^(Superpairs|Chronomatron|Ultrasequencer) \\((\\w+)\\)$")


    fun active(): Boolean = config.enabled && current != null

    fun container(): Container? = (McCompat.mc.screen as? ContainerScreen)?.menu?.container

    private fun screenTitle(): String = (McCompat.mc.screen as? ContainerScreen)?.title?.string ?: ""

    fun onTick() {
        if (!config.enabled || !SkyBlockData.onSkyBlock) {
            clear()
            return
        }
        val title = screenTitle()
        if (title != lastTitle) {
            lastTitle = title
            select(title)
        }
        val solver = current ?: return
        val container = container() ?: return
        runCatching { solver.tick(container) }
        runCatching { snapshot = buildLines() }
    }

    private fun select(title: String) {
        current?.reset()
        current = null
        tier = ""
        val match = TITLE.find(title) ?: return
        val solver = solvers.firstOrNull { it.titlePattern.matches(title) } ?: return
        if (!solver.enabled) return
        solver.reset()
        solver.start(title)
        tier = match.groupValues[2]
        current = solver
    }

    private fun clear() {
        current?.reset()
        current = null
        lastTitle = ""
        tier = ""
    }

    @JvmStatic
    fun onSlotChanged(containerId: Int, slotId: Int, stack: ItemStack) {
        if (!config.enabled) return
        val menu = (McCompat.mc.screen as? ContainerScreen)?.menu ?: return
        if (menu.containerId != containerId) return
        packetCount++
        val solver = current ?: return
        runCatching { solver.slotChanged(slotId, stack) }
    }

    @JvmStatic
    fun onClickSlot(slotId: Int, stack: ItemStack): Boolean {
        if (!config.enabled) return false
        val solver = current ?: return false
        return runCatching { solver.onClickSlot(slotId, stack) }.getOrDefault(false)
    }

    fun highlights(): List<Highlight> {
        val solver = current ?: return emptyList()
        return runCatching { solver.highlights() }.getOrDefault(emptyList())
    }

    private var packetCount = 0

    private var snapshot: List<String> = emptyList()

    private fun buildLines(): List<String> {
        val lines = ArrayList<String>()
        lines.add("Enabled: ${config.enabled}  onSkyBlock: ${SkyBlockData.onSkyBlock}")
        lines.add("Title: '${screenTitle()}'")
        lines.add("Solver: ${current?.displayName ?: "none"}  tier: ${if (tier.isEmpty()) "-" else tier}")
        lines.add("State: ${current?.state ?: "-"}  round: ${current?.round ?: 0}")
        lines.add("Container packets seen: $packetCount")
        lines.add("Highlights: ${highlights().size}")
        if (current === ChronomatronSolver) ChronomatronSolver.debugLines().forEach { lines.add(it) }
        return lines
    }

    fun describe(): List<String> {
        if (current != null) return buildLines().also { snapshot = it }
        if (snapshot.isNotEmpty()) return listOf("No menu open, showing the last session:") + snapshot
        return buildLines()
    }
}
