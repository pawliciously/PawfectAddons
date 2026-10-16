package dev.pawfect.addons

import dev.pawfect.addons.features.dungeon.DungeonMap
import dev.pawfect.addons.features.dungeon.RoomNameOverlay
import dev.pawfect.addons.features.dungeon.routes.RouteRecorder
import dev.pawfect.addons.features.dungeon.routes.RouteRenderer
import dev.pawfect.addons.features.dungeon.routes.SecretRoutes
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.commands.PawfectCommands
import dev.pawfect.addons.config.ConfigFileType
import dev.pawfect.addons.config.ConfigGuiManager
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.combat.Hitmarker
import dev.pawfect.addons.features.combat.Hitsound
import dev.pawfect.addons.features.dev.PacketLog
import dev.pawfect.addons.features.dev.PacketOverlay
import dev.pawfect.addons.features.dev.RoomCapture
import dev.pawfect.addons.features.dev.RoomRecorder
import dev.pawfect.addons.data.BazaarApi
import dev.pawfect.addons.data.ItemSources
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.data.SackApi
import dev.pawfect.addons.features.chat.ChatCosmetics
import dev.pawfect.addons.features.chat.ChatStyle
import dev.pawfect.addons.features.chat.LevelPrestige
import dev.pawfect.addons.features.chat.Emojis
import dev.pawfect.addons.features.cosmetics.Capes
import dev.pawfect.addons.features.cosmetics.celestial.Celestial
import dev.pawfect.addons.features.cosmetics.CosmeticEnroll
import dev.pawfect.addons.features.cosmetics.LegacyMotes
import dev.pawfect.addons.features.cosmetics.LegacyRipples
import dev.pawfect.addons.features.cosmetics.LegacyTrails
import dev.pawfect.addons.features.cosmetics.Motes
import dev.pawfect.addons.features.cosmetics.Ripples
import dev.pawfect.addons.features.cosmetics.Trails
import dev.pawfect.addons.features.cosmetics.Cosmetics
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.data.UpdateCheck
import dev.pawfect.addons.features.debug.ActionBarDebug
import dev.pawfect.addons.features.debug.ChatRecorder
import dev.pawfect.addons.features.stats.ActionBarStats
import dev.pawfect.addons.features.stats.LowHealthAlert
import dev.pawfect.addons.features.stats.StatsOverlay
import dev.pawfect.addons.features.recipetracker.RecipeTrackerOverlay
import dev.pawfect.addons.features.slayer.NukekubiGlow
import dev.pawfect.addons.features.slayer.NukekubiTracer
import dev.pawfect.addons.features.slayer.SlayerManager
import dev.pawfect.addons.features.slayer.SlayerOverlay
import dev.pawfect.addons.features.slayer.SlayerWorldRender
import dev.pawfect.addons.features.visual.LavaChanger
import dev.pawfect.addons.features.visual.handchams.ChamsPipelines
import dev.pawfect.addons.features.discord.DiscordPresence
import dev.pawfect.addons.features.media.MediaBridge
import dev.pawfect.addons.features.dungeon.DungeonWaypointRenderer
import dev.pawfect.addons.features.visual.menu.hub.HubCapture
import dev.pawfect.addons.features.visual.menu.hub.HubPipelines
import dev.pawfect.addons.features.visual.menu.hub.HubSceneRenderer
import dev.pawfect.addons.features.dungeon.DungeonParty
import dev.pawfect.addons.features.dungeon.HealerBox
import dev.pawfect.addons.features.dungeon.secrets.SecretRoomData
import dev.pawfect.addons.features.dungeon.secrets.SecretTracker
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretWaypointRenderer
import dev.pawfect.addons.features.media.MediaOverlay
import dev.pawfect.addons.features.visual.motionblur.MotionBlur
import dev.pawfect.addons.features.visual.motionblur.MotionBlurPipelines
import dev.pawfect.addons.features.visual.skybox.SkyboxPipelines
import dev.pawfect.addons.features.visual.skybox.Skybox
import dev.pawfect.addons.ui.gpu.UiPipelines
import dev.pawfect.addons.features.visual.handchams.HandChams
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import dev.pawfect.addons.features.experiments.ExperimentManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import dev.pawfect.addons.ui.Watermark
import dev.pawfect.addons.ui.Notifications
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import dev.pawfect.addons.features.dungeon.InvincibilityTimers
import dev.pawfect.addons.features.drops.DropCelebration
import dev.pawfect.addons.features.drops.DropCelebrationOverlay
import dev.pawfect.addons.features.drops.ItemGet
import dev.pawfect.addons.features.rng.RngMeter
import dev.pawfect.addons.features.visual.AmbientMotes
import dev.pawfect.addons.features.rng.RngMeterOverlay
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.screens.ChatScreen
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object PawfectAddons : ClientModInitializer {

    const val MOD_ID = "pawfectaddons"

    private const val TICKS_PER_SECOND = 20
    private const val AUTOSAVE_INTERVAL_TICKS = TICKS_PER_SECOND * 60

    private val logger = LoggerFactory.getLogger("PawfectAddons")

    val VERSION: String = FabricLoader.getInstance()
        .getModContainer(MOD_ID)
        .map { it.metadata.version.friendlyString }
        .orElse("unknown")

    private var pendingScreenAction: (() -> Unit)? = null

    private var secondTicker = 0
    private var autosaveTicker = 0
    private var lastVisualSignature = ""
    private var configApplied = false

    override fun onInitializeClient() {
        logger.info("Starting PawfectAddons {}", VERSION)

        ConfigManager.load()
        ConfigGuiManager.build()
        NeuRepo.loadAsync()
        PawfectCommands.register()
        PawfectKeybinds.register()
        ChamsPipelines.bootstrap()
        UiPipelines.bootstrap()
        SkyboxPipelines.bootstrap()
        MotionBlurPipelines.bootstrap()
        HubPipelines.bootstrap()
        SlayerWorldRender.register()
        NukekubiTracer.register()

        registerHud()
        registerScreenInput()
        DungeonWaypointRenderer.register()
        HealerBox.register()
        DungeonParty.register()
        Trails.register()
        Ripples.register()
        Motes.register()
        LegacyTrails.register()
        LegacyRipples.register()
        LegacyMotes.register()
        Celestial.register()
        SecretWaypointRenderer.register()
        SecretTracker.register()
        RoomRecorder.register()
        SecretRoutes.register()
        RngMeter.register()
        DropCelebration.register()
        ItemGet.register()
        AmbientMotes.register()
        InvincibilityTimers.register()
        RouteRenderer.register()
        RouteRecorder.register()
        registerChat()
        DiscordPresence.register()

        ClientTickEvents.END_CLIENT_TICK.register { onTick() }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            HandChams.shutdown()
            Skybox.shutdown()
            MotionBlur.shutdown()
            MediaBridge.stop()
            DiscordPresence.stop()
            runCatching { HubCapture.shutdown() }
            ConfigManager.saveAll("client stopping")
        }
    }

    fun queueScreen(action: () -> Unit) {
        pendingScreenAction = action
    }

    private fun registerScreenInput() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                val copyClick = screen is ChatScreen && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !event.hasShiftDown()
                if (copyClick && runCatching { ChatStyle.copyAt(event.x(), event.y()) }.getOrDefault(false)) {
                    false
                } else if (runCatching { PacketOverlay.mouseClicked(event.x(), event.y()) }.getOrDefault(false)) {
                    false
                } else {
                    !runCatching { MediaOverlay.mouseClicked(event.x(), event.y()) }.getOrDefault(false)
                }
            }
            ScreenMouseEvents.allowMouseScroll(screen).register { _, mouseX, mouseY, _, vertical ->
                !runCatching { PacketOverlay.mouseScrolled(mouseX, mouseY, vertical) }
                    .getOrDefault(false)
            }
            ScreenMouseEvents.allowMouseDrag(screen).register { _, event, _, _ ->
                if (runCatching { PacketOverlay.mouseDragged(event.x(), event.y()) }.getOrDefault(false)) {
                    false
                } else {
                    !runCatching { MediaOverlay.mouseDragged(event.x(), event.y()) }.getOrDefault(false)
                }
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { _, _ ->
                if (runCatching { PacketOverlay.mouseReleased() }.getOrDefault(false)) {
                    false
                } else {
                    !runCatching { MediaOverlay.mouseReleased() }.getOrDefault(false)
                }
            }
        }
    }

    private fun registerHud() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(MOD_ID, "overlay"),
        ) { graphics, _ ->
            if (McCompat.hideGui) return@attachElementAfter
            try {
                RenderContext.withContext(graphics) {
                    RecipeTrackerOverlay.render()
                    SlayerOverlay.render()
                    RngMeterOverlay.render()
                    InvincibilityTimers.render()
                    StatsOverlay.render()
                    LowHealthAlert.render()
                    MediaOverlay.render()
                    Hitmarker.render()
                    PacketOverlay.render()
                    Watermark.render()
                    RoomNameOverlay.render()
                    DungeonMap.renderHud()
                    Notifications.render()
                    ItemGet.renderHud()
                    DropCelebrationOverlay.renderHud()
                }
            } catch (e: Exception) {
                logger.error("Error while rendering the overlay", e)
            }
        }
    }

    private fun hiddenStatIcons(): Set<Char> {
        val stats = ModuleSwitch.view("visuals", ConfigManager.features.stats)
        if (!stats.hideReplacedStats) return emptySet()
        val icons = HashSet<Char>()
        if (stats.healthEnabled) icons.add(ActionBarStats.ICON_HEALTH)
        if (stats.defenseEnabled) icons.add(ActionBarStats.ICON_DEFENSE)
        if (stats.manaEnabled) icons.add(ActionBarStats.ICON_MANA)
        if (stats.overflowEnabled) icons.add(ActionBarStats.ICON_OVERFLOW)
        if (stats.vitalityEnabled) icons.add(ActionBarStats.ICON_VITALITY)
        return icons
    }

    private fun registerChat() {
        // First, so the recorder sees every line even if something later hides it
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            runCatching { ChatRecorder.onMessage(message, overlay) }
            true
        }
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ ->
            runCatching { ChatRecorder.onPlayerChat(message) }
            true
        }

        ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
            if (!overlay) runCatching {
                val prestiged = LevelPrestige.restyle(message, LevelPrestige.Where.CHAT) ?: message
                Emojis.apply(ChatCosmetics.chat(prestiged) ?: prestiged)
            }.getOrDefault(message)
            else runCatching {
                ActionBarStats.withoutIcons(message, hiddenStatIcons()) ?: message
            }.getOrDefault(message)
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay) {
                runCatching { ActionBarDebug.onActionBar(message) }
                runCatching { ActionBarStats.onActionBar(message) }
                runCatching { SecretRoutes.onActionBar(message) }
                runCatching { RoomNameOverlay.onActionBar(message) }
                !ModuleSwitch.view("visuals", ConfigManager.features.stats).hideActionBar
            } else {
                runCatching { Hitsound.onChat(message) }
                runCatching { RngMeter.onChat(message) }
                runCatching { InvincibilityTimers.onChat(message) }
                runCatching { DropCelebration.onChat(message) }
                runCatching { SackApi.handleChatMessage(message) }
                    .getOrElse {
                        logger.error("Error while handling a chat message", it)
                        true
                    }
            }
        }
    }

    private fun applyLoadedConfig() {
        logger.info("Applying saved settings")
        RecipeTrackerOverlay.invalidate()
        HandChams.invalidate()
        LavaChanger.invalidate()
    }

    private fun checkVisualSettings() {
        val visuals = ModuleSwitch.view("visuals", ConfigManager.features.visuals)
        val signature = listOf(
            visuals.lavaChanger,
            visuals.hideLavaFog,
        ).joinToString("|")

        if (signature == lastVisualSignature) return
        val first = lastVisualSignature.isEmpty()
        lastVisualSignature = signature
        if (!first) LavaChanger.invalidate()
    }

    private fun onTick() {
        pendingScreenAction?.let { action ->
            pendingScreenAction = null
            runCatching(action).onFailure { logger.error("Failed to open a screen", it) }
        }

        MediaBridge.tick()
        runCatching { DiscordPresence.onTick() }
        runCatching { Hitsound.onTick() }
        runCatching { PacketLog.tick() }
        runCatching { Cosmetics.onTick() }
        runCatching { Capes.onTick() }
        runCatching { Trails.onTick() }
        runCatching { Ripples.onTick() }
        runCatching { LegacyTrails.onTick() }
        runCatching { LegacyRipples.onTick() }
        runCatching { CosmeticEnroll.onTick() }
        runCatching { HubCapture.onTick() }
        runCatching { HubSceneRenderer.preload() }

        if (McCompat.player == null) return

        if (!configApplied) {
            configApplied = true
            runCatching { applyLoadedConfig() }
                .onFailure { logger.error("Failed to apply the saved config", it) }
        }

        runCatching {
            SkyBlockData.refreshSidebar()
            SackApi.onTick()
            ItemSources.onTick()
            RecipeTrackerOverlay.onTick()
            SlayerManager.onTick()
            NukekubiGlow.onTick()
            ExperimentManager.onTick()

            val needsRooms = ModuleSwitch.view("dungeon", ConfigManager.features.secretWaypoints).enabled ||
                ModuleSwitch.view("dungeon", ConfigManager.features.dungeonWaypoints).enabled ||
                ModuleSwitch.view("dungeon", ConfigManager.features.visuals).starGlow ||
                RoomRecorder.enabled
            if (needsRooms) {
                SecretRoomData.load()
                DungeonScanner.onTick()
            }
            RoomCapture.onTick()
            RoomRecorder.onTick()
        }.onFailure { logger.error("Error during tick", it) }

        if (++secondTicker >= TICKS_PER_SECOND) {
            secondTicker = 0
            runCatching {
                SkyBlockData.refreshProfile()
                BazaarApi.onTick()
                UpdateCheck.onTick()
                checkVisualSettings()
            }.onFailure { logger.error("Error during the one-second tick", it) }
        }

        if (++autosaveTicker >= AUTOSAVE_INTERVAL_TICKS) {
            autosaveTicker = 0
            ConfigManager.save(ConfigFileType.FEATURES, "autosave")
        }
    }
}
