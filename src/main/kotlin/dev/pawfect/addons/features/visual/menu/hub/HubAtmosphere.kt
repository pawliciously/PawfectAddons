package dev.pawfect.addons.features.visual.menu.hub

import org.joml.Vector3f
import kotlin.math.max

/**
 * The menu scene's night light. The colours and curve match hub_terrain.vsh, so whatever is
 * drawn outside that shader, like the walker, sits in the scene the same way the blocks around
 * it do. There is no fog: every block stays visible however far away it is.
 */
internal object HubAtmosphere {

    const val MOONLIGHT = 0.45f

    /** The sky's colour at the horizon, which the scene also fades in from. */
    val HORIZON = Vector3f(0.05f, 0.07f, 0.13f)

    /** Writes the light colour for a sky and block light level into [into] at [at]. */
    fun light(sky: Float, block: Float, into: FloatArray, at: Int) {
        val moon = curve(sky) * MOONLIGHT
        val lamps = curve(block)
        into[at] = max(0.52f * moon, lamps) + 0.025f
        into[at + 1] = max(0.64f * moon, 0.8f * lamps) + 0.03f
        into[at + 2] = max(moon, 0.55f * lamps) + 0.045f
    }

    private fun curve(level: Float): Float {
        val l = (level / 15f).coerceIn(0f, 1f)
        return l / (4f - 3f * l)
    }
}
