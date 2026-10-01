package dev.pawfect.addons.features.profile

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * Turns SkyBlock's `§` formatted strings into styled components. Keeping the codes as
 * raw text renders, but the colours then aren't real styles, so anything that reads an
 * item's rarity colour (the tooltip border, for one) can't see them.
 */
object LegacyText {

    private val BASE: Style = Style.EMPTY.withItalic(false)

    fun parse(text: String): MutableComponent {
        val root = Component.empty().withStyle(BASE)
        var style = BASE
        val run = StringBuilder()

        fun flush() {
            if (run.isEmpty()) return
            root.append(Component.literal(run.toString()).setStyle(style))
            run.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                val format = ChatFormatting.getByCode(text[i + 1].lowercaseChar())
                if (format != null) {
                    flush()
                    // A colour code clears any bold/italic before it, as it did in 1.8.
                    style = when {
                        format == ChatFormatting.RESET -> BASE
                        format.isColor -> BASE.withColor(format)
                        else -> style.applyFormat(format)
                    }
                }
                i += 2
                continue
            }
            run.append(c)
            i++
        }
        flush()
        return root
    }
}
