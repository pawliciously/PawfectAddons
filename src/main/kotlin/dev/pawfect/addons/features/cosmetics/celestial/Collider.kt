package dev.pawfect.addons.features.cosmetics.celestial

import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.player.PlayerModel
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs

/**
 * The player as boxes in body space, padded for armour, taken from the model as it is posed this
 * frame. Wing vertices that end up inside one are moved out through the nearest face, so wings
 * never cut into you whatever the pose, size or animation.
 */
class Collider {
    private val toBody = Array(BOXES.size) { Matrix4f() }
    private val fromBody = Array(BOXES.size) { Matrix4f() }
    private val local = Vector3f()
    private val exit = Vector3f()
    private val probe = Vector3f()

    /** Each box's bounds in body space, so most vertices are let through without a transform. */
    private val bounds = Array(BOXES.size) { FloatArray(6) }

    /** Places box [i] with [partToBody], the part's transform relative to the body. */
    fun place(i: Int, partToBody: Matrix4f) {
        toBody[i].set(partToBody)
        fromBody[i].set(partToBody).invert()
        val b = BOXES[i]
        val out = bounds[i]
        out.fill(Float.MAX_VALUE, 0, 3)
        out.fill(-Float.MAX_VALUE, 3, 6)
        for (corner in 0..7) {
            probe.set(b[if (corner and 1 == 0) 0 else 3], b[if (corner and 2 == 0) 1 else 4], b[if (corner and 4 == 0) 2 else 5])
            partToBody.transformPosition(probe)
            for (axis in 0..2) {
                out[axis] = minOf(out[axis], probe[axis])
                out[axis + 3] = maxOf(out[axis + 3], probe[axis])
            }
        }
    }

    /** Moves [q], in body space, to the nearest point outside every box. */
    fun push(q: Vector3f) {
        var bestX = 0f
        var bestY = 0f
        var bestZ = 0f
        var best = Float.MAX_VALUE
        for (i in BOXES.indices) {
            if (!inside(bounds[i], q)) continue
            val b = BOXES[i]
            fromBody[i].transformPosition(q, local)
            if (!inside(b, local)) continue
            for (face in 0..5) {
                val axis = face % 3
                val d = abs(local[axis] - b[face])
                if (d >= best) continue
                val out = if (face < 3) -1f else 1f
                exit.set(local).setComponent(axis, b[face] + out * (EXIT + BOUNCE * d))
                toBody[i].transformPosition(exit)
                if (contains(exit)) {
                    exit.set(local).setComponent(axis, b[face] + out * EXIT)
                    toBody[i].transformPosition(exit)
                    if (contains(exit)) continue
                }
                best = d
                bestX = exit.x
                bestY = exit.y
                bestZ = exit.z
            }
        }
        if (best != Float.MAX_VALUE) q.set(bestX, bestY, bestZ)
    }

    private fun contains(p: Vector3f): Boolean {
        for (i in BOXES.indices) if (inside(bounds[i], p) && inside(BOXES[i], fromBody[i].transformPosition(p, probe))) return true
        return false
    }

    private fun inside(b: FloatArray, l: Vector3f) =
        l.x > b[0] && l.x < b[3] && l.y > b[1] && l.y < b[4] && l.z > b[2] && l.z < b[5]

    companion object {
        /** Gap kept between wing and skin, enough for armour. */
        const val PAD = 0.07f

        /** How far past a face a moved vertex lands, so it is clearly outside. */
        private const val EXIT = 1e-4f

        /**
         * Vertices that went deeper come out further, so a wing bends off you instead of
         * flattening, and the two wings never meet on one plane and flicker.
         */
        private const val BOUNCE = 0.35f

        /** Helmets sit close and the head turns, so it gets less room than the rest. */
        private const val HEAD_PAD = 0.03f

        const val TORSO = 0
        const val HEAD = 1
        const val RIGHT_ARM = 2
        const val LEFT_ARM = 3
        const val RIGHT_LEG = 4
        const val LEFT_LEG = 5

        /** Each part's box in its own space, in blocks, padded: min x y z then max x y z. */
        private val BOXES = arrayOf(
            box(-4f, 0f, -2f, 4f, 12f, 2f),
            box(-4f, -8f, -4f, 4f, 0f, 4f, HEAD_PAD),
            box(-3f, -2f, -2f, 1f, 10f, 2f),
            box(-1f, -2f, -2f, 3f, 10f, 2f),
            box(-2f, 0f, -2f, 2f, 12f, 2f),
            box(-2f, 0f, -2f, 2f, 12f, 2f),
        )

        private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, pad: Float = PAD) =
            floatArrayOf(x0 / 16f - pad, y0 / 16f - pad, z0 / 16f - pad, x1 / 16f + pad, y1 / 16f + pad, z1 / 16f + pad)

        /** The part's transform from its pivot and rotation, as [ModelPart.translateAndRotate] applies it. */
        fun transform(x: Float, y: Float, z: Float, xRot: Float, yRot: Float = 0f, zRot: Float = 0f): Matrix4f =
            Matrix4f().translate(x / 16f, y / 16f, z / 16f).rotateZYX(zRot, yRot, xRot)

        private fun ModelPart.transform() = transform(x, y, z, xRot, yRot, zRot)

        /** A snapshot of [model] as posed right now, since the geometry is drawn after it moves on. */
        fun of(model: PlayerModel): Collider {
            val inv = model.body.transform().invert()
            val c = Collider()
            c.place(TORSO, Matrix4f())
            c.place(HEAD, Matrix4f(inv).mul(model.head.transform()))
            c.place(RIGHT_ARM, Matrix4f(inv).mul(model.rightArm.transform()))
            c.place(LEFT_ARM, Matrix4f(inv).mul(model.leftArm.transform()))
            c.place(RIGHT_LEG, Matrix4f(inv).mul(model.rightLeg.transform()))
            c.place(LEFT_LEG, Matrix4f(inv).mul(model.leftLeg.transform()))
            return c
        }

        /** The vanilla pose, standing or crouched, for checking without a model. */
        fun vanilla(crouch: Boolean): Collider {
            val body = if (crouch) transform(0f, 3.2f, 0f, 0.5f) else Matrix4f()
            val inv = Matrix4f(body).invert()
            val c = Collider()
            c.place(TORSO, Matrix4f())
            c.place(HEAD, Matrix4f(inv).mul(transform(0f, if (crouch) 4.2f else 0f, 0f, 0f)))
            c.place(RIGHT_ARM, Matrix4f(inv).mul(if (crouch) transform(-5f, 5.2f, 0f, 0.4f) else transform(-5f, 2f, 0f, 0f)))
            c.place(LEFT_ARM, Matrix4f(inv).mul(if (crouch) transform(5f, 5.2f, 0f, 0.4f) else transform(5f, 2f, 0f, 0f)))
            c.place(RIGHT_LEG, Matrix4f(inv).mul(if (crouch) transform(-1.9f, 12.2f, 4f, 0f) else transform(-1.9f, 12f, 0f, 0f)))
            c.place(LEFT_LEG, Matrix4f(inv).mul(if (crouch) transform(1.9f, 12.2f, 4f, 0f) else transform(1.9f, 12f, 0f, 0f)))
            return c
        }
    }
}
