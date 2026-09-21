package dev.pawfect.addons.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigGuiManager
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.features.dungeon.DungeonRooms
import dev.pawfect.addons.features.dungeon.DungeonWaypointRenderer
import dev.pawfect.addons.features.dungeon.DungeonWaypoints
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.FeatureRegistry
import dev.pawfect.addons.features.recipetracker.RecipeTracker
import dev.pawfect.addons.features.debug.ActionBarDebug
import dev.pawfect.addons.features.chat.Emojis
import dev.pawfect.addons.features.debug.StandDebug
import dev.pawfect.addons.features.visual.handchams.HandChams
import dev.pawfect.addons.features.recipetracker.RecipeTrackerOverlay
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.NumberUtil.addSeparators
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.commands.SharedSuggestionProvider

object PawfectCommands {

    private const val SUCCESS = 1
    private const val CLEAR_CONFIRM_WINDOW_MS = 15_000L
    private const val EMOJI_LIST_LIMIT = 60

    private var craftableNamesCache: List<String> = emptyList()
    private var clearRequestedAt = 0L

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> build(dispatcher) }
    }

    private fun build(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        val root = dispatcher.register(
            ClientCommands.literal("pa")
                .executes {
                    PawfectAddons.queueScreen { ConfigGuiManager.open() }
                    SUCCESS
                }
                .then(
                    ClientCommands.literal("actionbar").executes {
                        if (ActionBarDebug.toggle()) ChatUtils.success("Action bar logging on. Check the game log.")
                        else ChatUtils.chat("Action bar logging off.")
                        SUCCESS
                    },
                )
                .then(
                    ClientCommands.literal("emoji")
                        .executes {
                            ChatUtils.chat("${Emojis.names().size} emojis. Type them like :fire: in chat, or /pa emoji <search>.")
                            SUCCESS
                        }
                        .then(
                            ClientCommands.argument("search", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(Emojis.names(), builder)
                                }
                                .executes { context ->
                                    showEmojis(StringArgumentType.getString(context, "search"))
                                    SUCCESS
                                },
                        ),
                )
                .then(
                    ClientCommands.literal("stands").executes {
                        ChatUtils.chat("Named armour stands nearby:")
                        StandDebug.dump().forEach { ChatUtils.chat("  $it") }
                        SUCCESS
                    },
                )
                .then(
                    ClientCommands.literal("chams").executes {
                        ChatUtils.chat("Hand chams status:")
                        HandChams.status().forEach { ChatUtils.chat("  §7$it") }
                        SUCCESS
                    },
                )
                .then(
                    ClientCommands.literal("dw")
                        .then(ClientCommands.literal("add").executes { ChatUtils.chat(DungeonWaypoints.add()); SUCCESS })
                        .then(ClientCommands.literal("clear").executes { ChatUtils.chat(DungeonWaypoints.clearRoom()); SUCCESS })
                        .then(
                            ClientCommands.literal("list").executes {
                                val room = DungeonRooms.current()
                                if (room == null) ChatUtils.chat("Could not identify this room.")
                                else {
                                    val here = DungeonWaypoints.forRoom(room.id)
                                    ChatUtils.chat("Waypoints here: ${here.size} (total ${DungeonWaypoints.all().size})")
                                    here.forEachIndexed { i, w -> ChatUtils.chat("  §7${i + 1}. ${w.name} @ ${w.x}, ${w.y}, ${w.z}") }
                                }
                                SUCCESS
                            },
                        )
                        .then(
                            ClientCommands.literal("room").executes {
                                DungeonRooms.describe().forEach { ChatUtils.chat("§7$it") }
                                ChatUtils.chat("§7Render: ${DungeonWaypointRenderer.lastError}")
                                ChatUtils.chat("§7Last frame room: ${DungeonWaypointRenderer.lastRoom.take(8)} (${DungeonWaypointRenderer.lastCount} here)")
                                DungeonScanner.describe().forEach { ChatUtils.chat("§7$it") }
                                SUCCESS
                            },
                        )
                        .then(
                            ClientCommands.literal("import").executes {
                                ChatUtils.chat(DungeonWaypoints.importFrom(DungeonWaypoints.path().parent.resolve("pa-dw-import.json")))
                                SUCCESS
                            },
                        )
                        .executes {
                            ChatUtils.chat("/pa dw add | list | clear | room | import")
                            SUCCESS
                        },
                )
                .then(
                    ClientCommands.literal("exp").executes {
                        dev.pawfect.addons.features.experiments.ExperimentManager.describe()
                            .forEach { line -> ChatUtils.chat("§7$line") }
                        SUCCESS
                    },
                )
                .then(
                    ClientCommands.literal("media").executes {
                        val art = dev.pawfect.addons.features.media.MediaArt
                        val bridge = dev.pawfect.addons.features.media.MediaBridge
                        ChatUtils.chat("enabled=" + ConfigManager.features.media.enabled + " running=" + (bridge.track != null))
                        ChatUtils.chat("track=" + (bridge.track?.title ?: "none"))
                        ChatUtils.chat("helper=" + bridge.thumbReady + " capture=" + bridge.thumbState)
                        ChatUtils.chat("art=" + art.available + " " + art.width + "x" + art.height + " " + art.lastError)
                        ChatUtils.chat("path=" + (bridge.artPath ?: "none"))
                        SUCCESS
                    },
                )
                .then(
                    ClientCommands.literal("gui").executes {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                        SUCCESS
                    },
                )
                .then(
                    ClientCommands.literal("toggle")
                        .executes {
                            listFeatures()
                            SUCCESS
                        }
                        .then(
                            ClientCommands.argument("feature", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(FeatureRegistry.names(), builder)
                                }
                                .executes { context ->
                                    toggleFeature(StringArgumentType.getString(context, "feature"))
                                    SUCCESS
                                },
                        ),
                )
                .then(untrackNode("untrack"))
                .then(
                    ClientCommands.literal("track")
                        .executes { printList(); SUCCESS }
                        .then(ClientCommands.literal("list").executes { printList(); SUCCESS })
                        .then(ClientCommands.literal("toggle").executes { toggleOverlay(); SUCCESS })
                        .then(ClientCommands.literal("clear").executes { requestClear(); SUCCESS })
                        .then(untrackNode("remove"))
                        .then(
                            ClientCommands.argument("item", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(craftableNames(), builder)
                                }
                                .executes { context ->
                                    addGoal(StringArgumentType.getString(context, "item"))
                                    SUCCESS
                                },
                        ),
                ),
        )

        dispatcher.register(
            ClientCommands.literal("pawfectaddons")
                .executes(root.command)
                .redirect(root),
        )
    }

    private fun untrackNode(name: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal(name)
            .executes {
                ChatUtils.error("Usage: /pa $name <item>")
                SUCCESS
            }
            .then(
                ClientCommands.argument("item", StringArgumentType.greedyString())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(trackedNames(), builder) }
                    .executes { context ->
                        removeGoal(StringArgumentType.getString(context, "item"))
                        SUCCESS
                    },
            )

    private fun addGoal(input: String) {
        if (!NeuRepo.isLoaded) {
            ChatUtils.error("Item data is still loading, try again in a moment.")
            return
        }

        val tokens = input.trim().split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            ChatUtils.error("Usage: /pa track <item> [amount]")
            return
        }

        val trailingAmount = tokens.last().replace(",", "").toIntOrNull()
        val amount = trailingAmount?.coerceAtLeast(1) ?: 1
        val nameText = if (trailingAmount != null) tokens.dropLast(1).joinToString(" ") else input.trim()

        if (nameText.isBlank()) {
            ChatUtils.error("Usage: /pa track <item> [amount]")
            return
        }

        var item = NeuRepo.resolve(nameText)
        var set = if (item == null) NeuRepo.resolveArmorSet(nameText) else null
        var count = amount

        if (item == null && set == null && trailingAmount != null) {
            val whole = input.trim()
            item = NeuRepo.resolve(whole)
            set = if (item == null) NeuRepo.resolveArmorSet(whole) else null
            if (item != null || set != null) count = 1
        }

        if (item == null) {
            if (set != null) {
                addArmorSet(set, count)
                return
            }
            ChatUtils.error("Could not find an item called '$nameText'.")
            return
        }

        val total = RecipeTracker.add(item.internalName, count)
        RecipeTrackerOverlay.invalidate()

        if (item.recipe.isEmpty()) {
            ChatUtils.chat(
                "Now tracking ${NeuRepo.displayName(item.internalName)}§7 x${total.addSeparators()}. " +
                    "§eThis item has no known crafting recipe, so it is tracked directly.",
            )
        } else {
            ChatUtils.success(
                "Now tracking ${NeuRepo.displayName(item.internalName)}§a x${total.addSeparators()}.",
            )
        }
        warnIfOverlayHidden()
    }

    private fun warnIfOverlayHidden() {
        if (!ConfigManager.features.recipeTracker.enabled) {
            ChatUtils.error("The Recipe Tracker overlay is disabled in /pa. Enable it to see the list.")
        }
    }

    private fun addArmorSet(set: NeuRepo.ArmorSet, amount: Int) {
        for (piece in set.pieces) {
            RecipeTracker.add(piece, amount)
        }
        RecipeTrackerOverlay.invalidate()
        warnIfOverlayHidden()
        ChatUtils.success(
            "Now tracking §d${set.displayName} Armor§a, " +
                "${set.pieces.size} piece${if (set.pieces.size == 1) "" else "s"}" +
                if (amount > 1) " x${amount.addSeparators()}." else ".",
        )
    }

    private fun removeGoal(input: String) {
        val query = input.trim()
        val itemId = NeuRepo.resolve(query)?.internalName ?: matchTrackedByName(query)

        if (itemId != null && RecipeTracker.remove(itemId)) {
            RecipeTrackerOverlay.invalidate()
            ChatUtils.success("Stopped tracking ${NeuRepo.displayName(itemId)}§a.")
            return
        }

        val set = NeuRepo.resolveArmorSet(query)
        if (set != null) {
            val removed = set.pieces.count { RecipeTracker.remove(it) }
            if (removed > 0) {
                RecipeTrackerOverlay.invalidate()
                ChatUtils.success(
                    "Stopped tracking §d${set.displayName} Armor§a, " +
                        "$removed piece${if (removed == 1) "" else "s"}.",
                )
                return
            }
        }

        ChatUtils.error("'$query' is not being tracked.")
    }

    private fun requestClear() {
        val goals = RecipeTracker.goals()
        if (goals.isEmpty()) {
            ChatUtils.chat("Nothing was being tracked.")
            return
        }

        val now = System.currentTimeMillis()
        if (now - clearRequestedAt > CLEAR_CONFIRM_WINDOW_MS) {
            clearRequestedAt = now
            ChatUtils.chat(
                "This will clear §f${goals.size}§7 tracked recipe${if (goals.size == 1) "" else "s"}. " +
                    "Run §e/pa clear§7 again within 15 seconds to confirm.",
            )
            return
        }

        clearRequestedAt = 0L
        val count = RecipeTracker.clear()
        RecipeTrackerOverlay.invalidate()
        ChatUtils.success("Cleared $count tracked recipe${if (count == 1) "" else "s"}.")
    }

    private fun showEmojis(query: String) {
        val trimmed = query.trim()
        val matches = Emojis.search(trimmed)
        if (matches.isEmpty()) {
            ChatUtils.error("No emoji matching \"$trimmed\".")
            return
        }
        ChatUtils.chat("${matches.size} match${if (matches.size == 1) "" else "es"} for \"$trimmed\":")
        matches.take(EMOJI_LIST_LIMIT).chunked(6).forEach { row ->
            ChatUtils.chat("  §7" + row.joinToString(" ") { ":$it:" })
        }
        if (matches.size > EMOJI_LIST_LIMIT) {
            ChatUtils.chat("  §8and ${matches.size - EMOJI_LIST_LIMIT} more.")
        }
    }

    private fun toggleFeature(query: String) {
        val feature = FeatureRegistry.find(query)
        if (feature == null) {
            ChatUtils.error("No feature called '${query.trim()}'. Run §e/pa toggle§c to list them.")
            return
        }
        val enabled = !feature.isEnabled()
        feature.setEnabled(enabled)
        ConfigManager.save(ConfigFileType.FEATURES, "feature toggled")
        ChatUtils.chat("${feature.displayName} ${if (enabled) "§aenabled" else "§cdisabled"}§7.")
    }

    private fun listFeatures() {
        ChatUtils.chat("Toggleable features:")
        for (feature in FeatureRegistry.entries) {
            val state = if (feature.isEnabled()) "§aon" else "§coff"
            ChatUtils.chat(" §8- §f${feature.displayName} §8[$state§8]")
        }
        ChatUtils.chat("Use §e/pa toggle <feature>§7.")
    }

    private fun toggleOverlay() {
        val visible = !RecipeTracker.overlayVisible
        RecipeTracker.overlayVisible = visible
        RecipeTrackerOverlay.invalidate()
        ChatUtils.chat("Recipe tracker overlay ${if (visible) "§ashown" else "§chidden"}§7.")
    }

    private fun printList() {
        val goals = RecipeTracker.goals()
        if (goals.isEmpty()) {
            ChatUtils.chat("Nothing tracked. Use §e/pa track <item> [amount]§7 to add something.")
            return
        }
        ChatUtils.chat("Tracking ${goals.size} recipe${if (goals.size == 1) "" else "s"}:")
        for (goal in goals) {
            ChatUtils.chat(" §8- ${NeuRepo.displayName(goal.itemId)}§7 x${goal.amount.addSeparators()}")
        }
        ChatUtils.chat("Remove one with §e/pa untrack <item>§7.")
    }

    private fun craftableNames(): List<String> {
        if (!NeuRepo.isLoaded) return emptyList()
        if (craftableNamesCache.isEmpty()) {
            craftableNamesCache =
                (NeuRepo.armorSetNames() + NeuRepo.craftableDisplayNames()).distinct().sorted()
        }
        return craftableNamesCache
    }

    private fun trackedNames(): List<String> {
        val tracked = RecipeTracker.goals().map { it.itemId }.toSet()
        val itemNames = tracked.map { NeuRepo.displayName(it).removeColor().trim() }
        val setNames = NeuRepo.armorSetNames().mapNotNull { name ->
            val set = NeuRepo.resolveArmorSet(name) ?: return@mapNotNull null
            if (set.pieces.any { it in tracked }) name else null
        }
        return (setNames + itemNames).distinct()
    }

    private fun matchTrackedByName(query: String): String? = RecipeTracker.goals()
        .firstOrNull {
            NeuRepo.displayName(it.itemId).removeColor().trim().equals(query, ignoreCase = true)
        }?.itemId
}
