package dev.pawfect.addons.features.dev

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.sounds.SoundSource
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC10
import org.lwjgl.stb.STBVorbis
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dev only: menu clicks from a local sound pack instead of the vanilla button click, in PA's menus and every
 * other menu (AbstractWidgetMixin, SoundManagerMixin). The files live in
 * config/pawfectaddons/dev/sounds as <kind><n>.ogg (tap, on, off, open, close, tick, pluck...) and are not part
 * of the mod. Played straight through OpenAL in Minecraft's own context, at the UI volume.
 */
object DevClickSounds {

    private val logger = LoggerFactory.getLogger("PawfectAddons/DevClickSounds")
    private val dir: Path get() = FabricLoader.getInstance().configDir.resolve("pawfectaddons").resolve("dev").resolve("sounds")

    private class Clip(val pcm: ShortBuffer, val channels: Int, val rate: Int)

    private val clips = HashMap<String, List<Clip>>()
    private val playing = ArrayList<Pair<Int, Int>>()
    private val uiSource: SoundSource? = SoundSource.entries.firstOrNull { it.name == "UI" }

    val active: Boolean get() = ConfigManager.features.dev.devClickSounds && DevAccount.isDev()

    /** Plays a random file of this kind. False if there's none or OpenAL isn't up, so the caller can fall back. */
    fun play(kind: String): Boolean {
        if (ALC10.alcGetCurrentContext() == 0L) return false
        val options = clips.getOrPut(kind) { load(kind) }
        if (options.isEmpty()) return false
        cleanup()
        val clip = options.random()
        val volume = McCompat.mc.options.getSoundSourceVolume(SoundSource.MASTER) *
            (uiSource?.let { McCompat.mc.options.getSoundSourceVolume(it) } ?: 1f)
        if (volume <= 0f) return true
        val buffer = AL10.alGenBuffers()
        val source = AL10.alGenSources()
        AL10.alBufferData(buffer, if (clip.channels == 1) AL10.AL_FORMAT_MONO16 else AL10.AL_FORMAT_STEREO16, clip.pcm, clip.rate)
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer)
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
        AL10.alSourcef(source, AL10.AL_GAIN, volume)
        AL10.alSourcePlay(source)
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteSources(source)
            AL10.alDeleteBuffers(buffer)
            return false
        }
        playing.add(source to buffer)
        return true
    }

    /**
     * A vanilla (or other mod's) widget about to play the button click. Checkboxes and on/off cycle buttons play
     * the toggle sound for the state they're about to switch to, sliders tick, everything else taps. True if a dev
     * sound played, so the vanilla click is skipped.
     */
    @JvmStatic
    fun widgetClick(widget: Any): Boolean {
        if (!active) return false
        val kind = when (widget) {
            is Checkbox -> if (widget.selected()) "off" else "on"
            is CycleButton<*> -> (widget.value as? Boolean)?.let { if (it) "off" else "on" } ?: "tap"
            is AbstractSliderButton -> "tick"
            else -> "tap"
        }
        return runCatching { play(kind) || (kind != "tap" && play("tap")) }.getOrDefault(false)
    }

    /** Any other UI button click (tabs, world list, options keys, crafter slots, other mods): a tap. */
    @JvmStatic
    fun uiClick(): Boolean = active && runCatching { play("tap") }.getOrDefault(false)

    /** Forgets loaded files, for after swapping them in the folder. */
    fun reload() = clips.clear()

    private fun cleanup() {
        playing.removeIf { (source, buffer) ->
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) return@removeIf false
            AL10.alDeleteSources(source)
            AL10.alDeleteBuffers(buffer)
            true
        }
    }

    private fun load(kind: String): List<Clip> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("${Regex.escape(kind)}\\d*\\.ogg")) }.sorted().toList()
        }
        return files.mapNotNull { file ->
            runCatching {
                val bytes = Files.readAllBytes(file)
                val data = MemoryUtil.memAlloc(bytes.size)
                try {
                    data.put(bytes).flip()
                    MemoryStack.stackPush().use { stack ->
                        val channels = stack.mallocInt(1)
                        val rate = stack.mallocInt(1)
                        val pcm = STBVorbis.stb_vorbis_decode_memory(data, channels, rate) ?: return@runCatching null
                        Clip(pcm, channels.get(0), rate.get(0))
                    }
                } finally {
                    MemoryUtil.memFree(data)
                }
            }.onFailure { logger.warn("Could not load {}", file.fileName, it) }.getOrNull()
        }
    }
}
