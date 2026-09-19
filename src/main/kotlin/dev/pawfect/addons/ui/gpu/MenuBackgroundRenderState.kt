package dev.pawfect.addons.ui.gpu

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.pawfect.addons.mixin.BufferBuilderInvoker
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import org.lwjgl.system.MemoryUtil
import kotlin.math.ceil
import kotlin.math.floor

class MenuBackgroundRenderState(
    private val pose: Matrix3x2f,
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val colorTop: Int,
    private val colorBottom: Int,
    private val accent: Int,
    private val intensity: Float,
    private val time: Float,
    private val style: Float = 0f,
    private val renderPipeline: RenderPipeline = UiPipelines.MENU_BACKGROUND,
) : GuiElementRenderState {

    private val centerX = (left + right) / 2f
    private val centerY = (top + bottom) / 2f
    private val halfWidth = (right - left) / 2f
    private val halfHeight = (bottom - top) / 2f

    private val area: ScreenRectangle = ScreenRectangle(
        floor(left).toInt(),
        floor(top).toInt(),
        ceil(right - left).toInt() + 1,
        ceil(bottom - top).toInt() + 1,
    ).transformMaxBounds(pose)

    override fun buildVertices(consumer: VertexConsumer) {
        vertex(consumer, left, top)
        vertex(consumer, left, bottom)
        vertex(consumer, right, bottom)
        vertex(consumer, right, top)
    }

    private fun vertex(consumer: VertexConsumer, x: Float, y: Float) {
        consumer.addVertexWith2DPose(pose, x, y)
        consumer.setColor(colorTop)

        val invoker = consumer as BufferBuilderInvoker

        val localPointer = invoker.`pawfectaddons$beginElement`(UiFormats.LOCAL)
        if (localPointer != -1L) {
            MemoryUtil.memPutFloat(localPointer, x - centerX)
            MemoryUtil.memPutFloat(localPointer + 4L, y - centerY)
            MemoryUtil.memPutFloat(localPointer + 8L, style * 2f + intensity)
            MemoryUtil.memPutFloat(localPointer + 12L, time)
        }

        val shapePointer = invoker.`pawfectaddons$beginElement`(UiFormats.SHAPE)
        if (shapePointer != -1L) {
            MemoryUtil.memPutFloat(shapePointer, halfWidth)
            MemoryUtil.memPutFloat(shapePointer + 4L, halfHeight)
            MemoryUtil.memPutFloat(shapePointer + 8L, ((accent shr 16) and 0xFF) / 255f)
            MemoryUtil.memPutFloat(shapePointer + 12L, ((accent shr 8) and 0xFF) / 255f)
        }

        val borderPointer = invoker.`pawfectaddons$beginElement`(UiFormats.BORDER_COLOR)
        if (borderPointer != -1L) {
            MemoryUtil.memPutByte(borderPointer, ((colorBottom shr 16) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 1L, ((colorBottom shr 8) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 2L, (colorBottom and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 3L, (accent and 0xFF).toByte())
        }
    }

    override fun pipeline(): RenderPipeline = renderPipeline

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = null

    override fun bounds(): ScreenRectangle = area
}
