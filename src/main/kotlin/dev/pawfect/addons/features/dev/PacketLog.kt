package dev.pawfect.addons.features.dev

import dev.pawfect.addons.config.ConfigManager
import net.minecraft.network.protocol.Packet
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PacketEntry(
    val inbound: Boolean,
    val name: String,
    val fullId: String,
    val millis: Long,
    val packet: Packet<*>,
) {
    var seq: Int = 0

    private var stamp: String? = null

    val time: String
        get() = stamp ?: format(millis).also { stamp = it }

    private companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun format(millis: Long): String = runCatching {
            TIME.format(LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
        }.getOrDefault("--:--:--")
    }
}

object PacketLog {

    const val CAPACITY = 4096

    private const val VIEW_GRACE = 2000L
    private const val MAX_VALUE_CHARS = 200
    private const val MAX_LINES = 48

    @JvmField
    @Volatile
    var capturing = false

    private val lock = Any()
    private val ring = arrayOfNulls<PacketEntry>(CAPACITY)
    private var writeIndex = 0
    private var stored = 0
    private var sequence = 0

    @Volatile
    var version = 0
        private set

    @Volatile
    private var lastViewed = 0L

    private val config get() = ConfigManager.features.dev

    private val SPAM = setOf(
        "level_chunk_with_light",
        "light_update",
        "move_entity_pos",
        "move_entity_pos_rot",
        "move_entity_rot",
        "rotate_head",
        "set_entity_motion",
        "teleport_entity",
        "entity_position_sync",
        "keep_alive",
        "bundle_delimiter",
        "set_time",
        "set_chunk_cache_center",
        "chunk_batch_start",
        "chunk_batch_finished",
        "block_changed_ack",
        "move_player_pos",
        "move_player_pos_rot",
        "move_player_rot",
        "move_player_status_only",
        "client_tick_end",
        "accept_teleportation",
        "player_input",
    )

    fun markViewed() {
        lastViewed = System.currentTimeMillis()
    }

    fun tick() {
        val dev = runCatching { config }.getOrNull() ?: return
        capturing = dev.alwaysCapture || System.currentTimeMillis() - lastViewed < VIEW_GRACE
    }

    @JvmStatic
    fun record(inbound: Boolean, packet: Packet<*>) {
        val dev = config
        if (inbound && !dev.captureInbound) return
        if (!inbound && !dev.captureOutbound) return

        val id = runCatching { packet.type().id() }.getOrNull() ?: return
        val name = id.path
        if (dev.hideSpam && name in SPAM) return

        val entry = PacketEntry(inbound, name, id.toString(), System.currentTimeMillis(), packet)
        synchronized(lock) {
            entry.seq = ++sequence
            ring[writeIndex] = entry
            writeIndex = (writeIndex + 1) % CAPACITY
            if (stored < CAPACITY) stored++
            version++
        }
    }

    fun snapshot(): List<PacketEntry> = synchronized(lock) {
        val out = ArrayList<PacketEntry>(stored)
        val start = if (stored < CAPACITY) 0 else writeIndex
        for (i in stored - 1 downTo 0) {
            out.add(ring[(start + i) % CAPACITY] ?: continue)
        }
        out
    }

    fun size(): Int = synchronized(lock) { stored }

    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            writeIndex = 0
            stored = 0
            version++
        }
    }

    fun detail(entry: PacketEntry): List<String> {
        val lines = ArrayList<String>()
        lines.add("type    ${entry.fullId}")
        lines.add("flow    ${if (entry.inbound) "clientbound / inbound" else "serverbound / outbound"}")
        lines.add("class   ${entry.packet.javaClass.simpleName}")
        lines.add("")
        runCatching { readFields(entry.packet, lines) }.onFailure {
            lines.add("<fields unreadable: ${it.javaClass.simpleName}>")
        }
        if (lines.size == 4) lines.add("<no instance fields>")
        return lines
    }

    private fun readFields(packet: Packet<*>, out: MutableList<String>) {
        var cls: Class<*>? = packet.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                if (out.size >= MAX_LINES) {
                    out.add("...")
                    return
                }
                val value = runCatching {
                    field.isAccessible = true
                    render(field.get(packet))
                }.getOrElse { "<${it.javaClass.simpleName}>" }
                out.add(field.name.take(20).padEnd(20) + "  " + value)
            }
            cls = cls.superclass
        }
    }

    private fun render(value: Any?): String {
        val text = when (value) {
            null -> "null"
            is ByteArray -> "byte[${value.size}]"
            is IntArray -> "int[${value.size}]"
            is LongArray -> "long[${value.size}]"
            is Array<*> -> "${value.javaClass.simpleName}[${value.size}]"
            is Collection<*> -> collection(value)
            else -> runCatching { value.toString() }.getOrElse { "<toString threw>" }
        }
        return if (text.length > MAX_VALUE_CHARS) text.take(MAX_VALUE_CHARS) + "..." else text
    }

    private fun collection(value: Collection<*>): String {
        val head = "${value.javaClass.simpleName}(${value.size})"
        if (value.isEmpty() || value.size > 6) return head
        return head + " " + value.joinToString(", ", "[", "]") {
            runCatching { it.toString() }.getOrElse { "?" }.take(40)
        }
    }
}
