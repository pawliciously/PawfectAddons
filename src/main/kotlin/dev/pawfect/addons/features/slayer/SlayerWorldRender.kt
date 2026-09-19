package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.SlayersConfig.SlayerDisplay
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.gui.Font
import net.minecraft.util.LightCoordsUtil
import org.joml.Matrix4f

object SlayerWorldRender {

    private const val TEXT_SCALE = 0.025f
    private const val LINE_HEIGHT = 10f
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val config get() = ConfigManager.features.slayers

    fun register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register { context ->
            if (!config.enabled) return@register
            if (config.display == SlayerDisplay.HUD) return@register
            if (!SkyBlockData.onSkyBlock) return@register

            val boss = SlayerManager.boss ?: return@register
            if (!boss.alive) return@register

            val camera = McCompat.mc.gameRenderer.mainCamera
            if (!camera.isInitialized) return@register

            val cameraPos = camera.position()
            val target = boss.position
            val poseStack = context.poseStack()
            val scale = TEXT_SCALE * config.worldScale.coerceIn(0.1f, 5f)

            poseStack.pushPose()
            poseStack.translate(
                (target.x - cameraPos.x).toFloat(),
                (target.y + config.worldOffset - cameraPos.y).toFloat(),
                (target.z - cameraPos.z).toFloat(),
            )
            poseStack.mulPose(camera.rotation())
            poseStack.scale(scale, -scale, scale)

            val matrix = Matrix4f(poseStack.last().pose())
            poseStack.popPose()

            val font = McCompat.font
            val bufferSource = McCompat.mc.renderBuffers().bufferSource()
            val lines = SlayerFormat.lines(boss)

            for ((index, line) in lines.withIndex()) {
                font.drawInBatch(
                    line,
                    -font.width(line) / 2f,
                    index * LINE_HEIGHT,
                    WHITE,
                    false,
                    matrix,
                    bufferSource,
                    Font.DisplayMode.SEE_THROUGH,
                    0,
                    LightCoordsUtil.FULL_BRIGHT,
                )
            }

            bufferSource.endBatch()
        }
    }
}
