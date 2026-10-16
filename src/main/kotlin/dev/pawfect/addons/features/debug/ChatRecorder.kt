package dev.pawfect.addons.features.debug

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Writes everything the server says to a file while it's on: chat, action bar (only when it changes) and
 * titles. One readable file with § codes and one with the full JSON per line, for DungeonSim to copy.
 * Also keeps the last few action bars and titles for [DungeonDump] whether it's on or not.
 */
object ChatRecorder {

    private val logger = LoggerFactory.getLogger("PawfectAddons/ChatRecorder")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val RECENT = 30

    private var text: BufferedWriter? = null
    private var json: BufferedWriter? = null
    private var file: Path? = null
    private var lines = 0
    private var lastActionBar: String? = null

    val recentActionBars = ArrayDeque<String>()
    val recentTitles = ArrayDeque<String>()

    val recording: Boolean get() = text != null

    fun toggle(): String = if (recording) stop() else start()

    private fun start(): String {
        val dir = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev")
        Files.createDirectories(dir)
        val name = "chat_${LocalDateTime.now().format(stamp)}"
        val path = dir.resolve("$name.txt")
        text = Files.newBufferedWriter(path, StandardCharsets.UTF_8)
        json = Files.newBufferedWriter(dir.resolve("$name.jsonl"), StandardCharsets.UTF_8)
        file = path
        lines = 0
        lastActionBar = null
        return "§aRecording chat to §f${path.fileName}§a. Run it again to stop."
    }

    private fun stop(): String {
        runCatching { text?.close() }
        runCatching { json?.close() }
        text = null
        json = null
        return "§7Stopped. §f$lines §7lines saved to §f${file?.fileName}"
    }

    fun onMessage(message: Component, overlay: Boolean) {
        val legacy = DebugText.legacy(message)
        if (overlay) {
            if (legacy == lastActionBar) return
            lastActionBar = legacy
            remember(recentActionBars, legacy)
            write("ACTIONBAR", legacy, message)
        } else {
            write("CHAT", legacy, message)
        }
    }

    fun onPlayerChat(message: Component) = write("PLAYER", DebugText.legacy(message), message)

    fun onTitle(message: Component, subtitle: Boolean) {
        val kind = if (subtitle) "SUBTITLE" else "TITLE"
        val legacy = DebugText.legacy(message)
        remember(recentTitles, "$kind $legacy")
        write(kind, legacy, message)
    }

    private fun remember(list: ArrayDeque<String>, line: String) {
        list.addLast("${LocalTime.now().format(clock)} $line")
        while (list.size > RECENT) list.removeFirst()
    }

    private fun write(kind: String, legacy: String, message: Component) {
        val out = text ?: return
        val time = LocalTime.now().format(clock)
        runCatching {
            out.write("$time ${kind.padEnd(9)} $legacy")
            out.newLine()
            out.flush()
            json?.apply {
                write("{\"t\":\"$time\",\"kind\":\"$kind\",\"json\":${DebugText.json(message).takeIf { it.startsWith("{") || it.startsWith("[") || it.startsWith("\"") } ?: "null"}}")
                newLine()
                flush()
            }
            lines++
        }.onFailure { logger.warn("Could not write a chat line", it) }
    }
}
