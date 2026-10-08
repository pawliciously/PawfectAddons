package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import net.minecraft.world.phys.Vec3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Turning directions between the room's own frame and the world's, as the room is rotated. */
object RouteGeometry {

    fun direction(pitch: Float, yaw: Float): Vec3 {
        val p = Math.toRadians(pitch.toDouble())
        val y = Math.toRadians(yaw.toDouble())
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** Pitch and yaw in degrees, the way the game measures them. */
    fun angles(direction: Vec3): FloatArray {
        val length = direction.length().takeIf { it > 1.0e-6 } ?: return floatArrayOf(0f, 0f)
        val d = direction.scale(1.0 / length)
        val pitch = Math.toDegrees(-asin(d.y.coerceIn(-1.0, 1.0))).toFloat()
        val yaw = Math.toDegrees(atan2(-d.x, d.z)).toFloat()
        return floatArrayOf(pitch, yaw)
    }

    fun toActual(room: DungeonRoom, direction: Vec3): Vec3 =
        DungeonScanner.relativeToActual(room, direction).subtract(DungeonScanner.relativeToActual(room, Vec3.ZERO))

    fun toRelative(room: DungeonRoom, direction: Vec3): Vec3 =
        DungeonScanner.actualToRelative(room, direction).subtract(DungeonScanner.actualToRelative(room, Vec3.ZERO))
}
