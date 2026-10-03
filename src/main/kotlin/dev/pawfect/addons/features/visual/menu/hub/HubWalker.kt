package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.PoseStack
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.cosmetics.Capes
import dev.pawfect.addons.features.cosmetics.Cosmetics
import dev.pawfect.addons.features.cosmetics.Motes
import dev.pawfect.addons.features.cosmetics.Trails
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.PlayerSkinRenderCache
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.lwjgl.system.MemoryStack
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A player walking through the menu's hub scene in their cosmetics: in from off screen, a
 * greeting to the camera, then up the hill and into the castle. They move the way a player does,
 * ticked twenty times a second, and are drawn by the game's own player renderer, so the skin,
 * cape, name, trail and motes look exactly as they do in a world.
 */
internal object HubWalker {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HubWalker")

    private const val NAME = "pawliciously"
    private val ID: UUID = UUID.fromString("59abdd8f-1279-4822-bb24-7dc27b261f97")

    // Feet positions in the hub, from off screen to the spot inside the castle where the walk ends.
    private val ROUTE = listOf(
        WalkPlanner.Waypoint(-127.0, 70.0, 13.0),
        WalkPlanner.Waypoint(-155.0, 71.0, 29.4),
        WalkPlanner.Waypoint(-166.0, 73.0, 34.0),
        WalkPlanner.Waypoint(-165.0, 75.0, 46.0),
        WalkPlanner.Waypoint(-159.0, 77.0, 53.0),
        WalkPlanner.Waypoint(-177.0, 83.0, 68.0),
        WalkPlanner.Waypoint(-187.0, 84.0, 68.0),
        WalkPlanner.Waypoint(-191.0, 88.0, 43.0),
        WalkPlanner.Waypoint(-205.0, 91.0, 43.0),
        WalkPlanner.Waypoint(-217.0, 91.0, 60.0),
    )
    private const val GREETING_STOP = 1

    // Seconds after the camera settles; starting a little early means walking into a settled frame.
    private const val START_AT = -1.0
    private const val TICK_SECONDS = 0.05
    private const val MAX_TICKS_PER_FRAME = 10
    private const val SKIN_RETRY_MS = 30_000L
    // Once inside the castle the walker is gone; this long after, the walk starts over.
    private const val REAPPEAR_TICKS = 200

    // Vanilla's walking pace, jump and gravity, per tick.
    private const val WALK_SPEED = 0.2158
    private const val JUMP_SPEED = 0.42
    private const val GRAVITY = 0.08
    private const val DRAG = 0.98
    private const val STEP_UP = 0.6
    private const val LOOK_AHEAD = 1.5
    private const val WALK_PITCH = 8f
    private const val HEAD_TURN_LIMIT = 60f

    private const val SWING_TICKS = 6f
    private const val GREETING_TICKS = 72
    private val GREETING_JUMPS = intArrayOf(8, 22, 36, 50)
    private const val CROUCH_FROM = 6
    private const val CROUCH_UNTIL = 64

    private const val STANDING_HEIGHT = 1.8
    private const val CROUCHING_HEIGHT = 1.5
    private const val STANDING_EYES = 1.62f
    private const val CROUCHING_EYES = 1.27f
    private const val FULL_BRIGHT = 15728880

    // A vanilla name tag is 0.225 blocks tall wherever its owner stands, which is a handful of
    // pixels from across the scene, so it grows just enough to stay readable.
    private const val TAG_HEIGHT = 9 * 0.025
    private const val MIN_TAG_PIXELS = 12.0
    private const val MAX_TAG_SCALE = 3.0
    private val TAG_ORIGIN = Vec3(0.0, -0.5, 0.0)

    private const val FOG_SPAN = 1000f

    private enum class Phase { WAITING, WALKING, GREETING, RESTING }

    private class Body {
        var distance = 0.0
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var speed = 0.0
        var verticalSpeed = 0.0
        var onGround = true
        var bodyYaw = 0f
        var headYaw = 0f
        var headPitch = 0f
        var limbSpeed = 0f
        var limbPosition = 0f
        var cloakX = 0.0
        var cloakY = 0.0
        var cloakZ = 0.0
        var bob = 0f
        var walked = 0f
        var crouching = false
        var swing = -1f

        fun copy(from: Body) {
            distance = from.distance
            x = from.x
            y = from.y
            z = from.z
            speed = from.speed
            verticalSpeed = from.verticalSpeed
            onGround = from.onGround
            bodyYaw = from.bodyYaw
            headYaw = from.headYaw
            headPitch = from.headPitch
            limbSpeed = from.limbSpeed
            limbPosition = from.limbPosition
            cloakX = from.cloakX
            cloakY = from.cloakY
            cloakZ = from.cloakZ
            bob = from.bob
            walked = from.walked
            crouching = from.crouching
            swing = from.swing
        }
    }

    @Volatile
    private var walk: WalkPlanner.Walk? = null
    private var broken = false

    private var phase = Phase.WAITING
    private var phaseTicks = 0
    private var greeted = false
    private var age = 0
    private var pending = 0.0
    private var lastFrame = 0L
    private val current = Body()
    private val previous = Body()

    private val state = AvatarRenderState()
    private val camera = CameraRenderState()

    private var skinRequest: CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>>? = null
    private var skin: PlayerSkin? = null
    private var skinRetryAt = 0L

    private var lightTexture: DynamicTexture? = null
    private var lightColour = 0
    private var fogBuffer: GpuBuffer? = null
    private var lightmap: GpuTextureView? = null

    /** The lightmap the game should hand out while the walker draws, or null for its own. */
    @JvmStatic
    fun lightmapOverride(): GpuTextureView? = lightmap

    /** Plans the walk on the scene's build thread, while its blocks are loaded. */
    fun prepare(world: HubWorld) {
        val started = System.nanoTime()
        val planned = runCatching { WalkPlanner.plan(world, ROUTE) }
            .onFailure { logger.warn("Could not plan the menu walk.", it) }
            .getOrNull()
        walk = planned
        if (planned == null) {
            logger.info("The menu walk has no path through this capture, so nobody walks it.")
        } else {
            logger.info("Planned the menu walk: {} blocks in {} ms.", "%.1f".format(planned.length), (System.nanoTime() - started) / 1_000_000)
        }
    }

    /** Sends the walker back to the start, for a fresh visit to the menu. */
    fun restart() {
        phase = Phase.WAITING
        lastFrame = 0L
    }

    /** Stops the walker for the session after a draw error, leaving the scene itself running. */
    fun fail(error: Throwable) {
        if (broken) return
        broken = true
        logger.error("The menu walker stopped after a draw error.", error)
    }

    /**
     * Steps the walk and draws it into the scene. [showtime] counts seconds since the camera
     * settled for this visit, negative while it is still flying in.
     */
    fun draw(cameraX: Double, cameraY: Double, cameraZ: Double, rotation: Quaternionf, view: Matrix4f, projection: GpuBufferSlice, pixelAngle: Double, showtime: Double) {
        if (broken || !ConfigManager.features.menu.showcase) return
        val walk = walk ?: return
        val now = System.nanoTime()
        val elapsed = if (lastFrame == 0L) 0.0 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.5)
        lastFrame = now

        if (phase == Phase.WAITING) {
            if (showtime < START_AT || skin() == null) return
            begin(walk)
        }
        val skin = skin ?: return

        pending += elapsed / TICK_SECONDS
        var ticks = 0
        while (pending >= 1.0 && ticks < MAX_TICKS_PER_FRAME) {
            tick(walk, cameraX, cameraY, cameraZ)
            pending -= 1.0
            ticks++
        }
        pending %= 1.0
        if (phase == Phase.RESTING) {
            if (phaseTicks >= REAPPEAR_TICKS) begin(walk)
            return
        }
        render(walk, skin, pending.toFloat(), cameraX, cameraY, cameraZ, rotation, view, projection, pixelAngle)
    }

    private fun skin(): PlayerSkin? {
        skin?.let { return it }
        val request = skinRequest ?: run {
            if (System.currentTimeMillis() < skinRetryAt) return null
            McCompat.mc.playerSkinRenderCache().lookup(ResolvableProfile.createUnresolved(ID)).also { skinRequest = it }
        }
        if (!request.isDone) return null
        skin = runCatching { request.getNow(Optional.empty()).orElse(null) }.getOrNull()?.playerSkin()
        if (skin == null) {
            skinRequest = null
            skinRetryAt = System.currentTimeMillis() + SKIN_RETRY_MS
        }
        return skin
    }

    private fun begin(walk: WalkPlanner.Walk) {
        phase = Phase.WALKING
        phaseTicks = 0
        greeted = false
        age = 0
        pending = 0.0
        current.apply {
            distance = 0.0
            x = walk.xs[0]
            y = walk.floors[0]
            z = walk.zs[0]
            speed = WALK_SPEED
            verticalSpeed = 0.0
            onGround = true
            bodyYaw = travelYaw(walk, 0.0)
            headYaw = bodyYaw
            headPitch = WALK_PITCH
            limbSpeed = 0f
            limbPosition = 0f
            cloakX = x
            cloakY = y
            cloakZ = z
            bob = 0f
            walked = 0f
            crouching = false
            swing = -1f
        }
        previous.copy(current)
    }

    private fun tick(walk: WalkPlanner.Walk, cameraX: Double, cameraY: Double, cameraZ: Double) {
        previous.copy(current)
        val body = current
        age++
        phaseTicks++

        var jump = false
        when (phase) {
            Phase.WALKING -> {
                val stop = if (greeted) walk.length else walk.marks[GREETING_STOP]
                val left = stop - body.distance
                val target = if (left < 0.6) (left * 0.45).coerceIn(0.02, WALK_SPEED) else WALK_SPEED
                body.speed += (target - body.speed) * 0.5
                body.distance = min(stop, body.distance + body.speed)
                if (stop - body.distance < 1e-3) {
                    body.speed = 0.0
                    phase = if (greeted) Phase.RESTING else Phase.GREETING
                    phaseTicks = 0
                }
            }
            // A jump and a swing every so often, with the crouch key spammed throughout.
            Phase.GREETING -> {
                jump = phaseTicks in GREETING_JUMPS
                if (jump) body.swing = 0f
                body.crouching = phaseTicks in CROUCH_FROM until CROUCH_UNTIL && (phaseTicks - CROUCH_FROM) / 2 % 2 == 0
                if (phaseTicks >= GREETING_TICKS) {
                    body.crouching = false
                    greeted = true
                    phase = Phase.WALKING
                    phaseTicks = 0
                }
            }
            Phase.RESTING, Phase.WAITING -> body.speed = 0.0
        }

        val at = body.distance / WalkPlanner.STEP
        body.x = sample(walk.xs, at)
        body.z = sample(walk.zs, at)
        move(body, walk, at, jump)
        turn(body, walk, cameraX, cameraY, cameraZ)
        animate(body)
    }

    // Steps up whatever the planner allows without a jump, jumps anything taller, and falls the
    // rest of the way with vanilla's gravity.
    private fun move(body: Body, walk: WalkPlanner.Walk, at: Double, jump: Boolean) {
        val floor = sample(walk.floors, at)
        val ahead = sample(walk.floors, at + 0.4 / WalkPlanner.STEP)
        if (body.onGround) {
            when {
                jump || (phase == Phase.WALKING && ahead > body.y + STEP_UP) -> {
                    body.verticalSpeed = JUMP_SPEED
                    body.onGround = false
                }
                floor >= body.y - 1e-3 -> body.y = floor
                else -> {
                    body.verticalSpeed = 0.0
                    body.onGround = false
                }
            }
        }
        if (!body.onGround) {
            body.y += body.verticalSpeed
            body.verticalSpeed = (body.verticalSpeed - GRAVITY) * DRAG
            if (body.verticalSpeed <= 0.0 && body.y <= floor) {
                body.y = floor
                body.verticalSpeed = 0.0
                body.onGround = true
            }
        }
    }

    private fun turn(body: Body, walk: WalkPlanner.Walk, cameraX: Double, cameraY: Double, cameraZ: Double) {
        when (phase) {
            Phase.GREETING -> {
                val eyes = body.y + if (body.crouching) CROUCHING_EYES else STANDING_EYES
                val dx = cameraX - body.x
                val dz = cameraZ - body.z
                val yaw = yawToward(dx, dz)
                val pitch = -Math.toDegrees(atan2(cameraY - eyes, hypot(dx, dz))).toFloat()
                body.bodyYaw = approach(body.bodyYaw, yaw, 0.35f)
                body.headYaw = approach(body.headYaw, yaw, 0.6f)
                body.headPitch += (pitch - body.headPitch) * 0.6f
            }
            else -> {
                val yaw = travelYaw(walk, body.distance)
                body.bodyYaw = approach(body.bodyYaw, yaw, 0.3f)
                body.headYaw = approach(body.headYaw, yaw, 0.35f)
                body.headPitch += (WALK_PITCH - body.headPitch) * 0.3f
            }
        }
        body.headYaw = body.bodyYaw + Mth.clamp(Mth.wrapDegrees(body.headYaw - body.bodyYaw), -HEAD_TURN_LIMIT, HEAD_TURN_LIMIT)
    }

    // The same bookkeeping a client player does every tick: limbs, the lagging cape anchor, the
    // view bob that swings the cape, and the arm swing.
    private fun animate(body: Body) {
        val moved = hypot(body.x - previous.x, body.z - previous.z).toFloat()
        body.limbSpeed += (min(moved * 4f, 1f) - body.limbSpeed) * 0.4f
        body.limbPosition += body.limbSpeed
        body.cloakX += (body.x - body.cloakX) * 0.25
        body.cloakY += (body.y - body.cloakY) * 0.25
        body.cloakZ += (body.z - body.cloakZ) * 0.25
        body.bob += ((if (body.onGround) min(0.1f, moved) else 0f) - body.bob) * 0.4f
        body.walked += moved * 0.6f
        if (body.swing >= 0f) {
            body.swing += 1f
            if (body.swing > SWING_TICKS) body.swing = -1f
        }
    }

    private fun travelYaw(walk: WalkPlanner.Walk, distance: Double): Float {
        val here = distance / WalkPlanner.STEP
        val there = min(distance + LOOK_AHEAD, walk.length) / WalkPlanner.STEP
        val dx = sample(walk.xs, there) - sample(walk.xs, here)
        val dz = sample(walk.zs, there) - sample(walk.zs, here)
        return if (dx * dx + dz * dz < 1e-6) current.bodyYaw else yawToward(dx, dz)
    }

    private fun yawToward(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

    private fun approach(from: Float, to: Float, rate: Float): Float = from + Mth.wrapDegrees(to - from) * rate

    private fun sample(values: DoubleArray, at: Double): Double {
        val clamped = at.coerceIn(0.0, values.size - 1.0)
        val index = floor(clamped).toInt().coerceAtMost(values.size - 2).coerceAtLeast(0)
        if (values.size == 1) return values[0]
        return Mth.lerp(clamped - index, values[index], values[index + 1])
    }

    private fun render(
        walk: WalkPlanner.Walk,
        skin: PlayerSkin,
        partial: Float,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        rotation: Quaternionf,
        view: Matrix4f,
        projection: GpuBufferSlice,
        pixelAngle: Double,
    ) {
        val mc = McCompat.mc
        val x = Mth.lerp(partial.toDouble(), previous.x, current.x)
        val y = Mth.lerp(partial.toDouble(), previous.y, current.y)
        val z = Mth.lerp(partial.toDouble(), previous.z, current.z)
        val crouching = current.crouching
        val height = if (crouching) CROUCHING_HEIGHT else STANDING_HEIGHT
        val distance = sqrt((x - cameraX) * (x - cameraX) + (y + height * 0.5 - cameraY) * (y + height * 0.5 - cameraY) + (z - cameraZ) * (z - cameraZ))
        fill(skin, partial, x, y, z, crouching, distance)

        camera.pos = Vec3(cameraX, cameraY, cameraZ)
        camera.orientation.set(rotation)
        camera.initialized = true

        val features = mc.gameRenderer.featureRenderDispatcher
        val storage = features.submitNodeStorage
        val buffers = mc.renderBuffers().bufferSource()
        val lighting = mc.gameRenderer.lighting
        val modelView = RenderSystem.getModelViewStack()
        val sceneFog = RenderSystem.getShaderFog()
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE)
        modelView.pushMatrix().set(view)
        lighting.setupFor(Lighting.Entry.LEVEL)
        try {
            // The body, cape and effects take the scene's light and fog at the walker's spot.
            RenderSystem.setShaderFog(fog(distance))
            lightmap = light(walk, current.distance)
            val feet = Vec3(x, y, z)
            val eye = Vec3(cameraX, cameraY, cameraZ)
            mc.entityRenderDispatcher.submit(state, camera, x - cameraX, y - cameraY, z - cameraZ, PoseStack(), storage)
            Trails.submitShowcase(PoseStack(), storage, ID, feet, height, eye)
            Motes.submitShowcase(PoseStack(), storage, ID, feet, height, eye)
            // Features only fill the shared buffers; flushing here draws them with this camera
            // instead of leaving the last batch for the menu to draw with its flat projection.
            features.renderAllFeatures()
            buffers.endBatch()

            // The name tag stays clear of both, so it reads against the night.
            lightmap = null
            sceneFog?.let(RenderSystem::setShaderFog)
            nameTag(storage, x - cameraX, y + height + 0.5 - cameraY, z - cameraZ, distance, pixelAngle)
            features.renderAllFeatures()
            buffers.endBatch()
        } finally {
            lightmap = null
            sceneFog?.let(RenderSystem::setShaderFog)
            modelView.popMatrix()
            RenderSystem.restoreProjectionMatrix()
            lighting.setupFor(Lighting.Entry.ITEMS_3D)
        }
    }

    // What the player renderer would read off a real player entity this frame.
    private fun fill(skin: PlayerSkin, partial: Float, x: Double, y: Double, z: Double, crouching: Boolean, distance: Double) {
        val bodyYaw = Mth.rotLerp(partial, previous.bodyYaw, current.bodyYaw)
        val headYaw = Mth.rotLerp(partial, previous.headYaw, current.headYaw)
        state.skin = skin
        state.showCape = true
        Capes.apply(ID, state)
        state.x = x
        state.y = y
        state.z = z
        state.ageInTicks = age + partial
        state.boundingBoxWidth = 0.6f
        state.boundingBoxHeight = (if (crouching) CROUCHING_HEIGHT else STANDING_HEIGHT).toFloat()
        state.eyeHeight = if (crouching) CROUCHING_EYES else STANDING_EYES
        state.distanceToCameraSq = distance * distance
        state.isDiscrete = crouching
        state.lightCoords = FULL_BRIGHT
        state.nameTag = null
        state.bodyRot = bodyYaw
        state.yRot = Mth.wrapDegrees(headYaw - bodyYaw)
        state.xRot = Mth.lerp(partial, previous.headPitch, current.headPitch)
        state.walkAnimationPos = current.limbPosition - current.limbSpeed * (1f - partial)
        state.walkAnimationSpeed = min(Mth.lerp(partial, previous.limbSpeed, current.limbSpeed), 1f)
        state.pose = if (crouching) Pose.CROUCHING else Pose.STANDING
        state.isCrouching = crouching
        state.mainArm = HumanoidArm.RIGHT
        state.attackArm = HumanoidArm.RIGHT
        state.attackTime = if (current.swing >= 0f) Mth.lerp(partial, previous.swing.coerceAtLeast(0f), current.swing) / SWING_TICKS else 0f

        // Cape physics, as the player renderer works it out from the lagging cloak anchor.
        val dx = Mth.lerp(partial.toDouble(), previous.cloakX, current.cloakX) - x
        val dy = Mth.lerp(partial.toDouble(), previous.cloakY, current.cloakY) - y
        val dz = Mth.lerp(partial.toDouble(), previous.cloakZ, current.cloakZ) - z
        val forwardX = sin(Math.toRadians(bodyYaw.toDouble()))
        val forwardZ = -cos(Math.toRadians(bodyYaw.toDouble()))
        val bob = Mth.lerp(partial, previous.bob, current.bob)
        val walked = Mth.lerp(partial, previous.walked, current.walked)
        state.capeFlap = Mth.clamp(dy.toFloat() * 10f, -6f, 32f) + Mth.sin(walked * 6.0) * 32f * bob
        state.capeLean = Mth.clamp((dx * forwardX + dz * forwardZ).toFloat() * 100f, 0f, 150f)
        state.capeLean2 = Mth.clamp((dx * forwardZ - dz * forwardX).toFloat() * 100f, -20f, 20f)
    }

    private fun nameTag(storage: SubmitNodeCollector, x: Double, y: Double, z: Double, distance: Double, pixelAngle: Double) {
        val name = Cosmetics.styleNameTag(ID, NAME, Component.literal(NAME)) ?: Component.literal(NAME)
        val scale = (MIN_TAG_PIXELS * distance * pixelAngle / TAG_HEIGHT).coerceIn(1.0, MAX_TAG_SCALE).toFloat()
        val pose = PoseStack()
        pose.translate(x, y, z)
        pose.scale(scale, scale, scale)
        // Not see-through: the tag hides behind blocks like the walker does.
        storage.submitNameTag(pose, TAG_ORIGIN, 0, name, false, FULL_BRIGHT, distance * distance, camera)
    }

    // The game's fog is linear in distance and the scene's is not, so the fog starts just short
    // of the walker by the amount that gives the scene's fog at their distance. Across a body
    // that is a couple of blocks deep, it barely varies.
    private fun fog(distance: Double): GpuBufferSlice {
        val buffer = fogBuffer ?: RenderSystem.getDevice()
            .createBuffer({ "pawfect walker fog" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, FogRenderer.FOG_UBO_SIZE.toLong())
            .also { fogBuffer = it }
        val start = distance.toFloat() - HubAtmosphere.fog(distance.toFloat()) * FOG_SPAN
        val slice = buffer.slice(0L, FogRenderer.FOG_UBO_SIZE.toLong())
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE)
                .putVec4(HubAtmosphere.FOG.x, HubAtmosphere.FOG.y, HubAtmosphere.FOG.z, 1f)
                .putFloat(start)
                .putFloat(start + FOG_SPAN)
                .putFloat(Float.MAX_VALUE)
                .putFloat(Float.MAX_VALUE)
                .putFloat(Float.MAX_VALUE)
                .putFloat(Float.MAX_VALUE)
                .get()
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice, data)
        }
        return slice
    }

    // A one pixel lightmap in the scene's colour at the walker's spot: every light level reads
    // the same colour, so the body and the cape layer, which only carries light levels, match.
    private fun light(walk: WalkPlanner.Walk, distance: Double): GpuTextureView {
        val at = (distance / WalkPlanner.STEP).coerceIn(0.0, walk.xs.size - 1.0)
        val index = floor(at).toInt().coerceAtMost(walk.xs.size - 2).coerceAtLeast(0)
        val blend = (at - index).toFloat()
        fun channel(c: Int): Float {
            val a = walk.light[index * 3 + c]
            val b = walk.light[min(index + 1, walk.xs.size - 1) * 3 + c]
            return (a + (b - a) * blend).coerceIn(0f, 1f)
        }
        val colour = ARGB.colorFromFloat(1f, channel(0), channel(1), channel(2))
        val texture = lightTexture ?: DynamicTexture("Pawfect walker light", 1, 1, false).also {
            lightTexture = it
            lightColour = colour.inv()
        }
        if (colour != lightColour) {
            texture.pixels?.setPixel(0, 0, colour)
            texture.upload()
            lightColour = colour
        }
        return texture.textureView
    }
}
