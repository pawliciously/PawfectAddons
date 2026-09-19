package dev.pawfect.addons.features.slayer

import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.features.slayer.SlayerManager.Boss
import dev.pawfect.addons.utils.NumberUtil.shortFormat

object SlayerFormat {

    private val config get() = ConfigManager.features.slayers

    fun nameLine(boss: Boss): String {
        val suffix = if (boss.owned) "" else " §8(other)"
        return "§d§l${boss.type.displayName} ${boss.tier.label}$suffix"
    }

    fun healthLine(boss: Boss): String {
        val health = boss.currentHealth ?: return "§8?"
        val color = SlayerManager.colorFor(boss.percent)
        val text = StringBuilder()
        text.append(color).append(health.shortFormat())
        text.append("§7/").append(boss.maxHealth.shortFormat())
        if (config.showPercent) text.append(" $color${boss.percent.toInt()}%")
        return text.toString()
    }

    fun lines(boss: Boss): List<String> {
        val lines = ArrayList<String>(2)
        if (config.showName) lines.add(nameLine(boss))
        lines.add(healthLine(boss))
        return lines
    }
}
