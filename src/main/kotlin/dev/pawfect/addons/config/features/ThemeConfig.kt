package dev.pawfect.addons.config.features

import com.google.gson.annotations.Expose

class ThemeConfig {

    @Expose
    var preset: ThemePreset = ThemePreset.CARBON

    @Expose
    var customAccent: Int = 0x9D4EDD

    @Expose
    var customBackground: Int = 0x0D0D12

    @Expose
    var customPanel: Int = 0x14141B

    @Expose
    var customBorder: Int = 0x2A2A36

    @Expose
    var customText: Int = 0xE8E8F0

    @Expose
    var customTextDim: Int = 0x8A8A9A

    @Expose
    var animations: Boolean = true

    @Expose
    var clickSounds: Boolean = true

    @Expose
    var uiScale: Float = 1f

    @Expose
    var opacity: Float = 1f

    @Expose
    var textOffset: Float = 0f

    @Expose
    var font: UiFontChoice = UiFontChoice.INTER

    @Expose
    var fontShadow: Boolean = false

    @Expose
    var windowX: Float = -1f

    @Expose
    var windowY: Float = -1f

    @Expose
    var showPlayerIsland: Boolean = true

    @Expose
    val collapsedSections: MutableList<String> = mutableListOf()

    @Expose
    var lastCategory: String = ""

    @Expose
    val collapsedGroups: MutableList<String> = mutableListOf()

    @Expose
    var hudGridEnabled: Boolean = false

    @Expose
    var hudGridSize: Int = 10

    enum class UiFontChoice(private val label: String) {
        INTER("Inter"),
        SPACE_GROTESK("Space Grotesk"),
        OUTFIT("Outfit"),
        JETBRAINS_MONO("JetBrains Mono"),
        CHAKRA_PETCH("Chakra Petch"),
        LEXEND("Lexend"),
        MINECRAFT("Minecraft"),
        UNIFORM("Unicode"),
        ;

        override fun toString(): String = label
    }

    enum class ThemePreset(private val label: String) {
        MIDNIGHT("Midnight"),
        CARBON("Carbon"),
        ORCHID("Orchid"),
        EMBER("Ember"),
        MINT("Mint"),
        PORCELAIN("Porcelain"),
        CUSTOM("Custom"),
        ;

        override fun toString(): String = label
    }
}
