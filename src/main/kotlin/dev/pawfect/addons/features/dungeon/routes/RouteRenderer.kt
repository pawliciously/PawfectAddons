package dev.pawfect.addons.features.dungeon.routes

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.features.SecretRoutesConfig
import dev.pawfect.addons.config.features.SecretRoutesConfig.Shown
import dev.pawfect.addons.features.dungeon.WaypointRenderTypes
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the route: a line along where to walk, and a marked box for each etherwarp, stonk,
 * superboom, lever and pearl on the way, ending at the step's secret. Steps after the current
 * one are drawn fainter.
 */
object RouteRenderer {

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.secretRoutes)

    private class Label(val text: Component, val at: Vec3) {
        constructor(text: String, at: Vec3) : this(Component.literal(text), at)
    }

    private val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
        intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    private const val FAINT = 0.4f
    private const val PEARL_LINE = 6.0

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context.poseStack(), context.submitNodeCollector(), context.levelState().cameraRenderState) }
        }
    }

    private fun shownSteps(state: SecretRoutes.Progress): List<Pair<RouteData.Step, Boolean>> {
        val from = state.index
        val until = when (config.shown) {
            Shown.CURRENT -> from + 1
            Shown.NEXT_TWO -> from + 2
            Shown.WHOLE -> state.route.size
        }.coerceAtMost(state.route.size)
        return (from until until).map { state.route[it] to (it == from) }
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val (room, state) = SecretRoutes.current() ?: return
        if (state.done) return
        val steps = shownSteps(state)
        if (steps.isEmpty()) return

        val eye = McCompat.mc.gameRenderer.mainCamera().position()
        val opacity = config.opacity.coerceIn(0.1f, 1f)
        val width = config.lineWidth.coerceIn(0.5f, 6f)
        val labels = ArrayList<Label>()
        val lines = config.style != SecretRoutesConfig.Style.BOXES
        boxesOn = config.style != SecretRoutesConfig.Style.LINES
        val start = if (state.index == 0) startPoint(room, state.route) else null
        if (start != null) {
            labels += Label(Component.literal("START").withStyle { it.withColor(config.pathColor).withBold(true) }, start.add(0.0, 1.0, 0.0))
        }

        poseStack.pushPose()
        poseStack.translate(-eye.x, -eye.y, -eye.z)
        collector.submitCustomGeometry(poseStack, WaypointRenderTypes.forMode(config.throughWalls)) { pose, consumer ->
            // Lines carry on from where the step before the first one shown ended.
            var previousEnd: Vec3? = state.route.getOrNull(state.index - 1)?.let { before ->
                before.locations.lastOrNull()?.let { centre(room, it) }
                    ?: before.secretAt?.let { block(room, it) }?.let { Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
            }
            // The block you start on.
            if (start != null) {
                val at = BlockPos.containing(start.x, start.y - 0.5, start.z)
                box(pose, consumer, at, ((opacity * 255f).roundToInt().coerceIn(0, 255) shl 24) or (config.etherwarpColor and 0xFFFFFF), width)
            }
            steps.forEachIndexed { i, (step, current) ->
                val alpha = opacity * if (current) 1f else FAINT
                fun color(rgb: Int) = ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
                val stepNumber = state.index + i + 1

                if (lines) {
                    val points = step.locations.map { centre(room, it) }
                    // Steps shown together join up where one ends and the next begins.
                    val joined = listOfNotNull(previousEnd) + points
                    joined.zipWithNext { a, b -> line(pose, consumer, a, b, color(config.pathColor), width) }
                    previousEnd = points.lastOrNull() ?: previousEnd
                }
                step.etherwarps.forEach { p ->
                    val pos = block(room, p)
                    box(pose, consumer, pos, color(config.etherwarpColor), width)
                    if (current) labels += Label("Etherwarp", top(pos))
                }
                step.teleports.forEach { p ->
                    // On the block you land on when there is one, otherwise where you end up.
                    val landing = block(room, p)
                    val under = landing.below()
                    val spot = if (McCompat.mc.level?.getBlockState(under)?.isAir == false) under else landing
                    box(pose, consumer, spot, color(config.etherwarpColor), width)
                    if (current) labels += Label("Teleport", top(spot))
                }
                step.mines.forEach { p -> box(pose, consumer, block(room, p), color(config.stonkColor), width) }
                // One label for each run of blocks mined together.
                if (current) clusters(step.mines).forEach { first -> labels += Label("Stonk", top(block(room, first))) }
                step.tnts.forEach { p ->
                    val pos = block(room, p)
                    box(pose, consumer, pos, color(config.superboomColor), width)
                    if (current) labels += Label("Superboom", top(pos))
                }
                step.interacts.forEach { p ->
                    val pos = block(room, p)
                    box(pose, consumer, pos, color(config.interactColor), width)
                    if (current) labels += Label("Interact", top(pos))
                }
                step.pearls.forEachIndexed { k, p ->
                    val at = DungeonScanner.relativeToActual(room, Vec3(p[0], p[1], p[2]))
                    small(pose, consumer, at, color(config.pearlColor), width)
                    val angle = step.pearlAngles.getOrNull(k)
                    if (angle != null) {
                        val from = at.add(0.0, 1.62, 0.0)
                        val dir = RouteGeometry.toActual(room, RouteGeometry.direction(angle[0], angle[1])).normalize()
                        line(pose, consumer, from, from.add(dir.scale(PEARL_LINE)), color(config.pearlColor), width)
                    }
                    if (current) labels += Label("Pearl", at.add(0.0, 0.6, 0.0))
                }
                // A step tied to one of our secrets is drawn on it: the route's own spot for an item
                // or bat is only where its author was standing.
                val secret = step.ours?.let { intArrayOf(it.x, it.y, it.z) } ?: step.secretAt
                if (secret != null && step.secretType != "dummy") {
                    val pos = block(room, secret)
                    val secretColor = if (step.secretType == "lever" || step.secretType == "plate") config.interactColor else config.secretColor
                    box(pose, consumer, pos, color(secretColor), width + 0.5f)
                    val name = "$stepNumber. ${describe(step)}"
                    labels += Label(if (step.extra) "$name (not in route)" else name, top(pos))
                    if (lines) {
                        // From the step's last point, or for a step with none, from where the one before ended.
                        val end = step.locations.lastOrNull()?.let { centre(room, it) } ?: previousEnd
                        val middle = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                        if (end != null) line(pose, consumer, end, middle, color(config.pathColor), width)
                        if (step.locations.isEmpty()) previousEnd = middle
                    }
                }
            }
        }
        if (config.labels) {
            labels.forEach { label ->
                collector.submitNameTag(poseStack, label.at, 0, label.text, config.throughWalls, 0xF000F0, camera)
            }
        }
        poseStack.popPose()
    }

    private fun describe(step: RouteData.Step): String = if (step.secretType == "plate") "Pressure Plate" else when (step.ours?.category) {
        "chest" -> "Chest"
        "wither" -> "Essence"
        "lever" -> "Lever"
        "redstone_key" -> "Redstone Key"
        "item" -> "Item"
        "bat" -> "Bat"
        else -> describe(step.secretType)
    }

    private fun describe(type: String?): String = when (type) {
        "item" -> "Item"
        "interact" -> "Interact"
        "bat" -> "Bat"
        "exit", "exitroute" -> "Exit"
        null -> "Go here"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** Where the route begins: its first walking point, or failing that its first spot of any kind. */
    private fun startPoint(room: DungeonRoom, route: List<RouteData.Step>): Vec3? {
        val first = route.firstOrNull() ?: return null
        first.locations.firstOrNull()?.let { return centre(room, it) }
        val p = first.start ?: return null
        val pos = block(room, p)
        return Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
    }

    /** The first block of each group of mined blocks that touch each other. */
    private fun clusters(mines: List<IntArray>): List<IntArray> {
        val firsts = ArrayList<IntArray>()
        var previous: IntArray? = null
        for (p in mines) {
            val last = previous
            val touching = last != null && abs(p[0] - last[0]) <= 1 && abs(p[1] - last[1]) <= 1 && abs(p[2] - last[2]) <= 1
            if (!touching) firsts += p
            previous = p
        }
        return firsts
    }

    private fun block(room: DungeonRoom, p: IntArray): BlockPos = DungeonScanner.relativeToActual(room, p[0], p[1], p[2])

    /** A walking point: the middle of the block you stand in, just above the floor. */
    private fun centre(room: DungeonRoom, p: IntArray): Vec3 {
        val pos = block(room, p)
        return Vec3(pos.x + 0.5, pos.y + 0.1, pos.z + 0.5)
    }

    private fun top(pos: BlockPos): Vec3 = Vec3(pos.x + 0.5, pos.y + 1.3, pos.z + 0.5)

    private fun line(pose: PoseStack.Pose, consumer: VertexConsumer, a: Vec3, b: Vec3, color: Int, width: Float) {
        val normal = Vector3f((b.x - a.x).toFloat(), (b.y - a.y).toFloat(), (b.z - a.z).toFloat())
        if (normal.lengthSquared() < 1.0e-8f) return
        normal.normalize()
        consumer.addVertex(pose, a.x.toFloat(), a.y.toFloat(), a.z.toFloat()).setColor(color).setNormal(pose, normal.x, normal.y, normal.z).setLineWidth(width)
        consumer.addVertex(pose, b.x.toFloat(), b.y.toFloat(), b.z.toFloat()).setColor(color).setNormal(pose, normal.x, normal.y, normal.z).setLineWidth(width)
    }

    /** Whether boxes are drawn this frame, from the route style. */
    private var boxesOn = true

    private fun box(pose: PoseStack.Pose, consumer: VertexConsumer, pos: BlockPos, color: Int, width: Float) {
        if (boxesOn) cube(pose, consumer, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), 1.0, color, width)
    }

    private fun small(pose: PoseStack.Pose, consumer: VertexConsumer, at: Vec3, color: Int, width: Float) {
        if (boxesOn) cube(pose, consumer, at.x - 0.2, at.y, at.z - 0.2, 0.4, color, width)
    }

    private fun cube(pose: PoseStack.Pose, consumer: VertexConsumer, x: Double, y: Double, z: Double, size: Double, color: Int, width: Float) {
        val corners = arrayOf(
            Vec3(x, y, z), Vec3(x + size, y, z), Vec3(x + size, y, z + size), Vec3(x, y, z + size),
            Vec3(x, y + size, z), Vec3(x + size, y + size, z), Vec3(x + size, y + size, z + size), Vec3(x, y + size, z + size),
        )
        EDGES.forEach { edge -> line(pose, consumer, corners[edge[0]], corners[edge[1]], color, width) }
    }
}
