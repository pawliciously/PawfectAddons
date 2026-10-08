package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.features.dungeon.secrets.DungeonRoom
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
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
 * Records your own route through a room as you play it: where you walk, where you etherwarp to,
 * what you stonk, levers you flip and pearls you throw, split into a step each time you collect
 * a secret. Stopping (or leaving the room) saves it to recorded.json, where it takes over from
 * any other route for that room.
 */
object RouteRecorder {

    private class Builder {
        val locations = ArrayList<IntArray>()
        val etherwarps = ArrayList<IntArray>()
        val mines = ArrayList<IntArray>()
        val interacts = ArrayList<IntArray>()
        val tnts = ArrayList<IntArray>()
        val pearls = ArrayList<DoubleArray>()
        val angles = ArrayList<FloatArray>()

        val empty: Boolean
            get() = locations.isEmpty() && etherwarps.isEmpty() && mines.isEmpty() && interacts.isEmpty() && tnts.isEmpty() && pearls.isEmpty()

        fun build(type: String?, at: IntArray?) = RouteData.Step(
            locations.toList(), etherwarps.toList(), mines.toList(), interacts.toList(), tnts.toList(),
            pearls.toList(), angles.toList(), type, at,
        )
    }

    private var room: DungeonRoom? = null
    private val steps = ArrayList<RouteData.Step>()
    private var builder = Builder()
    private var lastTickPos: Vec3? = null

    val recording: Boolean get() = room != null

    private const val SAMPLE_GAP_SQ = 9
    private const val TELEPORT_SQ = 25.0

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
            RouteData.saveRecorded(name, steps.toList())
            SecretRoutes.restart()
            "Saved a ${steps.size} step route for $name."
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
        if (!SkyBlockData.inDungeons || DungeonScanner.matchedRoom() !== here) {
            ChatUtils.chat(stop())
            return
        }
        val player = McCompat.player ?: return
        val now = player.position()
        val last = lastTickPos
        lastTickPos = now
        // A jump of more than five blocks in one tick is a teleport: an etherwarp onto the block below.
        if (last != null && last.distanceToSqr(now) > TELEPORT_SQ) {
            builder.etherwarps += relative(here, player.blockPosition().below())
            sample(force = true)
            return
        }
        sample(force = false)
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
        // A chest fires both its click and its opening; the second one is the same secret.
        val last = steps.lastOrNull()
        if (builder.empty && last != null && last.secretType == type && last.secretAt?.contentEquals(at) == true) return
        sample(force = true)
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

    fun onInteract(pos: BlockPos) {
        val here = room ?: return
        val level = McCompat.mc.level ?: return
        val block = level.getBlockState(pos).block
        when {
            block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD ->
                finish("interact", pos)
            block == Blocks.LEVER || block is ButtonBlock -> builder.interacts += relative(here, pos)
        }
    }

    fun onItem(pos: BlockPos) = finish("item", pos)

    fun onBat(pos: BlockPos) = finish("bat", pos)

    private fun relative(room: DungeonRoom, pos: BlockPos): IntArray {
        val rel = DungeonScanner.actualToRelative(room, pos)
        return intArrayOf(rel.x, rel.y, rel.z)
    }
}
