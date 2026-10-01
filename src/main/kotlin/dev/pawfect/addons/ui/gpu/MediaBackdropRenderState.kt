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

/**
 * The media card's animated backdrop (shaders/core/media_bg.fsh). The four palette colours
 * ride in the vertex data: two in the colour slots and two packed into floats, which hold a
 * 24-bit integer exactly.
 */
class MediaBackdropRenderState(
    private val pose: Matrix3x2f,
    private val x: Float,
    private val y: Float,
    private val width: Float,
    private val height: Float,
    private val radius: Float,
    private val palette: IntArray,
    private val opacity: Float,
    private val phase: Float,
    private val awake: Float,
) : GuiElementRenderState {

    private val centerX = x + width / 2f
    private val centerY = y + height / 2f

    private val area: ScreenRectangle = ScreenRectangle(
        floor(x).toInt(),
        floor(y).toInt(),
        ceil(width).toInt() + 1,
        ceil(height).toInt() + 1,
    ).transformMaxBounds(pose)

    override fun buildVertices(consumer: VertexConsumer) {
        vertex(consumer, x, y)
        vertex(consumer, x, y + height)
        vertex(consumer, x + width, y + height)
        vertex(consumer, x + width, y)
    }

    private fun vertex(consumer: VertexConsumer, px: Float, py: Float) {
        consumer.addVertexWith2DPose(pose, px, py)
        consumer.setColor(((opacity.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (palette[0] and 0xFFFFFF))

        val invoker = consumer as BufferBuilderInvoker

        val localPointer = invoker.`pawfectaddons$beginElement`(UiFormats.LOCAL)
        if (localPointer != -1L) {
            MemoryUtil.memPutFloat(localPointer, px - centerX)
            MemoryUtil.memPutFloat(localPointer + 4L, py - centerY)
            MemoryUtil.memPutFloat(localPointer + 8L, phase)
            MemoryUtil.memPutFloat(localPointer + 12L, radius)
        }

        val shapePointer = invoker.`pawfectaddons$beginElement`(UiFormats.SHAPE)
        if (shapePointer != -1L) {
            MemoryUtil.memPutFloat(shapePointer, width / 2f)
            MemoryUtil.memPutFloat(shapePointer + 4L, height / 2f)
            MemoryUtil.memPutFloat(shapePointer + 8L, (palette[2] and 0xFFFFFF).toFloat())
            MemoryUtil.memPutFloat(shapePointer + 12L, (palette[3] and 0xFFFFFF).toFloat())
        }

        val borderPointer = invoker.`pawfectaddons$beginElement`(UiFormats.BORDER_COLOR)
        if (borderPointer != -1L) {
            val second = palette[1]
            MemoryUtil.memPutByte(borderPointer, ((second shr 16) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 1L, ((second shr 8) and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 2L, (second and 0xFF).toByte())
            MemoryUtil.memPutByte(borderPointer + 3L, (awake.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
    }

    override fun pipeline(): RenderPipeline = UiPipelines.MEDIA_BACKGROUND

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = null

    override fun bounds(): ScreenRectangle = area
}
