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

class ChatBackdropRenderState(
    private val pose: Matrix3x2f,
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val anchorX: Float,
    private val anchorY: Float,
    private val shapeLeft: Float,
    private val shapeTop: Float,
    private val shapeRight: Float,
    private val shapeBottom: Float,
    private val radius: Int,
    private val style: Int,
    private val color: Int,
    private val accent: Int,
    private val strength: Float,
    private val time: Float,
    private val scissor: ScreenRectangle?,
) : GuiElementRenderState {

    private val area: ScreenRectangle? = run {
        val rect = ScreenRectangle(
            floor(left).toInt(),
            floor(top).toInt(),
            ceil(right - left).toInt() + 1,
            ceil(bottom - top).toInt() + 1,
        ).transformMaxBounds(pose)
        if (scissor == null) rect else rect.intersection(scissor)
    }

    override fun buildVertices(consumer: VertexConsumer) {
        vertex(consumer, left, top)
        vertex(consumer, left, bottom)
        vertex(consumer, right, bottom)
        vertex(consumer, right, top)
    }

    private fun vertex(consumer: VertexConsumer, x: Float, y: Float) {
        consumer.addVertexWith2DPose(pose, x, y)
        consumer.setColor(color)

        val invoker = consumer as BufferBuilderInvoker

        val localPointer = invoker.`pawfectaddons$beginElement`(UiFormats.LOCAL)
        if (localPointer != -1L) {
            MemoryUtil.memPutFloat(localPointer, x - anchorX)
            MemoryUtil.memPutFloat(localPointer + 4L, y - anchorY)
            MemoryUtil.memPutFloat(localPointer + 8L, time)
            MemoryUtil.memPutFloat(localPointer + 12L, style.coerceIn(0, 15) + radius.coerceIn(0, 15) * 16f)
        }

        val shapePointer = invoker.`pawfectaddons$beginElement`(UiFormats.SHAPE)
        if (shapePointer != -1L) {
            MemoryUtil.memPutFloat(shapePointer, (shapeRight - shapeLeft) / 2f)
            MemoryUtil.memPutFloat(shapePointer + 4L, (shapeBottom - shapeTop) / 2f)
            MemoryUtil.memPutFloat(shapePointer + 8L, (shapeLeft + shapeRight) / 2f - anchorX)
            MemoryUtil.memPutFloat(shapePointer + 12L, (shapeTop + shapeBottom) / 2f - anchorY)
        }

        val borderPointer = invoker.`pawfectaddons$beginElement`(UiFormats.BORDER_COLOR)
        if (borderPointer != -1L) {
            MemoryUtil.memPutByte(borderPointer, ((accent shr 16) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 1L, ((accent shr 8) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 2L, (accent and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 3L, (strength.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
    }

    override fun pipeline(): RenderPipeline = UiPipelines.CHAT_BACKGROUND

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = area
}
