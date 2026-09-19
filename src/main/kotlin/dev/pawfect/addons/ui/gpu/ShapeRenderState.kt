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

class ShapeRenderState(
    private val pose: Matrix3x2f,
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val centerX: Float,
    private val centerY: Float,
    private val halfWidth: Float,
    private val halfHeight: Float,
    private val radius: Float,
    private val borderWidth: Float,
    private val softness: Float,
    private val mode: Float,
    private val colorTop: Int,
    private val colorBottom: Int,
    private val borderColor: Int,
    private val scissor: ScreenRectangle?,
    private val horizontal: Boolean = false,
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
        if (horizontal) {
            vertex(consumer, left, top, colorTop)
            vertex(consumer, left, bottom, colorTop)
            vertex(consumer, right, bottom, colorBottom)
            vertex(consumer, right, top, colorBottom)
        } else {
            vertex(consumer, left, top, colorTop)
            vertex(consumer, left, bottom, colorBottom)
            vertex(consumer, right, bottom, colorBottom)
            vertex(consumer, right, top, colorTop)
        }
    }

    private fun vertex(consumer: VertexConsumer, x: Float, y: Float, color: Int) {
        consumer.addVertexWith2DPose(pose, x, y)
        consumer.setColor(color)

        val invoker = consumer as BufferBuilderInvoker

        val localPointer = invoker.`pawfectaddons$beginElement`(UiFormats.LOCAL)
        if (localPointer != -1L) {
            MemoryUtil.memPutFloat(localPointer, x - centerX)
            MemoryUtil.memPutFloat(localPointer + 4L, y - centerY)
            MemoryUtil.memPutFloat(localPointer + 8L, softness)
            MemoryUtil.memPutFloat(localPointer + 12L, mode)
        }

        val shapePointer = invoker.`pawfectaddons$beginElement`(UiFormats.SHAPE)
        if (shapePointer != -1L) {
            MemoryUtil.memPutFloat(shapePointer, halfWidth)
            MemoryUtil.memPutFloat(shapePointer + 4L, halfHeight)
            MemoryUtil.memPutFloat(shapePointer + 8L, radius)
            MemoryUtil.memPutFloat(shapePointer + 12L, borderWidth)
        }

        val borderPointer = invoker.`pawfectaddons$beginElement`(UiFormats.BORDER_COLOR)
        if (borderPointer != -1L) {
            MemoryUtil.memPutByte(borderPointer, ((borderColor shr 16) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 1L, ((borderColor shr 8) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 2L, (borderColor and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 3L, ((borderColor ushr 24) and 0xFF).toByte())
        }
    }

    override fun pipeline(): RenderPipeline = UiPipelines.SHAPE

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = area
}
