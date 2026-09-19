package dev.pawfect.addons.features.dungeon.secrets

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.dungeon.WaypointRenderTypes
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.roundToInt

object SecretWaypointRenderer {

    private val config get() = ConfigManager.features.secretWaypoints

    private val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
        intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            runCatching {
                draw(context.poseStack(), context.submitNodeCollector())
                if (config.showNames) {
                    runCatching {
                        names(context.poseStack(), context.submitNodeCollector(), context.levelState().cameraRenderState)
                    }
                }
            }
        }
    }

    private fun active(): List<Pair<SecretRoomData.SecretEntry, net.minecraft.core.BlockPos>> {
        if (!config.enabled) return emptyList()
        val match = DungeonScanner.matchedRoom() ?: return emptyList()
        SecretTracker.syncRoom()
        return SecretRoomData.secretsFor(match.name!!)
            .filter { config.style(it.category).enabled }
            .filter { !config.hideClaimed || !SecretTracker.isClaimed(it) }
            .map { it to DungeonScanner.relativeToActual(match, it.x, it.y, it.z) }
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        val entries = active()
        if (entries.isEmpty()) return

        val camera = McCompat.mc.gameRenderer.mainCamera.position()
        val alpha = (config.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)
        collector.submitCustomGeometry(poseStack, WaypointRenderTypes.forMode(config.throughWalls)) { pose, consumer ->
            entries.forEach { (secret, pos) ->
                val style = config.style(secret.category)
                val thickness = (if (style.lineWidth > 0f) style.lineWidth else config.lineWidth).coerceIn(0.5f, 6f)
                val color = (alpha shl 24) or (style.color and 0xFFFFFF)
                box(pose, consumer, pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat(), color, thickness)
            }
        }
        poseStack.popPose()
    }

    private fun names(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: net.minecraft.client.renderer.state.level.CameraRenderState,
    ) {
        val entries = active()
        if (entries.isEmpty()) return

        val eye = McCompat.mc.gameRenderer.mainCamera.position()
        poseStack.pushPose()
        poseStack.translate(-eye.x, -eye.y, -eye.z)
        entries.forEach { (secret, pos) ->
            val centre = Vec3(pos.x + 0.5, pos.y + 1.3, pos.z + 0.5)
            collector.submitNameTag(
                poseStack,
                centre,
                0,
                Component.literal(secret.secretName),
                config.throughWalls,
                0xF000F0,
                centre.distanceToSqr(eye),
                camera,
            )
        }
        poseStack.popPose()
    }

    private fun box(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        color: Int,
        thickness: Float,
    ) {
        val corners = arrayOf(
            Vector3f(x, y, z), Vector3f(x + 1f, y, z),
            Vector3f(x + 1f, y, z + 1f), Vector3f(x, y, z + 1f),
            Vector3f(x, y + 1f, z), Vector3f(x + 1f, y + 1f, z),
            Vector3f(x + 1f, y + 1f, z + 1f), Vector3f(x, y + 1f, z + 1f),
        )
        EDGES.forEach { edge ->
            val start = corners[edge[0]]
            val end = corners[edge[1]]
            val normal = Vector3f(end).sub(start)
            if (normal.lengthSquared() < 1.0e-8f) return@forEach
            normal.normalize()
            consumer.addVertex(pose, start.x, start.y, start.z)
                .setColor(color).setNormal(pose, normal.x, normal.y, normal.z).setLineWidth(thickness)
            consumer.addVertex(pose, end.x, end.y, end.z)
                .setColor(color).setNormal(pose, normal.x, normal.y, normal.z).setLineWidth(thickness)
        }
    }
}
