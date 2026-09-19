package dev.pawfect.addons.features.combat

import com.mojang.blaze3d.audio.SoundBuffer
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.features.HitsoundConfig
import dev.pawfect.addons.mixin.SoundBufferLibraryAccessor
import dev.pawfect.addons.mixin.SoundEngineAccessor
import dev.pawfect.addons.mixin.SoundManagerAccessor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.sounds.JOrbisAudioStream
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.resources.Identifier
import net.minecraft.util.valueproviders.ConstantFloat
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

object HitsoundLibrary {

    private val BUILT_IN = listOf("impact.ogg")
    private val EXTENSIONS = setOf("ogg", "wav")
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val logger = LoggerFactory.getLogger("PawfectAddons/Hitsounds")

    val directory: Path get() = ConfigManager.configDirectory.resolve("hitsounds")

    private val installed = mutableListOf<String>()
    private var probe: Identifier? = null

    fun ids(): List<String> {
        ensureInstalled()
        return installed
    }

    fun ensureInstalled() {
        val marker = probe
        if (marker != null && registry()?.containsKey(marker) == true) return
        install()
    }

    fun reload() {
        installed.clear()
        probe = null
        install()
    }

    private fun registry(): MutableMap<Identifier, WeighedSoundEvents>? = runCatching {
        (McCompat.mc.soundManager as SoundManagerAccessor).pawfectaddonsRegistry()
    }.getOrNull()

    private fun cache(): MutableMap<Identifier, CompletableFuture<SoundBuffer>>? = runCatching {
        val engine = (McCompat.mc.soundManager as SoundManagerAccessor).pawfectaddonsSoundEngine()
        val library = (engine as SoundEngineAccessor).pawfectaddonsSoundBuffers()
        (library as SoundBufferLibraryAccessor).pawfectaddonsCache()
    }.getOrNull()

    private fun install() {
        val registry = registry() ?: return
        val cache = cache() ?: return

        installed.clear()
        probe = null

        BUILT_IN.forEach { file ->
            val bytes = runCatching {
                javaClass.getResourceAsStream("/assets/${PawfectAddons.MOD_ID}/hitsounds/$file")
                    ?.use { it.readBytes() }
            }.getOrNull() ?: return@forEach
            add(registry, cache, sanitizeName(file.substringBeforeLast('.')), bytes)
        }

        runCatching { prepareDirectory() }.onFailure {
            logger.warn("Could not prepare the hitsound folder", it)
        }

        runCatching { userFiles() }.getOrDefault(emptyList()).forEach { path ->
            val size = runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE)
            if (size > MAX_BYTES) {
                logger.warn("Skipping {}, larger than 4 MB", path.fileName)
                return@forEach
            }
            val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return@forEach
            add(registry, cache, sanitizeName(path.nameWithoutExtension), bytes)
        }

        val chosen = ConfigManager.features.hitsounds.soundId
        if (chosen.startsWith("${PawfectAddons.MOD_ID}:") && chosen !in installed) {
            ConfigManager.features.hitsounds.soundId = HitsoundConfig.DEFAULT_SOUND
        }

        logger.info("Installed {} hitsounds", installed.size)
    }

    private fun userFiles(): List<Path> {
        if (!directory.exists()) return emptyList()
        Files.list(directory).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .filter { it.extension.lowercase() in EXTENSIONS }
                .sorted(compareBy { it.fileName.toString().lowercase() })
                .toList()
        }
    }

    private fun add(
        registry: MutableMap<Identifier, WeighedSoundEvents>,
        cache: MutableMap<Identifier, CompletableFuture<SoundBuffer>>,
        name: String,
        bytes: ByteArray,
    ) {
        if (name.isEmpty()) return
        val id = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "hitsound/$name")
        if (installed.contains(id.toString())) return

        val buffer = runCatching { decode(bytes) }.getOrElse {
            logger.warn("Could not decode hitsound {}", name, it)
            null
        } ?: return

        val sound = Sound(
            id,
            ConstantFloat.of(1f),
            ConstantFloat.of(1f),
            1,
            Sound.Type.FILE,
            false,
            false,
            16,
        )
        cache[sound.path] = CompletableFuture.completedFuture(buffer)

        val events = WeighedSoundEvents(id, null)
        events.addSound(sound)
        registry[id] = events

        installed += id.toString()
        if (probe == null) probe = id
    }

    private fun decode(bytes: ByteArray): SoundBuffer {
        if (isOgg(bytes)) {
            JOrbisAudioStream(ByteArrayInputStream(bytes)).use { stream ->
                return SoundBuffer(stream.readAll(), stream.format)
            }
        }
        BufferedInputStream(ByteArrayInputStream(bytes)).use { input ->
            AudioSystem.getAudioInputStream(input).use { source ->
                val target = pcmFormat(source.format)
                val stream = if (source.format.matches(target)) {
                    source
                } else {
                    AudioSystem.getAudioInputStream(target, source)
                }
                val pcm = stream.readAllBytes()
                val direct = ByteBuffer.allocateDirect(pcm.size)
                direct.put(pcm)
                direct.flip()
                return SoundBuffer(direct, target)
            }
        }
    }

    private fun pcmFormat(source: AudioFormat): AudioFormat {
        val channels = if (source.channels >= 2) 2 else 1
        val rate = if (source.sampleRate > 0f) source.sampleRate else 44100f
        return AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)
    }

    private fun isOgg(bytes: ByteArray): Boolean =
        bytes.size > 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()

    private fun sanitizeName(raw: String): String =
        raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-') it else '_' }
            .joinToString("")
            .trim('_')

    private fun prepareDirectory() {
        if (directory.exists()) return
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("README.txt"), README)
    }

    private val README = """
        Drop .ogg or .wav files in this folder to add your own hitsounds.

        Each file shows up in the sound picker as pawfectaddons:hitsound/<filename>,
        so beep.ogg becomes pawfectaddons:hitsound/beep.

        Rules:
          - .ogg must be Ogg Vorbis, .wav must be PCM
          - keep them under 4 MB
          - short files feel best, roughly 50 to 300 ms
          - names are lowercased and anything unusual becomes an underscore

        Press Reload Sounds in the Hitsounds settings after adding files.
        The three sounds that ship with the mod are always available and are
        not written to this folder, so you cannot break them.
    """.trimIndent()
}
