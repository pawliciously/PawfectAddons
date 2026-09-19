package dev.pawfect.addons.features.visual.menu

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.mixin.GuiGraphicsAccessor
import dev.pawfect.addons.ui.Brand
import dev.pawfect.addons.ui.Brand.CONTENT_RATIO
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.stringCentered
import dev.pawfect.addons.ui.Draw.stringScaled
import dev.pawfect.addons.ui.Shapes
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.ui.gpu.MenuBackgroundRenderState
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.joml.Matrix3x2f
import kotlin.math.min
import kotlin.math.sin

object MenuStyle {

    private val config get() = ConfigManager.features.menu

    private val startedAt = System.currentTimeMillis()

    @JvmStatic
    fun stylesBackground(): Boolean =
        config.enabled && inScope() && isOwnScreen()

    @JvmStatic
    fun stylesButtons(): Boolean = config.enabled && config.styleButtons

    @JvmStatic
    fun stylesWidget(widget: AbstractWidget): Boolean {
        if (!stylesButtons()) return false
        return isVanilla(widget.javaClass) && isOwnScreen()
    }

    private fun isOwnScreen(): Boolean {
        val screen = McCompat.mc.screen
        if (screen is AbstractContainerScreen<*>) return false
        return isVanilla(screen?.javaClass)
    }

    private fun isVanilla(type: Class<*>?): Boolean {
        val name = type?.name ?: return true
        return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
    }

    @JvmStatic
    fun hidesSplash(): Boolean = config.enabled && config.hideSplash

    @JvmStatic
    fun replacesLogo(): Boolean = config.enabled && config.showBranding

    @JvmStatic
    fun stylesLoadingScreens(): Boolean = config.enabled

    private fun inScope(): Boolean = config.styleEverywhere || McCompat.mc.level == null

    private fun seconds(): Float = (System.currentTimeMillis() - startedAt) / 1000f

    private fun topColor(): Int = if (config.customColors) config.colorTop else shade(Theme.palette.background, 0.85f)

    private fun bottomColor(): Int = if (config.customColors) config.colorBottom else shade(Theme.palette.background, 0.45f)

    private fun accentColor(): Int = if (config.customColors) config.colorAccent else Theme.palette.accent

    private fun shade(rgb: Int, factor: Float): Int {
        val red = (((rgb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val green = (((rgb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val blue = ((rgb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    private fun alpha(rgb: Int, value: Int): Int = (value.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    @JvmStatic
    fun drawBackground(graphics: GuiGraphicsExtractor) {
        val width = McCompat.scaledWidth.toFloat()
        val height = McCompat.scaledHeight.toFloat()
        if (width <= 0f || height <= 0f) return

        val state = MenuBackgroundRenderState(
            Matrix3x2f(graphics.pose()),
            0f,
            0f,
            width,
            height,
            opaque(topColor()),
            opaque(bottomColor()),
            accentColor(),
            config.intensity.coerceIn(0f, 1f),
            seconds(),
        )
        (graphics as GuiGraphicsAccessor).`pawfectaddons$guiRenderState`().addGuiElement(state)
    }

    @JvmStatic
    fun drawButton(graphics: GuiGraphicsExtractor, widget: AbstractWidget) {
        val x = widget.x.toFloat()
        val y = widget.y.toFloat()
        val width = widget.width.toFloat()
        val height = widget.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val radius = config.buttonRadius.coerceIn(0f, height / 2f)
        val enabled = widget.active
        val hovered = widget.isHovered && enabled
        val accent = accentColor()

        if (hovered) {
            Shapes.shadow(graphics, x, y, width, height, radius, 8f, alpha(accent, 60), 2f)
        }

        val fillTop: Int
        val fillBottom: Int
        val border: Int
        when {
            !enabled -> {
                fillTop = alpha(shade(topColor(), 1.1f), 150)
                fillBottom = alpha(shade(bottomColor(), 1.1f), 150)
                border = alpha(Theme.palette.border, 110)
            }
            hovered -> {
                fillTop = alpha(Theme.mix(topColor(), accent, 0.22f), 250)
                fillBottom = alpha(Theme.mix(bottomColor(), accent, 0.10f), 250)
                border = alpha(accent, 225)
            }
            else -> {
                fillTop = alpha(shade(topColor(), 1.35f), 215)
                fillBottom = alpha(shade(bottomColor(), 1.35f), 215)
                border = alpha(Theme.palette.border, 175)
            }
        }

        Shapes.rect(graphics, x, y, width, height, radius, fillTop, fillBottom, 1f, border)

        val label = widget.message.string
        if (label.isNotEmpty()) {
            graphics.stringCentered(
                Draw.truncate(label, width - 8f),
                x + width / 2f,
                y + (height - Draw.LINE_HEIGHT) / 2f,
                alpha(if (enabled) Theme.palette.text else Theme.palette.textDim, if (enabled) 255 else 160),
                shadow = true,
            )
        }
    }

    @JvmStatic
    fun drawSlider(graphics: GuiGraphicsExtractor, widget: AbstractWidget, value: Double) {
        val x = widget.x.toFloat()
        val y = widget.y.toFloat()
        val width = widget.width.toFloat()
        val height = widget.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val radius = config.buttonRadius.coerceIn(0f, height / 2f)
        val enabled = widget.active
        val hovered = widget.isHovered && enabled
        val accent = accentColor()
        val fraction = value.coerceIn(0.0, 1.0).toFloat()

        Shapes.rect(
            graphics,
            x,
            y,
            width,
            height,
            radius,
            alpha(shade(topColor(), 1.35f), 215),
            alpha(shade(bottomColor(), 1.35f), 215),
            1f,
            alpha(if (hovered) accent else Theme.palette.border, if (hovered) 225 else 175),
        )

        val trackInset = 3f
        val trackWidth = (width - trackInset * 2f) * fraction
        if (trackWidth > 0.5f) {
            Shapes.rect(
                graphics,
                x + trackInset,
                y + trackInset,
                trackWidth,
                height - trackInset * 2f,
                (radius - 1f).coerceAtLeast(0f),
                alpha(Theme.mix(topColor(), accent, 0.55f), 230),
                alpha(Theme.mix(bottomColor(), accent, 0.35f), 230),
            )
        }

        val handleWidth = 4f
        val handleX = x + trackInset + (width - trackInset * 2f - handleWidth) * fraction
        Shapes.rect(
            graphics,
            handleX,
            y + 2f,
            handleWidth,
            height - 4f,
            handleWidth / 2f,
            alpha(accent, if (enabled) 255 else 140),
        )

        val label = widget.message.string
        graphics.stringCentered(
            label,
            x + width / 2f,
            y + (height - 8f) / 2f,
            alpha(if (enabled) Theme.palette.text else Theme.palette.textDim, 255),
            shadow = true,
        )
    }

    @JvmStatic
    fun drawTitle(graphics: GuiGraphicsExtractor, screenWidth: Int) {
        if (!config.enabled || !config.showBranding) return

        val time = seconds()
        val centerX = screenWidth / 2f
        val accent = accentColor()
        val pulse = 0.72f + 0.28f * sin(time * 1.6f)

        val version = "v${PawfectAddons.VERSION}"
        val versionScale = 1.6f
        val versionHeight = 9f * versionScale
        val gap = 4f

        val band = McCompat.scaledHeight / 4f + 44f
        val size = min(124f, (band - versionHeight - gap - 6f) / CONTENT_RATIO).coerceAtLeast(48f)
        val logoHeight = size * CONTENT_RATIO
        val block = logoHeight + gap + versionHeight

        val bounce = sin(time * 1.35f) * 2.4f
        val logoX = centerX - size / 2f
        val logoY = ((band - block) * 0.42f).coerceIn(2f, 26f) - (size - logoHeight) / 2f + bounce
        val contentY = logoY + (size - logoHeight) / 2f

        Shapes.shadow(
            graphics,
            logoX + size * 0.10f,
            contentY + logoHeight * 0.14f,
            size * 0.80f,
            logoHeight * 0.80f,
            logoHeight * 0.32f,
            26f,
            alpha(accent, (105f * pulse).toInt()),
            3f,
        )
        Brand.logo(graphics, logoX, logoY, size)

        val versionWidth = Draw.width(version) * versionScale
        graphics.stringScaled(
            version,
            centerX - versionWidth / 2f,
            contentY + logoHeight + gap,
            versionScale,
            alpha(Theme.palette.textDim, (170 + 70 * pulse).toInt().coerceIn(0, 255)),
            shadow = true,
        )
    }
}
