package dev.pawfect.addons.config.settings

import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigGuiManager
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ConfigPresets
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.features.SecretWaypointsConfig
import dev.pawfect.addons.config.features.InventoryConfig.PlateStyle
import dev.pawfect.addons.config.features.SkyboxConfig.SkyEffect
import dev.pawfect.addons.config.features.SkyboxConfig.SkyQuality
import dev.pawfect.addons.config.features.SlayersConfig.SlayerDisplay
import dev.pawfect.addons.config.features.SlayersConfig.SlayerTarget
import dev.pawfect.addons.config.features.ThemeConfig.ThemePreset
import dev.pawfect.addons.config.features.TooltipConfig.BorderSource
import dev.pawfect.addons.config.features.TooltipConfig.Frame
import dev.pawfect.addons.config.features.ThemeConfig.UiFontChoice
import dev.pawfect.addons.config.features.VisualsConfig.HighlightColor
import dev.pawfect.addons.config.features.HandChamsConfig.DebugView
import dev.pawfect.addons.config.features.HandChamsConfig.OverlayEffect
import dev.pawfect.addons.config.features.PlayerChamsConfig.Style as PlayerStyle
import dev.pawfect.addons.features.combat.Hitsound
import dev.pawfect.addons.features.combat.HitsoundLibrary
import dev.pawfect.addons.features.dungeon.DungeonWaypoints
import dev.pawfect.addons.features.dungeon.secrets.DungeonScanner
import dev.pawfect.addons.features.dungeon.secrets.SecretTracker
import dev.pawfect.addons.features.recipetracker.RecipeTrackerOverlay
import dev.pawfect.addons.features.visual.LavaChanger
import dev.pawfect.addons.features.media.MediaBridge
import dev.pawfect.addons.features.visual.handchams.HandChams
import dev.pawfect.addons.features.cosmetics.Cosmetics
import dev.pawfect.addons.config.features.CosmeticsConfig.Visibility
import dev.pawfect.addons.features.cosmetics.CosmeticLink
import dev.pawfect.addons.ui.Icons
import dev.pawfect.addons.ui.Notifications
import dev.pawfect.addons.utils.ChatUtils
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.SoundCue

object PawfectSettings {

    private const val DEV_NAME = "pawliciously"

    private val config get() = ConfigManager.features

    private val dev get() = config.dev

    fun build(): List<SettingCategory> {
        val tracker = config.recipeTracker
        val slayers = config.slayers
        val visuals = config.visuals
        val chams = config.handChams
        val players = config.playerChams
        val stats = config.stats
        val general = config.general
        val theme = config.theme
        val media = config.media
        val waypoints = config.dungeonWaypoints
        val secrets = config.secretWaypoints
        val menu = config.menu
        val cosmetics = config.cosmetics
        val experiments = config.experiments
        val hitsounds = config.hitsounds
        val skybox = config.skybox
        val inventory = config.inventory
        val tooltip = config.tooltip
        val motion = config.motionBlur

        return listOfNotNull(
            category(
                "recipetracker",
                "Recipe Tracker",
                Icons.PACKAGE,
                "0.60.0",
                group(
                    "Overlay",
                    ToggleSetting("Enabled", "Show the recipe tracker overlay.", tracker::enabled)
                        .onChange { RecipeTrackerOverlay.invalidate() },
                    ToggleSetting("Show Item Icons", "Draw each ingredient's item icon next to its name.", tracker::showIcons),
                    ToggleSetting("Show Percentage", "Show the completion percentage for each ingredient.", tracker::showPercentage),
                    ToggleSetting("Hide Completed", "Hide ingredients you already have enough of.", tracker::hideCompleted),
                ),
                group(
                    "Pricing",
                    ToggleSetting("Show Bazaar Cost", "Show the instant-buy cost of what you're still missing.", tracker::showCost),
                ),
                group(
                    "Layout",
                    IntSliderSetting("Line Spacing", "Extra pixels between each line.", tracker::lineSpacing, 0, 6),
                    PositionSetting("Overlay Position", "Drag the overlay to reposition it.", tracker.position) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    },
                ),
            ),
            category(
                "slayers",
                "Slayers",
                Icons.FIRE,
                "0.52.0",
                group(
                    "Boss Health",
                    ToggleSetting("Enabled", "Track your slayer boss and show its health.", slayers::enabled),
                    DropdownSetting("Display", "Where the boss health is shown.", slayers::display, SlayerDisplay.entries.toList()),
                    DropdownSetting("Boss Target", "Which slayer boss to follow.", slayers::target, SlayerTarget.entries.toList()),
                ),
                group(
                    "Readout",
                    ToggleSetting("Show Boss Name", "Show the boss name and tier above its health.", slayers::showName),
                    ToggleSetting("Show Percentage", "Show remaining health as a percentage.", slayers::showPercent),
                ),
                group(
                    "World Text",
                    FloatSliderSetting("Text Scale", "Size of the health text drawn at the boss.", slayers::worldScale, 0.5f, 3f)
                        .showIf { slayers.display != SlayerDisplay.HUD },
                    FloatSliderSetting("Text Height", "Vertical offset in blocks. Negative sits below the boss.", slayers::worldOffset, -3f, 3f)
                        .showIf { slayers.display != SlayerDisplay.HUD },
                ),
                group(
                    "Alerts",
                    ToggleSetting("Boss Spawn", "Play a sound when your boss appears.", slayers::alertSpawn),
                    SoundListSetting(
                        "Spawn Sound",
                        "Sound played when the boss appears. Your own files from the hitsounds folder are listed too.",
                        slayers::spawnSoundId,
                        { HitsoundLibrary.ids() },
                    ) { SoundCue.play(slayers.spawnSoundId, slayers.alertVolume, slayers.alertPitch) }
                        .showIf { slayers.alertSpawn },
                    ToggleSetting("Boss Slain", "Play a sound when your boss dies.", slayers::alertSlain),
                    SoundListSetting(
                        "Slain Sound",
                        "Sound played when the boss dies.",
                        slayers::slainSoundId,
                        { HitsoundLibrary.ids() },
                    ) { SoundCue.play(slayers.slainSoundId, slayers.alertVolume, slayers.alertPitch) }
                        .showIf { slayers.alertSlain },
                    ToggleSetting("Low Health", "Play a sound once the boss drops below a health threshold.", slayers::alertLowHealth),
                    FloatSliderSetting("Low Health At", "Percentage that triggers the low health alert.", slayers::lowHealthPercent, 5f, 50f, 1f)
                        .showIf { slayers.alertLowHealth },
                    SoundListSetting(
                        "Low Health Sound",
                        "Sound played when the boss drops below the threshold.",
                        slayers::lowHealthSoundId,
                        { HitsoundLibrary.ids() },
                    ) { SoundCue.play(slayers.lowHealthSoundId, slayers.alertVolume, slayers.alertPitch) }
                        .showIf { slayers.alertLowHealth },
                    FloatSliderSetting("Alert Volume", "How loud the slayer alerts are.", slayers::alertVolume, 0f, 1f, 0.05f),
                    FloatSliderSetting("Alert Pitch", "Pitch of the slayer alerts.", slayers::alertPitch, 0.5f, 2f, 0.05f),
                    ButtonSetting("Custom Sounds", "Drop .ogg or .wav files into the hitsounds folder, then reload to pick them up.", "Reload Sounds") {
                        HitsoundLibrary.reload()
                        ChatUtils.success("Loaded ${HitsoundLibrary.ids().size} custom sounds.")
                    },
                ),
                group(
                    "Layout",
                    PositionSetting("Overlay Position", "Drag the overlay to reposition it.", slayers.position) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    },
                ),
            ),
            category(
                "dungeon",
                "Dungeon",
                Icons.SHIELD,
                "0.52.0",
                group(
                    "Starred Mobs",
                    ToggleSetting("Starred Mob Glow", "Soft glow around starred mobs in your current room.", visuals::starGlow),
                    ColorSetting("Glow Colour", "Colour of the starred mob glow.", visuals::starColor, false)
                        .showIf { visuals.starGlow },
                    FloatSliderSetting("Glow Opacity", "Strength of the glow.", visuals::starOpacity, 0.15f, 0.9f, 0.05f)
                        .showIf { visuals.starGlow },
                    FloatSliderSetting("Glow Radius", "How far the glow spreads.", visuals::starRadius, 2f, 32f, 1f)
                        .showIf { visuals.starGlow },
                    ToggleSetting("Minibosses", "Glow Shadow Assassins, Lost Adventurers, Diamond Guys and King Midas.", visuals::starMinibosses)
                        .showIf { visuals.starGlow },
                    ToggleSetting("Fels", "Glow Fels.", visuals::starFels)
                        .showIf { visuals.starGlow },
                ),
                group(
                    "Bats",
                    ToggleSetting("Bat Highlight", "Outline dungeon bats through walls.", visuals::batHighlighter),
                    DropdownSetting("Highlight Colour", "Colour of the bat outline.", visuals::batColor, HighlightColor.entries.toList())
                        .showIf { visuals.batHighlighter },
                    IntSliderSetting("Highlight Range", "How far away bats are highlighted.", visuals::batRange, 8, 64, suffix = "m")
                        .showIf { visuals.batHighlighter },
                ),
                group(
                    "Secret Waypoints",
                    ToggleSetting("Secret Waypoints", "Show dungeon secret locations in the room you are in.", secrets::enabled),
                    ToggleSetting("Secret Names", "Draw the secret's name above it.", secrets::showNames)
                        .showIf { secrets.enabled },
                    ToggleSetting("Secrets Through Walls", "Draw secrets even when something is in front of them.", secrets::throughWalls)
                        .showIf { secrets.enabled },
                    FloatSliderSetting("Secret Line Width", "Default outline thickness.", secrets::lineWidth, 0.5f, 6f, 0.1f)
                        .showIf { secrets.enabled },
                    FloatSliderSetting("Secret Opacity", "Opacity of secret outlines.", secrets::opacity, 0.1f, 1f, 0.05f)
                        .showIf { secrets.enabled },
                    ToggleSetting("Hide Claimed", "Remove a secret once you have collected it.", secrets::hideClaimed)
                        .showIf { secrets.enabled },
                ),
                group(
                    "Recovery",
                    ButtonSetting("Rescan Dungeon", "Forget the map anchor and every scanned room, then pick the dungeon up again from scratch.", "Rescan") {
                        DungeonScanner.reset()
                        ChatUtils.success("Dungeon scan reset.")
                    }.showIf { secrets.enabled },
                ),
                group(
                    "Secret Sound",
                    ToggleSetting("Play Sound", "Play a sound when you claim a secret.", secrets::playSound)
                        .showIf { secrets.enabled },
                    SoundListSetting(
                        "Sound",
                        "Which sound to play. Your own files from the hitsounds folder are listed too. Click one to preview it.",
                        secrets::soundId,
                        { HitsoundLibrary.ids() },
                    ) { SecretTracker.previewSound() }.showIf { secrets.enabled && secrets.playSound },
                    FloatSliderSetting("Volume", "How loud the sound is.", secrets::soundVolume, 0f, 1f, 0.05f)
                        .showIf { secrets.enabled && secrets.playSound },
                    FloatSliderSetting("Pitch", "Pitch of the sound.", secrets::soundPitch, 0.5f, 2f, 0.05f)
                        .showIf { secrets.enabled && secrets.playSound },
                ),
                group(
                    "Secret Types",
                    *SecretWaypointsConfig.CATEGORIES.flatMap { category ->
                        val style = secrets.style(category)
                        val label = SecretWaypointsConfig.label(category)
                        listOf(
                            ToggleSetting(label, "Show $label secrets.", style::enabled)
                                .showIf { secrets.enabled },
                            ColorSetting("$label Colour", "Outline colour for $label secrets.", style::color)
                                .showIf { secrets.enabled && style.enabled },
                            FloatSliderSetting("$label Width", "0 uses the default width.", style::lineWidth, 0f, 6f, 0.1f)
                                .showIf { secrets.enabled && style.enabled },
                        )
                    }.toTypedArray(),
                ),
                group(
                    "Waypoints",
                    ToggleSetting("Dungeon Waypoints", "Outline saved blocks when you re-enter the same room.", waypoints::enabled),
                    ToggleSetting("Show Names", "Draw each waypoint's name above it.", waypoints::showNames)
                        .showIf { waypoints.enabled },
                    ToggleSetting("Through Walls", "Draw waypoints even when something is in front of them.", waypoints::throughWalls)
                        .showIf { waypoints.enabled },
                    FloatSliderSetting("Line Width", "Thickness of the outline.", waypoints::lineWidth, 0.5f, 6f, 0.1f)
                        .showIf { waypoints.enabled },
                    FloatSliderSetting("Opacity", "Opacity of the outline.", waypoints::opacity, 0.1f, 1f, 0.05f)
                        .showIf { waypoints.enabled },
                    FloatSliderSetting("Inflate", "Grow the box slightly so it does not z-fight.", waypoints::inflate, 0f, 0.25f, 0.002f)
                        .showIf { waypoints.enabled },
                    ColorSetting("Default Colour", "Colour given to new waypoints.", waypoints::defaultColor)
                        .showIf { waypoints.enabled },
                    WaypointListSetting("Saved", "Click a name to rename, the swatch to recolour, the cross to delete."),
                    ButtonSetting("Export", "Copy every waypoint to your clipboard.", "Copy") {
                        ChatUtils.chat(DungeonWaypoints.exportToClipboard())
                    },
                    ButtonSetting("Import Clipboard", "Add waypoints from JSON on your clipboard.", "Paste") {
                        ChatUtils.chat(DungeonWaypoints.importFromClipboard())
                    },
                    ButtonSetting("Import File", "Add waypoints from pa-dw-import.json.", "Load") {
                        ChatUtils.chat(DungeonWaypoints.importFromFile())
                    },
                ),
            ),
            category(
                "visuals",
                "Visuals",
                Icons.EYE,
                "0.48.0",
                group(
                    "Motion Blur",
                    ToggleSetting("Motion Blur", "Smear the world along the camera's movement, the way a real shutter does.", motion::enabled),
                    FloatSliderSetting("Strength", "How far the smear reaches.", motion::strength, 0f, 2f, 0.05f)
                        .showIf { motion.enabled },
                    FloatSliderSetting("Shutter", "Shutter angle. Higher holds the frame open longer.", motion::shutter, 0.1f, 2f, 0.05f)
                        .showIf { motion.enabled },
                    ToggleSetting("Blur Movement", "Also blur from walking and flying, not just looking around.", motion::includeMovement)
                        .showIf { motion.enabled },
                ),
                group(
                    "Motion Blur Quality",
                    IntSliderSetting("Samples", "Taps along the blur. More is smoother and costs more.", motion::samples, 2, 32)
                        .showIf { motion.enabled },
                    FloatSliderSetting("Max Length", "Ceiling on the smear so fast spins stay readable.", motion::maxRadius, 0.005f, 0.25f, 0.005f)
                        .showIf { motion.enabled },
                ),
                group(
                    "Lava Changer",
                    ToggleSetting("Lava To Water", "Render lava as water.", visuals::lavaChanger)
                        .onChange { LavaChanger.invalidate() },
                    ToggleSetting("Hide Lava Fog", "Removes the thick fog while your head is inside lava.", visuals::hideLavaFog)
                        .showIf { visuals.lavaChanger }
                        .onChange { LavaChanger.invalidate() },
                ),
                group(
                    "Stats",
                    ToggleSetting("Hide Replaced Stats", "Remove a stat from Hypixel's action bar when you show it as a module.", stats::hideReplacedStats),
                    ToggleSetting("Hide Whole Action Bar", "Hide Hypixel's action bar entirely.", stats::hideActionBar),
                    ToggleSetting("Show Labels", "Prefix each module with its stat name. Turn off for numbers only.", stats::showLabels),
                    ToggleSetting("Show Maximum", "Show the max value after the current one.", stats::showMax),
                    ToggleSetting("Use Bar Colours", "Colour each module the way Hypixel coloured it, so absorption still shows gold.", stats::useNativeColors),
                ),
                group(
                    "Stat Modules",
                    ToggleSetting("Health", "Show health as its own on screen module.", stats::healthEnabled),
                    PositionSetting("Health Position", "Drag the health module.", stats.healthPosition) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    }.showIf { stats.healthEnabled },
                    ToggleSetting("Defense", "Show defense as its own module.", stats::defenseEnabled),
                    PositionSetting("Defense Position", "Drag the defense module.", stats.defensePosition) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    }.showIf { stats.defenseEnabled },
                    ToggleSetting("Mana", "Show mana as its own module.", stats::manaEnabled),
                    PositionSetting("Mana Position", "Drag the mana module.", stats.manaPosition) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    }.showIf { stats.manaEnabled },
                    ToggleSetting("Overflow Mana", "Show overflow mana. Only appears when Hypixel sends it.", stats::overflowEnabled),
                    PositionSetting("Overflow Position", "Drag the overflow mana module.", stats.overflowPosition) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    }.showIf { stats.overflowEnabled },
                    ToggleSetting("Vitality", "Show vitality as its own module.", stats::vitalityEnabled),
                    PositionSetting("Vitality Position", "Drag the vitality module.", stats.vitalityPosition) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    }.showIf { stats.vitalityEnabled },
                ),
                group(
                    "Low Health Alert",
                    ToggleSetting("Low Health Alert", "Flash the screen edges red when your health is low.", stats::lowHealthAlert),
                    IntSliderSetting("Threshold", "Health percentage that triggers the alert.", stats::lowHealthThreshold, 5, 50, suffix = "%")
                        .showIf { stats.lowHealthAlert },
                    ColorSetting("Alert Colour", "Colour of the screen edge flash.", stats::lowHealthColor)
                        .showIf { stats.lowHealthAlert },
                    FloatSliderSetting("Alert Opacity", "How strong the flash is.", stats::lowHealthOpacity, 0.1f, 1f, 0.05f)
                        .showIf { stats.lowHealthAlert },
                    FloatSliderSetting("Flash Speed", "Pulses per second.", stats::lowHealthSpeed, 0.2f, 5f, 0.1f)
                        .showIf { stats.lowHealthAlert },
                    IntSliderSetting("Edge Thickness", "How far the flash reaches in from the edges.", stats::lowHealthThickness, 10, 200, suffix = "px")
                        .showIf { stats.lowHealthAlert },
                ),
                group(
                    "Tooltips",
                    ToggleSetting("Hide Tooltips", "Hide item tooltips inside menus. Chat hover text is unaffected.", visuals::hideTooltips),
                ),
            ),
            category(
                "chams",
                "Chams",
                Icons.CHAMELEON,
                "0.56.0",
                sectionGroup(
                    "Hand Chams",
                    "Core",
                    FloatSliderSetting("Opacity", "How strongly the effects cover your hand.", chams::opacity, 0f, 1f, 0.05f),
                    FloatSliderSetting("Edge Softness", "Softens the silhouette against the world.", chams::edgeSoftness, 0f, 1f, 0.05f),
                    ToggleSetting("Hide Enchant Glint", "Remove the vanilla enchantment shimmer from items.", chams::hideEnchantGlint),
                ),
                group(
                    "Colour",
                    ToggleSetting("Tint", "Blend a colour into the hand.", chams::tintEnabled),
                    ColorSetting("Tint Colour", "Colour blended into the hand.", chams::tintColor)
                        .showIf { chams.tintEnabled },
                    FloatSliderSetting("Tint Strength", "How much of the tint colour to apply.", chams::tintStrength, 0f, 1f, 0.05f)
                        .showIf { chams.tintEnabled },
                    ToggleSetting("Saturation", "Push or drain colour from the hand.", chams::saturationEnabled),
                    FloatSliderSetting("Saturation Amount", "Below 1 drains colour, above 1 boosts it.", chams::saturation, 0f, 3f, 0.05f)
                        .showIf { chams.saturationEnabled },
                ),
                group(
                    "Outline",
                    ToggleSetting("Outline", "Draw a border that traces your hand's silhouette.", chams::outlineEnabled),
                    ColorSetting("Outline Colour", "Colour of the border.", chams::outlineColor)
                        .showIf { chams.outlineEnabled },
                    FloatSliderSetting("Thickness", "Width of the line in pixels.", chams::outlineThickness, 0.5f, 6f, 0.5f)
                        .showIf { chams.outlineEnabled },
                    FloatSliderSetting("Softness", "Zero is a razor edge, high feathers the line.", chams::outlineSoftness, 0.02f, 1f, 0.02f)
                        .showIf { chams.outlineEnabled },
                    FloatSliderSetting("Outline Opacity", "Opacity of the border.", chams::outlineOpacity, 0f, 1f, 0.05f)
                        .showIf { chams.outlineEnabled },
                ),
                group(
                    "Motion Trail",
                    ToggleSetting("Motion Trail", "Leave a fading echo of your hand behind as it moves.", chams::trailEnabled),
                    FloatSliderSetting("Trail Length", "How long the echo survives. Higher lingers longer.", chams::trailDecay, 0.1f, 0.99f, 0.01f)
                        .showIf { chams.trailEnabled },
                    FloatSliderSetting("Trail Strength", "Opacity of the echo.", chams::trailStrength, 0f, 2f, 0.05f)
                        .showIf { chams.trailEnabled },
                    ToggleSetting("Tint Trail", "Colour the echo instead of leaving it as the scene behind it.", chams::trailTinted)
                        .showIf { chams.trailEnabled },
                    ColorSetting("Trail Colour", "Colour of the echo.", chams::trailColor)
                        .showIf { chams.trailEnabled && chams.trailTinted },
                ),
                group(
                    "Overlay",
                    DropdownSetting("Overlay Effect", "Animated effect layered over the hand.", chams::overlay, OverlayEffect.entries.toList()),
                    FloatSliderSetting("Overlay Strength", "How strong the overlay effect is.", chams::overlayStrength, 0f, 1f, 0.05f)
                        .showIf { chams.overlay != OverlayEffect.NONE },
                    FloatSliderSetting("Overlay Speed", "Animation speed of the overlay.", chams::overlaySpeed, 0f, 4f, 0.1f)
                        .showIf { chams.overlay != OverlayEffect.NONE },
                    IntSliderSetting("Portal Layers", "Depth layers in the end portal. Vanilla uses 15.", chams::portalLayers, 1, 16)
                        .showIf { chams.overlay == OverlayEffect.END_PORTAL },
                ),
                group(
                    "Overlay Colours",
                    ToggleSetting("Custom Colours", "Recolour the overlay instead of using its built in palette.", chams::overlayCustomColors)
                        .showIf { chams.overlay != OverlayEffect.NONE },
                    ColorSetting("Primary", "Liquid flow, or the portal's deep tone.", chams::overlayColorA)
                        .showIf { chams.overlay != OverlayEffect.NONE && chams.overlayCustomColors },
                    ColorSetting("Secondary", "Liquid depth, or the portal's mid tone.", chams::overlayColorB)
                        .showIf { chams.overlay != OverlayEffect.NONE && chams.overlayCustomColors },
                    ColorSetting("Highlight", "Bright cores, the portal's starfield, or the stars in space.", chams::overlayColorC)
                        .showIf { chams.overlay != OverlayEffect.NONE && chams.overlayCustomColors },
                ),
                group(
                    "Performance",
                    ToggleSetting("Half Resolution", "Render at half resolution. Full is the default now that copies are gone.", chams::halfResolution),
                    DropdownSetting("Debug View", "Draw an intermediate texture full screen to diagnose the effect.", chams::debugView, DebugView.entries.toList()),
                ),
                sectionGroup(
                    "Player Chams",
                    "Targets",
                    ToggleSetting("Player Chams", "Shade player models. Walls still hide them.", players::enabled),
                    ToggleSetting("Yourself", "Apply to your own model in third person and menus.", players::self)
                        .showIf { players.enabled },
                    ToggleSetting("Other Players", "Apply to everyone else.", players::others)
                        .showIf { players.enabled },
                    ToggleSetting("Ignore NPCs", "Skip Hypixel NPCs that use player models.", players::ignoreNpcs)
                        .showIf { players.enabled && players.others },
                ),
                group(
                    "Style",
                    DropdownSetting("Style", "Shader applied to the model.", players::style, PlayerStyle.entries.toList())
                        .showIf { players.enabled },
                    ColorSetting("Colour", "Main colour of the effect.", players::color)
                        .showIf { players.enabled },
                    FloatSliderSetting("Tint", "How much the effect covers the skin.", players::tint, 0f, 1f, 0.05f)
                        .showIf { players.enabled },
                    FloatSliderSetting("Intensity", "Density and brightness of the effect.", players::intensity, 0.1f, 1.5f, 0.05f)
                        .showIf { players.enabled },
                    FloatSliderSetting("Speed", "Animation speed.", players::speed, 0f, 4f, 0.1f)
                        .showIf { players.enabled },
                    FloatSliderSetting("Opacity", "Lower values let the world show through the model.", players::opacity, 0.1f, 1f, 0.05f)
                        .showIf { players.enabled },
                ),
                group(
                    "Rim",
                    FloatSliderSetting("Rim Glow", "Light along the model's edges.", players::rim, 0f, 2f, 0.05f)
                        .showIf { players.enabled },
                    ColorSetting("Rim Colour", "Colour of the edge light.", players::accentColor)
                        .showIf { players.enabled && players.rim > 0f },
                ),
            ),
            category(
                "cosmetics",
                "Cosmetics",
                Icons.USER,
                "0.78.0",
                group(
                    "Account",
                    GuideSetting(
                        "Your Cosmetics",
                        "Link this account to the cosmetics editor.",
                        lines = {
                            if (Cosmetics.owned) {
                                listOf("This account has cosmetics. Open the editor to change how they look.")
                            } else {
                                listOf(
                                    "Cosmetics are granted by hand, never sold.",
                                    "Once you have one, open the editor to pick a style, colours and animation.",
                                    "Signing in runs the same check the game already does when you join a server. You will never be asked for a password.",
                                )
                            }
                        },
                        label = { if (CosmeticLink.busy) "Opening" else "Open Editor" },
                    ) { CosmeticLink.start() },
                ),
                group(
                    "Cosmetics",
                    ToggleSetting("Enabled", "Show PawfectAddons cosmetics on players who have them.", cosmetics::enabled),
                    ToggleSetting("Name Colours", "Paint custom name colours over the nametags of cosmetic owners.", cosmetics::nameColors)
                        .showIf { cosmetics.enabled },
                    DropdownSetting("Capes", "Whose custom capes to render.", cosmetics::capes, Visibility.entries.toList())
                        .showIf { cosmetics.enabled },
                    DropdownSetting("Trails", "Whose motion trails to render.", cosmetics::trails, Visibility.entries.toList())
                        .showIf { cosmetics.enabled },
                    DropdownSetting("Ambient Motes", "Whose drifting motes to render.", cosmetics::motes, Visibility.entries.toList())
                        .showIf { cosmetics.enabled },
                    DropdownSetting("Badges", "Whose status badges to show beside their name.", cosmetics::badges, Visibility.entries.toList())
                        .showIf { cosmetics.enabled },
                    ToggleSetting("First Person", "Draw your own trail and motes while you are in first person.", cosmetics::firstPerson)
                        .showIf { cosmetics.enabled && (cosmetics.trails != Visibility.NONE || cosmetics.motes != Visibility.NONE) },
                    ToggleSetting("Animate", "Let animated names, capes and trails move. Turn this off to freeze them.", cosmetics::animate)
                        .showIf { cosmetics.enabled && (cosmetics.nameColors || cosmetics.capes != Visibility.NONE || cosmetics.trails != Visibility.NONE) },
                    ButtonSetting("Cosmetic List", "Fetch the newest cosmetic list from pawfectaddons.net.", "Reload") {
                        Notifications.push("Cosmetics", Cosmetics.reload(), Icons.USER)
                    },
                ),
            ),
            category(
                "general",
                "General",
                Icons.SLIDERS,
                "0.79.0",
                group(
                    "Sacks",
                    ToggleSetting("Track Sacks", "Read your sack contents and keep them updated from chat.", general::trackSacks),
                    ToggleSetting("Hide Sack Messages", "Hide Hypixel's sack chat spam. Contents are still read first.", general::hideSackMessages),
                ),
                group(
                    "Item Sources",
                    ToggleSetting("Count Inventory", "Also count your inventory and equipped gear.", general::countInventory),
                    ToggleSetting("Count Storage", "Also count your ender chest and backpacks.", general::countStorage),
                ),
                group(
                    "Advanced",
                    ToggleSetting("Debug Logging", "Log extra detail about parsing and repo loading.", general::debug),
                ),
            ),
            category(
                "hitsounds",
                "Hitsounds",
                Icons.SWORD,
                "0.79.0",
                group(
                    "Hitsound",
                    ToggleSetting("Hitsounds", "Play a sound of your choice whenever you land a hit.", hitsounds::enabled),
                    ToggleSetting("Melee", "Play it when you hit something with a weapon.", hitsounds::melee)
                        .showIf { hitsounds.enabled },
                    ToggleSetting("Arrows", "Play it when one of your arrows connects.", hitsounds::arrows)
                        .showIf { hitsounds.enabled },
                    ToggleSetting("Predict Arrows", "Sound the hit as the arrow arrives instead of waiting on the server. Feels instant, but can occasionally fire on a near miss.", hitsounds::predictArrows)
                        .showIf { hitsounds.enabled && hitsounds.arrows },
                    ToggleSetting("Replace Vanilla Ping", "Mute the vanilla arrow ding so you only hear your own sound.", hitsounds::suppressVanilla)
                        .showIf { hitsounds.enabled && hitsounds.arrows },
                ),
                group(
                    "Abilities",
                    ToggleSetting("Ability Hits", "Play it when an ability lands, read from the \"Your Spirit Sceptre hit 3 enemies\" line. One sound per cast, not per enemy.", hitsounds::abilities)
                        .showIf { hitsounds.enabled },
                    ToggleSetting("Mute Explosions", "Silence every explosion sound the server sends, including other people's Hyperions.", hitsounds::muteExplosions),
                ),
                group(
                    "Sound",
                    SoundListSetting(
                        "Sound",
                        "Which sound to play. Click one to preview it.",
                        hitsounds::soundId,
                        { HitsoundLibrary.ids() },
                    ) { Hitsound.preview() }.showIf { hitsounds.enabled },
                    FloatSliderSetting("Volume", "How loud the hitsound is.", hitsounds::volume, 0f, 1f, 0.05f)
                        .showIf { hitsounds.enabled },
                    FloatSliderSetting("Pitch", "Pitch of the hitsound.", hitsounds::pitch, 0.5f, 2f, 0.05f)
                        .showIf { hitsounds.enabled },
                    ButtonSetting("Custom Sounds", "Drop .ogg or .wav files into the hitsounds folder, then reload to pick them up.", "Reload Sounds") {
                        HitsoundLibrary.reload()
                        ChatUtils.success("Loaded ${HitsoundLibrary.ids().size} hitsounds.")
                    },
                ),
                group(
                    "Hitmarker",
                    ToggleSetting("Hitmarker", "Flash four ticks around your crosshair on every hit.", hitsounds::markerEnabled),
                    ColorSetting("Colour", "Colour of the hitmarker.", hitsounds::markerColour)
                        .showIf { hitsounds.markerEnabled },
                    FloatSliderSetting("Size", "How far the ticks reach from the crosshair.", hitsounds::markerSize, 2f, 12f, 0.5f)
                        .showIf { hitsounds.markerEnabled },
                    FloatSliderSetting("Thickness", "How heavy the ticks are.", hitsounds::markerThickness, 0.5f, 4f, 0.5f)
                        .showIf { hitsounds.markerEnabled },
                ),
            ),
            category(
                "inventory",
                "Inventory",
                Icons.TAG,
                "0.59.0",
                sectionGroup(
                    "Inventory",
                    "Plate",
                    ToggleSetting("Inventory Plates", "Replace the vanilla container panel with an animated plate. Slots stay exactly where they are.", inventory::enabled),
                    DropdownSetting("Style", "Which plate to draw behind the slots.", inventory::style, PlateStyle.entries.toList())
                        .showIf { inventory.enabled },
                    FloatSliderSetting("Intensity", "How strong the pattern is over the base colour.", inventory::intensity, 0f, 1f, 0.05f)
                        .showIf { inventory.enabled },
                ),
                group(
                    "Slots",
                    ToggleSetting("Slot Plates", "Draw a soft plate behind every slot so items stay readable.", inventory::slotPlates)
                        .showIf { inventory.enabled },
                    FloatSliderSetting("Slot Opacity", "Opacity of the slot plates.", inventory::slotOpacity, 0f, 1f, 0.05f)
                        .showIf { inventory.enabled && inventory.slotPlates },
                    FloatSliderSetting("Slot Rounding", "Corner radius of the slot plates.", inventory::slotRadius, 0f, 8f, 0.5f)
                        .showIf { inventory.enabled && inventory.slotPlates },
                    ToggleSetting("Hide Slot Highlight", "Remove the white box vanilla draws over the slot under your cursor.", inventory::hideSlotHighlight)
                        .showIf { inventory.enabled },
                    ToggleSetting("Hide Empty Slot Icons", "Remove the armour, shield and fuel outlines drawn in empty slots.", inventory::hideSlotIcons)
                        .showIf { inventory.enabled },
                    ToggleSetting("Hide Recipe Book", "Remove the recipe book button and panel from every container screen.", inventory::hideRecipeBook),
                ),
                group(
                    "Colours",
                    ToggleSetting("Custom Colours", "Use your own palette instead of the theme.", inventory::customColors)
                        .showIf { inventory.enabled },
                    ColorSetting("Top", "Colour at the top of the plate.", inventory::colorTop)
                        .showIf { inventory.enabled && inventory.customColors },
                    ColorSetting("Bottom", "Colour at the bottom of the plate.", inventory::colorBottom)
                        .showIf { inventory.enabled && inventory.customColors },
                    ColorSetting("Accent", "Colour of the pattern and slot edges.", inventory::colorAccent)
                        .showIf { inventory.enabled && inventory.customColors },
                ),
                sectionGroup(
                    "Tooltips",
                    "Frame",
                    ToggleSetting(
                        "Tooltip Restyle",
                        "Replace the vanilla tooltip frame with a Pawfect plate. The lines themselves are left exactly as the server sent them.",
                        tooltip::enabled,
                    ),
                    DropdownSetting("Style", "Which frame to draw behind the text.", tooltip::frame, Frame.entries.toList())
                        .showIf { tooltip.enabled },
                    DropdownSetting("Border", "Where the border colour comes from.", tooltip::border, BorderSource.entries.toList())
                        .showIf { tooltip.enabled },
                    FloatSliderSetting("Opacity", "How solid the frame is. At the top of the slider the plate is fully opaque.", tooltip::opacity, 0.2f, 1f, 0.02f)
                        .showIf { tooltip.enabled },
                    FloatSliderSetting("Size", "Scales the whole tooltip, text and frame together.", tooltip::scale, 0.7f, 1.6f, 0.05f)
                        .showIf { tooltip.enabled },
                    FloatSliderSetting("Corner Rounding", "Corner radius of the frame.", tooltip::radius, 0f, 10f, 0.5f)
                        .showIf { tooltip.enabled },
                    FloatSliderSetting("Padding", "How far the frame sits outside the text.", tooltip::inset, 0f, 10f, 0.5f)
                        .showIf { tooltip.enabled },
                    ToggleSetting("Drop Shadow", "Cast a shadow behind the tooltip.", tooltip::shadow)
                        .showIf { tooltip.enabled },
                ),
                group(
                    "Text",
                    ToggleSetting(
                        "Use Menu Font",
                        "Render tooltip text in the font picked on the Theme tab. Glyphs the font is missing fall back to vanilla automatically.",
                        tooltip::restyleFont,
                    ).showIf { tooltip.enabled },
                ),
            ),
            category(
                "skybox",
                "Skybox",
                Icons.SKYBOX,
                "0.57.0",
                group(
                    "Sky",
                    ToggleSetting("Skybox Shader", "Replace the vanilla sky with a custom one.", skybox::enabled),
                    DropdownSetting("Sky", "Which sky to render.", skybox::effect, SkyEffect.entries.toList())
                        .showIf { skybox.enabled },
                    DropdownSetting("Quality", "Lookup resolution and march steps. Drop this if the sky costs you frames.", skybox::quality, SkyQuality.entries.toList())
                        .showIf { skybox.enabled },
                    FloatSliderSetting("Render Resolution", "Draw the sky at a fraction of screen resolution and upscale it. Lower is faster, softer.", skybox::renderScale, 0.25f, 1f, 0.05f)
                        .showIf { skybox.enabled },
                    ToggleSetting("Night Only", "Only render between dusk and dawn.", skybox::onlyAtNight)
                        .showIf { skybox.enabled },
                ),
                group(
                    "Look",
                    FloatSliderSetting("Intensity", "How much of the sky the effect fills.", skybox::intensity, 0f, 1f, 0.05f)
                        .showIf { skybox.enabled },
                    FloatSliderSetting("Brightness", "Overall exposure.", skybox::brightness, 0.2f, 3f, 0.05f)
                        .showIf { skybox.enabled },
                    FloatSliderSetting("Speed", "How fast the sky animates. Zero freezes it.", skybox::speed, 0f, 3f, 0.05f)
                        .showIf { skybox.enabled },
                    FloatSliderSetting("Feature Scale", "Size of the shapes in the sky. Higher zooms in, lower packs more detail per degree.", skybox::featureScale, 0.3f, 3f, 0.05f)
                        .showIf { skybox.enabled },
                    IntSliderSetting("Seed", "Reshuffles the layout of this sky.", skybox::seed, 1, 999)
                        .showIf { skybox.enabled && skybox.effect.randomised },
                ),
                group(
                    "Colour",
                    ToggleSetting("Custom Colours", "Use your own palette instead of the one this sky ships with.", skybox::customColors)
                        .showIf { skybox.enabled },
                    ColorSetting("Primary", "Main body of the effect.", skybox::colorPrimary)
                        .showIf { skybox.enabled && skybox.customColors },
                    ColorSetting("Secondary", "Deep tone behind it.", skybox::colorSecondary)
                        .showIf { skybox.enabled && skybox.customColors },
                    ColorSetting("Accent", "Bright cores, crests and highlights.", skybox::colorAccent)
                        .showIf { skybox.enabled && skybox.customColors },
                ),
            ),
            category(
                "media",
                "Media",
                Icons.MUSIC,
                "0.17.1",
                group(
                    "Display",
                    ToggleSetting("Media Display", "Show what is currently playing on this PC.", media::enabled),
                    ToggleSetting("Album Tile", "Show the coloured art tile.", media::showArt),
                    ToggleSetting("Artist", "Show the artist under the title.", media::showArtist),
                    ToggleSetting("Duration Bar", "Show the progress bar and timestamps.", media::showProgress),
                    ToggleSetting("Controls", "Show the transport buttons.", media::showControls),
                    ToggleSetting("Source App", "Show which app the audio is coming from.", media::showSource),
                    ToggleSetting("Hide When Stopped", "Hide the panel when nothing is playing.", media::hideWhenStopped),
                    ToggleSetting("Clickable In Menus", "Draw the panel above open screens and let the controls be clicked.", media::clickableInMenus),
                ),
                group(
                    "Appearance",
                    FloatSliderSetting("Transparency", "Opacity of the media panel only.", media::opacity, 0.1f, 1f, 0.05f),
                    IntSliderSetting("Width", "Panel width in pixels.", media::width, 120, 320),
                    PositionSetting("Panel Position", "Drag the panel to reposition it.", media.position) {
                        PawfectAddons.queueScreen { GuiEditManager.openEditor() }
                    },
                ),
            ),
            category(
                "experiments",
                "Experiments",
                Icons.CLOCK,
                "0.34.7",
                group(
                    "Solvers",
                    ToggleSetting("Enabled", "Solve the Experimentation Table minigames.", experiments::enabled),
                    ToggleSetting("Chronomatron", "Show the remembered pattern order.", experiments::chronomatron)
                        .showIf { experiments.enabled },
                    ToggleSetting("Ultrasequencer", "Show the next number to click.", experiments::ultrasequencer)
                        .showIf { experiments.enabled },
                    ToggleSetting("Superpairs", "Remember revealed items and show them in hidden slots.", experiments::superpairs)
                        .showIf { experiments.enabled },
                ),
                group(
                    "Display",
                    ToggleSetting("Info Panel", "Show the panel beside the menu with round and status.", experiments::showPanel)
                        .showIf { experiments.enabled },
                    ToggleSetting("Order Numbers", "Badge each highlighted slot with its click order.", experiments::showOrder)
                        .showIf { experiments.enabled },
                    ToggleSetting("Ghost Upcoming", "Dimly highlight the clicks after the next one.", experiments::ghostUpcoming)
                        .showIf { experiments.enabled },
                    IntSliderSetting("Ghost Depth", "How many upcoming clicks to preview.", experiments::ghostCount, 1, 5)
                        .showIf { experiments.enabled && experiments.ghostUpcoming },
                    FloatSliderSetting("Corner Rounding", "Radius of the slot highlights.", experiments::radius, 0f, 8f, 0.5f)
                        .showIf { experiments.enabled },
                ),
                group(
                    "Max Reward",
                    IntSliderSetting("Chronomatron Rounds", "Round where rewards stop improving.", experiments::chronomatronRounds, 1, 20)
                        .showIf { experiments.enabled },
                    IntSliderSetting("Ultrasequencer Rounds", "Round where rewards stop improving.", experiments::ultrasequencerRounds, 1, 20)
                        .showIf { experiments.enabled },
                ),
                group(
                    "Advanced",
                    ToggleSetting("Block Incorrect Clicks", "Swallow a click that would break your streak.", experiments::blockIncorrectClicks)
                        .showIf { experiments.enabled },
                ),
            ),
            dividedCategory(
                "menu",
                "Main Menu",
                Icons.SPARKLE,
                "0.58.0",
                group(
                    "Core",
                    ToggleSetting("Custom Main Menu", "Replace the vanilla menu backdrop with an animated shader.", menu::enabled),
                    ToggleSetting("Style Buttons", "Redraw every vanilla button and slider, overriding resource packs.", menu::styleButtons)
                        .showIf { menu.enabled },
                    ToggleSetting("Backdrop In World", "Also paint the backdrop on screens opened while playing.", menu::styleEverywhere)
                        .showIf { menu.enabled },
                    FloatSliderSetting("Intensity", "How strong the moving detail is.", menu::intensity, 0f, 1f, 0.05f)
                        .showIf { menu.enabled },
                    FloatSliderSetting("Button Rounding", "Corner radius of the buttons.", menu::buttonRadius, 0f, 12f, 0.5f)
                        .showIf { menu.enabled && menu.styleButtons },
                ),
                group(
                    "Branding",
                    ToggleSetting("Hide Splash", "Remove the yellow splash text.", menu::hideSplash)
                        .showIf { menu.enabled },
                    ToggleSetting("Show Branding", "Replace the Minecraft logo with an animated PawfectAddons title.", menu::showBranding)
                        .showIf { menu.enabled },
                ),
                group(
                    "Colours",
                    ToggleSetting("Custom Colours", "Use your own palette instead of the theme.", menu::customColors)
                        .showIf { menu.enabled },
                    ColorSetting("Top", "Colour at the top of the backdrop.", menu::colorTop)
                        .showIf { menu.enabled && menu.customColors },
                    ColorSetting("Bottom", "Colour at the bottom of the backdrop.", menu::colorBottom)
                        .showIf { menu.enabled && menu.customColors },
                    ColorSetting("Accent", "Colour of the drifting glow.", menu::colorAccent)
                        .showIf { menu.enabled && menu.customColors },
                ),
            ),
            category(
                "theme",
                "Theme",
                Icons.PALETTE,
                "0.79.0",
                group(
                    "Preset",
                    DropdownSetting("Theme", "Colour preset for this menu.", theme::preset, ThemePreset.entries.toList()),
                ),
                group(
                    "Custom Colours",
                    ColorSetting("Accent", "Highlight colour.", theme::customAccent).showIf { theme.preset == ThemePreset.CUSTOM },
                    ColorSetting("Background", "Window background.", theme::customBackground).showIf { theme.preset == ThemePreset.CUSTOM },
                    ColorSetting("Panel", "Group box background.", theme::customPanel).showIf { theme.preset == ThemePreset.CUSTOM },
                    ColorSetting("Border", "Border and divider colour.", theme::customBorder).showIf { theme.preset == ThemePreset.CUSTOM },
                    ColorSetting("Text", "Primary text colour.", theme::customText).showIf { theme.preset == ThemePreset.CUSTOM },
                    ColorSetting("Dim Text", "Secondary text colour.", theme::customTextDim).showIf { theme.preset == ThemePreset.CUSTOM },
                ),
                group(
                    "Behaviour",
                    FloatSliderSetting("Menu Scale", "Size of this menu.", theme::uiScale, 0.5f, 1f, 0.05f),
                    FloatSliderSetting("Transparency", "Opacity of the whole menu.", theme::opacity, 0.1f, 1f, 0.05f),
                    DropdownSetting("Font", "Typeface used across the mod.", theme::font, UiFontChoice.entries.toList()),
                    ToggleSetting("Font Shadow", "Draw a drop shadow behind all text.", theme::fontShadow),
                    FloatSliderSetting("Text Offset", "Nudge text up or down if the font sits off centre.", theme::textOffset, -4f, 4f, 0.5f),
                    ToggleSetting("Player Card", "Show the player card above the menu.", theme::showPlayerIsland),
                    ToggleSetting("Animations", "Animate hovers, toggles and scrolling.", theme::animations),
                    ToggleSetting("Click Sounds", "Play a click when you change a setting.", theme::clickSounds),
                ),
                group(
                    "Configs",
                    PresetListSetting("Saved Configs", "Every toggle and value in the mod, saved under a name. Click one to load it."),
                    TextSetting("Name", "What to call the config you are about to save.", ConfigPresets::draftName, "my config"),
                    ButtonSetting("Save Config", "Write your current settings out under that name.", "Save") {
                        ConfigPresets.save(ConfigPresets.draftName)
                            .onSuccess { saved ->
                                ConfigPresets.draftName = ""
                                ChatUtils.success("Saved the config $saved.")
                                PawfectAddons.queueScreen { ConfigGuiManager.open("theme") }
                            }
                            .onFailure { ChatUtils.error(it.message ?: "Could not save that config.") }
                    },
                    ButtonSetting("Configs Folder", "Open the folder your saved configs live in, ready to share.", "Open") {
                        ConfigPresets.openFolder()
                            .onFailure { ChatUtils.error("Could not open the configs folder.") }
                    },
                ),
            ),
            if (isDev()) devCategory() else null,
        )
    }

    private fun isDev(): Boolean = runCatching {
        McCompat.mc.user.name.equals(DEV_NAME, ignoreCase = true)
    }.getOrDefault(false)

    private fun devCategory(): SettingCategory = category(
        "dev",
        "Dev",
        Icons.SLIDERS,
        "0.47.0",
        group(
            "Packets",
            PacketLogSetting(
                "Packet Log",
                "Read-only view of traffic between you and the server. Nothing is sent, altered or blocked.",
            ),
            ToggleSetting(
                "Capture While Playing",
                "Keep recording when this menu is shut. Off means it only records while you have this tab open.",
                dev::alwaysCapture,
            ),
        ),
        group(
            "Overlay",
            ToggleSetting(
                "Packet Overlay",
                "Draw the packet list on your HUD. It keeps capture running while it is up.",
                dev::overlayEnabled,
            ),
            ToggleSetting(
                "Interactive",
                "Click rows, scroll and drag the overlay while any menu is open. The HUD itself has no cursor, so this only applies over screens.",
                dev::overlayClickable,
            ).showIf { dev.overlayEnabled },
            ToggleSetting(
                "Inline Detail",
                "Show the clicked packet's fields underneath the list.",
                dev::overlayDetail,
            ).showIf { dev.overlayEnabled },
            IntSliderSetting("Rows", "How many packets the overlay lists.", dev::overlayRows, 3, 30)
                .showIf { dev.overlayEnabled },
            IntSliderSetting("Width", "Overlay width.", dev::overlayWidth, 160, 420, 10, "px")
                .showIf { dev.overlayEnabled },
            PositionSetting("Overlay Position", "Drag the overlay to reposition it.", dev.position) {
                PawfectAddons.queueScreen { GuiEditManager.openEditor() }
            },
        ),
    )

    private val ORDER = listOf(
        "visuals", "chams", "skybox", "media",
        "hitsounds",
        "slayers", "dungeon",
        "recipetracker", "general", "experiments",
        "cosmetics",
        "inventory",
        "menu", "theme", "dev",
    )

    fun buildSections(): List<SettingSection> {
        val all = build()
        val byId = all.associateBy { it.id }
        val ordered = ORDER.mapNotNull { byId[it] }
        val rest = all.filter { it.id !in ORDER }
        return listOf(section("all", "All", Icons.GEAR, *(ordered + rest).toTypedArray()))
    }
}
