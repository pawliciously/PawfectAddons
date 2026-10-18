package dev.pawfect.addons.features.visual.menu.hub

import net.minecraft.core.BlockPos
import net.minecraft.world.level.LightLayer
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal object WalkPlanner {

    const val STEP = 0.1

    private const val STEP_UP = 0.6
    private const val MAX_CLIMB = 1.0
    private const val MAX_DROP = 3.0
    private const val JUMP_COST = 1.5
    private const val SEARCH_PAD = 10
    private const val STRIDE = 0.25
    private const val BODY_HEIGHT = 1.8
    private const val HALF_WIDTH = 0.3
    private const val EPSILON = 1e-4

    class Waypoint(val x: Double, val y: Double, val z: Double)

    class Walk(
        val xs: DoubleArray,
        val zs: DoubleArray,
        val floors: DoubleArray,
        val light: FloatArray,
        val marks: DoubleArray,
    ) {
        val length: Double get() = (xs.size - 1) * STEP
    }

    private class Cell(val x: Int, val z: Int, val feet: Double, val cost: Double, val estimate: Double, val parent: Cell?) {
        val centreX: Double get() = x + 0.5
        val centreZ: Double get() = z + 0.5
    }

    private class Stride(val feet: Double, val jumped: Boolean)

    fun plan(world: HubWorld, waypoints: List<Waypoint>): Walk? {
        val corners = ArrayList<Cell>()
        val cornerOf = IntArray(waypoints.size)
        for (i in 0 until waypoints.size - 1) {
            val cells = search(world, waypoints[i], waypoints[i + 1]) ?: return null
            val pulled = pull(world, cells)
            if (corners.isEmpty()) corners += pulled.first()
            corners += pulled.drop(1)
            cornerOf[i + 1] = corners.size - 1
        }
        return sample(world, corners, cornerOf)
    }

    private fun search(world: HubWorld, from: Waypoint, to: Waypoint): List<Cell>? {
        val startX = floor(from.x).toInt()
        val startZ = floor(from.z).toInt()
        val goalX = floor(to.x).toInt()
        val goalZ = floor(to.z).toInt()
        val minX = min(startX, goalX) - SEARCH_PAD
        val maxX = max(startX, goalX) + SEARCH_PAD
        val minZ = min(startZ, goalZ) - SEARCH_PAD
        val maxZ = max(startZ, goalZ) + SEARCH_PAD
        val width = maxZ - minZ + 1

        fun estimate(x: Int, z: Int) = hypot((x - goalX).toDouble(), (z - goalZ).toDouble())

        val startFeet = support(world, startX + 0.5, startZ + 0.5, from.y) ?: return null
        val best = HashMap<Int, Double>()
        val closed = HashSet<Int>()
        val open = PriorityQueue<Cell>(compareBy { it.cost + it.estimate })
        open += Cell(startX, startZ, startFeet, 0.0, estimate(startX, startZ), null)
        while (open.isNotEmpty()) {
            val cell = open.poll()
            if (!closed.add((cell.x - minX) * width + (cell.z - minZ))) continue
            if (cell.x == goalX && cell.z == goalZ) return generateSequence(cell) { it.parent }.toList().asReversed()

            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val x = cell.x + dx
                val z = cell.z + dz
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue
                val key = (x - minX) * width + (z - minZ)
                if (closed.contains(key)) continue
                val stride = stride(world, cell.centreX, cell.centreZ, x + 0.5, z + 0.5, cell.feet, true) ?: continue
                val cost = cell.cost + hypot(dx.toDouble(), dz.toDouble()) + if (stride.jumped) JUMP_COST else 0.0
                if ((best[key] ?: Double.MAX_VALUE) <= cost) continue
                best[key] = cost
                open += Cell(x, z, stride.feet, cost, estimate(x, z), cell)
            }
        }
        return null
    }

    private fun pull(world: HubWorld, cells: List<Cell>): List<Cell> {
        val kept = arrayListOf(cells.first())
        var at = 0
        while (at < cells.size - 1) {
            var reach = cells.size - 1
            while (reach > at + 1 && stride(world, cells[at].centreX, cells[at].centreZ, cells[reach].centreX, cells[reach].centreZ, cells[at].feet, false) == null) {
                reach--
            }
            kept += cells[reach]
            at = reach
        }
        return kept
    }

    private fun stride(world: HubWorld, fromX: Double, fromZ: Double, toX: Double, toZ: Double, feet: Double, mayJump: Boolean): Stride? {
        val steps = max(1, ceil(hypot(toX - fromX, toZ - fromZ) / STRIDE).toInt())
        var height = feet
        var jumped = false
        for (i in 1..steps) {
            val x = fromX + (toX - fromX) * i / steps
            val z = fromZ + (toZ - fromZ) * i / steps
            val ground = support(world, x, z, height) ?: return null
            if (ground - height > STEP_UP + EPSILON) {
                if (!mayJump) return null
                jumped = true
            }
            if (height - ground > MAX_DROP) return null
            height = ground
            if (!bodyClear(world, x, z, height)) return null
        }
        return Stride(height, jumped)
    }

    private fun sample(world: HubWorld, corners: List<Cell>, cornerOf: IntArray): Walk {
        val xs = ArrayList<Double>()
        val zs = ArrayList<Double>()
        val reachedAt = DoubleArray(corners.size)
        var travelled = 0.0
        for (i in 0 until corners.size - 1) {
            val a = corners[i]
            val b = corners[i + 1]
            val length = hypot(b.centreX - a.centreX, b.centreZ - a.centreZ)
            reachedAt[i] = travelled
            var along = ceil(travelled / STEP - EPSILON) * STEP - travelled
            while (along < length) {
                xs += a.centreX + (b.centreX - a.centreX) * along / length
                zs += a.centreZ + (b.centreZ - a.centreZ) * along / length
                along += STEP
            }
            travelled += length
        }
        xs += corners.last().centreX
        zs += corners.last().centreZ
        reachedAt[corners.size - 1] = (xs.size - 1) * STEP

        val floors = DoubleArray(xs.size)
        val light = FloatArray(xs.size * 3)
        var feet = corners.first().feet
        for (i in xs.indices) {
            feet = support(world, xs[i], zs[i], feet) ?: feet
            floors[i] = feet
            lightAt(world, xs[i], feet + 1.0, zs[i], light, i * 3)
        }
        return Walk(xs.toDoubleArray(), zs.toDoubleArray(), floors, light, DoubleArray(cornerOf.size) { reachedAt[cornerOf[it]] })
    }

    private fun support(world: HubWorld, x: Double, z: Double, feet: Double): Double? {
        var top = Double.NEGATIVE_INFINITY
        val base = floor(feet).toInt()
        forFootprint(x, z) { bx, bz ->
            for (by in base - MAX_DROP.toInt() - 1..base + 1) {
                val pos = BlockPos(bx, by, bz)
                val shape = world.getBlockState(pos).getCollisionShape(world, pos)
                if (shape.isEmpty) continue
                for (box in shape.toAabbs()) {
                    if (!overlaps(bx + box.minX, bx + box.maxX, x) || !overlaps(bz + box.minZ, bz + box.maxZ, z)) continue
                    val surface = by + box.maxY
                    if (surface <= feet + MAX_CLIMB + EPSILON && surface >= feet - MAX_DROP) top = max(top, surface)
                }
            }
        }
        return if (top == Double.NEGATIVE_INFINITY) null else top
    }

    private fun bodyClear(world: HubWorld, x: Double, z: Double, feet: Double): Boolean {
        var clear = true
        forFootprint(x, z) { bx, bz ->
            for (by in floor(feet).toInt()..floor(feet + BODY_HEIGHT).toInt()) {
                val pos = BlockPos(bx, by, bz)
                val shape = world.getBlockState(pos).getCollisionShape(world, pos)
                if (shape.isEmpty) continue
                for (box in shape.toAabbs()) {
                    if (!overlaps(bx + box.minX, bx + box.maxX, x) || !overlaps(bz + box.minZ, bz + box.maxZ, z)) continue
                    if (by + box.minY < feet + BODY_HEIGHT - EPSILON && by + box.maxY > feet + EPSILON) clear = false
                }
            }
        }
        return clear
    }

    private inline fun forFootprint(x: Double, z: Double, visit: (Int, Int) -> Unit) {
        for (bx in floor(x - HALF_WIDTH).toInt()..floor(x + HALF_WIDTH).toInt()) {
            for (bz in floor(z - HALF_WIDTH).toInt()..floor(z + HALF_WIDTH).toInt()) visit(bx, bz)
        }
    }

    private fun overlaps(min: Double, max: Double, centre: Double): Boolean =
        max > centre - HALF_WIDTH + EPSILON && min < centre + HALF_WIDTH - EPSILON

    private fun lightAt(world: HubWorld, x: Double, y: Double, z: Double, into: FloatArray, at: Int) {
        val fx = x - 0.5
        val fy = y - 0.5
        val fz = z - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val z0 = floor(fz).toInt()
        var sky = 0.0
        var block = 0.0
        var weight = 0.0
        for (i in 0..1) for (j in 0..1) for (k in 0..1) {
            val pos = BlockPos(x0 + i, y0 + j, z0 + k)
            if (world.getBlockState(pos).isSolidRender) continue
            val w = (if (i == 0) x0 + 1 - fx else fx - x0) * (if (j == 0) y0 + 1 - fy else fy - y0) * (if (k == 0) z0 + 1 - fz else fz - z0)
            sky += world.getBrightness(LightLayer.SKY, pos) * w
            block += world.getBrightness(LightLayer.BLOCK, pos) * w
            weight += w
        }
        if (weight > 0.0) {
            sky /= weight
            block /= weight
        } else {
            sky = 15.0
        }
        HubAtmosphere.light(sky.toFloat(), block.toFloat(), into, at)
    }
}
