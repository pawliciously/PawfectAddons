package dev.pawfect.addons.ui

class Anim(private val duration: Long = 180L, initial: Float = 0f) {

    var value: Float = initial
        private set

    private var start: Float = initial
    private var target: Float = initial
    private var startedAt: Long = 0L

    fun update(newTarget: Float): Float {
        if (!Theme.animationsEnabled) {
            set(newTarget)
            return value
        }

        if (newTarget != target) {
            target = newTarget
            start = value
            startedAt = System.currentTimeMillis()
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val progress = (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
        value = if (progress >= 1.0) target else start + (target - start) * easeOutQuad(progress).toFloat()
        return value
    }

    fun set(newValue: Float) {
        value = newValue
        start = newValue
        target = newValue
        startedAt = 0L
    }

    companion object {
        fun easeOutQuad(t: Double): Double = 1.0 - (1.0 - t) * (1.0 - t)
        fun easeOutCubic(t: Double): Double = 1.0 - Math.pow(1.0 - t, 3.0)
    }
}
