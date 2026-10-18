package dev.pawfect.addons.features.visual.skybox

import org.joml.Quaternionf
import org.joml.Vector3f
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

internal object MilkyWay {

    const val METEOR_SLOTS = 3

    private const val DRIFT_PERIOD = 3600.0
    private val pole = Vector3f(0f, cos(Math.toRadians(35.0)).toFloat(), sin(Math.toRadians(35.0)).toFloat()).normalize()

    private val baseX = Vector3f()
    private val baseY = Vector3f()
    private val baseZ = Vector3f()
    private var baseSeed = Int.MIN_VALUE

    private val axisX = Vector3f()
    private val axisY = Vector3f()
    private val axisZ = Vector3f()
    private val turn = Quaternionf()

    private class Meteor {
        var active = false
        var born = 0.0
        var life = 0.0
        val start = Vector3f()
        val heading = Vector3f()
        val side = Vector3f()
        val tint = Vector3f()
        var speed = 0f
        var length = 0f
        var width = 0f
        var brightness = 0f

        val head = Vector3f()
        val path = Vector3f()
        var shown = 0f
        var trail = 0f
    }

    private val meteors = Array(METEOR_SLOTS) { Meteor() }
    private val random = Random()
    private var nextMeteor = -1.0
    private val scratch = Vector3f()

    fun update(clock: Double, seed: Int, now: Double, frequency: Float) {
        if (seed != baseSeed) placeGalaxy(seed)
        val angle = ((clock / DRIFT_PERIOD) % 1.0) * 2.0 * PI
        turn.rotationAxis(angle.toFloat(), pole)
        turn.transform(baseX, axisX)
        turn.transform(baseY, axisY)
        turn.transform(baseZ, axisZ)
        updateMeteors(now, frequency)
    }

    private fun placeGalaxy(seed: Int) {
        baseSeed = seed
        val azimuth = Math.toRadians(40.0 + seed * 137.508)
        val elevation = Math.toRadians(28.0)
        baseY.set(cos(elevation) * cos(azimuth), sin(elevation), cos(elevation) * sin(azimuth)).normalize()
        Vector3f(0f, 1f, 0f).cross(baseY, baseX).normalize()
        Quaternionf().rotationAxis(Math.toRadians(seed * 97.0).toFloat(), baseY).transform(baseX)
        baseX.cross(baseY, baseZ).normalize()
    }

    fun writeFrame(buffer: ByteBuffer, right: Vector3f, up: Vector3f, twinkle: Float, quality: Int, pixelAngle: Float) {
        put(buffer, axisX, pixelAngle)
        put(buffer, axisY, 0f)
        put(buffer, axisZ, 0f)
        buffer.putFloat(right.dot(axisX)).putFloat(right.dot(axisY)).putFloat(right.dot(axisZ)).putFloat(twinkle)
        buffer.putFloat(up.dot(axisX)).putFloat(up.dot(axisY)).putFloat(up.dot(axisZ)).putFloat(quality.toFloat())
    }

    fun writeMeteors(buffer: ByteBuffer) {
        for (m in meteors) put(buffer, m.head, if (m.active) m.shown else 0f)
        for (m in meteors) put(buffer, m.path, m.trail)
        for (m in meteors) put(buffer, m.side, m.width)
        for (m in meteors) put(buffer, m.tint, 0f)
    }

    fun clear() {
        meteors.forEach { it.active = false }
        nextMeteor = -1.0
    }

    private fun put(buffer: ByteBuffer, v: Vector3f, w: Float) {
        buffer.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(w)
    }

    private fun updateMeteors(now: Double, frequency: Float) {
        if (frequency <= 0f) {
            clear()
            return
        }
        if (nextMeteor < 0.0) nextMeteor = now + gap(frequency)
        if (now >= nextMeteor) {
            meteors.firstOrNull { !it.active }?.let { spawn(it, now) }
            nextMeteor = now + gap(frequency)
        }
        for (m in meteors) {
            if (!m.active) continue
            val age = now - m.born
            if (age >= m.life) {
                m.active = false
                continue
            }
            val angle = (m.speed * age).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            m.start.mul(c, m.head).add(scratch.set(m.heading).mul(s))
            m.heading.mul(c, m.path).add(scratch.set(m.start).mul(-s))
            m.trail = min(m.length, angle)
            val rise = smooth((age / 0.08).toFloat())
            val fall = 1f - smooth(((age - m.life * 0.55) / (m.life * 0.45)).toFloat())
            m.shown = m.brightness * rise * fall
        }
    }

    private fun gap(frequency: Float): Double {
        val rarity = 1.0 - frequency
        val mean = 2.5 + 45.0 * rarity * rarity
        return -ln(1.0 - random.nextDouble()) * mean
    }

    private fun spawn(m: Meteor, now: Double) {
        val azimuth = random.nextDouble() * 2.0 * PI
        val elevation = Math.toRadians(18.0 + random.nextDouble() * 52.0)
        m.start.set(cos(elevation) * cos(azimuth), sin(elevation), cos(elevation) * sin(azimuth)).normalize()

        val down = Vector3f(m.start.x * m.start.y, m.start.y * m.start.y - 1f, m.start.z * m.start.y).normalize()
        val across = Vector3f(m.start).cross(down).normalize()
        val swing = Math.toRadians((random.nextDouble() * 2.0 - 1.0) * 75.0).toFloat()
        m.heading.set(down).mul(cos(swing)).add(across.mul(sin(swing))).normalize()
        m.start.cross(m.heading, m.side).normalize()

        m.speed = (0.30 + random.nextDouble() * 0.36).toFloat()
        m.life = 0.45 + random.nextDouble() * 0.8
        m.length = (0.09 + random.nextDouble() * 0.17).toFloat()
        m.width = (0.0006 + random.nextDouble() * 0.0007).toFloat()
        val r = random.nextDouble()
        m.brightness = (0.8 + r * r * 1.8).toFloat()
        when (random.nextInt(100)) {
            in 0 until 70 -> m.tint.set(1f, 0.98f, 0.95f)
            in 70 until 88 -> m.tint.set(0.82f, 1f, 0.94f)
            else -> m.tint.set(1f, 0.86f, 0.72f)
        }
        m.born = now
        m.active = true
        m.head.set(m.start)
        m.path.set(m.heading)
        m.trail = 0f
        m.shown = 0f
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
