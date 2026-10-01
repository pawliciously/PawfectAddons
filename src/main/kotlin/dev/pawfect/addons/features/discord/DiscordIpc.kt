package dev.pawfect.addons.features.discord

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal class DiscordIpc(private val clientId: String) {

    private val logger = LoggerFactory.getLogger("PawfectAddons/DiscordIpc")

    private var pipe: Pipe? = null

    val connected: Boolean get() = pipe != null

    fun connect(): Boolean {
        close()
        pipe = candidates().firstNotNullOfOrNull { runCatching { open(it) }.getOrNull() } ?: return false
        return try {
            send(OP_HANDSHAKE, JsonObject().apply {
                addProperty("v", 1)
                addProperty("client_id", clientId)
            })
            val reply = receive()
            if (reply.op == OP_FRAME && reply.json?.get("evt")?.asString == "READY") return true
            logger.warn("Discord refused the connection: {}", reply.text)
            close()
            false
        } catch (error: IOException) {
            close()
            false
        }
    }

    fun setActivity(activity: JsonObject?) {
        val nonce = UUID.randomUUID().toString()
        send(OP_FRAME, JsonObject().apply {
            addProperty("cmd", "SET_ACTIVITY")
            add("args", JsonObject().apply {
                addProperty("pid", ProcessHandle.current().pid())
                add("activity", activity ?: JsonNull.INSTANCE)
            })
            addProperty("nonce", nonce)
        })
        while (true) {
            val frame = receive()
            when (frame.op) {
                OP_PING -> write(OP_PONG, frame.text.toByteArray(Charsets.UTF_8))
                OP_CLOSE -> throw IOException("Discord closed the connection: ${frame.text}")
                OP_FRAME -> {
                    val json = frame.json ?: continue
                    if (json.get("nonce")?.takeUnless { it.isJsonNull }?.asString != nonce) continue
                    if (json.get("evt")?.takeUnless { it.isJsonNull }?.asString == "ERROR") {
                        logger.warn("Discord rejected the activity: {}", json.get("data"))
                    }
                    return
                }
            }
        }
    }

    fun close() {
        runCatching { pipe?.close() }
        pipe = null
    }

    private fun send(op: Int, payload: JsonObject) = write(op, payload.toString().toByteArray(Charsets.UTF_8))

    private fun write(op: Int, body: ByteArray) {
        val pipe = pipe ?: throw IOException("Not connected")
        val frame = ByteBuffer.allocate(HEADER + body.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(op).putInt(body.size).put(body)
        pipe.write(frame.array())
    }

    private fun receive(): Frame {
        val pipe = pipe ?: throw IOException("Not connected")
        val header = ByteBuffer.wrap(pipe.read(HEADER)).order(ByteOrder.LITTLE_ENDIAN)
        val op = header.getInt()
        val length = header.getInt()
        if (length !in 0..MAX_FRAME) throw IOException("Bad frame length $length")
        return Frame(op, String(pipe.read(length), Charsets.UTF_8))
    }

    private class Frame(val op: Int, val text: String) {
        val json: JsonObject? by lazy { runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() }
    }

    private interface Pipe {
        fun write(bytes: ByteArray)
        fun read(count: Int): ByteArray
        fun close()
    }

    private class WindowsPipe(path: String) : Pipe {
        private val file = RandomAccessFile(path, "rw")
        override fun write(bytes: ByteArray) = file.write(bytes)
        override fun read(count: Int): ByteArray = ByteArray(count).also { file.readFully(it) }
        override fun close() = file.close()
    }

    private class UnixPipe(path: Path) : Pipe {
        private val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path)) }
        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        override fun read(count: Int): ByteArray {
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw IOException("Discord closed the socket")
            return buffer.array()
        }
        override fun close() = channel.close()
    }

    private fun open(path: String): Pipe =
        if (WINDOWS) WindowsPipe(path) else UnixPipe(Path.of(path).also { if (!Files.exists(it)) throw IOException("No socket") })

    private fun candidates(): List<String> {
        if (WINDOWS) return (0..9).map { """\\.\pipe\discord-ipc-$it""" }
        val roots = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull { System.getenv(it) } + "/tmp"
        val nested = listOf("", "app/com.discordapp.Discord/", "snap.discord/", ".flatpak/dev.vencord.Vesktop/xdg-run/")
        return roots.distinct().flatMap { root -> nested.flatMap { sub -> (0..9).map { "$root/${sub}discord-ipc-$it" } } }
    }

    private companion object {
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1
        const val OP_CLOSE = 2
        const val OP_PING = 3
        const val OP_PONG = 4
        const val HEADER = 8
        const val MAX_FRAME = 1 shl 20
        val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}
