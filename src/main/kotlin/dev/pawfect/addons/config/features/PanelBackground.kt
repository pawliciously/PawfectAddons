package dev.pawfect.addons.config.features

/** Backgrounds shared by the scoreboard and chat. Glass is flat; the rest are the inventory's animated styles. */
enum class PanelBackground(private val label: String, val style: Int) {
    GLASS("Glass", -1),
    AURORA("Aurora", 0),
    CAUSTICS("Caustics", 1),
    SILK("Silk", 2),
    CARBON("Carbon", 3),
    EMBER("Ember", 4),
    ;

    override fun toString(): String = label
}
