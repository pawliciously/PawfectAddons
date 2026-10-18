package dev.pawfect.addons.features.debug

import com.mojang.serialization.JsonOps
import dev.pawfect.addons.utils.McCompat
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import java.util.Optional

object DebugText {

    private val NAMES = listOf(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
    )

    fun legacy(component: Component?): String {
        if (component == null) return ""
        val out = StringBuilder()
        var last: Style? = null
        component.visit(
            object : FormattedText.StyledContentConsumer<Unit> {
                override fun accept(style: Style, text: String): Optional<Unit> {
                    if (text.isEmpty()) return Optional.empty()
                    if (style != last) {
                        out.append(codes(style))
                        last = style
                    }
                    out.append(text)
                    return Optional.empty()
                }
            },
            Style.EMPTY,
        )
        return out.toString()
    }

    private fun codes(style: Style): String {
        val sb = StringBuilder()
        val color = style.color
        if (color == null) {
            sb.append("§r")
        } else {
            val code = NAMES.indexOf(color.serialize())
            sb.append(if (code >= 0) "§" + "0123456789abcdef"[code] else "§" + color.serialize())
        }
        if (style.isObfuscated) sb.append("§k")
        if (style.isBold) sb.append("§l")
        if (style.isStrikethrough) sb.append("§m")
        if (style.isUnderlined) sb.append("§n")
        if (style.isItalic) sb.append("§o")
        return sb.toString()
    }

    fun json(component: Component?): String {
        if (component == null) return "null"
        return runCatching {
            val ops = McCompat.mc.level?.registryAccess()?.createSerializationContext(JsonOps.INSTANCE) ?: JsonOps.INSTANCE
            ComponentSerialization.CODEC.encodeStart(ops, component).result().map { it.toString() }.orElse("(no json)")
        }.getOrElse { "(json failed: ${it.javaClass.simpleName})" }
    }

    fun item(stack: ItemStack): String {
        if (stack.isEmpty) return "empty"
        val level = McCompat.mc.level ?: return stack.toString()
        return runCatching {
            ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                .result().map { it.toString() }.orElse(stack.toString())
        }.getOrElse { "(could not save: ${it.javaClass.simpleName})" }
    }
}
