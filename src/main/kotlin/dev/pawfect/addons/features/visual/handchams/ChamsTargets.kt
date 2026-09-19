package dev.pawfect.addons.features.visual.handchams

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat

class Surface(val texture: GpuTexture, val view: GpuTextureView, val width: Int, val height: Int) {
    fun close() {
        view.close()
        texture.close()
    }
}

object ChamsTargets {

    const val MAX_LEVELS = 5

    private const val COLOR_USAGE =
        GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC or
            GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT

    var fullWidth = 0
        private set

    var fullHeight = 0
        private set

    private var divisor = 0

    var mask: Surface? = null
        private set

    var glow: Surface? = null
        private set

    var trail: Surface? = null
        private set

    var trailHistory: Surface? = null
        private set

    var outlineRow: Surface? = null
        private set

    var levels: List<Surface> = emptyList()
        private set

    val ready: Boolean
        get() = mask != null && glow != null && trail != null && trailHistory != null && outlineRow != null &&
            levels.size == MAX_LEVELS

    fun swapTrail() {
        val current = trail
        trail = trailHistory
        trailHistory = current
    }

    fun ensure(width: Int, height: Int, workDivisor: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width == fullWidth && height == fullHeight && workDivisor == divisor && ready) return true

        close()
        fullWidth = width
        fullHeight = height
        divisor = workDivisor

        val device = RenderSystem.getDevice()

        fun color(name: String, w: Int, h: Int): Surface {
            val safeW = w.coerceAtLeast(1)
            val safeH = h.coerceAtLeast(1)
            val texture = device.createTexture(name, COLOR_USAGE, TextureFormat.RGBA8, safeW, safeH, 1, 1)
            return Surface(texture, device.createTextureView(texture), safeW, safeH)
        }

        val workWidth = (width / workDivisor).coerceAtLeast(1)
        val workHeight = (height / workDivisor).coerceAtLeast(1)

        mask = color("pawfect_chams_mask", workWidth, workHeight)
        glow = color("pawfect_chams_glow", workWidth, workHeight)
        trail = color("pawfect_chams_trail_a", workWidth, workHeight)
        trailHistory = color("pawfect_chams_trail_b", workWidth, workHeight)
        outlineRow = color("pawfect_chams_outline_row", workWidth, workHeight)
        levels = (0 until MAX_LEVELS).map { level ->
            color("pawfect_chams_level_$level", workWidth shr level, workHeight shr level)
        }

        return ready
    }

    fun close() {
        mask?.close()
        glow?.close()
        trail?.close()
        trailHistory?.close()
        outlineRow?.close()
        levels.forEach { it.close() }

        mask = null
        glow = null
        trail = null
        trailHistory = null
        outlineRow = null
        levels = emptyList()

        fullWidth = 0
        fullHeight = 0
        divisor = 0
    }
}
