package dev.pawfect.addons.features.profile

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

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
