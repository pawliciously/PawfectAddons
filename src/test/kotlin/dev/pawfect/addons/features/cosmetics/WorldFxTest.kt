package dev.pawfect.addons.features.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs motes, trails and landings through a recording consumer and checks what comes out. */
class WorldFxTest {

    private class Recorder {
        var vertices = 0
        var bad = 0
        var furthest = 0f

        val consumer: VertexConsumer = Proxy.newProxyInstance(VertexConsumer::class.java.classLoader, arrayOf(VertexConsumer::class.java)) { proxy, method, args ->
            if (method.name == "addVertex" && args != null && args.size == 4 && args[0] is PoseStack.Pose) {
                vertices++
                val x = args[1] as Float
                val y = args[2] as Float
                val z = args[3] as Float
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) bad++
                furthest = maxOf(furthest, abs(x), abs(y), abs(z))
            }
            if (method.returnType == VertexConsumer::class.java) proxy else null
        } as VertexConsumer

        fun fx() = Fx(PoseStack().last(), consumer, 0.9f)
    }

    private val palette = ResolvedName(NameStyle.WAVE, intArrayOf(0xFF6AD5, 0x8A7CFF, 0x5CE1FF), 1f, 1f, false, false)
    private val camera = Vec3(1203.5, 70.0, -4410.2)

    @Test
    fun motes() {
        val motes = ResolvedMotes(palette, 48, 0.06f, 1f, 2.2f, 2f, true)
        val anchor = camera.add(3.0, -0.5, 2.0)
        for (time in floatArrayOf(0f, 3.7f, 1800f)) {
            val r = Recorder()
            val swarm = Motes.Swarm(Motes.Job(motes, anchor, anchor.add(-1.0, 0.0, 0.4), 12345, 48), camera, time)
            swarm.gems(r.fx())
            swarm.glow(r.fx())
            println("motes t=$time: ${r.vertices} vertices, furthest ${r.furthest}")
            assertTrue(r.bad == 0 && r.vertices % 4 == 0 && r.vertices > 0)
            assertTrue(r.furthest < 8f, "motes stay round the player")
        }
    }

    @Test
    fun trail() {
        val trail = ResolvedTrail(palette, 0.45f, 0.9f, true, null)
        val track = Trails.Track()
        var dist = 0f
        // A curving run, then straight up, then hovering: every frame case the ribbon meets.
        for (i in 0 until 64) {
            val step = when {
                i < 30 -> Vec3(0.12 * kotlin.math.cos(i * 0.15), 0.0, 0.12 * kotlin.math.sin(i * 0.15))
                i < 45 -> Vec3(0.0, 0.15, 0.0)
                else -> Vec3(0.0, 0.0, 0.0001)
            }
            val last = track.points.lastOrNull()
            val p = (last?.let { Vec3(it.x, it.y, it.z) } ?: camera.add(2.0, -1.0, 2.0)).add(step)
            dist += step.length().toFloat()
            track.points.addLast(Trails.Point(p.x, p.y, p.z, 1_000_000L + i * 20L, 0.8f, dist, i))
        }
        val r = Recorder()
        val ribbon = Trails.Ribbon.of(trail, track, camera, 1_000_000L + 64 * 20L, 2f)!!
        ribbon.draw(r.fx())
        println("trail: ${r.vertices} vertices, furthest ${r.furthest}")
        assertTrue(r.bad == 0 && r.vertices % 4 == 0 && r.vertices > 0)
        assertTrue(r.furthest < 12f)
    }

    @Test
    fun landing() {
        val trail = ResolvedTrail(palette, 0.45f, 0.9f, true, ResolvedRipple(1.6f, 1f, 0.18f, 1.5f, 0.9f))
        val ring = Ripples.Ring(camera.add(4.0, -1.6, 1.0), 1_000_000L, trail, trail.ripple!!, 1f, 3)
        for (ms in longArrayOf(0L, 40L, 200L, 450L, 880L)) {
            val r = Recorder()
            val bloom = Ripples.Bloom(ring, camera, 1_000_000L + ms, 5f)
            bloom.shards(r.fx())
            bloom.light(r.fx())
            println("landing ${ms}ms: ${r.vertices} vertices, furthest ${r.furthest}")
            assertTrue(r.bad == 0 && r.vertices % 4 == 0)
            assertTrue(r.furthest < 10f)
        }
    }
}
