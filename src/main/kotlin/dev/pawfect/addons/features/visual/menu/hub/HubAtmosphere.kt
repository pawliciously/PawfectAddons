package dev.pawfect.addons.features.visual.menu.hub

import org.joml.Vector3f
import kotlin.math.max

/**
 * The menu scene's night light and fog. The light colours and curve match hub_terrain.vsh and
 * the fog matches hub_terrain.fsh, so whatever is drawn outside those shaders, like the walker,
 * sits in the scene the same way the blocks around it do.
 */
internal object HubAtmosphere {

    const val MOONLIGHT = 0.3f
    const val FOG_START = 90f
    const val FOG_END = 165f
    val FOG = Vector3f(0.05f, 0.07f, 0.13f)

    /** Writes the light colour for a sky and block light level into [into] at [at]. */
    fun light(sky: Float, block: Float, into: FloatArray, at: Int) {
        val moon = curve(sky) * MOONLIGHT
        val lamps = curve(block)
        into[at] = max(0.52f * moon, lamps) + 0.025f
        into[at + 1] = max(0.64f * moon, 0.8f * lamps) + 0.03f
        into[at + 2] = max(moon, 0.55f * lamps) + 0.045f
    }

    /** How much fog covers something this far from the camera. */
    fun fog(distance: Float): Float {
        val t = ((distance - FOG_START) / (FOG_END - FOG_START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun curve(level: Float): Float {
        val l = (level / 15f).coerceIn(0f, 1f)
        return l / (4f - 3f * l)
    }
}
