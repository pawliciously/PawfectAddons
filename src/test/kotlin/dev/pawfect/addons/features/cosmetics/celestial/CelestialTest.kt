package dev.pawfect.addons.features.cosmetics.celestial

import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CelestialTest {

    private val slack = 0.04f

    private fun allowed(size: Float) = 0.04f + 0.1f * size
    private val quick = !System.getProperty("search").isNullOrEmpty()

    private class Result(val pushed: Int, val furthest: Float, val reach: Float, val worst: Vector3f)

    private fun check(style: WingStyle, crouch: Boolean, wrap: Float, size: Float, back: Float, rig: Rig = style.rig): Result {
        val body = Collider.vanilla(crouch)
        val motion = Motion()
        val p = Vector3f()
        val q = Vector3f()
        val worst = Vector3f()
        var pushed = 0
        var furthest = 0f
        var reach = 1f
        for (phase in if (quick) floatArrayOf(0f, 3.1f) else floatArrayOf(0f, 1.6f, 3.1f, 4.7f)) {
            motion.force(0f, if (crouch) 1f else 0f, 0f, wrap, phase)
            val mats = motion.matrices(rig, back, size)
            for (mesh in listOf(style.fill, style.glow)) {
                for (t in if (quick) floatArrayOf(0f) else floatArrayOf(0f, 2.3f)) {
                    for (i in 0 until mesh.size) {
                        p.set(mesh.x[i], mesh.y[i], mesh.z[i])
                        style.displace(mesh.kind[i].toInt(), mesh.u[i], mesh.v[i], mesh.seed[i], t, p)
                        for (side in 0..1) {
                            mats[side * 3 + mesh.seg[i]].transformPosition(p, q)
                            val before = Vector3f(q)
                            body.push(q)
                            val d = before.distance(q)
                            if (d > 0f) {
                                pushed++
                                if (d > furthest) { furthest = d; worst.set(before) }
                            }
                            val again = Vector3f(q)
                            body.push(again)
                            assertTrue(again.distance(q) < 1e-5f, "${style.id} pushed point still inside")
                            if (side == 0 && q.z < -0.2f) reach = min(reach, q.x)
                        }
                    }
                }
            }
        }
        return Result(pushed, furthest, reach, worst)
    }

    @Test
    fun meshesAreSound() {
        for (piece in Wings.ALL + Halos.ALL + Horns.ALL) {
            for (mesh in listOf(piece.fill, piece.glow)) {
                assertEquals(0, mesh.size % 4, "${piece.id} quads")
                for (i in 0 until mesh.size) assertTrue(mesh.seg[i] in 0..2, "${piece.id} seg")
            }
        }
    }

    @Test
    fun wingsClearTheBody() {
        if (System.getProperty("search") == "1") return search()
        if (System.getProperty("search") == "fold") return searchFold()
        val problems = ArrayList<String>()
        for (style in Wings.ALL) {
            for (size in floatArrayOf(0.1f, 0.5f, 0.75f, 1f, 1.5f)) {
                for (back in floatArrayOf(WingStyle.BACK, WingStyle.BACK_CAPE)) {
                    for ((stand, wrap) in listOf(true to 0f, false to 0f, false to 0.25f, false to 0.5f, false to 0.75f, false to 1f)) {
                        val r = check(style, !stand, wrap, size, back)
                        val deep = r.furthest / 1.35f
                        if (deep > allowed(size)) problems += "${style.id} ${if (stand) "standing" else "crouch wrap $wrap"} size $size back $back: ${r.pushed} pushed, up to ${"%.3f".format(deep)} deep at ${r.worst}"
                    }
                }
            }
            val r = check(style, true, 1f, 1f, WingStyle.BACK)
            println("${style.id}: reaches x ${"%.2f".format(r.reach)} across the front")
        }
        problems.sortedByDescending { it.substringAfter("up to ").take(5) }.take(40).forEach(::println)
        println("${problems.size} poses over")
        assertTrue(problems.isEmpty(), "${problems.size} poses clip")
    }

    private fun Rig.with(wrap: Wrap? = this.wrap, fold: Fold = this.fold) =
        Rig(l0, l1, l2, sweep, lift, elbowSweep, elbowLift, wristSweep, wristLift, amp, period, lag, scale, tilt, wrap, fold)

    private fun searchFold() {
        for (style in Wings.ALL) {
            val found = ArrayList<Pair<Float, String>>()
            for (sweep in floatArrayOf(-10f, 0f, 10f, 20f, 30f)) for (elbowSweep in floatArrayOf(10f, 25f, 40f)) for (lift in floatArrayOf(-22f, -10f, 0f))
                for (tilt in floatArrayOf(0f, -6f, -12f)) for (b in floatArrayOf(0f, 0.03f, 0.06f)) {
                    val fold = Fold(sweep, lift, elbowSweep, -14f, 12f, -10f, tilt, b)
                    var worst = 0f
                    for (size in floatArrayOf(1f, 1.5f)) for (w in floatArrayOf(0f, 0.5f)) worst = max(worst, check(style, true, w, size, WingStyle.BACK, style.rig.with(fold = fold)).furthest)
                    found += worst to "${"%.3f".format(worst)} sweep $sweep elbowSweep $elbowSweep lift $lift tilt $tilt back $b"
                }
            println("${style.id}:")
            found.sortedBy { it.first }.take(6).forEach { println("  " + it.second) }
        }
    }

    private fun search() {
        val styles = listOf(Seraph, Phoenix, Prism, CelestialWings)
        val found = ArrayList<Pair<Float, String>>()
        for (rootX in floatArrayOf(0.4f, 0.46f, 0.52f)) for (rootZ in floatArrayOf(0.04f, 0.1f)) for (sweep in floatArrayOf(-10f, 0f, 10f)) for (elbowSweep in floatArrayOf(-60f, -75f, -90f))
            for (wristSweep in floatArrayOf(-50f, -70f, -90f, -110f)) for (wristLift in floatArrayOf(-10f, 10f)) for (lift in floatArrayOf(0f, 10f)) for (elbowLift in floatArrayOf(-8f, 8f)) {
                val wrap = Wrap(rootX, rootZ, sweep, lift, 0f, elbowSweep, elbowLift, wristSweep, wristLift)
                var clean = true
                var reach = -1f
                for (style in styles) {
                    val rig = style.rig.with(wrap = wrap)
                    val r = check(style, true, 1f, 1f, WingStyle.BACK, rig)
                    if (r.furthest > slack) { clean = false; break }
                    reach = max(reach, r.reach)
                }
                if (clean) found += reach to "rootX $rootX rootZ $rootZ sweep $sweep lift $lift elbowSweep $elbowSweep elbowLift $elbowLift wristSweep $wristSweep wristLift $wristLift reach ${"%.2f".format(reach)}"
            }
        found.sortedBy { it.first }.take(15).forEach { println(it.second) }
        println("${found.size} clean poses")
    }
}
