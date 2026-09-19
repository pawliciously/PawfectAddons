package dev.pawfect.addons.features.combat

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext

object Hitmarker {

    private const val DURATION_MS = 280L
    private const val GAP = 3.5f
    private const val STEPS = 5

    private var flashAt = 0L

    private val config get() = ConfigManager.features.hitsounds

    fun flash() {
        flashAt = System.currentTimeMillis()
    }

    fun reset() {
        flashAt = 0L
    }

    fun render() {
        if (!config.markerEnabled || flashAt <= 0L) return
        if (McCompat.hideGui || McCompat.mc.screen != null) return

        val age = System.currentTimeMillis() - flashAt
        if (age > DURATION_MS) {
            flashAt = 0L
            return
        }

        val fade = 1f - age.toFloat() / DURATION_MS
        val alpha = (fade * fade * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 2) return

        if (!RenderContext.isActive) return
        val graphics = RenderContext.graphics
        val colour = (alpha shl 24) or (config.markerColour and 0xFFFFFF)

        val centerX = McCompat.scaledWidth / 2f
        val centerY = McCompat.scaledHeight / 2f
        val thickness = config.markerThickness.coerceIn(0.5f, 4f)
        val length = config.markerSize.coerceIn(2f, 12f)
        val step = length / STEPS

        for (signX in intArrayOf(-1, 1)) {
            for (signY in intArrayOf(-1, 1)) {
                for (i in 0 until STEPS) {
                    val offset = GAP + i * step
                    graphics.rect(
                        centerX + signX * offset - thickness / 2f,
                        centerY + signY * offset - thickness / 2f,
                        thickness,
                        thickness,
                        colour,
                    )
                }
            }
        }
    }
}
