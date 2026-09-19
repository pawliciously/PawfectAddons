package dev.pawfect.addons.features.media

import com.google.gson.JsonParser
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

object MediaBridge {

    class Track(
        val title: String,
        val artist: String,
        val album: String,
        val app: String,
        val playing: Boolean,
        val position: Double,
        val duration: Double,
        val canPlay: Boolean,
        val canPause: Boolean,
        val canNext: Boolean,
        val canPrev: Boolean,
    )

    private val logger = LoggerFactory.getLogger("PawfectAddons/Media")

    private val config get() = ConfigManager.features.media

    @Volatile
    var track: Track? = null
        private set

    @Volatile
    var artPath: String? = null
        private set

    @Volatile
    var artVersion: Int = 0
        private set

    @Volatile
    var thumbState: String = "no data"
        private set

    @Volatile
    var thumbReady: Boolean = false
        private set

    @Volatile
    private var running = false

    private var process: Process? = null
    private var workDir: Path? = null

    private var positionBase = 0.0
    private var positionStamp = 0L

    val available: Boolean get() = System.getProperty("os.name", "").startsWith("Windows")

    fun tick() {
        if (config.enabled && !running) start()
        if (!config.enabled && running) stop()
    }

    fun start() {
        if (running || !available) return
        running = true

        thread(name = "PawfectAddons Media Bridge", isDaemon = true) {
            runCatching { run() }.onFailure {
                logger.warn("Media bridge stopped: {}", it.message)
            }
            running = false
        }
    }

    private fun run() {
        val directory = FabricLoader.getInstance().configDir.resolve(PawfectAddons.MOD_ID).also {
            Files.createDirectories(it)
        }
        workDir = directory

        extract("MediaThumb.cs", directory.resolve("MediaThumb.cs"))

        val script = directory.resolve("media_bridge.ps1")
        if (!extract("media_bridge.ps1", script)) {
            logger.error("Media bridge script is missing from the jar")
            return
        }

        val builder = ProcessBuilder(
            shell(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            script.toAbsolutePath().toString(),
            "-WorkDir",
            directory.toAbsolutePath().toString(),
        )
        builder.redirectErrorStream(false)

        val started = builder.start()
        process = started

        started.inputStream.bufferedReader().use { reader -> consume(reader) }
    }

    private fun extract(name: String, target: Path): Boolean {
        javaClass.getResourceAsStream("/assets/${PawfectAddons.MOD_ID}/bridge/$name").use { stream ->
            if (stream == null) return false
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }

    private fun shell(): String {
        val path = System.getenv("PATH").orEmpty().split(';')
        val hasPwsh = path.any { entry ->
            runCatching { Path.of(entry, "pwsh.exe").let(Files::exists) }.getOrDefault(false)
        }
        return if (hasPwsh) "pwsh" else "powershell"
    }

    private fun consume(reader: BufferedReader) {
        while (running) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            runCatching { parse(line) }
        }
    }

    private fun parse(line: String) {
        val json = JsonParser.parseString(line).asJsonObject
        if (json.get("state")?.asString != "ok") {
            track = null
            return
        }

        fun text(key: String) = json.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        fun flag(key: String) = json.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        fun number(key: String) = json.get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

        val reported = number("position")
        if (track == null || reported != positionBase) {
            positionBase = reported
            positionStamp = System.currentTimeMillis()
        }

        thumbState = text("thumbState").ifBlank { thumbState }
        thumbReady = flag("thumbReady")

        if (flag("thumb")) {
            artPath = text("thumbPath").takeIf { it.isNotBlank() }
            artVersion++
        }

        track = Track(
            title = text("title"),
            artist = text("artist"),
            album = text("album"),
            app = text("app"),
            playing = text("status").equals("Playing", ignoreCase = true),
            position = reported,
            duration = number("duration"),
            canPlay = flag("canPlay"),
            canPause = flag("canPause"),
            canNext = flag("canNext"),
            canPrev = flag("canPrev"),
        )
    }

    fun smoothPosition(): Double {
        val current = track ?: return 0.0
        if (!current.playing) return current.position
        val elapsed = (System.currentTimeMillis() - positionStamp) / 1000.0
        return (positionBase + elapsed).coerceAtMost(current.duration)
    }

    fun command(command: String) {
        val directory = workDir ?: return
        runCatching {
            Files.writeString(directory.resolve("pawfect_media_command.txt"), command)
        }
    }

    fun togglePlayback() = command("TOGGLE")

    fun next() = command("NEXT")

    fun previous() = command("PREV")

    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
        track = null
        artPath = null
    }
}
