package dev.pawfect.addons.features.media

import dev.pawfect.addons.config.ModuleSwitch
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

    data class Track(
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

    private val config get() = ModuleSwitch.view("media", ConfigManager.features.media)

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
    private var lastReported = Double.NaN
    private var lastUpdated = Long.MIN_VALUE

    @Volatile
    private var lastLineAt = 0L
    private var startedAt = 0L

    @Volatile
    private var heldPlaying: Boolean? = null
    @Volatile
    private var heldUntil = 0L

    val available: Boolean get() = System.getProperty("os.name", "").startsWith("Windows")

    fun tick() {
        val now = System.currentTimeMillis()
        if (config.enabled && !running && now - startedAt >= RESTART_GAP_MS) start()
        if (!config.enabled && running) stop()
        if (running && config.enabled) {
            val quietFor = now - maxOf(lastLineAt, startedAt)
            val limit = if (lastLineAt < startedAt) STARTUP_TIMEOUT_MS else QUIET_TIMEOUT_MS
            if (quietFor > limit) {
                logger.warn("Media bridge went quiet for {} ms, restarting it", quietFor)
                runCatching { process?.destroyForcibly() }
                startedAt = now
            }
        }
    }

    fun start() {
        if (running || !available) return
        running = true
        startedAt = System.currentTimeMillis()

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
            "-ParentPid",
            ProcessHandle.current().pid().toString(),
        )
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)

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
            lastLineAt = System.currentTimeMillis()
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

        thumbState = text("thumbState").ifBlank { thumbState }
        thumbReady = flag("thumbReady")

        if (flag("thumb")) {
            artPath = text("thumbPath").takeIf { it.isNotBlank() }
            artVersion++
        }

        val now = System.currentTimeMillis()
        val reportedPlaying = text("status").equals("Playing", ignoreCase = true)
        val held = heldPlaying
        val holding = held != null && now < heldUntil
        val playing = if (holding && held != reportedPlaying) {
            held
        } else {
            heldPlaying = null
            reportedPlaying
        }

        val reported = number("position")
        val duration = number("duration")
        val updated = json.get("updated")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        val age = now - updated
        val live = if (reportedPlaying && updated > SANE_STAMP && age in 0..(duration * 1000.0 + 5_000.0).toLong()) {
            (reported + age / 1000.0).coerceAtMost(duration.takeIf { it > 0.0 } ?: Double.MAX_VALUE)
        } else {
            reported
        }
        if (!holding && (track == null || reported != lastReported || updated != lastUpdated)) {
            positionBase = live
            positionStamp = now
        }
        lastReported = reported
        lastUpdated = updated

        track = Track(
            title = text("title"),
            artist = text("artist"),
            album = text("album"),
            app = text("app"),
            playing = playing,
            position = if (holding) positionBase else live,
            duration = duration,
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

    fun togglePlayback() {
        command("TOGGLE")
        val current = track ?: return
        val now = System.currentTimeMillis()
        if (current.playing) {
            positionBase = smoothPosition()
        }
        positionStamp = now
        heldPlaying = !current.playing
        heldUntil = now + HOLD_MS
        track = current.copy(playing = !current.playing, position = positionBase)
    }

    fun next() = command("NEXT")

    fun previous() = command("PREV")

    private const val RESTART_GAP_MS = 5_000L
    private const val STARTUP_TIMEOUT_MS = 20_000L
    private const val QUIET_TIMEOUT_MS = 8_000L
    private const val HOLD_MS = 1_500L

    private const val SANE_STAMP = 978_307_200_000L

    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
        track = null
        artPath = null
    }
}
