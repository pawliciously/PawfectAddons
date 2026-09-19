package dev.pawfect.addons.features.cosmetics

import com.google.gson.annotations.Expose
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

enum class NameStyle {
    SOLID,
    GRADIENT,
    WAVE,
    RAINBOW,
    PULSE,
}

private fun parseColorHex(raw: String): Int? {
    val text = raw.trim().removePrefix("#").removePrefix("0x")
    if (text.length != 6) return null
    return text.toIntOrNull(16)
}

private fun resolvePalette(
    style: String?,
    colors: List<String>?,
    speed: Float,
    spread: Float,
    bold: Boolean = false,
    uppercase: Boolean = false,
): ResolvedName? {
    val mode = when (style?.trim()?.uppercase()) {
        "SOLID" -> NameStyle.SOLID
        "GRADIENT" -> NameStyle.GRADIENT
        "WAVE" -> NameStyle.WAVE
        "RAINBOW", "RGB" -> NameStyle.RAINBOW
        "PULSE" -> NameStyle.PULSE
        null -> NameStyle.GRADIENT
        else -> return null
    }

    val palette = colors.orEmpty().mapNotNull { parseColorHex(it) }
    if (palette.isEmpty() && mode != NameStyle.RAINBOW) return null

    return ResolvedName(
        mode,
        palette.toIntArray(),
        speed.coerceIn(0f, 8f),
        spread.coerceIn(0.05f, 8f),
        bold,
        uppercase,
    )
}

class NameCosmetic {

    @Expose
    var style: String? = null

    @Expose
    var colors: List<String>? = null

    @Expose
    var speed: Float = 1f

    @Expose
    var spread: Float = 1f

    @Expose
    var bold: Boolean = false

    @Expose
    var uppercase: Boolean = false

    fun resolve(): ResolvedName? = resolvePalette(style, colors, speed, spread, bold, uppercase)
}

class TrailCosmetic {

    @Expose
    var style: String? = null

    @Expose
    var colors: List<String>? = null

    @Expose
    var speed: Float = 1f

    @Expose
    var spread: Float = 1f

    @Expose
    var width: Float = 0.45f

    @Expose
    var length: Float = 0.9f

    @Expose
    var glow: Boolean = true

    @Expose
    var ripple: Boolean = false

    @Expose
    var rippleSize: Float = 1.6f

    @Expose
    var rippleSpeed: Float = 1f

    @Expose
    var rippleThickness: Float = 0.18f

    @Expose
    var rippleMinFall: Float = 1.5f

    @Expose
    var rippleLife: Float = 0.9f

    fun resolve(): ResolvedTrail? {
        val palette = resolvePalette(style, colors, speed, spread) ?: return null
        return ResolvedTrail(
            palette,
            width.coerceIn(0.1f, 1.5f),
            length.coerceIn(0.2f, 2f),
            glow,
            if (ripple) {
                ResolvedRipple(
                    rippleSize.coerceIn(0.4f, 6f),
                    rippleSpeed.coerceIn(0.2f, 4f),
                    rippleThickness.coerceIn(0.03f, 1f),
                    rippleMinFall.coerceIn(0f, 12f),
                    rippleLife.coerceIn(0.2f, 4f),
                )
            } else {
                null
            },
        )
    }
}

class MotesCosmetic {

    @Expose
    var style: String? = null

    @Expose
    var colors: List<String>? = null

    @Expose
    var speed: Float = 1f

    @Expose
    var spread: Float = 1f

    @Expose
    var density: Int = 14

    @Expose
    var size: Float = 0.06f

    @Expose
    var drift: Float = 1f

    @Expose
    var radius: Float = 2.2f

    @Expose
    var height: Float = 2f

    @Expose
    var glow: Boolean = true

    fun resolve(): ResolvedMotes? {
        val palette = resolvePalette(style, colors, speed, spread) ?: return null
        return ResolvedMotes(
            palette,
            density.coerceIn(1, 48),
            size.coerceIn(0.01f, 0.4f),
            drift.coerceIn(0.05f, 4f),
            radius.coerceIn(0.5f, 6f),
            height.coerceIn(0.3f, 6f),
            glow,
        )
    }
}

class CapeCosmetic {

    @Expose
    var url: String? = null

    fun resolve(): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty() || text.length > 512) return null
        if (!text.startsWith("https://", ignoreCase = true)) return null
        return text
    }
}

class CosmeticEntry {

    @Expose
    var ign: String? = null

    @Expose
    var name: NameCosmetic? = null

    @Expose
    var cape: CapeCosmetic? = null

    @Expose
    var trail: TrailCosmetic? = null

    @Expose
    var og: Boolean = false

    @Expose
    var badges: List<String>? = null

    @Expose
    var motes: MotesCosmetic? = null

    fun resolve(): ResolvedCosmetic? {
        val resolvedName = name?.resolve()
        val resolvedCape = cape?.resolve()
        val resolvedTrail = trail?.resolve()
        val resolvedMotes = motes?.resolve()
        val resolvedBadges = Badge.ordered(badges.orEmpty() + if (og) listOf(Badge.FOUNDER.id) else emptyList())
        if (resolvedName == null &&
            resolvedCape == null &&
            resolvedTrail == null &&
            resolvedMotes == null &&
            resolvedBadges.isEmpty()
        ) {
            return null
        }
        return ResolvedCosmetic(resolvedName, resolvedCape, resolvedTrail, resolvedMotes, resolvedBadges)
    }
}

class ResolvedCosmetic(
    val name: ResolvedName?,
    val cape: String?,
    val trail: ResolvedTrail?,
    val motes: ResolvedMotes?,
    val badges: List<Badge>,
)

class ResolvedTrail(
    val palette: ResolvedName,
    val width: Float,
    val length: Float,
    val glow: Boolean,
    val ripple: ResolvedRipple?,
)

class ResolvedRipple(
    val size: Float,
    val speed: Float,
    val thickness: Float,
    val minFall: Float,
    val life: Float,
)

class ResolvedMotes(
    val palette: ResolvedName,
    val density: Int,
    val size: Float,
    val drift: Float,
    val radius: Float,
    val height: Float,
    val glow: Boolean,
)

class ResolvedName(
    val style: NameStyle,
    private val colors: IntArray,
    private val speed: Float,
    private val spread: Float,
    val bold: Boolean,
    val uppercase: Boolean,
) {

    val isAnimated: Boolean
        get() = speed > 0.001f && (style == NameStyle.WAVE || style == NameStyle.RAINBOW || style == NameStyle.PULSE)

    fun colorAt(position: Float, time: Float): Int = when (style) {
        NameStyle.SOLID -> colors.firstOrNull() ?: 0xFFFFFF
        NameStyle.GRADIENT -> sample(position, false)
        NameStyle.WAVE -> sample(position * spread - time * speed * 0.35f, true)
        NameStyle.RAINBOW -> hsv(position * spread * 0.35f - time * speed * 0.25f, 0.82f, 1f)
        NameStyle.PULSE -> sample(triangle(time * speed * 0.25f), false)
    }

    private fun sample(raw: Float, cyclic: Boolean): Int {
        if (colors.isEmpty()) return 0xFFFFFF
        if (colors.size == 1) return colors[0]

        val steps = if (cyclic) colors.size else colors.size - 1
        val position = (if (cyclic) frac(raw) else raw.coerceIn(0f, 1f)) * steps
        val index = floor(position).toInt()
        val blend = position - index

        val from = colors[index % colors.size]
        val to = colors[(index + 1) % colors.size]
        return mix(from, to, blend)
    }

    private fun mix(from: Int, to: Int, blend: Float): Int {
        val t = blend.coerceIn(0f, 1f)
        return pack(
            blendChannel(from, to, 16, t),
            blendChannel(from, to, 8, t),
            blendChannel(from, to, 0, t),
        )
    }

    private fun blendChannel(from: Int, to: Int, shift: Int, t: Float): Float {
        val a = channel(from, shift)
        val b = channel(to, shift)
        return sqrt(a * a + (b * b - a * a) * t)
    }

    private fun channel(color: Int, shift: Int): Float = ((color shr shift) and 0xFF) / 255f

    private fun frac(value: Float): Float = value - floor(value)

    private fun triangle(value: Float): Float = abs(frac(value) * 2f - 1f)

    private fun hsv(hue: Float, saturation: Float, value: Float): Int {
        val h = frac(hue) * 6f
        val sector = floor(h).toInt()
        val f = h - sector
        val p = value * (1f - saturation)
        val q = value * (1f - saturation * f)
        val t = value * (1f - saturation * (1f - f))

        val (r, g, b) = when (sector % 6) {
            0 -> Triple(value, t, p)
            1 -> Triple(q, value, p)
            2 -> Triple(p, value, t)
            3 -> Triple(p, q, value)
            4 -> Triple(t, p, value)
            else -> Triple(value, p, q)
        }

        return pack(r, g, b)
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val red = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val green = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val blue = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (red shl 16) or (green shl 8) or blue
    }
}
