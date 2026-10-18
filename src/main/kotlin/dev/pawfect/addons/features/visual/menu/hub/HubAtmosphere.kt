package dev.pawfect.addons.features.visual.menu.hub

import org.joml.Vector3f
import kotlin.math.max

internal object HubAtmosphere {

    const val MOONLIGHT = 0.45f

    val HORIZON = Vector3f(0.05f, 0.07f, 0.13f)

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
