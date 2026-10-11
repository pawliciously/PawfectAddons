package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Records your own route through a room as you play it: where you walk, where you etherwarp or
 * teleport to, what you stonk, levers you flip and pearls you throw, split into a step each time
 * you collect a secret. Stopping (or leaving the dungeon) saves it to recorded.json, where it
 * takes over from any other route for that room. Recording again from the same start replaces
 * that route; from another start it's kept as a second route, picked when you come in that way.
 */
object RouteRecorder {

    private class Builder {
        val locations = ArrayList<IntArray>()
        val etherwarps = ArrayList<IntArray>()
        val teleports = ArrayList<IntArray>()
        val mines = ArrayList<IntArray>()
        val interacts = ArrayList<IntArray>()
        val tnts = ArrayList<IntArray>()
        val pearls = ArrayList<DoubleArray>()
        val angles = ArrayList<FloatArray>()

        val empty: Boolean
            get() = locations.isEmpty() && etherwarps.isEmpty() && teleports.isEmpty() && mines.isEmpty() &&
                interacts.isEmpty() && tnts.isEmpty() && pearls.isEmpty()

        fun build(type: String?, at: IntArray?) = RouteData.Step(
            locations.toList(), etherwarps.toList(), mines.toList(), interacts.toList(), tnts.toList(),
            pearls.toList(), angles.toList(), type, at, teleports = teleports.toList(),
        )
    }

    private var room: DungeonRoom? = null
    private val steps = ArrayList<RouteData.Step>()
    private var builder = Builder()
    private var lastTickPos: Vec3? = null

    val recording: Boolean get() = room != null

    /**
     * What's been recorded so far, for drawing as you go: the room, the finished steps and the one
     * still being walked, which has no secret yet.
     */
    fun preview(): Pair<DungeonRoom, List<RouteData.Step>>? {
        val here = room ?: return null
        val live = if (builder.empty) emptyList() else listOf(builder.build(null, null))
        return here to steps.toList() + live
    }

    private const val SAMPLE_GAP_SQ = 9
    private const val TELEPORT_SQ = 25.0

    /** A bat has to die this close to you, squared, to be your secret. */
    private const val BAT_RANGE_SQ = 256.0

    /** How close a clicked chest or essence has to be to one of the room's secrets, squared. */
    private const val SECRET_RANGE_SQ = 2

    fun register() {
        ClientPlayerBlockBreakEvents.AFTER.register { _, _, pos, _ -> runCatching { onMined(pos) } }
        UseItemCallback.EVENT.register { player, _, hand ->
            runCatching { if (player.getItemInHand(hand).`is`(Items.ENDER_PEARL)) onPearl() }
            InteractionResult.PASS
        }
    }

    fun start(): String {
        if (!SkyBlockData.inDungeons) return "Start recording inside a dungeon room."
        val here = DungeonScanner.matchedRoom() ?: return "This room isn't recognised yet. Walk around it a little first."
        room = here
        steps.clear()
        builder = Builder()
        lastTickPos = McCompat.player?.position()
        sample(force = true)
        return "Recording a route for ${here.name}. Collect each secret in order, then /pa route record stop."
    }

    fun stop(save: Boolean = true): String {
        val here = room ?: return "Not recording."
        val name = here.name
        room = null
        if (!save || name == null) return "Recording thrown away."
        // Whatever is left over walks out of the room.
        val player = McCompat.player
        if (player != null) {
            val exit = relative(here, player.blockPosition())
            if (!builder.empty || steps.isEmpty()) steps += builder.build("exit", exit)
        }
        if (steps.isEmpty()) return "Nothing was recorded."
        return runCatching {
            val replaced = RouteData.saveRecorded(name, steps.toList())
            SecretRoutes.restart()
            if (replaced) "Saved a ${steps.size} step route for $name, replacing the one that starts there."
            else "Saved a ${steps.size} step route for $name."
        }.getOrElse { "Could not save the route: ${it.message}" }
    }

    /** Marks the block you're looking at as a superboom spot for this step. */
    fun markSuperboom(): String {
        val here = room ?: return "Not recording."
        val hit = McCompat.mc.hitResult as? BlockHitResult ?: return "Look at the wall to superboom."
        if (hit.type != HitResult.Type.BLOCK) return "Look at the wall to superboom."
        builder.tnts += relative(here, hit.blockPos)
        return "Superboom spot added."
    }

    fun tick() {
        val here = room ?: return
        // Positions stay relative to the room you started in, so a route can pass through the
        // next room over; only leaving the dungeon ends it.
        if (!SkyBlockData.inDungeons) {
            ChatUtils.chat(stop())
            return
        }
        if (!teleported(here)) sample(force = false)
    }

    /**
     * Logs a jump of more than five blocks since the last check as a teleport. Crouching makes it
     * an etherwarp onto the block below you; otherwise it lands you in the air or on the ground.
     */
    private fun teleported(here: DungeonRoom): Boolean {
        val player = McCompat.player ?: return false
        val now = player.position()
        val last = lastTickPos
        lastTickPos = now
        if (last == null || last.distanceToSqr(now) <= TELEPORT_SQ) return false
        if (player.isShiftKeyDown) builder.etherwarps += relative(here, player.blockPosition().below())
        else builder.teleports += relative(here, player.blockPosition())
        sample(force = true)
        return true
    }

    private fun sample(force: Boolean) {
        val here = room ?: return
        val player = McCompat.player ?: return
        val point = relative(here, player.blockPosition())
        val previous = builder.locations.lastOrNull()
        if (!force && previous != null) {
            val dx = point[0] - previous[0]
            val dy = point[1] - previous[1]
            val dz = point[2] - previous[2]
            if (dx * dx + dy * dy + dz * dz < SAMPLE_GAP_SQ) return
        }
        if (previous == null || !previous.contentEquals(point)) builder.locations += point
    }

    private fun finish(type: String, pos: BlockPos) {
        val here = room ?: return
        val at = relative(here, pos)
        // The same secret again (a chest's click and opening, or a second click) is one step.
        if (steps.any { it.secretType == type && it.secretAt?.contentEquals(at) == true }) return
        // Etherwarping onto an item picks it up before the tick sees the jump, so the
        // etherwarp has to go in this step rather than the next.
        if (!teleported(here)) sample(force = true)
        steps += builder.build(type, at)
        builder = Builder()
        ChatUtils.chat("§7Step ${steps.size}: $type")
    }

    private fun onMined(pos: BlockPos) {
        val here = room ?: return
        builder.mines += relative(here, pos)
    }

    private fun onPearl() {
        val here = room ?: return
        val player = McCompat.player ?: return
        val at = DungeonScanner.actualToRelative(here, player.position())
        builder.pearls += doubleArrayOf(at.x, at.y, at.z)
        val direction = RouteGeometry.toRelative(here, player.lookAngle)
        builder.angles += RouteGeometry.angles(direction)
    }

    /** Whether [at] is one of the room's chests or essences; any chest counts in a room we have no data for. */
    private fun isSecret(here: DungeonRoom, at: IntArray): Boolean {
        val secrets = here.name?.let { SecretRoomData.secretsFor(it) }.orEmpty()
        if (secrets.isEmpty()) return true
        return secrets.any { s ->
            s.category in setOf("chest", "wither", "redstone_key") &&
                (s.x - at[0]) * (s.x - at[0]) + (s.y - at[1]) * (s.y - at[1]) + (s.z - at[2]) * (s.z - at[2]) <= SECRET_RANGE_SQ
        }
    }

    fun onInteract(pos: BlockPos) {
        val here = room ?: return
        val level = McCompat.mc.level ?: return
        val block = level.getBlockState(pos).block
        when {
            block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD ->
                if (isSecret(here, relative(here, pos))) finish("interact", pos)
            block == Blocks.LEVER || block is ButtonBlock -> builder.interacts += relative(here, pos)
        }
    }

    fun onItem(pos: BlockPos) = finish("item", pos)

    fun onBat(pos: BlockPos) {
        val player = McCompat.player ?: return
        // A bat killed in another room, by you or a teammate, isn't part of this route.
        if (player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > BAT_RANGE_SQ) return
        finish("bat", pos)
    }

    private fun relative(room: DungeonRoom, pos: BlockPos): IntArray {
        val rel = DungeonScanner.actualToRelative(room, pos)
        return intArrayOf(rel.x, rel.y, rel.z)
    }
}
