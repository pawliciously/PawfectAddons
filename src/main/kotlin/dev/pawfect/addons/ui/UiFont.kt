package dev.pawfect.addons.ui

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.features.ThemeConfig.UiFontChoice
import dev.pawfect.addons.utils.McCompat
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

object UiFont {

    private val ICONS: Identifier = font("icons")
    private val BRAND: Identifier = font("brand")
    private val UNIFORM: Identifier = Identifier.fromNamespaceAndPath("minecraft", "uniform")

    private fun font(path: String): Identifier = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, path)

    private fun style(id: Identifier): Style = Style.EMPTY.withFont(FontDescription.Resource(id))

    private val iconStyle: Style = style(ICONS)
    private val brandStyle: Style = style(BRAND)
    private val uniformStyle: Style = style(UNIFORM)

    private val faces: Map<UiFontChoice, Pair<Style, Style>> = mapOf(
        UiFontChoice.INTER to (style(font("ui")) to style(font("ui_medium"))),
        UiFontChoice.SPACE_GROTESK to (style(font("grotesk")) to style(font("grotesk_medium"))),
        UiFontChoice.OUTFIT to (style(font("outfit")) to style(font("outfit_medium"))),
        UiFontChoice.JETBRAINS_MONO to (style(font("jetbrains")) to style(font("jetbrains_medium"))),
        UiFontChoice.CHAKRA_PETCH to (style(font("chakra")) to style(font("chakra_medium"))),
        UiFontChoice.LEXEND to (style(font("lexend")) to style(font("lexend_medium"))),
    )

    private val regularCache = HashMap<String, Component>()
    private val mediumCache = HashMap<String, Component>()
    private val iconCache = HashMap<String, Component>()
    private val brandCache = HashMap<String, Component>()
    private val widthCache = HashMap<String, Float>()

    private var lastChoice: UiFontChoice? = null

    private fun choice(): UiFontChoice =
        runCatching { Theme.fontChoice }.getOrNull() ?: UiFontChoice.INTER

    @JvmStatic
    fun face(): FontDescription? {
        if (choice() == UiFontChoice.MINECRAFT) return null
        return styleFor(false).font
    }

    private fun styleFor(bold: Boolean): Style {
        val current = choice()
        if (current == UiFontChoice.MINECRAFT) return Style.EMPTY
        if (current == UiFontChoice.UNIFORM) return uniformStyle
        val face = faces[current] ?: faces.getValue(UiFontChoice.INTER)
        return if (bold) face.second else face.first
    }

    private fun checkChoice() {
        val current = choice()
        if (lastChoice != current) {
            lastChoice = current
            regularCache.clear()
            mediumCache.clear()
            widthCache.clear()
        }
    }

    fun component(text: String, bold: Boolean = false): Component {
        checkChoice()
        val cache = if (bold) mediumCache else regularCache
        cache[text]?.let { return it }
        if (cache.size > CACHE_LIMIT) cache.clear()
        val built = Component.literal(text).withStyle(styleFor(bold))
        cache[text] = built
        return built
    }

    fun iconComponent(glyph: String): Component {
        iconCache[glyph]?.let { return it }
        if (iconCache.size > CACHE_LIMIT) iconCache.clear()
        val built = Component.literal(glyph).withStyle(iconStyle)
        iconCache[glyph] = built
        return built
    }

    fun width(text: String): Float {
        checkChoice()
        widthCache[text]?.let { return it }
        if (widthCache.size > CACHE_LIMIT) widthCache.clear()
        val measured = McCompat.font.width(component(text)).toFloat()
        widthCache[text] = measured
        return measured
    }

    fun iconWidth(glyph: String): Float = McCompat.font.width(iconComponent(glyph)).toFloat()

    fun brandComponent(text: String): Component {
        brandCache[text]?.let { return it }
        if (brandCache.size > CACHE_LIMIT) brandCache.clear()
        val built = Component.literal(text).withStyle(brandStyle)
        brandCache[text] = built
        return built
    }

    fun brandWidth(text: String): Float = McCompat.font.width(brandComponent(text)).toFloat()

    fun invalidate() {
        regularCache.clear()
        mediumCache.clear()
        iconCache.clear()
        brandCache.clear()
        widthCache.clear()
        lastChoice = null
    }

    private const val CACHE_LIMIT = 4096
}
