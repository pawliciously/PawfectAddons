package dev.pawfect.addons.features.drops

import dev.pawfect.addons.features.cosmetics.WorldFx
import dev.pawfect.addons.features.drops.DropCelebration.Cue
import dev.pawfect.addons.features.drops.DropCelebration.argb
import dev.pawfect.addons.features.drops.DropDraw.backOut
import dev.pawfect.addons.features.drops.DropDraw.blob
import dev.pawfect.addons.features.drops.DropDraw.easeInCubic
import dev.pawfect.addons.features.drops.DropDraw.easeOutCubic
import dev.pawfect.addons.features.drops.DropDraw.glint
import dev.pawfect.addons.features.drops.DropDraw.line
import dev.pawfect.addons.features.drops.DropDraw.ray
import dev.pawfect.addons.features.drops.DropDraw.ring
import dev.pawfect.addons.features.drops.DropDraw.soft
import dev.pawfect.addons.ui.Draw.gradientHorizontal
import dev.pawfect.addons.ui.Draw.rect
import dev.pawfect.addons.ui.Theme
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** An item's own moment, on top of its tier's. */
enum class Signature(val id: String, val ownMusic: Boolean) {
    NONE("none", false),

    /** The dye's colour splashes over the screen like paint and runs. */
    DYE("dye", false),

    /** It falls from the sky, slams into the ground point first and cracks the screen. */
    GIANT("giant", false),

    /** A dial ticks it up through every rarity, as if it's recombobulating itself. */
    RECOMB("recomb", true),

    /** A constellation draws itself a star at a time, a star for each the Master Star is. */
    STARS("stars", true),

    /** Necron's lasers sweep the screen and close in on it. */
    NECRON("necron", false),
}

/**
 * Where the item sits and how it's turned, off the centre of the screen. Anything of it lower than
 * [clipBelow] (also off the centre) is hidden, as if it's gone into the screen there.
 */
class ItemPose(
    val x: Float,
    val y: Float,
    val zoom: Float,
    val spin: Float,
    val tilt: Float,
    val roll: Float,
    val clipBelow: Float = Float.NaN,
)

object DropSignatures {

    fun of(name: String, id: String?): Signature {
        val key = id?.uppercase().orEmpty()
        return when {
            key.endsWith("_DYE") || name.trim().endsWith(" Dye", ignoreCase = true) -> Signature.DYE
            key == "GIANTS_SWORD" -> Signature.GIANT
            key == "RECOMBOBULATOR_3000" -> Signature.RECOMB
            key.endsWith("_MASTER_STAR") -> Signature.STARS
            key == "NECRON_HANDLE" -> Signature.NECRON
            else -> Signature.NONE
        }
    }

    /** How many stars a Master Star draws. */
    fun starCount(id: String?): Int = when (id?.uppercase()) {
        "FIRST_MASTER_STAR" -> 1
        "SECOND_MASTER_STAR" -> 2
        "THIRD_MASTER_STAR" -> 3
        "FOURTH_MASTER_STAR" -> 4
        else -> 5
    }

    /** Roughly the colour each dye is named for; anything else takes its vanilla dye's colour. */
    private val dyeColors = mapOf(
        "necron" to 0xE7413C, "pearlescent" to 0x115555, "jade" to 0x00A86B, "nyanza" to 0xE9FFDB,
        "aquamarine" to 0x7FFFD4, "celadon" to 0xACE1AF, "emerald" to 0x50C878, "bone" to 0xE3DAC9,
        "carmine" to 0x960018, "mango" to 0xFDBE02, "byzantium" to 0x702963, "cyclamen" to 0xF56FA1,
        "holly" to 0x3C6746, "flame" to 0xE25822, "wild strawberry" to 0xFF43A4, "dark purple" to 0x301934,
        "livid" to 0xCEB7AA, "nadeshiko" to 0xF6ADC6, "midnight" to 0x50216C, "pelt" to 0x50414C,
        "iceberg" to 0x71A6D2, "brick red" to 0xCB4154, "sangria" to 0xD40808, "chocolate" to 0x7B3F00,
        "copper" to 0xB87333, "treasure" to 0xFCD12A, "celeste" to 0xB2FFFF, "tentacle" to 0x324D6C,
        "frostbitten" to 0x09D8EB, "matcha" to 0x74A12E, "periwinkle" to 0xCCCCFF, "dung" to 0x4F2A2A,
        "mocha" to 0x967969, "sunset" to 0xF5BB5F, "rose" to 0xFE5AB6, "wild cherry" to 0xC6283F,
        "warden" to 0x005C5C, "chartreuse" to 0x7FFF00, "fossil" to 0x866F12, "secret" to 0x7D7D7D,
    )

    val dyeNames: List<String> get() = dyeColors.keys.map { name -> name.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } + " Dye" }

    fun dyeColor(name: String, stack: ItemStack): Int {
        val key = name.trim().lowercase().removeSuffix(" dye").trim()
        dyeColors[key]?.let { return it }
        stack.get(DataComponents.DYE)?.let { return it.textureDiffuseColor and 0xFFFFFF }
        return java.awt.Color.HSBtoRGB((key.hashCode() and 0xFFFF) / 65535f, 0.7f, 0.95f) and 0xFFFFFF
    }

    // ------------------------------------------------------------------ hooks

    /** Drawn over the wind up's light, under its glow. */
    fun windup(fr: DropFrame, t: Float) {
        when (fr.show.signature) {
            Signature.NECRON -> lasers(fr, t / fr.f.windup)
            Signature.GIANT -> fallStreaks(fr, t)
            else -> Unit
        }
    }

    /** The item, if it's already on screen before it lands. */
    fun windupItem(fr: DropFrame, t: Float): ItemPose? {
        if (fr.show.signature != Signature.GIANT) return null
        val from = fr.f.windup * GIANT_FALL_FROM
        val q = (t - from) / (fr.f.windup - from)
        if (q < 0f) return null
        val top = -(fr.cy + fr.unit * 3f)
        val y = top + (plantedY(fr, GIANT_ZOOM) - top) * easeInCubic(q)
        return ItemPose(0f, y, GIANT_ZOOM, 0f, 0f, SWORD_DOWN, entryY(fr))
    }

    fun impactPose(fr: DropFrame, index: Int): ItemPose? = when (fr.show.signature) {
        Signature.GIANT -> ItemPose(0f, plantedY(fr, GIANT_ZOOM), GIANT_ZOOM, 0f, 0f, SWORD_DOWN + if (index % 2 == 0) -0.04f else 0.04f, entryY(fr))
        else -> null
    }

    /** Drawn behind the item through the reveal. */
    fun behind(fr: DropFrame, r: Float, out: Float) {
        when (fr.show.signature) {
            Signature.DYE -> paint(fr, r, out)
            Signature.GIANT -> cracks(fr, r, out)
            Signature.STARS -> constellation(fr, r, out)
            Signature.NECRON -> sweeps(fr, r, out)
            Signature.RECOMB -> soft(fr.g, fr.cx, fr.cy, fr.unit * 0.95f, argb(rarity(r).first, 0.35f * out))
            Signature.NONE -> Unit
        }
    }

    /** How much lower the rarity text sits, to clear the signature. */
    fun labelDrop(fr: DropFrame): Float = if (fr.show.signature == Signature.GIANT) fr.unit * 0.45f else 0f

    /** Drawn over the item, under the text. */
    fun front(fr: DropFrame, r: Float, out: Float) {
        when (fr.show.signature) {
            Signature.RECOMB -> dial(fr, r, out)
            Signature.GIANT -> slit(fr, r, out)
            else -> Unit
        }
    }

    /** The item through the reveal, moved its own way. [outro] runs 0 to 1 as it goes. */
    fun pose(fr: DropFrame, r: Float, outro: Float, base: ItemPose): ItemPose = when (fr.show.signature) {
        Signature.GIANT -> {
            // Stuck a third of the way into the screen, juddering from the hit, and otherwise dead
            // still: no bob or tilt, so it reads as stuck fast.
            val judder = sin(r * 0.09f) * 0.14f * exp(-r / 450f)
            // Before the end it grinds down through the slit and is gone.
            val sink = easeInCubic((r - sinkStart(fr.show)) / SINK_MS)
            val shudder = sin(r * 0.25f) * 1.5f * sink * (1f - sink)
            val down = sink * (2f * SWORD_TIP * GIANT_ZOOM + 0.3f) * fr.unit
            ItemPose(shudder, plantedY(fr, GIANT_ZOOM) + down, GIANT_ZOOM, 0f, 0f, SWORD_DOWN + judder, entryY(fr))
        }
        else -> base
    }

    fun cues(show: DropCelebration.Show): List<Cue> {
        val f = show.feel
        val hit = show.impactAt
        val reveal = show.revealAt
        val list = ArrayList<Cue>()
        when (show.signature) {
            Signature.DYE -> {
                list += Cue(reveal, "minecraft:entity.glow_squid.squirt", 0.9f, 1f)
                for (k in 0 until 4) list += Cue(reveal + k * SPLAT_GAP, "minecraft:entity.slime.squish", 0.6f, 0.8f + WorldFx.hash(show.seed, k, 74) * 0.5f)
                for (d in 0 until 3) list += Cue(reveal + 900f + d * 750f, "minecraft:block.pointed_dripstone.drip_water", 0.8f, 0.9f + d * 0.1f)
            }
            Signature.GIANT -> {
                list += Cue(f.windup * GIANT_FALL_FROM, "minecraft:item.trident.riptide_3", 0.8f, 0.6f)
                list += Cue(hit, "minecraft:block.anvil.land", 1f, 0.5f)
                list += Cue(hit, "minecraft:block.deepslate.break", 1f, 0.6f)
                list += Cue(hit + 30f, "minecraft:block.glass.break", 0.9f, 0.6f)
                val sink = reveal + sinkStart(show)
                list += Cue(sink, "minecraft:block.grindstone.use", 0.9f, 0.6f)
                list += Cue(sink + SINK_MS * 0.5f, "minecraft:block.grindstone.use", 0.7f, 0.5f)
                list += Cue(sink + SINK_MS, "minecraft:block.deepslate.break", 0.8f, 0.5f)
            }
            Signature.RECOMB -> {
                for (k in RARITIES.indices) {
                    val at = reveal + DIAL_START + k * DIAL_STEP
                    list += Cue(at, "minecraft:block.note_block.bit", 0.8f, 0.7f + k * 0.24f)
                    list += Cue(at, "minecraft:ui.button.click", 0.4f, 1.6f)
                }
                val done = reveal + DIAL_START + RARITIES.size * DIAL_STEP
                list += Cue(done, "minecraft:block.smithing_table.use", 0.9f, 1f)
                list += Cue(done, "minecraft:block.enchantment_table.use", 0.8f, 1f)
                list += Cue(done + 80f, "minecraft:entity.player.levelup", 0.6f, 1.4f)
            }
            Signature.STARS -> {
                val notes = intArrayOf(0, 4, 7, 12, 16)
                for (k in 0 until show.extra) {
                    val at = reveal + starAt(k)
                    list += Cue(at, "minecraft:block.amethyst_block.chime", 1f, 0.9f + k * 0.2f)
                    list += Cue(at, "minecraft:block.note_block.chime", 0.8f, 2f.pow(notes[k] / 12f))
                }
                list += Cue(reveal + starAt(show.extra) + 120f, "minecraft:block.beacon.power_select", 0.6f, 1.6f)
            }
            Signature.NECRON -> {
                list += Cue(0f, "minecraft:entity.guardian.attack", 0.9f, 0.9f)
                list += Cue(0f, "minecraft:block.beacon.activate", 0.6f, 0.6f)
                var at = SWEEP_START
                while (reveal + at < show.outroAt) {
                    list += Cue(reveal + at, "minecraft:entity.guardian.attack", 0.35f, 1.5f)
                    at += SWEEP_EVERY
                }
            }
            Signature.NONE -> Unit
        }
        return list
    }

    // ------------------------------------------------------------------ dye

    private fun paint(fr: DropFrame, r: Float, out: Float) {
        val g = fr.g
        val seed = fr.seed
        val base = fr.color
        val tones = intArrayOf(base, Theme.mix(base, 0x000000, 0.3f), Theme.mix(base, 0xFFFFFF, 0.25f))
        if (r < 900f) DropDraw.full(fr, argb(base, 0.22f * (1f - r / 900f)))
        for (k in 0 until SPLATS) {
            val at = k * SPLAT_GAP
            val q = (r - at) / 140f
            if (q <= 0f) continue
            val grow = backOut(min(1f, q))
            val sx = fr.w * (0.08f + WorldFx.hash(seed, k, 70) * 0.84f)
            val sy = fr.h * (0.08f + WorldFx.hash(seed, k, 71) * 0.78f)
            val radius = (22f + WorldFx.hash(seed, k, 72) * 34f) * fr.size * grow
            val a = 0.93f * out
            val tone = tones[k % tones.size]
            blob(g, sx, sy, radius, argb(tone, a))
            // A ragged edge of smaller blobs round it.
            for (j in 0 until 10) {
                val angle = WorldFx.hash(seed, k * 13 + j, 73) * WorldFx.TAU
                val d = radius * (0.65f + WorldFx.hash(seed, k * 13 + j, 75) * 0.45f)
                blob(g, sx + cos(angle) * d, sy + sin(angle) * d, radius * (0.16f + WorldFx.hash(seed, k * 13 + j, 76) * 0.26f), argb(tone, a))
            }
            // Drops flung off it.
            for (j in 0 until 8) {
                val angle = WorldFx.hash(seed, k * 17 + j, 77) * WorldFx.TAU
                val d = radius * (1.25f + WorldFx.hash(seed, k * 17 + j, 78) * 1.1f) * min(1f, q * 0.8f)
                blob(g, sx + cos(angle) * d, sy + sin(angle) * d, (1.2f + WorldFx.hash(seed, k * 17 + j, 79) * 2.6f) * fr.size, argb(tone, a))
            }
            // Runs down the screen, each at its own pace.
            val drips = 2 + (WorldFx.hash(seed, k, 80) * 3f).toInt()
            for (d in 0 until drips) {
                val s = k * 7 + d
                val dx = sx + (WorldFx.hash(seed, s, 81) - 0.5f) * radius * 1.3f
                val top = sy + radius * 0.5f
                val start = at + 260f + WorldFx.hash(seed, s, 82) * 400f
                val grown = ((r - start) / 1000f).coerceAtLeast(0f) * (14f + WorldFx.hash(seed, s, 83) * 30f) * fr.size
                val length = min(grown, (30f + WorldFx.hash(seed, s, 84) * 110f) * fr.size)
                if (length > 0.5f) {
                    val width = (2.2f + WorldFx.hash(seed, s, 85) * 2.8f) * fr.size
                    g.rect(dx - width / 2f, top, width, length, argb(tone, a))
                    blob(g, dx, top + length, width * 0.75f, argb(tone, a))
                }
            }
            blob(g, sx - radius * 0.35f, sy - radius * 0.35f, radius * 0.22f, argb(tones[2], a * 0.5f))
        }
    }

    // ------------------------------------------------------------------ giant's sword

    /** The line it goes into the screen at, below the centre. */
    private fun entryY(fr: DropFrame): Float = SWORD_ENTRY * fr.unit

    /** Where the sword's centre sits with its point [SWORD_BURY] blocks into the screen. */
    private fun plantedY(fr: DropFrame, zoom: Float): Float = (SWORD_ENTRY - (SWORD_TIP * zoom - SWORD_BURY)) * fr.unit

    /** When it starts sinking out of sight, counted from the reveal. */
    private fun sinkStart(show: DropCelebration.Show): Float = show.outroAt - show.revealAt - SINK_MS

    /** Where the point went in. */
    private fun tip(fr: DropFrame): Pair<Float, Float> = fr.cx to fr.cy + entryY(fr)

    /** The slit it's stuck in, with sparks thrown up as it grinds down through. */
    private fun slit(fr: DropFrame, r: Float, out: Float) {
        val g = fr.g
        val (tx, ty) = tip(fr)
        val half = fr.unit * 0.42f
        g.gradientHorizontal(tx - half, ty - 1.2f, half, 2.4f, argb(0x000000, 0f), argb(0x000000, 0.85f * out))
        g.gradientHorizontal(tx, ty - 1.2f, half, 2.4f, argb(0x000000, 0.85f * out), argb(0x000000, 0f))
        g.gradientHorizontal(tx - half, ty + 1.2f, half, 0.8f, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.6f * out))
        g.gradientHorizontal(tx, ty + 1.2f, half, 0.8f, argb(0xFFFFFF, 0.6f * out), argb(0xFFFFFF, 0f))
        val sink = ((r - sinkStart(fr.show)) / SINK_MS).coerceIn(0f, 1f)
        if (sink <= 0f || sink >= 1f) return
        for (i in 0 until 14) {
            val phase = ((r / 260f) + WorldFx.hash(fr.seed, i, 110)) % 1f
            val side = if (i % 2 == 0) 1f else -1f
            val x = tx + side * (fr.unit * 0.08f + phase * 26f * fr.size)
            val y = ty - phase * 16f * fr.size + phase * phase * 34f * fr.size
            val a = (1f - phase) * out
            soft(g, x, y, 2.6f * fr.size, argb(0xFFB347, a * 0.7f))
            blob(g, x, y, 0.8f * fr.size, argb(0xFFFFFF, a))
        }
    }

    private fun fallStreaks(fr: DropFrame, t: Float) {
        val from = fr.f.windup * GIANT_FALL_FROM
        val q = (t - from) / (fr.f.windup - from)
        if (q <= 0f) return
        // Air rushing past it, and its shadow growing where it'll hit.
        for (i in 0 until 24) {
            val x = fr.cx + (WorldFx.hash(fr.seed, i, 90) - 0.5f) * fr.unit * 2.2f
            val y = ((WorldFx.hash(fr.seed, i, 91) + q * 3f) % 1f) * fr.h
            val length = 20f + 50f * q
            line(fr.g, x, y - length, x, y, 1f, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.5f * q))
        }
        val (tx, ty) = tip(fr)
        soft(fr.g, tx, ty, 10f + 30f * q * q, argb(0x000000, 0.6f * q))
    }

    private fun cracks(fr: DropFrame, r: Float, out: Float) {
        val g = fr.g
        val seed = fr.seed
        val (tx, ty) = tip(fr)
        val grow = easeOutCubic(min(1f, r / 160f))
        for (i in 0 until 11) {
            val angle = i / 11f * WorldFx.TAU + (WorldFx.hash(seed, i, 92) - 0.5f) * 0.5f
            val length = (0.25f + WorldFx.hash(seed, i, 93) * 0.6f) * fr.reach * grow
            crack(fr, tx, ty, angle, length, seed + i * 7, out, 6)
            if (WorldFx.hash(seed, i, 94) > 0.4f) {
                val at = 0.45f + WorldFx.hash(seed, i, 95) * 0.25f
                val bx = tx + cos(angle) * length * at
                val by = ty + sin(angle) * length * at
                crack(fr, bx, by, angle + (if (i % 2 == 0) 0.6f else -0.6f), length * 0.4f, seed + i * 13, out, 3)
            }
        }
        // Dust thrown out low along the ground.
        val time = r / 1000f
        for (i in 0 until 16) {
            val life = 0.9f + WorldFx.hash(seed, i, 96) * 0.6f
            if (time > life) continue
            val side = if (i % 2 == 0) 1f else -1f
            val speed = (60f + WorldFx.hash(seed, i, 97) * 120f) * fr.size
            val d = speed * (1f - exp(-3f * time)) / 3f
            val x = tx + side * d
            val y = ty - (6f + WorldFx.hash(seed, i, 98) * 14f) * time * fr.size
            val q = time / life
            soft(g, x, y, (6f + 20f * q) * fr.size, argb(0x9A9086, 0.45f * (1f - q) * out))
        }
    }

    private fun crack(fr: DropFrame, x0: Float, y0: Float, angle: Float, length: Float, seed: Int, out: Float, segments: Int) {
        if (length <= 1f) return
        var x = x0
        var y = y0
        val step = length / segments
        for (s in 1..segments) {
            val bend = angle + (WorldFx.hash(seed, s, 99) - 0.5f) * 0.9f
            val nx = x + cos(bend) * step
            val ny = y + sin(bend) * step
            line(fr.g, x, y, nx, ny, 2.4f, argb(0x000000, 0.75f * out), argb(0x000000, 0.75f * out))
            line(fr.g, x + 0.8f, y + 0.8f, nx + 0.8f, ny + 0.8f, 0.8f, argb(0xFFFFFF, 0.85f * out), argb(0xFFFFFF, 0.6f * out))
            x = nx
            y = ny
        }
    }

    // ------------------------------------------------------------------ recombobulator

    private val RARITIES = intArrayOf(0xFFFFFF, 0x55FF55, 0x5555FF, 0xAA00AA, 0xFFAA00, 0xFF55FF)
    private val RARITY_NAMES = arrayOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    /** The rarity the dial's on and how fresh that step is (1 just now, toward 0 after). */
    private fun rarity(r: Float): Pair<Int, Float> {
        val step = floor((r - DIAL_START) / DIAL_STEP).toInt().coerceIn(0, RARITIES.size - 1)
        val since = r - DIAL_START - step * DIAL_STEP
        return RARITIES[step] to if (since >= 0f) exp(-since / 120f) else 0f
    }

    private fun dial(fr: DropFrame, r: Float, out: Float) {
        if (r < DIAL_START - 200f) return
        val g = fr.g
        val step = floor((r - DIAL_START) / DIAL_STEP).toInt().coerceIn(0, RARITIES.size - 1)
        val (tone, flash) = rarity(r)
        val appear = min(1f, (r - DIAL_START + 200f) / 200f) * out
        val radius = fr.unit * 0.95f
        val progress = min(1f, (r - DIAL_START) / (DIAL_STEP * RARITIES.size)).coerceAtLeast(0f)
        val ticks = 36
        for (i in 0 until ticks) {
            val angle = i / ticks.toFloat() * WorldFx.TAU + r / 1000f * 0.8f
            val major = i % 6 == 0
            val length = (if (major) 12f else 6f) * fr.size
            val lit = i < ticks * progress
            val a = appear * (if (lit) 1f else 0.3f)
            ray(g, fr.cx, fr.cy, angle, radius - length, length, 2f * fr.size, argb(tone, a), argb(tone, a))
        }
        if (flash > 0.02f) ring(g, fr.cx, fr.cy, radius * (1f + (1f - flash) * 0.4f), 3f * flash + 0.5f, argb(0xFFFFFF, flash * appear))
        val done = r >= DIAL_START + RARITIES.size * DIAL_STEP
        val word = if (done) "RECOMBOBULATED!" else RARITY_NAMES[step]
        val pop = if (done) exp(-(r - DIAL_START - RARITIES.size * DIAL_STEP) / 160f) else flash
        DropDraw.text(g, word, fr.cx, fr.cy - fr.unit * 1.05f, (1.5f + 0.7f * pop) * fr.size, argb(tone, appear))
    }

    // ------------------------------------------------------------------ master stars

    private fun starAt(k: Int): Float = 350f + k * 380f

    private fun constellation(fr: DropFrame, r: Float, out: Float) {
        val g = fr.g
        val seed = fr.seed
        val n = fr.show.extra
        val appear = min(1f, r / 400f) * out
        // Faint stars scattered behind.
        for (i in 0 until 22) {
            val x = fr.w * WorldFx.hash(seed, i, 100)
            val y = fr.h * WorldFx.hash(seed, i, 101) * 0.8f
            val twinkle = 0.5f + 0.5f * sin(r / 1000f * (2f + WorldFx.hash(seed, i, 102) * 3f) + i)
            soft(g, x, y, 2.5f, argb(0xFFFFFF, 0.35f * twinkle * appear))
        }
        val points = Array(n) { k ->
            val angle = PI.toFloat() + (k + 1f) / (n + 1f) * PI.toFloat()
            val radius = fr.unit * (1.35f + WorldFx.hash(seed, k, 103) * 0.35f)
            (fr.cx + cos(angle) * radius) to (fr.cy + sin(angle) * radius * 0.85f)
        }
        val gold = 0xFFE9A8
        val allIn = r > starAt(n - 1) + 400f
        val pulse = if (allIn) 0.8f + 0.2f * sin(r / 300f) else 1f
        for (k in 1 until n) {
            val drawn = ((r - starAt(k) + 150f) / 250f).coerceIn(0f, 1f)
            if (drawn <= 0f) continue
            val (x0, y0) = points[k - 1]
            val (x1, y1) = points[k]
            val ex = x0 + (x1 - x0) * drawn
            val ey = y0 + (y1 - y0) * drawn
            line(g, x0, y0, ex, ey, 3f, argb(gold, 0.25f * out * pulse), argb(gold, 0.25f * out * pulse))
            line(g, x0, y0, ex, ey, 1f, argb(0xFFFFFF, 0.8f * out * pulse), argb(0xFFFFFF, 0.8f * out * pulse))
        }
        for (k in 0 until n) {
            val since = r - starAt(k)
            if (since < 0f) continue
            val pop = backOut(min(1f, since / 300f))
            val flash = exp(-since / 180f)
            val twinkle = 0.85f + 0.15f * sin(r / 200f + k)
            val (x, y) = points[k]
            soft(g, x, y, 9f * fr.size * pop, argb(gold, 0.55f * out))
            glint(g, x, y, 10f * fr.size * pop * twinkle * (1f + flash), argb(0xFFFFFF, out))
            blob(g, x, y, 1.8f * fr.size, argb(0xFFFFFF, out))
            if (flash > 0.05f) ring(g, x, y, (4f + 18f * (1f - flash)) * fr.size, 1.5f, argb(gold, flash * out))
        }
    }

    // ------------------------------------------------------------------ necron

    /** Three beams from off screen, swinging in to meet on the point it lands. */
    private fun lasers(fr: DropFrame, p: Float) {
        val pivots = arrayOf(-0.1f * fr.w to -0.1f * fr.h, 1.1f * fr.w to -0.05f * fr.h, 0.5f * fr.w to 1.15f * fr.h)
        val strength = min(1f, p / 0.15f)
        pivots.forEachIndexed { i, (px, py) ->
            val toCentre = atan2(fr.cy - py, fr.cx - px)
            val swing = (if (i % 2 == 0) 0.9f else -0.9f) * (1f - easeInCubic(p)) + sin(p * 9f + i) * 0.04f
            beam(fr, px, py, toCentre + swing, strength)
        }
    }

    /** Every so often through the reveal, two beams sweep across behind it. */
    private fun sweeps(fr: DropFrame, r: Float, out: Float) {
        if (r < SWEEP_START) return
        val s = ((r - SWEEP_START) % SWEEP_EVERY) / 900f
        if (s > 1f) return
        val strength = sin(s * PI.toFloat()) * 0.6f * out
        beam(fr, -0.05f * fr.w, -0.1f * fr.h, 0.25f + s * 1.0f, strength)
        beam(fr, 1.05f * fr.w, -0.1f * fr.h, PI.toFloat() - 0.25f - s * 1.0f, strength)
    }

    private fun beam(fr: DropFrame, x: Float, y: Float, angle: Float, strength: Float) {
        if (strength <= 0f) return
        val length = fr.reach * 2.5f
        val red = 0xFF2A2A
        ray(fr.g, x, y, angle, 0f, length, 9f * fr.size, argb(red, 0.25f * strength), argb(red, 0.25f * strength))
        ray(fr.g, x, y, angle, 0f, length, 4f * fr.size, argb(red, 0.8f * strength), argb(red, 0.8f * strength))
        ray(fr.g, x, y, angle, 0f, length, 1.4f * fr.size, argb(0xFFFFFF, 0.95f * strength), argb(0xFFFFFF, 0.95f * strength))
    }

    private const val SPLATS = 7
    private const val SPLAT_GAP = 70f
    private const val GIANT_FALL_FROM = 0.35f
    private const val GIANT_ZOOM = 2.1f

    /** How far below the centre, in blocks, the sword goes into the screen. */
    private const val SWORD_ENTRY = 0.9f

    /** From the sword's centre to its point, in blocks per unit of zoom: half its diagonal sprite. */
    private const val SWORD_TIP = 0.66f

    /** How deep, in blocks, the point's in once it's landed. */
    private const val SWORD_BURY = 0.35f
    private const val SINK_MS = 1500f

    /**
     * Turns the sword's sprite to point straight down. The model space is flipped from the
     * screen's, so this turns the other way to how it reads.
     */
    private const val SWORD_DOWN = (3.0 * PI / 4.0).toFloat()
    private const val DIAL_START = 250f
    private const val DIAL_STEP = 260f
    private const val SWEEP_START = 1200f
    private const val SWEEP_EVERY = 2600f
}
