package dev.pawfect.addons.features.stats

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.ui.Draw.gradient
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import kotlin.math.PI
import kotlin.math.sin

object LowHealthAlert {

    private val config get() = ConfigManager.features.stats

    val active: Boolean
        get() {
            if (!config.lowHealthAlert) return false
            if (!SkyBlockData.onSkyBlock || !ActionBarStats.hasData) return false
            val health = ActionBarStats.health ?: return false
            if (health.max == null) return false
            return health.percent < config.lowHealthThreshold.coerceIn(1, 100).toDouble()
        }

    fun render() {
        if (!active) return

        val graphics = RenderContext.graphics
        val width = McCompat.scaledWidth.toFloat()
        val height = McCompat.scaledHeight.toFloat()
        val thickness = config.lowHealthThickness.coerceIn(10, 300).toFloat()

        val speed = config.lowHealthSpeed.coerceIn(0.1f, 5f)
        val pulse = (sin(System.currentTimeMillis() / 1000.0 * speed * PI * 2.0) * 0.5 + 0.5).toFloat()
        val strength = 0.35f + pulse * 0.65f
        val alpha = (config.lowHealthOpacity.coerceIn(0f, 1f) * strength * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 2) return

        val rgb = config.lowHealthColor and 0xFFFFFF
        val solid = (alpha shl 24) or rgb
        val clear = rgb

        val edge = thickness.coerceAtMost(minOf(width, height) / 2f)

        graphics.gradient(0f, 0f, width, edge, solid, clear)
        graphics.gradient(0f, height - edge, width, edge, clear, solid)
        graphics.gradientHorizontal(0f, 0f, edge, height, solid, clear)
        graphics.gradientHorizontal(width - edge, 0f, edge, height, clear, solid)
    }
}
