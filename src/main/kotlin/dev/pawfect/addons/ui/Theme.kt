package dev.pawfect.addons.ui

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.ThemeConfig.ThemePreset

object Theme {

    class Palette(
        val background: Int,
        val panel: Int,
        val header: Int,
        val border: Int,
        val accent: Int,
        val text: Int,
        val textDim: Int,
    )

    private val presets = mapOf(
        ThemePreset.MIDNIGHT to Palette(0x0D0D12, 0x14141B, 0x101017, 0x2A2A36, 0x9D4EDD, 0xE8E8F0, 0x8A8A9A),
        ThemePreset.CARBON to Palette(0x0F0F0F, 0x171717, 0x121212, 0x2E2E2E, 0x35D0E0, 0xEDEDED, 0x8C8C8C),
        ThemePreset.ORCHID to Palette(0x100A14, 0x181021, 0x130C19, 0x33203F, 0xE05FD8, 0xF0E6F5, 0x9B8AA5),
        ThemePreset.EMBER to Palette(0x120E0B, 0x1B1512, 0x150F0C, 0x372A22, 0xFF8A3D, 0xF2E9E2, 0xA08D80),
        ThemePreset.MINT to Palette(0x0A1210, 0x101A17, 0x0C1512, 0x1F332D, 0x3DDCA0, 0xE2F2EC, 0x7E9A91),
        ThemePreset.PORCELAIN to Palette(0xF2F2F5, 0xFFFFFF, 0xE8E8EE, 0xD0D0DA, 0x6C4BC4, 0x1A1A22, 0x6E6E7E),
    )

    private val config get() = ConfigManager.features.theme

    val palette: Palette
        get() {
            val preset = config.preset
            if (preset == ThemePreset.CUSTOM) {
                return Palette(
                    config.customBackground,
                    config.customPanel,
                    config.customPanel,
                    config.customBorder,
                    config.customAccent,
                    config.customText,
                    config.customTextDim,
                )
            }
            return presets[preset] ?: presets.getValue(ThemePreset.MIDNIGHT)
        }

    val animationsEnabled: Boolean get() = config.animations

    val clickSoundsEnabled: Boolean get() = config.clickSounds

    val background: Int get() = palette.background
    val panel: Int get() = palette.panel
    val header: Int get() = palette.header
    val border: Int get() = palette.border
    val accent: Int get() = palette.accent
    val text: Int get() = palette.text
    val textDim: Int get() = palette.textDim

    val opacity: Float get() = config.opacity.coerceIn(0.1f, 1f)

    val textOffset: Float get() = config.textOffset.coerceIn(-4f, 4f)

    val fontShadow: Boolean get() = runCatching { config.fontShadow }.getOrDefault(false)

    val fontChoice get() = config.font

    fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    fun withAlpha(rgb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    fun surface(rgb: Int, alpha: Int = 255): Int {
        val floor = 0.35f + 0.65f * opacity
        val scaled = (alpha.coerceIn(0, 255) * floor).toInt().coerceIn(0, 255)
        return (scaled shl 24) or (rgb and 0xFFFFFF)
    }

    fun lerp(from: Int, to: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val start = (from shr shift) and 0xFF
            val end = (to shr shift) and 0xFF
            return (start + (end - start) * t).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun mix(rgb: Int, towards: Int, progress: Float): Int =
        lerp(opaque(rgb), opaque(towards), progress)
}
