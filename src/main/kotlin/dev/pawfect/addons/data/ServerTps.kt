package dev.pawfect.addons.data

/**
 * The server's tick rate, read from its time packets. A server running at full speed sends one
 * every 20 ticks, so the gap between two of them says how fast it is really ticking.
 */
object ServerTps {

    private const val SAMPLES = 6
    private const val FULL = 20f

    private val gaps = ArrayDeque<Long>()
    private var lastAt = 0L

    /** Ticks per second, or null until two time packets have arrived. */
    val tps: Float?
        get() {
            if (gaps.isEmpty()) return null
            val seconds = gaps.average() / 1_000_000_000.0
            return (FULL / seconds).toFloat().coerceIn(0f, FULL)
        }

    @JvmStatic
    fun onTimePacket() {
        val now = System.nanoTime()
        if (lastAt != 0L) {
            gaps.addLast(now - lastAt)
            while (gaps.size > SAMPLES) gaps.removeFirst()
        }
        lastAt = now
    }

    @JvmStatic
    fun reset() {
        gaps.clear()
        lastAt = 0L
    }
}
