package dev.pawfect.addons.utils

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

object ChatUtils {

    private const val GRADIENT_START = 0xE6B8FF
    private const val GRADIENT_END = 0x7B2CBF
    private const val BRACKET_COLOR = 0x9D4EDD

    private const val MOD_NAME = "PawfectAddons"

    private val prefix: Component by lazy { buildPrefix() }

    private fun buildPrefix(): Component {
        val result = Component.empty()
        result.append(colored("[", BRACKET_COLOR))

        val lastIndex = (MOD_NAME.length - 1).coerceAtLeast(1)
        for ((index, character) in MOD_NAME.withIndex()) {
            val progress = index.toDouble() / lastIndex
            result.append(colored(character.toString(), lerp(GRADIENT_START, GRADIENT_END, progress)))
        }

        result.append(colored("] ", BRACKET_COLOR))
        return result
    }

    private fun colored(text: String, rgb: Int): MutableComponent =
        Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))

    private fun lerp(from: Int, to: Int, progress: Double): Int {
        fun channel(shift: Int): Int {
            val start = from shr shift and 0xFF
            val end = to shr shift and 0xFF
            return (start + (end - start) * progress).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun send(body: String) {
        val player = McCompat.player ?: return
        player.sendSystemMessage(Component.empty().append(prefix).append(Component.literal(body)))
    }

    fun chat(message: String) = send("§7$message")

    fun success(message: String) = send("§a$message")

    fun error(message: String) = send("§c$message")
}
