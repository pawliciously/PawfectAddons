package dev.pawfect.addons.features.drops

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import kotlin.math.ceil

class DropItemState(
    val item: ItemStackRenderState,
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
    private val unit: Float,
    val offsetX: Float,
    val offsetY: Float,
    val zoom: Float,
    val spin: Float,
    val tilt: Float,
    val roll: Float,
    private val scissor: ScreenRectangle?,
) : PictureInPictureRenderState {
    override fun x0(): Int = left
    override fun y0(): Int = top
    override fun x1(): Int = right
    override fun y1(): Int = bottom
    override fun scale(): Float = unit
    override fun scissorArea(): ScreenRectangle? = scissor
    override fun bounds(): ScreenRectangle? = PictureInPictureRenderState.getBounds(left, top, right, bottom, scissor)
}

class DropItemRenderer : PictureInPictureRenderer<DropItemState>() {

    override fun getRenderStateClass(): Class<DropItemState> = DropItemState::class.java

    override fun renderToTexture(state: DropItemState, pose: PoseStack, collector: SubmitNodeCollector) {
        val unit = state.scale()
        pose.translate(state.offsetX / unit, state.offsetY / unit, 0f)
        pose.scale(1f, -1f, -1f)
        pose.mulPose(Axis.ZP.rotation(state.roll))
        pose.mulPose(Axis.XP.rotation(state.tilt))
        pose.mulPose(Axis.YP.rotation(state.spin))
        pose.scale(state.zoom, state.zoom, state.zoom)
        val lighting = if (state.item.usesBlockLight()) Lighting.Entry.ITEMS_3D else Lighting.Entry.ITEMS_FLAT
        McCompat.mc.gameRenderer.lighting().setupFor(lighting)
        state.item.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)
    }

    override fun getTranslateY(height: Int, guiScale: Int): Float = height / 2f

    override fun getTextureLabel(): String = "pawfectaddons drop item"

    companion object {
        private const val FULL_BRIGHT = 0xF000F0

        fun draw(
            graphics: GuiGraphicsExtractor, stack: ItemStack, cx: Float, cy: Float, unit: Float, reach: Float,
            offsetX: Float, offsetY: Float, zoom: Float, spin: Float, tilt: Float, roll: Float,
            clipBelow: Float = Float.NaN,
        ) {
            if (zoom <= 0.01f || unit <= 0f) return
            val client = McCompat.mc
            val item = ItemStackRenderState()
            client.itemModelResolver.updateForTopItem(item, stack, ItemDisplayContext.FIXED, client.level, client.player, 0)
            val half = ceil(unit * reach).toInt()
            val x = Math.round(cx)
            val y = Math.round(cy)
            val scissor = if (clipBelow.isNaN()) {
                null
            } else {
                val window = client.window
                ScreenRectangle(0, 0, window.guiScaledWidth, Math.round(clipBelow).coerceIn(0, window.guiScaledHeight))
            }
            val state = DropItemState(
                item, x - half, y - half, x + half, y + half, unit,
                offsetX + (cx - x), offsetY + (cy - y), zoom, spin, tilt, roll, scissor,
            )
            (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addPicturesInPictureState(state)
        }
    }
}
