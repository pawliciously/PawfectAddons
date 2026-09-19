package dev.pawfect.addons.features.dungeon

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.roundToInt

object DungeonWaypointRenderer {

    var lastEvent = 0L
        private set
    var lastRoom = "none"
        private set
    var lastCount = 0
        private set
    var lastError = "none"
        private set

    private val config get() = ConfigManager.features.dungeonWaypoints

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

    private fun names(poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (!config.enabled) return
        if (!SkyBlockData.inDungeons) return
        val match = DungeonScanner.matchedRoom() ?: return
        val jobs = DungeonWaypoints.forRoom(match.name!!).filter { it.visible && it.showName }.map { match to it }
        if (jobs.isEmpty()) return

        val eye = McCompat.mc.gameRenderer.mainCamera.position()
        poseStack.pushPose()
        poseStack.translate(-eye.x, -eye.y, -eye.z)
        jobs.forEach { (room, waypoint) ->
            val world = DungeonScanner.relativeToActual(room, waypoint.x, waypoint.y, waypoint.z)
            val centre = Vec3(world.x + 0.5, world.y + 1.3, world.z + 0.5)
            val distance = centre.distanceToSqr(eye)
            collector.submitNameTag(
                poseStack,
                centre,
                0,
                Component.literal(waypoint.name),
                config.throughWalls,
                0xF000F0,
                distance,
                camera,
            )
        }
        poseStack.popPose()
    }

    private fun draw(poseStack: PoseStack, collector: SubmitNodeCollector) {
        lastEvent = System.currentTimeMillis()
        if (!config.enabled) {
            lastError = "disabled in settings"
            return
        }
        if (!SkyBlockData.inDungeons) {
            lastError = "not in a dungeon"
            lastRoom = "none"
            return
        }
        val match = DungeonScanner.matchedRoom()
        if (match == null) {
            lastError = "room not identified"
            lastRoom = "none"
            return
        }
        lastRoom = match.name ?: "?"
        val jobs = DungeonWaypoints.forRoom(match.name!!).filter { it.visible }.map { match to it }
        lastCount = jobs.size
        if (jobs.isEmpty()) {
            lastError = "no waypoints saved for the rooms around you"
            return
        }
        lastError = "drew ${jobs.size}"

        val camera = McCompat.mc.gameRenderer.mainCamera.position()
        val inflate = config.inflate.coerceIn(0f, 0.25f)
        val thickness = config.lineWidth.coerceIn(0.5f, 6f)
        val alpha = (config.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)

        collector.submitCustomGeometry(poseStack, WaypointRenderTypes.forMode(config.throughWalls)) { pose, consumer ->
            jobs.forEach { (room, waypoint) ->
                val world = DungeonScanner.relativeToActual(room, waypoint.x, waypoint.y, waypoint.z)
                val minX = world.x - inflate
                val minY = world.y - inflate
                val minZ = world.z - inflate
                val maxX = world.x + 1f + inflate
                val maxY = world.y + 1f + inflate
                val maxZ = world.z + 1f + inflate
                val color = (alpha shl 24) or (waypoint.color and 0xFFFFFF)
                box(pose, consumer, minX.toFloat(), minY.toFloat(), minZ.toFloat(), maxX.toFloat(), maxY.toFloat(), maxZ.toFloat(), color, thickness)
            }
        }

        poseStack.popPose()
    }

    private fun box(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float,
        color: Int,
        thickness: Float,
    ) {
        val corners = arrayOf(
            Vector3f(minX, minY, minZ), Vector3f(maxX, minY, minZ),
            Vector3f(maxX, minY, maxZ), Vector3f(minX, minY, maxZ),
            Vector3f(minX, maxY, minZ), Vector3f(maxX, maxY, minZ),
            Vector3f(maxX, maxY, maxZ), Vector3f(minX, maxY, maxZ),
        )
        EDGES.forEach { edge ->
            val start = corners[edge[0]]
            val end = corners[edge[1]]
            val normal = Vector3f(end).sub(start)
            if (normal.lengthSquared() < 1.0e-8f) return@forEach
            normal.normalize()
            consumer.addVertex(pose, start.x, start.y, start.z)
                .setColor(color)
                .setNormal(pose, normal.x, normal.y, normal.z)
                .setLineWidth(thickness)
            consumer.addVertex(pose, end.x, end.y, end.z)
                .setColor(color)
                .setNormal(pose, normal.x, normal.y, normal.z)
                .setLineWidth(thickness)
        }
    }
}
