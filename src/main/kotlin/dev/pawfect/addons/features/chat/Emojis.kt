package dev.pawfect.addons.features.chat

import com.google.gson.JsonParser
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.resources.Identifier
import java.util.concurrent.CompletableFuture

object Emojis {

    private const val WORD = "abcdefghijklmnopqrstuvwxyz0123456789_+-"

    private const val LONGEST = 32

    private val shortcode = Regex(":([a-z0-9_+-]{1,32}):")

    private val face = FontDescription.Resource(
        Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "emoji"),
    )

    private val style = Style.EMPTY
        .withFont(face)
        .withColor(0xFFFFFF)
        .withBold(false)
        .withItalic(false)
        .withUnderlined(false)
        .withStrikethrough(false)
        .withObfuscated(false)

    private val codes: Map<String, String> by lazy { load() }

    private val config get() = ConfigManager.features.emojis

    fun names(): List<String> = codes.keys.sorted()

    fun search(query: String): List<String> =
        codes.keys.filter { it.contains(query, ignoreCase = true) }.sorted()

    private val listed: List<String> by lazy { codes.keys.sorted().map { ":" + it + ":" } }

    fun apply(message: Component): Component {
        if (!config.enabled || codes.isEmpty()) return message
        return convert(message)
    }

    fun suggest(text: String, cursor: Int): CompletableFuture<Suggestions>? {
        if (!config.enabled || codes.isEmpty()) return null

        val typed = text.substring(0, cursor.coerceIn(0, text.length))
        val start = typed.lastIndexOf(':')
        if (start < 0 || start == typed.length - 1) return null
        if (start > 0 && !typed[start - 1].isWhitespace()) return null

        val word = typed.substring(start + 1)
        if (word.length > LONGEST || word.any { it.lowercaseChar() !in WORD }) return null

        val builder = SuggestionsBuilder(typed, start)
        return SharedSuggestionProvider.suggest(listed, builder)
    }

    private fun load(): Map<String, String> {
        val path = "/assets/" + PawfectAddons.MOD_ID + "/emoji/shortcodes.json"
        val stream = javaClass.getResourceAsStream(path) ?: return emptyMap()
        stream.use { input ->
            val json = JsonParser.parseReader(input.reader()).asJsonObject
            return json.entrySet().associate { it.key to it.value.asString }
        }
    }

    private fun convert(component: Component): Component {
        val text = (component.contents as? PlainTextContents)?.text()
        val copy = if (text != null && shortcode.containsMatchIn(text)) split(text) else component.plainCopy()
        copy.setStyle(component.style)
        component.siblings.forEach { copy.append(convert(it)) }
        return copy
    }

    private fun split(text: String): MutableComponent {
        val built = Component.empty()
        var last = 0

        for (match in shortcode.findAll(text)) {
            val code = codes[match.groupValues[1]] ?: continue
            if (match.range.first > last) built.append(Component.literal(text.substring(last, match.range.first)))
            built.append(Component.literal(code).setStyle(style))
            last = match.range.last + 1
        }

        if (last < text.length) built.append(Component.literal(text.substring(last)))
        return built
    }
}
