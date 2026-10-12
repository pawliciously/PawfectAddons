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

/**
 * The drop as a real 3D model, rendered into its own texture at full resolution and laid over
 * the screen, so it can turn every way and stays sharp however big it gets. The box it's drawn
 * into stays put; moving, spinning and zooming all happen inside it.
 */
class DropItemState(
    val item: ItemStackRenderState,
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
    /** How many GUI units one block of the model takes. */
    private val unit: Float,
    val offsetX: Float,
    val offsetY: Float,
    val zoom: Float,
    val spin: Float,
    val tilt: Float,
    val roll: Float,
) : PictureInPictureRenderState {
    override fun x0(): Int = left
    override fun y0(): Int = top
    override fun x1(): Int = right
    override fun y1(): Int = bottom
    override fun scale(): Float = unit
    override fun scissorArea(): ScreenRectangle? = null
    override fun bounds(): ScreenRectangle? = PictureInPictureRenderState.getBounds(left, top, right, bottom, null)
}

class DropItemRenderer : PictureInPictureRenderer<DropItemState>() {

    override fun getRenderStateClass(): Class<DropItemState> = DropItemState::class.java

    override fun renderToTexture(state: DropItemState, pose: PoseStack, collector: SubmitNodeCollector) {
        val unit = state.scale()
        // Still in screen space here (y down), so the screen offsets go in first.
        pose.translate(state.offsetX / unit, state.offsetY / unit, 0f)
        pose.scale(1f, -1f, -1f)
        pose.mulPose(Axis.ZP.rotation(state.roll))
        pose.mulPose(Axis.XP.rotation(state.tilt))
        pose.mulPose(Axis.YP.rotation(state.spin))
        pose.scale(state.zoom, state.zoom, state.zoom)
        McCompat.mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D)
        state.item.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)
    }

    override fun getTextureLabel(): String = "pawfectaddons drop item"

    companion object {
        private const val FULL_BRIGHT = 0xF000F0

        /**
         * Draws [stack] centred on ([cx], [cy]) with one block of it [unit] GUI units across, in a box
         * with room for it to move [reach] blocks off centre and still be in frame.
         */
        fun draw(
            graphics: GuiGraphicsExtractor, stack: ItemStack, cx: Float, cy: Float, unit: Float, reach: Float,
            offsetX: Float, offsetY: Float, zoom: Float, spin: Float, tilt: Float, roll: Float,
        ) {
            if (zoom <= 0.01f || unit <= 0f) return
            val client = McCompat.mc
            val item = ItemStackRenderState()
            client.itemModelResolver.updateForTopItem(item, stack, ItemDisplayContext.FIXED, client.level, client.player, 0)
            val half = ceil(unit * reach).toInt()
            val x = Math.round(cx)
            val y = Math.round(cy)
            val state = DropItemState(
                item, x - half, y - half, x + half, y + half, unit,
                offsetX + (cx - x), offsetY + (cy - y), zoom, spin, tilt, roll,
            )
            (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addPicturesInPictureState(state)
        }
    }
}
