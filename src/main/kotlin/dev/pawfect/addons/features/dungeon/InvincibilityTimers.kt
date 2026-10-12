package dev.pawfect.addons.features.dungeon

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.config.ModuleSwitch
import dev.pawfect.addons.config.core.GuiEditManager
import dev.pawfect.addons.config.core.HudDefaults
import dev.pawfect.addons.config.features.InvincibilityConfig.ShowWhen
import dev.pawfect.addons.data.NeuRepo
import dev.pawfect.addons.data.SkyBlockData
import dev.pawfect.addons.ui.Draw
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import dev.pawfect.addons.ui.Theme
import dev.pawfect.addons.utils.ItemUtil.loreLines
import dev.pawfect.addons.utils.ItemUtil.skyblockId
import dev.pawfect.addons.utils.McCompat
import dev.pawfect.addons.utils.RenderContext
import dev.pawfect.addons.utils.SoundCue
import dev.pawfect.addons.utils.StringUtil.removeColor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.EnumMap

/**
 * Timers for the things that save you from dying: Bonzo's Mask, the Spirit Mask and the Phoenix
 * pet. Each one's chat line starts a few seconds of invincibility and then its cooldown, read off
 * the mask's own "Cooldown:" line so starred masks are right. Shown as a HUD and, on the masks,
 * as a cooldown sliding down the item like vanilla's.
 */
object InvincibilityTimers {

    enum class Kind(
        val label: String,
        val regex: Regex,
        val invincibleMs: Long,
        val cooldownMs: Long,
        val ids: Set<String>,
        val icon: String,
    ) {
        BONZO("Bonzo's Mask", Regex("^Your (?:. )?Bonzo's Mask saved your life!$"), 3_000, 180_000, setOf("BONZO_MASK", "STARRED_BONZO_MASK"), "BONZO_MASK"),
        SPIRIT("Spirit Mask", Regex("^Second Wind Activated! Your Spirit Mask saved your life!$"), 3_000, 30_000, setOf("SPIRIT_MASK", "STARRED_SPIRIT_MASK"), "SPIRIT_MASK"),
        PHOENIX("Phoenix", Regex("^Your Phoenix Pet saved you from certain death!$"), 4_000, 60_000, emptySet(), "PHOENIX;4"),
    }

    private val config get() = ModuleSwitch.view("dungeon", ConfigManager.features.invincibility)

    private val cooldownRegex = Regex("""^Cooldown: (\d+)s$""")

    private val procAt = EnumMap<Kind, Long>(Kind::class.java)
    private val cooldown = EnumMap<Kind, Long>(Kind::class.java)

    /** Masks you carry, and the one you're wearing, checked every so often rather than every frame. */
    private var owned: Set<Kind> = emptySet()
    private var equipped: Kind? = null
    private var ticks = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { runCatching { tick() } }
    }

    private fun tick() {
        if (!config.enabled || ++ticks < SCAN_INTERVAL) return
        ticks = 0
        val player = McCompat.player ?: return
        val found = HashSet<Kind>()
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) kindOf(inventory.getItem(slot))?.let { found += it }
        equipped = kindOf(player.getItemBySlot(EquipmentSlot.HEAD))
        equipped?.let { found += it }
        owned = found
    }

    private fun kindOf(stack: ItemStack): Kind? {
        if (stack.isEmpty || !stack.`is`(Items.PLAYER_HEAD)) return null
        val id = stack.skyblockId() ?: return null
        return Kind.entries.firstOrNull { id in it.ids }
    }

    private val active: Boolean get() = !config.onlyInDungeons || SkyBlockData.inDungeons

    fun onChat(message: Component) {
        if (!config.enabled || !active) return
        val text = message.string.removeColor().trim()
        val kind = Kind.entries.firstOrNull { it.regex.matches(text) } ?: return
        procAt[kind] = System.currentTimeMillis()
        cooldown[kind] = maskCooldown(kind) ?: kind.cooldownMs
        if (config.alert) {
            val gui = McCompat.mc.gui.hud
            gui.setTimes(2, 30, 6)
            gui.setSubtitle(Component.literal("§fInvincible for ${kind.invincibleMs / 1000}s"))
            gui.setTitle(Component.literal("§6§l${kind.label.uppercase()}"))
        }
        if (config.sound) SoundCue.play(config.soundId, 1f, 1f)
        if (config.announce) McCompat.mc.connection?.sendCommand("pc ${kind.label} procced!")
    }

    /** The cooldown on the mask that went off: the one you're wearing, else one you carry. */
    private fun maskCooldown(kind: Kind): Long? {
        if (kind.ids.isEmpty()) return null
        val player = McCompat.player ?: return null
        val inventory = player.inventory
        val stacks = listOf(player.getItemBySlot(EquipmentSlot.HEAD)) + (0 until inventory.containerSize).map { inventory.getItem(it) }
        val mask = stacks.firstOrNull { kindOf(it) == kind } ?: return null
        val seconds = mask.loreLines().firstNotNullOfOrNull { cooldownRegex.find(it.removeColor().trim()) }?.groupValues?.get(1)?.toLongOrNull()
        return seconds?.times(1000)
    }

    private fun invincibleLeft(kind: Kind, now: Long): Long {
        val at = procAt[kind] ?: return 0
        return (at + kind.invincibleMs - now).coerceAtLeast(0)
    }

    private fun cooldownLeft(kind: Kind, now: Long): Long {
        val at = procAt[kind] ?: return 0
        return (at + (cooldown[kind] ?: kind.cooldownMs) - now).coerceAtLeast(0)
    }

    private fun shown(kind: Kind): Boolean = when (kind) {
        Kind.BONZO -> config.bonzo
        Kind.SPIRIT -> config.spirit
        Kind.PHOENIX -> config.phoenix
    }

    // ------------------------------------------------------------------ item cooldown

    /** Slides the cooldown down a mask in your inventory or hotbar, like vanilla's item cooldowns. */
    @JvmStatic
    fun decorate(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!config.enabled || !config.onItem || procAt.isEmpty()) return
        val kind = kindOf(stack) ?: return
        val now = System.currentTimeMillis()
        val left = cooldownLeft(kind, now)
        if (left <= 0) return
        val fraction = left.toFloat() / (cooldown[kind] ?: kind.cooldownMs)
        val top = y + Math.round(16f * (1f - fraction))
        graphics.fill(x, top, x + 16, y + 16, ITEM_COOLDOWN)
    }

    // ------------------------------------------------------------------ HUD

    private class Row(val kind: Kind, val status: String, val color: Int)

    fun render() {
        if (!config.enabled || !RenderContext.isActive) return
        val editing = GuiEditManager.isEditorOpen()
        val now = System.currentTimeMillis()
        val rows = if (active) rows(now) else emptyList()
        val list = rows.ifEmpty { if (editing) preview() else return }

        val height = PAD * 2 + list.size * ROW - (ROW - ICON)
        val position = config.position
        val scale = position.effectiveScale
        val scaledWidth = (WIDTH * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        val x = position.getAbsX(scaledWidth)
        val y = position.getAbsY(scaledHeight)

        RenderContext.pushPop {
            RenderContext.translate(x.toFloat(), y.toFloat())
            if (scale != 1f) RenderContext.scale(scale)
            draw(RenderContext.graphics, list, height)
        }
        GuiEditManager.register(position, HudDefaults.INVINCIBILITY, scaledWidth, scaledHeight)
    }

    private fun rows(now: Long): List<Row> = Kind.entries.filter(::shown).mapNotNull { kind ->
        val invincible = invincibleLeft(kind, now)
        val left = cooldownLeft(kind, now)
        val used = left > 0
        // The Phoenix can't be seen in your inventory, so it shows once it has saved you.
        val known = kind in owned || used || (kind == Kind.PHOENIX && procAt.containsKey(kind))
        if (!known || (config.showWhen == ShowWhen.COOLING && !used)) return@mapNotNull null
        when {
            invincible > 0 -> Row(kind, "Invincible %.1fs".format(invincible / 1000f), INVINCIBLE)
            used -> Row(kind, clock(left), COOLING)
            else -> Row(kind, "Ready", READY)
        }
    }

    private fun preview() = listOf(
        Row(Kind.BONZO, clock(141_000), COOLING),
        Row(Kind.SPIRIT, "Invincible 2.1s", INVINCIBLE),
        Row(Kind.PHOENIX, "Ready", READY),
    )

    private fun clock(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return if (seconds >= 60) "%d:%02d".format(seconds / 60, seconds % 60) else "${seconds}s"
    }

    private fun draw(graphics: GuiGraphicsExtractor, rows: List<Row>, height: Float) {
        graphics.dropShadow(0f, 0f, WIDTH, height, RADIUS, 10f, Theme.withAlpha(0x000000, 140), 2f)
        graphics.roundPanel(0f, 0f, WIDTH, height, RADIUS, Theme.surface(Theme.background, 240), Theme.opaque(Theme.border))
        rows.forEachIndexed { i, row ->
            val top = PAD + i * ROW
            graphics.item(NeuRepo.itemStack(row.kind.icon), PAD.toInt(), top.toInt())
            val textY = top + (ICON - Draw.LINE_HEIGHT) / 2f
            // The mask you're wearing is the one that'll go off next.
            val nameColor = if (row.kind == equipped) Theme.accent else Theme.text
            graphics.string(row.kind.label, PAD + ICON + 5f, textY, Theme.opaque(nameColor))
            graphics.stringRight(row.status, WIDTH - PAD, textY, Theme.opaque(row.color))
        }
    }

    private const val SCAN_INTERVAL = 10
    private const val WIDTH = 150f
    private const val PAD = 6f
    private const val ICON = 16f
    private const val ROW = 19f
    private const val RADIUS = 5f
    private const val READY = 0x7EE08A
    private const val INVINCIBLE = 0xFFD23F
    private const val COOLING = 0xFF6B6B
    private const val ITEM_COOLDOWN = 0x80FF6B6B.toInt()
}
