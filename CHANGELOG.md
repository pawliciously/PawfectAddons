# Changelog

## 0.78.0
- Nametags, trails, ripples and motes now draw over the custom sky instead of being painted out.

## 0.77.0
- Fixed the menu and sky shaders failing to compile on AMD GPUs.

## 0.76.0
- `/pawfectaddons` now works as an alias for `/pa`.

## 0.75.0
- Ripples read much brighter again, with softer edges and a gentler fade.

## 0.74.0
- Fixed trails, motes and ripples ignoring their own transparency, so fades and pulses actually show now.

## 0.73.0
- Cosmetics: a First Person toggle for whether your own trail and motes draw around you in first person.

## 0.72.0
- Motes now drift on their own paths instead of all tracing the same figure-eight.
- Ripples fade in and out softly and take a lifetime slider.

## 0.71.0
- Cosmetics: ambient motes that drift around you while you stand still, and a landing ripple that rides your trail colours.

## 0.70.0
- Cosmetics: six status badges, each in its own colour, shown in a fixed order beside the name. Pick which of yours to wear on the website.
- Fixed the missing interface icons, which broke in 0.69.0.

## 0.69.0
- Cosmetics: a founder paw beside the names of the first fifty people to run PawfectAddons, with a toggle for whose to show.

## 0.68.2
- Cosmetics: motion trails now actually draw, they were being built on a pipeline with no camera matrices.

## 0.68.1
- Dungeons: secrets that serve two numbers (like a 1/2 lever) now stay marked until both are found, and picking up a redstone skull no longer hides the rest of its route.

## 0.68.0
- Cosmetics: motion trails that follow players as they move, with their own colours and an in-game toggle for whose trails to show.

## 0.67.0
- Cosmetics: custom capes from an image or gif link, set in the editor, with an in-game toggle for whose capes to show.

## 0.66.0
- Cosmetics: link your account from the Cosmetics tab and open the editor at me.pawfectaddons.net.


## 0.65.0
- Cosmetics: grants can list an `ign` so they also apply on offline-mode servers.


## 0.64.0
- Cosmetics: rank prefixes no longer break into raw colour codes on recoloured names.


## 0.63.0
- Cosmetics: a failed fetch now retries with backoff instead of waiting the full 30 minutes.


## 0.62.0
- Cosmetics: name cosmetics can now render the username in caps.


## 0.61.0
- Cosmetics: animated custom name colours for cosmetic owners, with solid, gradient, wave, rainbow and pulse styles.
- New Cosmetics tab with viewer toggles and a list reload button.


## 0.60.0
- Fixed sack chat updates counting the same items more than once, which inflated Recipe Tracker progress.


## 0.59.0
- Custom fonts now fall back to the full default font, so server resource pack glyphs render instead of empty boxes.


## 0.58.0
- New PawfectAddons logo, now a high-resolution texture with smooth filtering instead of a 64px upscale.
- The main menu logo is twice as big and fits itself above the button column.


## 0.57.0
- Tooltips gained a Size slider, and the Opacity slider now reaches fully opaque.
- Use Menu Font for tooltips now applies to lines the server already gave a font.
- The nebula swirl is animated.
- Main Menu branding is now the PawfectAddons logo with a gentle bounce and the version below it.
- New wavy main menu background styled after the logo.


## 0.56.0
- Nebula rewritten as a lensed, swirling vortex with filaments and a burning core instead of splotchy clouds.
- Skybox colours cut down to a Custom Colours toggle plus Primary, Secondary and Accent, matching the Inventory tab.
- Notifications now stack, are always the badge style, and the duration bar no longer jumps on summon.
- The recipe book button and panel can be hidden from every container screen.
- Player Chams: Solid, Smoke and Hologram replaced with Ghost, Prism and Ripple.
- Hand Chams and Player Chams merged into one Chams tab, and Tooltips merged into Inventory.
- Main Menu moved below the sidebar divider, with a PawfectAddons button on the title screen itself.
- The title screen wordmark now uses a high-resolution font instead of an upscaled one.
- Removed the Maxed Items feature and the accent line on top of tooltips.

## 0.55.0
- New Tooltips tab: reskins every tooltip frame with a rarity-coloured plate, with a toggle.
- Skybox Scale now resizes the sky itself; the old slider is renamed Render Resolution.
- Skybox colours are now a Highlight/Deep pair that every sky uses directly, instead of a hue rotation.
- Nebula and Galaxy overhauled, Synthwave replaced with Aurora, and a new Caustics sky added.
- The Galaxy is fully 3D now, so the seam opposite the black hole is gone and the hole lenses what is behind it.
- Badge notifications use the logo and give the text more room; Inventory moved above Main Menu with a divider before the misc tabs.

## 0.54.0
- PawfectAddons has a logo, shown in the config title bar and in Mod Menu.
- Group headers use the icon-font caret, so they no longer draw a box under the new fonts.
- The Badge notification's duration bar now runs the full width of the plate and follows its corners.

## 0.53.1
- Inventory Plates now work on Hypixel alongside Skyblocker's equipment inventory.
- Silenced the FONT-LICENSES resource-pack warning on startup.

## 0.53.0
- Added five new menu fonts: Space Grotesk, Outfit, JetBrains Mono, Chakra Petch and Lexend.
- Inventory Plates now sit under the paperdoll, the hover box and empty slot icons are gone, and the pattern no longer cuts off at the edge.
- Replaced the Frosted and Circuit plates with Aurora and Caustics.
- The main menu restyle no longer reaches into container screens.
- Fixed the Galaxy sky pinching opposite its anchor.
- Removed the Black Hole option from Player Chams and Skybox.
- The Badge notification lost its badge.

## 0.52.1
- Fixed the Inventory Plates shader failing to compile, which also turned off your resource packs.

## 0.52.0
- Cut the cost of the star, nebula and outline shaders without changing how they look.
- Added the Galaxy skybox, the player chams black hole ported to the sky.
- Added Inventory Plates: five animated container backgrounds with slots left where they are.
- Added notification styles with an in game preview button.
- Dungeon secret and new slayer alert sounds can now use your custom hitsound files.
- Fixed secrets never showing for a whole dungeon when Mort was missed, plus a manual rescan button.

## 0.51.0
- Starred Mob Glow now only shows mobs in the same dungeon room as you.

## 0.50.0
- Added Starred Mob Glow to Dungeon with a soft halo, colour, opacity and radius.

## 0.49.0
- Added a Stars overlay to Hand Chams and a Stars sky to Skybox.
- Added a Render Scale slider to Skybox for performance.

## 0.48.0
- Added Player Chams: shader styles for your own and other players' models, hidden by walls.
- Moved Motion Blur into Visuals and removed Blur from Hand Chams.
- Each settings tab now shows the version it was last updated in.

## 0.47.0

- Added a draggable Packet Log overlay toggle, clickable while any menu is open.

## 0.46.0

- Added a dev-only Packet Log tab with search and per-packet detail. Trimmed the built-in hitsounds to impact.

## 0.45.1

- Fixed ability hitsounds never firing, and the vanilla arrow ping being muted before your first hit.

## 0.45.0

- Hitsounds now fire on ability hits, and explosion sounds can be muted.

## 0.44.0

- Added hitsounds with optional arrow prediction, a hitmarker, and custom sounds from config/pawfectaddons/hitsounds.

## 0.43.0

- Reverted the texture-driven black hole. Skies are back to the 0.41.0 shaders.

## 0.42.0

- Black Hole can now be driven by textures instead of the shader. Drop PNGs in config/pawfectaddons/sky: hole_0.png onward for the frames, cube_0.png to cube_5.png for the backdrop.
- Frames ping-pong and crossfade, so a sequence that does not loop still plays seamlessly.
- New Hole Size slider, 40 to 140 degrees.
- Falls back to the procedural black hole when no textures are present.

## 0.41.0

- Removed the Storm and Aurora skies.
- Fixed the nebula being one huge pink and yellow splotch: the coverage mask ran at too low a frequency to form more than a single lobe, and the hot core colour was painted over whole volumes instead of filament tips.
- Stars no longer twinkle, and cost roughly half what they did.
- Each sky now keeps its own colour.

## 0.40.0

- Skyboxes rebuilt: six distinct skies (Nebula, Liquid, Aurora, Black Hole, Synthwave, Storm), each on its own technique instead of one noise function reskinned.
- Dropped fbm and ACES for spiral noise and a saturation-preserving tonemap, so the skies are sharper, more colourful and cheaper.
- Storm supercells march only the cloud slab with Beer-Powder lighting and early-out.
- Sky lookup table is now throttled, and rendered once for the four skies that do not animate it.
- Skybox settings cut from 27 to 8.

## 0.39.1

- Fixed the skybox shaders failing to compile: flat and packed are reserved GLSL keywords.

## 0.39.0

- Skybox rebuilt from scratch around a world-space sky lookup table, so the expensive marching runs on ~32k texels instead of every screen pixel.
- New Atmosphere sky: real Rayleigh, Mie and ozone scattering with a physical sun, haze and ozone controls.
- New Deep Space sky: blackbody star colours from real spectral class weights, power-law magnitudes, diffraction spikes, Milky Way dust lanes that occlude.
- New Gas Giant Moon sky: banded differential rotation, a storm spot, rings that shadow the planet and are shadowed by it, and planetshine.
- Removed Aurora, Nebula, Liquid, Clouds, Void Rift and Storm, and redesigned the skybox settings per sky.

## 0.38.0

- Sky march steps and star layers now follow the Quality setting instead of being fixed, with early-outs in the cloud and aurora density functions.
- Motion blur samples scale with actual screen velocity and the pass is skipped entirely when the camera is still; added depth dilation and linear-space accumulation.
- Hand chams outline rebuilt as an exact separable disc dilation, so wide outlines no longer have gaps.
- Hand chams no longer runs the glow and blur pyramids when those effects are off.

## 0.37.0

- Sky shaders rebuilt around volume marching, tonemapping and dithering instead of flat noise gradients.
- Aurora is now marched curtains with vertical rays, overlap and height tinted emission.
- Storm is a lit cloud volume with Beer's law extinction and lightning as a point light inside it, plus a branching bolt.
- Deep Space gained domain warped dust that actually occludes stars, two seeded nebulae and a seeded ringed gas giant.
- Stars are drawn at native resolution on a cube face grid: round, no stretching, and flares cross cell borders instead of clipping.
- The sky pass no longer blends its own output, which was multiplying every effect by its alpha and washing it out.
- Removed Iridescent, Plasma and Warp Speed.

## 0.36.0

- New Storm sky: a heavy cloud deck lit from inside by lightning, with bolts. Strike rate triples while the world is actually storming.
- Deep Space rebuilt as a seeded galaxy with dust lanes, a nebula and three star layers; a Variant slider reshuffles it.
- Stars no longer flicker at high scale: they keep a fixed angular size and are antialiased against the pixel footprint.
- New Sky Quality setting. The sky used to always render at quarter resolution.
- Hand chams outline is a true dilation of the silhouette, measured in pixels, instead of a blur difference.
- New Motion Blur module under Visuals: camera reprojection blur with configurable strength, shutter, samples and length.

## 0.35.2

- Removed the Refraction, Rim Light and Dissolve hand chams effects.
- Config groups lay out three per row in a wider window, with smaller rows.

## 0.35.1

- Skybox only paints pixels the world never touched, instead of anything past a depth epsilon that distant terrain could cross.

## 0.35.0

- Hand Chams: refraction, rim light, motion trail, dissolve on item swap, plus Hologram and Space overlays with drifting parallax stars.
- Every colour picker now has an alpha bar and accepts 8 digit hex. Saved colours stay opaque.

## 0.34.7

- Chronomatron finds the board by its buttons instead of a fixed slot range, so the top half of every stacked button is tracked and highlighted again.
- A step now draws as one tall box spanning its whole column run.

## 0.34.6

- Highlights merge vertically first and only within the same step, so a stacked button is one tall box instead of being eaten by a sideways merge with the next click.

## 0.34.5

- Chronomatron matches by colour like Skyblocker instead of by remembered slot, so both halves of a stacked button light up and merge into one box with a single badge.

## 0.34.4

- Chronomatron only reads the board slots (17-25 or 17-34 by tier) instead of the whole chest, so border panes no longer invent sequence steps.

## 0.34.3

- Adjacent highlights now merge regardless of colour, as one rectangle with a gradient from the next click into the ghosted ones.

## 0.34.2

- Highlights merge in any direction (1x2 included) and a slot clicked twice in one sequence shows one box with both numbers.
- Chronomatron ignores packets from other containers and matches clicks by slot, fixing wrong-slot reads.

## 0.34.1

- Chronomatron tracks the slot that lit up instead of just its colour, so duplicate colours no longer highlight two slots at once.
- Adjacent highlights now merge into one rounded shape with a single badge.
- /pa exp keeps the last session so it can be run after closing the menu.

## 0.34.0

- Theme: font dropdown (Inter, Minecraft, Unicode) and a global font shadow toggle.
- Chronomatron now detects glint via item components and scans the whole chest, so a hidden glint override or an off-range board no longer stops it. Added /pa exp for diagnostics.

## 0.33.1

- Fixed Chronomatron never detecting anything. Client-side container updates do not notify slot listeners, so the solver now reads the container packets directly.

## 0.33.0

- Experimentation Table solvers: Chronomatron, Ultrasequencer and Superpairs. Rounded slot highlights with click-order badges, ghosted preview of upcoming clicks, and an info panel with a max-reward round counter.

## 0.32.0

- Menu overhaul no longer restyles other mods: buttons, sliders and backdrop only apply on vanilla screens/widgets.

## 0.31.1

- Wither essence, redstone key and other head secrets now claim on interaction, the way
  NoammAddons does it. They were routed through the open-screen packet, but a head never opens a
  screen, so they could never clear.
- Claim attempts are logged and reported by /pa dw room, including which secrets of that type are
  unclaimed and where they actually are.

## 0.31.0

- Secrets are grouped by the leading number in their name, the way Skyblocker does it, so claiming
  a secret clears every waypoint sharing that index. Entrance, superboom and stonk markers now
  disappear along with the chest or item they lead to instead of lingering.
- All 14 secret categories from Skyblocker are configurable, with their colours: entrance,
  superboom, chest, item, bat, wither, key, lever, fairysoul, stonk, aotv, pearl, prince, default.
  Five of them had no settings entry before and the colours were my own invention.
- Redstone key secrets can claim: the data calls the category "key" and I was matching
  "redstone_key", so they never cleared.

## 0.30.2

- Menu and pause buttons draw their label in the mod's font, matching the sliders. Previously only
  the button background was replaced, so vanilla still drew the text in its own font.

## 0.30.1

- Claimed secrets are stored on the room instance instead of a global set that was cleared whenever
  the room name changed, so leaving a cleared room and coming back no longer brings its waypoints
  back. Rooms are cached per physical segment, so each instance keeps its own state.

## 0.30.0

- Item secrets never cleared because the pickup hook injected at TAIL and then looked the entity up
  by id, by which point vanilla had already removed it. Now injects at HEAD.
- Chests are detected from ChestBlockEntity.triggerEvent, the lid opening event, the way Skyblocker
  does it, instead of tracking interactions and waiting for a screen.
- Rooms with a green checkmark on the dungeon map mark all their secrets claimed, so a room cleared
  by a teammate clears its waypoints.

## 0.29.2

- A failed room match now clears its checked blocks, rebuilds its candidates and retries after 50
  ticks, as Skyblocker does. I had made FAILED permanent, so a single bad scan left the room
  unidentified forever.
- /pa dw room reports candidates remaining, blocks checked and failed attempts.

## 0.29.1

- Room creation now follows Skyblocker's tick exactly. The map position is derived from the
  player's physical room position rather than read off the map marker, unknown room types abort
  instead of defaulting to ROOM, and only ROOM types flood fill for segments.
- Defaulting unknown colours to ROOM meant gaps between rooms were treated as rooms and flood
  filled from black, which is where the bogus 1x4 shapes came from.
- /pa dw room prints the room's segments.

## 0.29.0

- Skyblocker's DungeonMapUtils.java is now vendored verbatim rather than reimplemented, along with
  the Room type/direction/shape enums. Only two lines were removed, an unused overload and a debug
  log. All map maths, room segment flood fill and coordinate transforms are their code.
- Room type colours come from MapColor.getPackedId as they should. I had hardcoded byte values I
  guessed at, which mistyped puzzle, trap and miniboss rooms and matched them against the wrong
  room shape.

## 0.28.2

- The entrance room is now anchored to Mort's armor stand, the way Skyblocker does it. I had
  anchored it to wherever the player was standing when the map was first read, so unless you were
  in the entrance at that exact moment every map to world conversion was offset. That is why
  secrets showed in the wrong rooms or not at all.

## 0.28.1

- Room scanning runs every tick instead of once a second, matching Skyblocker.
- /pa dw room reports how much room data actually loaded, whether you are in a dungeon, and whether
  the dungeon map was found.

## 0.28.0

- Room detection rewritten as a direct port of Skyblocker's, replacing my brute-force matcher.
  Reads the dungeon map in slot 9 to find the entrance and room size, flood fills the map to get
  the room's segments, derives the shape from those segments, then eliminates candidates by
  checking blocks within 5 of the player, skipping doorways, with a 10 block double check.
- This fixes rooms flipping between identified and unidentified, rooms never matching, and secrets
  appearing in the wrong room, all of which came from guessing corners and sampling random blocks.
- Rooms are cached per segment so re-entering a room keeps its match.

## 0.27.1

- Room identification runs whenever either Dungeon Waypoints or Secret Waypoints is on, so the two
  features no longer depend on each other's toggle.

## 0.27.0

- Secret claiming rewritten to match NoammAddons exactly: driven by packets, not client events.
  Bat death from ClientboundSoundPacket, item pickup from ClientboundTakeItemEntityPacket and
  ClientboundRemoveEntitiesPacket, chests and wither essence from ClientboundOpenScreenPacket.
  Ranges copied too: item 25, bat 144, pickup 36.
- Custom dungeon waypoints are keyed by matched room name and stored relative to the room corner,
  the way NoammAddons does it, instead of my cell hash. They only render inside a dungeon.
- Both list scrollbars can be clicked and dragged; the bar takes priority over the rows.

## 0.26.0

- Secrets disappear once claimed: chest/wither/lever on interaction, item secrets when the item
  entity is picked up, bat secrets when the bat dies. Resets when you change room.
- Secret claim sound with a searchable, scrollable list of every registered sound, plus volume and
  pitch. Clicking a sound previews it.
- Fixed list content spilling outside the window. Shapes.popScissor called disableScissor outright
  instead of restoring the previous clip, so a nested list destroyed the content pane's scissor and
  everything drawn after it escaped the GUI. Scissors are now a real stack.

## 0.25.0

- New Secret Waypoints module under Dungeon, separate from your own Dungeon Waypoints. Rooms are
  identified by matching blocks against the bundled room skeletons, then the room's secrets are
  drawn at their real positions for that room's rotation.
- Per-category styling: toggle, colour and line width for chest, item, bat, wither, lever,
  fairysoul, stonk, superboom and entrance.
- Secret names, through-walls and opacity options.
- Custom dungeon waypoints are scoped to the room you are actually in again; the 3x3 cell search
  was masking the misaligned grid and is no longer needed now the grid is correct.
- /pa dw room also reports secret room matching state.

## 0.24.1

- Fixed the dungeon room grid. Rooms sit on a 32x32 grid offset by 8 blocks, not the origin I had
  assumed, so every room boundary was misaligned and cells straddled two real rooms.
- Vendored dungeon room skeletons and secret locations (139 rooms) for the upcoming Secret
  Waypoints module. Licence changed to GPL-3.0 with attribution in NOTICE.md, since that data is
  derived from DungeonRoomsMod (GPL-3.0).

## 0.24.0

- Waypoints draw through walls, via a line render type cloned from vanilla's with the depth test
  set to always pass. Toggleable.
- Waypoints no longer vanish when you step away: rooms are matched across the 3x3 cells around you
  and cached per cell, which also fixes 2x2 rooms losing waypoints in other quadrants.
- /pa dw add refuses air and non-block targets.
- Each row in the list has a tag icon (toggles that waypoint's name) and an eye icon (toggles that
  waypoint entirely). New TAG and EYE_OFF glyphs in the icon font.
- The saved list collapses from its header row.

## 0.23.1

- Waypoints were submitted during the render phase, after the submit collector had already been
  drained, so nothing drew. Moved to the COLLECT_SUBMITS phase.
- One Waypoints group now holds the settings, the saved list and the sharing buttons.
- The saved list is a single scrollable widget: click a name to rename, the swatch for a colour
  picker, the cross to delete.
- /pa dw room reports what the renderer did on the last frame.

## 0.23.0

- Dungeon Waypoints moved into the Dungeon tab instead of a tab of its own.
- Every saved waypoint is listed in the menu with an editable name, a colour picker and a delete
  button. The list rebuilds whenever the menu opens.
- Export copies all waypoints to the clipboard; import reads the clipboard or pa-dw-import.json,
  both from buttons in the menu.
- Waypoint names render in the world when Show Names is on.
- Dungeon Waypoints now defaults to on.

## 0.22.1

- Dungeon Waypoints tab was missing: the sidebar was built from an explicit allowlist that the new
  category was never added to. Categories are now ordered by a list and anything not in it is
  appended rather than dropped.

## 0.22.0

- Dungeon Waypoints. /pa dw add saves the block you are looking at; it is outlined whenever you
  re-enter that room. Rooms are identified by a rotation-canonical height-map signature of their
  32x32 cell, so waypoints survive across runs and are shareable.
- Waypoints save to config/pawfectaddons/pa-dw.json. /pa dw import reads pa-dw-import.json.
- /pa dw list, clear, and room (diagnostic readout).
- Dungeon Waypoints settings tab: enable, show names, line width, opacity, inflate, default colour.

## 0.21.0

- Sidebar flattened: removed the Visuals/Combat/General grouping, every tab is listed directly.
- Sidebar scrolls with its own scrollbar; the wheel scrolls whichever pane the cursor is over.

## 0.20.1

- Maxed detection: rarity line is now matched anywhere in the line rather than anchored, so
  recombobulated items (which carry obfuscated characters around it) parse correctly. Wider
  category keywords, and ExtraAttributes is read whether it sits at the root or nested.
- Added /pa item to dump what the detector sees for the held item.
- Build: pinned the Gradle daemon JVM and moved Kotlin compilation in-process so builds stop
  spawning a new daemon JVM each time.

## 0.20.0

- New Maxed Items module under General. Adds a [PA] gradient line to the bottom of every SkyBlock
  item tooltip showing how upgraded it is, coloured red to yellow to lime, bold lime when maxed.
- Hold middle click on a tooltip to list the missing upgrades and an estimated bazaar cost.
- Enchants are excluded from the percentage.
- Removed the Item Shell hand chams option.

## 0.19.1

- Lava Changer reduced to a single Lava To Water toggle. Removed the appearance presets, custom
  texture field, tint, translucency and the generated liquid sprites.

## 0.19.0

- Lava Changer gains five procedural animated liquids: Plasma, Liquid Metal, Void, Molten and
  Electric. Generated offline with periodic 3D value noise so they tile seamlessly and loop, then
  stitched into the block atlas as animated sprites. Vanilla animates them, so they cost nothing
  at runtime regardless of how much lava is on screen.
- Recolour any of them with the Tint picker.

## 0.18.1

- Lava Changer no longer tints everything blue. It was reusing water's tint source, which is the
  biome water colour, so every texture came out blue. Now uses a constant tint.
- New presets: Solid Colour, Void, Crying Obsidian, Sculk, Magma, Prismarine, Sea Lantern,
  Blue Ice, Honey, Slime.
- Added a Tint colour picker that recolours any preset, and a Translucent toggle.
- Replacements render on the solid layer by default instead of water's translucent layer.

## 0.18.0

- Hand chams: the wireframe box is replaced by Item Shell, a coloured layer that follows the held
  item's actual geometry instead of a cuboid around it.
- Config menu: search moved to the title bar where the version was, version moved to the footer,
  search icon moved to the right of the field.
- Four new skyboxes: Iridescent, Deep Space, Plasma, Warp Speed.

## 0.17.1

- Album art: the blit was cropping, not scaling. Switched to the overload that takes a source
  region, so the whole image is drawn instead of its top-left 32x32 corner.
- Hand chams box no longer crashes: the LINES format carries a LineWidth element that has to be
  written per vertex. Added a Line Width slider.
- Buttons and sliders are now styled on every screen, including the pause menu.
- Sliders match the button styling; loading and world-transform screens use the menu backdrop.
- Minecraft logo replaced with an animated PawfectAddons title and the version below it.
- Hover state matches the backdrop palette; removed the hover accent bar.
- Media panel is draggable on any screen, including the title screen.
- Menu backdrop animates faster. Removed the End Portal sky effect.

## 0.17.0

- Album art is now box-downscaled on the CPU to the exact pixel size it is drawn at, so it keeps
  full detail instead of being point-sampled down from 150px to 32px.
- Media panel draws above open screens and its transport controls are clickable there.
- New End Portal skybox effect with its own default palette.
- Hand chams: optional true 3D wireframe box around the held item, submitted as world-space line
  geometry so it tracks the item's pose. Full edges or corner brackets.
- New Main Menu section: animated shader backdrop, restyled vanilla buttons that override resource
  packs, splash text replaced with PawfectAddons and the version.

## 0.16.1

- Icons: Inter maps 764 private-use codepoints and was shadowing the icon font (only FIRE at
  U+E001 fell through). Stripped the PUA cmap ranges from the bundled Inter faces.
- Album art: WinRT `OpenReadAsync` never completes on PowerShell's STA thread. The helper now
  runs on an MTA worker and waits on `Completed` instead of polling `Status`.
- Skybox horizon slider now reaches full-sky coverage at its low end.
- Skybox uses the live camera FOV, so zoom and sprint no longer skew the projection.

## 0.16.0

- Icons fixed. picosvg was converting strokes correctly, but open stroked outlines are single
  self-intersecting contours that nonzero fill renders solid. Each shape is now simplified with
  skia-pathops before it becomes a glyph.
- Skybox rewritten in world space. A per pixel view ray is built from the camera basis and field
  of view, so the sky stays fixed while the camera turns and no longer tiles.
- Skybox now renders the effect at quarter resolution and masks it at full resolution, so edges
  stay crisp while costing a sixteenth of the fragments.
- Sun, moon and vanilla stars are hidden while the skybox is on.
- Aurora rebuilt around real elevation, so it actually appears. Nebula is now filaments and dust
  lanes with its own embedded stars. Void Rift no longer seams. Gradient replaced with Clouds,
  projected onto a cloud plane so it converges at the horizon.
- New Oil Slick overlay for hand chams: thin film interference over a domain warped thickness
  field, so the iridescence shifts rather than scrolling.
- Media album art now blits at the source image size instead of assuming 32 by 32, and capture
  retries when the file is missing. Added /pa media to report bridge and art state.

## 0.15.0

- Icons are now a generated font built from Iconify SVGs, with codepoints emitted alongside
  Icons.kt so the mapping can never drift. Phosphor and the icon probe screen are gone.
- Album art works. A small C# helper is compiled once by the .NET compiler that ships with
  Windows, which projects WinRT correctly where PowerShell 5.1 cannot.
- Media keybinds now have defaults: Home toggles playback, Page Up and Page Down change track.
- Hand chams: removed Ice, Smoke, Fire and Glint. Edge Glow is now an Outline that traces the
  silhouette, with thickness, hardness and opacity. Removed the glow animation options.
- Hand chams: Portal Layers only shows for End Portal, custom colours now grade the End Portal,
  and Hide Enchant Glint was added to Core.
- New Skybox tab under Visuals. Aurora, Nebula, Liquid, Gradient and Void Rift, drawn only where
  the world is empty, with stars, horizon control and custom colours.

## 0.14.2

- Menu and media transparency now affect backgrounds only. Text, borders and accents stay solid,
  and the background floors at about 40 percent so the menu stays readable at the lowest setting.
- Sidebar rows use fixed caret and icon columns, so labels line up regardless of glyph width.
- Tab highlight no longer lags behind a dragged window. It is anchored to the window and only
  animates when you actually select a tab.
- Duration bar has a horizontal gradient. Added horizontal gradients to the shape pipeline.
- Media playback keybinds for play/pause, next and previous, unbound by default.
- Media bridge prefers pwsh when installed, which is what album art would need.
- Added /pa icons, a labelled glyph grid for verifying icon codepoints.

## 0.14.1

- Fixed every menu glyph rendering as a box. The font providers declared "minecraft:ttf", but the
  glyph provider type is a plain string enum in 26.1.2, so the correct value is "ttf". The font
  failed to load and Minecraft substituted its missing-glyph font.

## 0.14.0

- Menu font is now Inter, rendered through Minecraft's TrueType provider, so the UI is no longer
  pixelated. Icons come from Phosphor as a second provider in the same font.
- Sidebar is now grouped into collapsible Visuals, Combat and General sections, each with an icon.
  Search still spans every category in every section.
- Menu is draggable by its title bar and is clamped so it can never leave the screen.
- Player card floats above the menu with the head, name and session uptime.
- New Media feature under Visuals. Reads the Windows media session for title, artist, position,
  duration and playback state, with a draggable themed panel and its own transparency slider.
- Carbon is now the default theme. Added a Transparency slider for the whole menu and a Text
  Offset slider for nudging the new font's baseline.

## 0.13.0

- New GPU shape engine for the menu. Custom vertex format and render pipeline submitted through
  vanilla's GuiElementRenderState, so every panel, border, shadow and knob is one SDF quad and the
  whole menu batches into a single draw call.
- Genuinely rounded corners at any resolution. Edge antialiasing is derived per pixel with fwidth,
  so radii stay smooth at every GUI scale and monitor DPI.
- Menu transform now snaps to whole physical pixels, which fixes the soft resampled text.
- Redesign: rounded window with elevation shadows, sidebar pills with a sliding accent indicator,
  pill toggle switches, rounded slider tracks with circular knobs, floating dropdown cards.
- Colour picker saturation field and hue bar are now one quad each instead of roughly 250 strips.

## 0.12.1

- Fixed hand chams disabling itself when the End Portal overlay loaded its textures. The lazy
  texture upload ran inside an open render pass, which the GPU encoder rejects. Portal textures
  are now resolved before the composite pass and only when the overlay is selected.

## 0.12.0

- Replaced Nebula with two overlays. The star and dust layers were computed in screen space,
  so the white specks stayed pinned to the screen instead of moving with the hand.
- New Liquid overlay: the domain warped flow from Nebula on its own, without the stars.
- New End Portal overlay, ported 1:1 from vanilla rendertype_end_portal. Same sixteen colour
  constants, same per layer translate, rotate and scale matrix, same textures via
  end_sky.png and end_portal.png, and the same fifteen layers vanilla uses.
- Portal Layers slider, 1 to 16. Vanilla is 15, the end gateway uses 16.
- Custom Colours is hidden for End Portal, which uses vanilla's exact palette.

## 0.11.0

- Hand chams features are no longer gated behind a core toggle. Blur, Tint, Saturation,
  Edge Glow and Overlay each enable independently, and the effect runs if any of them is on.
- Tint and Saturation are now separate toggles with their own settings rather than always on.
- New Nebula overlay: domain warped fbm clouds, drifting dust and twinkling stars.
- Overlay Colours group with Primary, Secondary and Highlight, behind a Custom Colours
  toggle so each effect keeps its built in palette by default.
- Edge glow minimum radius lowered from 0.5 to 0.1.
- Animate Glow pulses and shifts the halo colour, and Sync With Overlay drives it from the
  overlay's own timing and palette. Nebula tints the halo with the live nebula colours.

## 0.10.0

- Hand chams now runs with zero full screen copies and defaults to full resolution.
  Minecraft's own colour and depth textures are created with USAGE_TEXTURE_BINDING, so they
  can be sampled directly instead of copied. The four full screen copies per frame were
  roughly 66MB of bandwidth at 1080p and were the real cost, not the blur.
- The mask now samples the live depth buffer, so the second mixin hook is gone. One hook,
  one mask pass, the blur pyramid and one composite.
- The composite no longer samples the scene. It outputs the processed colour with alpha and
  lets blend hardware mix it with what is already in the framebuffer.
- Removed Blur Amount, Mask Threshold and Colour Fallback, which only existed to support the
  copy based mask. Opacity is now the single strength control.

## 0.9.3

- Tracking an item now shows the recipe tracker overlay. The overlay had a per profile
  visibility flag separate from the config toggle, so if it had ever been turned off,
  /pa track would save the goal and confirm in chat while nothing appeared on screen.
- Tracking something while the Recipe Tracker feature is disabled in /pa now says so
  instead of failing silently.

## 0.9.2

- HUD editor can reset positions. Right-click an element (or hover and press R) to send just
  that one back to its default, or use Reset All, which asks for confirmation first.
- Grid snapping in the HUD editor. Toggle with the button or G, change the size with the
  plus and minus buttons or by scrolling on empty space. Grid and centre lines are drawn
  while it is on, and the setting is saved.
- Default HUD positions now live in one place, so a reset can never drift from the value a
  fresh config would use.
- Hovering an element now shows its coordinates and scale.

## 0.9.1

- Fixed stat values reading wrong. The action bar string still contains its colour codes,
  and codes whose character is a digit were being swallowed into the number, so gold health
  read 63,244 instead of 3,244 and vitality read 4104 instead of 104. Mana and defense were
  unaffected only because their codes are letters. The parser now consumes the colour code
  explicitly and keeps it.
- Enabling a stat module now removes just that stat from Hypixel's action bar and leaves the
  rest intact, via MODIFY_GAME. Hide Whole Action Bar is still available separately.
- New Use Bar Colours toggle: modules take Hypixel's own colour, so absorption still shows
  gold. Show Labels off gives numbers only.

## 0.9.0

- Action bar stats are parsed by their Hypixel font icons rather than by position, so
  missing or reordered stats do not break it. Health U+E010, Defense U+E008, Mana U+E003,
  Overflow Mana U+E017, Vitality U+E028.
- New Stats groups under Visuals. Each stat can be shown as its own draggable on screen
  module with independent position, plus label and maximum toggles.
- Optional Hide Action Bar so the modules replace Hypixel's bar.
- New Low Health Alert. Pulses the screen edges below a configurable health percentage,
  with colour, opacity, speed and edge thickness.
- Added a horizontal gradient primitive to the render layer.

## 0.8.4

- Fixed hand chams rendering nothing. Minecraft clears the depth buffer to 1.0 immediately
  before drawing the hand, so the "before" depth was all 1.0 and the hand writes something
  nearer. The mask tested for a larger depth value, which is never true after a clear, so
  the mask was empty and every pixel discarded. The test is now inverted and also treats any
  depth below 1.0 as hand, which is exact given the clear.
- New Debug View under Hand Chams / Performance: draw the mask, blurred mask or blurred
  scene full screen to see what the effect is actually computing.

## 0.8.3

- Group boxes collapse when you click their header, and the collapsed state is saved.
- All hand chams settings moved into their own Hand Chams section, split into collapsible
  Core, Blur, Edge Glow, Overlay and Performance groups.
- Added /pa chams, which prints why the effect is or is not running: capture and composite
  counts, target sizes, the last skip reason and the last error.
- A GPU failure now reports in chat instead of only the log.

## 0.8.2

- Fixed hand chams producing torn, see-through pixels on the held item. On 26.1
  renderItemInHand only submits the hand; it is actually drawn later in renderLevel by
  renderAllFeatures and endBatch. The old hook captured the "after" frame before the hand
  existed and composited before the hand was drawn, so the effect was built from a partial
  frame and then painted over. Capture now happens after the real flush.

## 0.8.1

- Fixed the hand chams performance collapse. The Kawase blur was running every pass at
  full resolution; it now downsamples through a mip pyramid the way dual filtering is
  meant to work, and the whole effect runs at half resolution by default.
- Chams now skip entirely in third person, with no world, or with no player.
- Colour fallback in the mask is optional and saves a full screen copy when off.
- New overlay effects: Ice Distortion, Smoke, Fire and Glint, with strength and speed.
- Every colour picker now has a hex field you can type into directly.
- The menu reopens on the category you closed it on.
- Saved settings are applied on the first tick after load, not just held in memory.
- Keybind category now reads PawfectAddons instead of the raw translation key.
- Menu Scale now maxes out at 1.

## 0.8.0

- Hand Chams under Visuals. A glass shader over your hand and held item.
- The hand is isolated by diffing the framebuffer before and after hands are drawn,
  using the depth delta with a colour-diff fallback. No separate render target needed.
- Kawase dual-filter blur with radius, quality (pass count) and blend amount.
- Edge glow derived from the blurred mask minus the sharp mask, with colour,
  intensity and radius.
- Edge softness, mask threshold, opacity, saturation and a tint colour.
- Shaders adapted from Refract by TCD1234 under the MIT license, included in
  licenses/Refract-MIT.txt.
- Any GPU error disables the feature and logs it rather than breaking rendering.

## 0.7.1

- Fixed the Menu Scale slider going haywire while dragging. Changing the scale moved the
  window and remapped the cursor mid-drag, which fed back into the slider. The UI transform
  is now frozen for the duration of any drag and applied on release.
- Search now spans every category, not just the open one. Results show which category each
  group came from. Clicking a category clears the search.

## 0.7.0

- Replaced MoulConfig with a custom config UI. Jar shrank from 746KB to 338KB.
- Single centred window: category rail on the left, titled group boxes in two columns.
- All rectangles are drawn as a scaled 1x1 quad, so positions are float precise and
  animations no longer snap to whole pixels.
- The menu renders on its own 960x540 canvas, so it looks identical at every GUI scale.
- Six theme presets plus a full custom palette with an inline HSV colour picker.
- Menu scale, animation and click sound toggles.
- Settings can now hide themselves: Custom Texture only appears when Lava Appearance
  is set to Custom, and the bat options only appear when the highlighter is on.
- New Dungeon category. Bat Highlight moved there from Visuals.
- Saved config keeps the same JSON shape, so existing settings carry over.

## 0.6.0

- New Slayers config tab. Tracks your slayer boss and shows its health live.
- Boss health reads the boss name armour stand, so it updates every frame.
- Health colour ramps with remaining health: red above 75%, orange above 50%,
  yellow above 25%, lime below that.
- Display choice of screen overlay, world text below the boss (drawn through walls), or both.
- Boss target choice of Your Boss or Nearest.
- Hide Tooltips under Visuals, with a keybind that shares the same toggle state and
  works while a menu is open. Only menu tooltips are hidden, chat hover text is untouched.
- Dungeon Bat Highlight under Visuals. Outlines dungeon bats through walls and skips
  pet bats and invisible bats. Colour and range are configurable.
- Sidebar scoreboard lines are now parsed, adding dungeon detection.

## 0.5.0

- New Visuals config tab.
- Lava Changer: renders lava as water by default, or as any block texture including nether portal.
  Purely client side, no packets, and lava still burns and slows you exactly as before.
- Optional lava fog removal.
- `/pa toggle <feature>` now toggles any feature instead of only the recipe tracker;
  `/pa toggle` with no argument lists features and their state.
- Tracker specific commands moved under `/pa track` (`list`, `clear`, `toggle`).
  `/pa untrack <item>` stays top level.

## 0.4.0

- `/pa untrack <set> armor` now removes a whole armour set, matching `/pa track`.
- Untrack tab completion suggests tracked armour sets by name.
- Enchanted books now show their enchant name instead of all being called "Enchanted Book", so
  craftable ones like Crop Fever appear in `/pa track` completion.
- Enchant levels accept both roman and arabic forms, e.g. `crop fever v` and `crop fever 5`.

## 0.3.0

- Track a whole armour set at once, e.g. `/pa track fermento armor`. Adds every piece as a normal
  tracked item, so their shared materials merge into the same list.
- Set names resolve by display name too, so `necron armor` works despite the internal id being
  `POWER_WITHER`.
- Armour set names appear in `/pa track` tab completion.

## 0.2.0

- Count items from your inventory and equipped gear on top of sacks, so worn armour pieces register
  as recipe ingredients.
- Count ender chest and backpack contents for pages you have opened at least once.
- Added `/pa untrack <item>`, `/pa list`, `/pa toggle` and `/pa clear` as top level commands so they
  are visible from `/pa` without digging into `/pa track`.
- `/pa clear` now asks for confirmation, requiring a second run within 15 seconds.
- Mod author set to pawliciously.
- Removed all code comments.

## 0.1.1

- Mod icon is now pawliciously's player head.
- Recipes use only an item's direct ingredients instead of expanding into a full tree.
- Ingredients shared between tracked items are summed into a single row.
- Custom head items such as Fermento, Cropie and Squash render their real icons.
- Ingredients at 100% or more show a lime bold check mark instead of a percentage.
- Removed the non functional Text Scale option; use the scroll wheel in `/pa gui`.
- Chat prefix is now a purple gradient.

## 0.1.0

- First build: Recipe Tracker overlay, sack tracking, NEU recipe data, bazaar pricing,
  MoulConfig settings screen and a draggable HUD position editor.
