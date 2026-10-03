package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.dungeon.WaypointRenderTypes
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import org.joml.Vector3f

/**
 * Draws a line from the crosshair to every Nukekubi head, through walls, so you can tell which
 * way to turn to look at one. Any point straight ahead of the camera lands on the crosshair, so
 * starting the line there makes it come out of the middle of the screen.
 */
object NukekubiTracer {

    private val config get() = ConfigManager.features.slayers

    // The skull sits on the stand's head, about this far above its feet.
    private const val HEAD_HEIGHT = 1.75
    private const val START_AHEAD = 0.5f

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching { draw(context) }
        }
    }

    private fun draw(context: LevelRenderContext) {
        if (!config.nukekubiTracer) return
        val mc = McCompat.mc
        val level = mc.level ?: return
        val heads = level.entitiesForRendering().filter(NukekubiGlow::isNearbyHead)
        if (heads.isEmpty()) return

        val camera = mc.gameRenderer.mainCamera
        val eye = camera.position()
        val forward = Vector3f(camera.forwardVector())
        val start = Vector3f(forward).mul(START_AHEAD)
        val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val color = (0xFF shl 24) or (config.nukekubiTracerColor and 0xFFFFFF)
        val width = config.nukekubiTracerWidth.coerceIn(0.5f, 6f)

        // Drawn relative to the camera, so the numbers stay small and the line doesn't jitter.
        val poseStack = context.poseStack()
        poseStack.pushPose()
        context.submitNodeCollector().submitCustomGeometry(poseStack, WaypointRenderTypes.seeThrough) { pose, consumer ->
            for (head in heads) {
                val at = head.getPosition(partial)
                val end = Vector3f((at.x - eye.x).toFloat(), (at.y + HEAD_HEIGHT - eye.y).toFloat(), (at.z - eye.z).toFloat())
                val normal = Vector3f(end).sub(start)
                if (normal.lengthSquared() < 1.0e-6f) continue
                normal.normalize()
                consumer.addVertex(pose, start.x, start.y, start.z).setColor(color).setNormal(pose, normal.x, normal.y, normal.z).setLineWidth(width)
                consumer.addVertex(pose, end.x, end.y, end.z).setColor(color).setNormal(pose, normal.x, normal.y, normal.z).setLineWidth(width)
            }
        }
        poseStack.popPose()
    }
}
