package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import org.joml.Vector3f

object HealerBox {

    private const val MIN_X = 53f
    private const val MAX_X = 56f
    private const val MIN_Z = 113f
    private const val MAX_Z = 116f
    private const val Y = 64.02f
    private const val THICKNESS = 2f

    private val config get() = ConfigManager.features.visuals

    private val CORNERS = arrayOf(
        Vector3f(MIN_X, Y, MIN_Z),
        Vector3f(MAX_X, Y, MIN_Z),
        Vector3f(MAX_X, Y, MAX_Z),
        Vector3f(MIN_X, Y, MAX_Z),
    )

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching {
                if (!config.healerBox || !SkyBlockData.inFloor7) return@runCatching
                val poseStack = context.poseStack()
                val camera = McCompat.mc.gameRenderer.mainCamera.position()
                val color = (0xFF shl 24) or (config.healerBoxColor and 0xFFFFFF)

                poseStack.pushPose()
                poseStack.translate(-camera.x, -camera.y, -camera.z)
                context.submitNodeCollector().submitCustomGeometry(poseStack, WaypointRenderTypes.forMode(true)) { pose, consumer ->
                    for (i in CORNERS.indices) {
                        val start = CORNERS[i]
                        val end = CORNERS[(i + 1) % CORNERS.size]
                        val normal = Vector3f(end).sub(start).normalize()
                        consumer.addVertex(pose, start.x, start.y, start.z)
                            .setColor(color)
                            .setNormal(pose, normal.x, normal.y, normal.z)
                            .setLineWidth(THICKNESS)
                        consumer.addVertex(pose, end.x, end.y, end.z)
                            .setColor(color)
                            .setNormal(pose, normal.x, normal.y, normal.z)
                            .setLineWidth(THICKNESS)
                    }
                }
                poseStack.popPose()
            }
        }
    }
}
