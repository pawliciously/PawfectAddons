package dev.pawfect.addons.ui

import dev.pawfect.addons.config.settings.ButtonSetting
import dev.pawfect.addons.config.settings.ColorSetting
import dev.pawfect.addons.config.settings.DropdownSetting
import dev.pawfect.addons.config.settings.FloatSliderSetting
import dev.pawfect.addons.config.settings.GuideSetting
import dev.pawfect.addons.config.settings.IntSliderSetting
import dev.pawfect.addons.config.settings.PacketLogSetting
import dev.pawfect.addons.config.settings.PositionSetting
import dev.pawfect.addons.config.settings.Setting
import dev.pawfect.addons.config.settings.TextSetting
import dev.pawfect.addons.config.settings.ToggleSetting
import dev.pawfect.addons.config.settings.SoundListSetting
import dev.pawfect.addons.config.settings.WaypointListSetting
import dev.pawfect.addons.ui.Draw.circle
import dev.pawfect.addons.ui.Draw.dropShadow
import dev.pawfect.addons.ui.Draw.pill
import dev.pawfect.addons.ui.Draw.roundOutline
import dev.pawfect.addons.ui.Draw.roundPanel
import dev.pawfect.addons.ui.Draw.roundRect
import dev.pawfect.addons.ui.Draw.string
import dev.pawfect.addons.ui.Draw.stringRight
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color as AwtColor

abstract class Widget(val setting: Setting<*>) {

    var x = 0f
    var y = 0f
    var width = 0f

    open val height: Float get() = ROW_HEIGHT

    protected val hoverAnim = Anim(160L)

    abstract fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float)

    open fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    open fun mouseReleased(button: Int) {}

    open fun mouseDragged(mouseX: Float, mouseY: Float) {}

    open fun keyPressed(keyCode: Int): Boolean = false

    open fun charTyped(character: Char): Boolean = false

    open fun loseFocus() {}

    protected fun hovered(mouseX: Float, mouseY: Float): Boolean =
        Draw.inside(mouseX, mouseY, x, y, width, ROW_HEIGHT)

    protected fun drawRow(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float): Float {
        val anim = hoverAnim.update(if (hovered(mouseX, mouseY)) 1f else 0f)
        if (anim > 0.01f) {
            graphics.roundRect(
                x + 3f,
                y + 1f,
                width - 6f,
                ROW_HEIGHT - 2f,
                ROW_RADIUS,
                Theme.withAlpha(Theme.text, (16 * anim).toInt()),
            )
            val barHeight = (ROW_HEIGHT - 8f) * anim
            graphics.roundRect(
                x + 3f,
                y + (ROW_HEIGHT - barHeight) / 2f,
                2f,
                barHeight,
                1f,
                Theme.withAlpha(Theme.accent, (235 * anim).toInt()),
            )
        }
        graphics.string(
            setting.name,
            x + LABEL_INSET + 2f * anim,
            y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), anim)),
        )
        return anim
    }

    companion object {
        const val ROW_HEIGHT = 18f
        const val LABEL_INSET = 8f
        const val RIGHT_INSET = 8f
        const val ROW_RADIUS = 5f
        const val FIELD_RADIUS = 4f
    }
}

class ToggleWidget(private val toggle: ToggleSetting) : Widget(toggle) {

    private val onAnim = Anim(160L, if (toggle.value) 1f else 0f)

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        drawRow(graphics, mouseX, mouseY)
        val on = onAnim.update(if (toggle.value) 1f else 0f)

        val trackWidth = 22f
        val trackHeight = 11f
        val trackX = x + width - RIGHT_INSET - trackWidth
        val trackY = y + (ROW_HEIGHT - trackHeight) / 2f

        if (on > 0.01f) {
            graphics.dropShadow(
                trackX,
                trackY,
                trackWidth,
                trackHeight,
                trackHeight / 2f,
                5f,
                Theme.withAlpha(Theme.accent, (110 * on).toInt()),
            )
        }

        graphics.pill(
            trackX,
            trackY,
            trackWidth,
            trackHeight,
            Theme.lerp(Theme.surface(Theme.background, 235), Theme.opaque(Theme.accent), on),
            1f,
            Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), on),
        )

        val knobRadius = trackHeight / 2f - 2f
        val travel = trackWidth - trackHeight
        val knobX = trackX + trackHeight / 2f + travel * on
        graphics.circle(
            knobX,
            trackY + trackHeight / 2f,
            knobRadius,
            Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(0xFFFFFF), on),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || !hovered(mouseX, mouseY)) return false
        toggle.value = !toggle.value
        UiSound.click()
        return true
    }
}

abstract class SliderWidget(setting: Setting<*>) : Widget(setting) {

    protected var dragging = false
    protected val fillAnim = Anim(200L)

    override val height: Float get() = SLIDER_HEIGHT

    protected abstract fun percent(): Float

    protected abstract fun applyPercent(fraction: Float)

    protected abstract fun displayValue(): String

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val hover = hoverAnim.update(if (rowHovered(mouseX, mouseY) || dragging) 1f else 0f)

        if (hover > 0.01f) {
            graphics.roundRect(
                x + 3f,
                y + 1f,
                width - 6f,
                SLIDER_HEIGHT - 2f,
                ROW_RADIUS,
                Theme.withAlpha(Theme.text, (16 * hover).toInt()),
            )
            val barHeight = (SLIDER_HEIGHT - 10f) * hover
            graphics.roundRect(
                x + 3f,
                y + (SLIDER_HEIGHT - barHeight) / 2f,
                2f,
                barHeight,
                1f,
                Theme.withAlpha(Theme.accent, (235 * hover).toInt()),
            )
        }

        graphics.string(
            setting.name,
            x + LABEL_INSET + 2f * hover,
            y + 4f,
            Theme.opaque(Theme.lerp(Theme.opaque(Theme.textDim), Theme.opaque(Theme.text), hover)),
        )
        graphics.stringRight(displayValue(), x + width - RIGHT_INSET, y + 4f, Theme.opaque(Theme.accent))

        val trackX = x + LABEL_INSET
        val trackWidth = width - LABEL_INSET - RIGHT_INSET
        val trackHeight = 4f
        val trackY = y + SLIDER_HEIGHT - 9f
        val fill = fillAnim.update(percent())

        graphics.pill(trackX, trackY, trackWidth, trackHeight, Theme.withAlpha(Theme.border, 200))
        if (fill > 0f) {
            graphics.pill(trackX, trackY, trackWidth * fill, trackHeight, Theme.opaque(Theme.accent))
        }

        val knobX = trackX + trackWidth * fill
        val knobY = trackY + trackHeight / 2f
        val knobRadius = 3.5f + hover * 0.8f

        if (hover > 0.01f) {
            graphics.circle(knobX, knobY, knobRadius + 3f, Theme.withAlpha(Theme.accent, (70 * hover).toInt()))
        }
        graphics.circle(knobX, knobY, knobRadius, Theme.opaque(Theme.accent))
        graphics.circle(knobX, knobY, knobRadius - 1.6f, Theme.opaque(Theme.background))
    }

    private fun rowHovered(mouseX: Float, mouseY: Float) =
        Draw.inside(mouseX, mouseY, x, y, width, SLIDER_HEIGHT)

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || !rowHovered(mouseX, mouseY)) return false
        dragging = true
        applyFromMouse(mouseX)
        UiSound.click()
        return true
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        if (dragging) applyFromMouse(mouseX)
    }

    override fun mouseReleased(button: Int) {
        dragging = false
    }

    private fun applyFromMouse(mouseX: Float) {
        val trackX = x + LABEL_INSET
        val trackWidth = width - LABEL_INSET - RIGHT_INSET
        if (trackWidth <= 0f) return
        applyPercent(((mouseX - trackX) / trackWidth).coerceIn(0f, 1f))
    }

    companion object {
        const val SLIDER_HEIGHT = 24f
    }
}

class IntSliderWidget(private val slider: IntSliderSetting) : SliderWidget(slider) {
    override fun percent(): Float = slider.percent
    override fun applyPercent(fraction: Float) {
        slider.value = slider.fromPercent(fraction)
    }
    override fun displayValue(): String = slider.display()
}

class FloatSliderWidget(private val slider: FloatSliderSetting) : SliderWidget(slider) {
    override fun percent(): Float = slider.percent
    override fun applyPercent(fraction: Float) {
        slider.value = slider.fromPercent(fraction)
    }
    override fun displayValue(): String = slider.display()
}

class DropdownWidget(private val dropdown: DropdownSetting<*>) : Widget(dropdown) {

    override val height: Float
        get() = if (dropdown.expanded) ROW_HEIGHT + dropdown.options.size * OPTION_HEIGHT + 10f else ROW_HEIGHT

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        drawRow(graphics, mouseX, mouseY)

        val current = dropdown.options[dropdown.selectedIndex].toString()
        val arrow = if (dropdown.expanded) "-" else "+"
        graphics.stringRight(arrow, x + width - RIGHT_INSET, y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f, Theme.opaque(Theme.textDim))
        graphics.stringRight(
            Draw.truncate(current, width * 0.5f),
            x + width - RIGHT_INSET - 10f,
            y + (ROW_HEIGHT - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.accent),
        )

        if (!dropdown.expanded) return

        val cardX = x + 6f
        val cardWidth = width - 12f
        val cardY = y + ROW_HEIGHT - 1f
        val cardHeight = dropdown.options.size * OPTION_HEIGHT + 6f

        graphics.dropShadow(cardX, cardY, cardWidth, cardHeight, CARD_RADIUS, 7f, Theme.withAlpha(0x000000, 130), 2f)
        graphics.roundPanel(
            cardX,
            cardY,
            cardWidth,
            cardHeight,
            CARD_RADIUS,
            Theme.surface(Theme.background),
            Theme.opaque(Theme.border),
        )

        var optionY = cardY + 3f
        dropdown.options.forEachIndexed { index, option ->
            val selected = index == dropdown.selectedIndex
            val optionHovered = Draw.inside(mouseX, mouseY, cardX, optionY, cardWidth, OPTION_HEIGHT)

            if (selected) {
                graphics.roundRect(
                    cardX + 3f,
                    optionY,
                    cardWidth - 6f,
                    OPTION_HEIGHT,
                    FIELD_RADIUS,
                    Theme.withAlpha(Theme.accent, 46),
                )
            } else if (optionHovered) {
                graphics.roundRect(
                    cardX + 3f,
                    optionY,
                    cardWidth - 6f,
                    OPTION_HEIGHT,
                    FIELD_RADIUS,
                    Theme.withAlpha(Theme.text, 18),
                )
            }

            graphics.string(
                option.toString(),
                cardX + 10f,
                optionY + (OPTION_HEIGHT - Draw.LINE_HEIGHT) / 2f,
                if (selected) Theme.opaque(Theme.accent) else Theme.opaque(Theme.textDim),
            )
            optionY += OPTION_HEIGHT
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false

        if (hovered(mouseX, mouseY)) {
            dropdown.expanded = !dropdown.expanded
            UiSound.click()
            return true
        }

        if (!dropdown.expanded) return false

        var optionY = y + ROW_HEIGHT + 2f
        for (index in dropdown.options.indices) {
            if (Draw.inside(mouseX, mouseY, x + 6f, optionY, width - 12f, OPTION_HEIGHT)) {
                dropdown.selectIndex(index)
                dropdown.expanded = false
                UiSound.click()
                return true
            }
            optionY += OPTION_HEIGHT
        }
        return false
    }

    override fun loseFocus() {
        dropdown.expanded = false
    }

    companion object {
        const val OPTION_HEIGHT = 14f
        const val CARD_RADIUS = 6f
    }
}

class TextWidget(private val text: TextSetting) : Widget(text) {

    private var editing = false
    private var buffer = ""

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        drawRow(graphics, mouseX, mouseY)

        val fieldWidth = (width * 0.45f).coerceAtLeast(40f)
        val fieldX = x + width - RIGHT_INSET - fieldWidth
        val fieldY = y + 3f
        val fieldHeight = ROW_HEIGHT - 6f

        if (editing) {
            graphics.dropShadow(
                fieldX,
                fieldY,
                fieldWidth,
                fieldHeight,
                FIELD_RADIUS,
                5f,
                Theme.withAlpha(Theme.accent, 90),
            )
        }
        graphics.roundPanel(
            fieldX,
            fieldY,
            fieldWidth,
            fieldHeight,
            FIELD_RADIUS,
            Theme.surface(Theme.background, 235),
            if (editing) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )

        val shown = if (editing) buffer else text.value
        val display = if (shown.isEmpty() && !editing) text.placeholder else shown
        val color = if (shown.isEmpty() && !editing) Theme.opaque(Theme.textDim) else Theme.opaque(Theme.text)
        val caret = if (editing && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""

        graphics.string(
            Draw.truncate(display + caret, fieldWidth - 6f),
            fieldX + 3f,
            fieldY + (fieldHeight - Draw.LINE_HEIGHT) / 2f,
            color,
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false
        if (!hovered(mouseX, mouseY)) {
            commit()
            return false
        }
        editing = true
        buffer = text.value
        UiSound.click()
        return true
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (!editing) return false
        when (keyCode) {
            KEY_ENTER -> commit()
            KEY_ESCAPE -> editing = false
            KEY_BACKSPACE -> if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
        }
        return true
    }

    override fun charTyped(character: Char): Boolean {
        if (!editing) return false
        buffer += character
        return true
    }

    override fun loseFocus() = commit()

    private fun commit() {
        if (!editing) return
        editing = false
        text.value = buffer
    }

    companion object {
        const val KEY_ENTER = 257
        const val KEY_ESCAPE = 256
        const val KEY_BACKSPACE = 259
    }
}

class ColorWidget(private val color: ColorSetting) : Widget(color) {

    private var expanded = false
    private var draggingField = false
    private var draggingHue = false
    private var draggingAlpha = false

    private var hue = 0f
    private var saturation = 1f
    private var brightness = 1f
    private var opacity = 255
    private var synced = false

    private var editingHex = false
    private var hexBuffer = ""

    override val height: Float
        get() = if (expanded) ROW_HEIGHT + PICKER_HEIGHT + 6f else ROW_HEIGHT

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        if (!synced) syncFromValue()
        drawRow(graphics, mouseX, mouseY)

        val swatchWidth = 24f
        val swatchHeight = 11f
        val swatchX = x + width - RIGHT_INSET - swatchWidth
        val swatchY = y + (ROW_HEIGHT - swatchHeight) / 2f

        graphics.dropShadow(
            swatchX,
            swatchY,
            swatchWidth,
            swatchHeight,
            FIELD_RADIUS,
            4f,
            Theme.withAlpha(color.rgb, 120),
        )
        graphics.roundPanel(
            swatchX,
            swatchY,
            swatchWidth,
            swatchHeight,
            FIELD_RADIUS,
            Theme.surface(Theme.background, 255),
            Theme.withAlpha(0xFFFFFF, 60),
        )
        Shapes.rect(
            graphics,
            swatchX,
            swatchY,
            swatchWidth,
            swatchHeight,
            FIELD_RADIUS,
            Theme.withAlpha(color.rgb, color.alphaByte),
        )

        if (!expanded) return

        val fieldX = x + LABEL_INSET
        val fieldY = y + ROW_HEIGHT + 2f
        val fieldWidth = fieldWidth()

        drawSaturationField(graphics, fieldX, fieldY, fieldWidth, FIELD_HEIGHT)
        drawHueBar(graphics, fieldX + fieldWidth + 6f, fieldY, HUE_WIDTH, FIELD_HEIGHT)
        if (color.supportsAlpha) {
            drawAlphaBar(graphics, fieldX + fieldWidth + 6f + HUE_WIDTH + 5f, fieldY, HUE_WIDTH, FIELD_HEIGHT)
        }

        val hexText = if (editingHex) "#$hexBuffer" else "#" + currentHex()
        val hexWidth = 58f
        val hexY = fieldY + FIELD_HEIGHT + 3f

        graphics.roundPanel(
            fieldX,
            hexY,
            hexWidth,
            11f,
            FIELD_RADIUS,
            Theme.surface(Theme.background, 235),
            if (editingHex) Theme.opaque(Theme.accent) else Theme.opaque(Theme.border),
        )
        val caret = if (editingHex && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        graphics.string(
            hexText + caret,
            fieldX + 4f,
            hexY + 1f,
            if (editingHex) Theme.opaque(Theme.accent) else Theme.opaque(Theme.textDim),
        )
    }

    private fun fieldWidth(): Float {
        val bars = if (color.supportsAlpha) HUE_WIDTH * 2f + 11f else HUE_WIDTH + 6f
        return width - LABEL_INSET - RIGHT_INSET - bars
    }

    private fun currentHex(): String =
        if (color.supportsAlpha && color.alphaByte != 255) {
            String.format("%02X%06X", color.alphaByte, color.rgb)
        } else {
            String.format("%06X", color.rgb)
        }

    private fun hexFieldBounds(): FloatArray {
        val fieldX = x + LABEL_INSET
        val fieldY = y + ROW_HEIGHT + 2f
        return floatArrayOf(fieldX, fieldY + FIELD_HEIGHT + 3f, 58f, 11f)
    }

    private fun drawAlphaBar(
        graphics: GuiGraphicsExtractor,
        barX: Float,
        barY: Float,
        barWidth: Float,
        barHeight: Float,
    ) {
        val radius = barWidth / 2f
        Shapes.rect(graphics, barX, barY, barWidth, barHeight, radius, Theme.surface(Theme.background, 255))
        Shapes.rect(
            graphics,
            barX,
            barY,
            barWidth,
            barHeight,
            radius,
            Theme.withAlpha(color.rgb, 255),
            Theme.withAlpha(color.rgb, 0),
        )
        graphics.roundOutline(barX, barY, barWidth, barHeight, radius, Theme.opaque(Theme.border))

        val markerY = barY + barHeight * (1f - opacity / 255f)
        graphics.pill(barX - 2f, markerY - 1.5f, barWidth + 4f, 3f, Theme.opaque(0xFFFFFF))
    }

    private fun commitHex() {
        if (!editingHex) return
        editingHex = false
        val text = hexBuffer.trim().removePrefix("#")
        val parsed = text.toLongOrNull(16)?.toInt() ?: return
        if (text.length > 6 && color.supportsAlpha) {
            color.apply(parsed and 0xFFFFFF, (parsed ushr 24) and 0xFF)
        } else {
            color.apply(parsed and 0xFFFFFF, opacity)
        }
        synced = false
    }

    private fun drawSaturationField(
        graphics: GuiGraphicsExtractor,
        fieldX: Float,
        fieldY: Float,
        fieldWidth: Float,
        fieldHeight: Float,
    ) {
        Shapes.saturationField(graphics, fieldX, fieldY, fieldWidth, fieldHeight, FIELD_RADIUS, hue)
        graphics.roundOutline(fieldX, fieldY, fieldWidth, fieldHeight, FIELD_RADIUS, Theme.opaque(Theme.border))

        val cursorX = fieldX + fieldWidth * saturation
        val cursorY = fieldY + fieldHeight * (1f - brightness)
        graphics.circle(cursorX, cursorY, 3.2f, Theme.withAlpha(0x000000, 150))
        graphics.circle(cursorX, cursorY, 2.6f, Theme.opaque(0xFFFFFF))
        graphics.circle(cursorX, cursorY, 1.4f, Theme.opaque(color.rgb))
    }

    private fun drawHueBar(
        graphics: GuiGraphicsExtractor,
        barX: Float,
        barY: Float,
        barWidth: Float,
        barHeight: Float,
    ) {
        Shapes.hueBar(graphics, barX, barY, barWidth, barHeight, barWidth / 2f)
        graphics.roundOutline(barX, barY, barWidth, barHeight, barWidth / 2f, Theme.opaque(Theme.border))

        val markerY = barY + barHeight * hue
        graphics.pill(barX - 2f, markerY - 1.5f, barWidth + 4f, 3f, Theme.opaque(0xFFFFFF))
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) return false

        if (hovered(mouseX, mouseY)) {
            commitHex()
            expanded = !expanded
            UiSound.click()
            return true
        }

        if (!expanded) return false

        val hex = hexFieldBounds()
        if (Draw.inside(mouseX, mouseY, hex[0], hex[1], hex[2], hex[3])) {
            editingHex = true
            hexBuffer = currentHex()
            UiSound.click()
            return true
        }
        commitHex()

        val fieldX = x + LABEL_INSET
        val fieldY = y + ROW_HEIGHT + 2f
        val fieldWidth = fieldWidth()

        if (Draw.inside(mouseX, mouseY, fieldX, fieldY, fieldWidth, FIELD_HEIGHT)) {
            draggingField = true
            updateField(mouseX, mouseY, fieldX, fieldY, fieldWidth)
            return true
        }

        if (Draw.inside(mouseX, mouseY, fieldX + fieldWidth + 6f, fieldY, HUE_WIDTH, FIELD_HEIGHT)) {
            draggingHue = true
            updateHue(mouseY, fieldY)
            return true
        }

        val alphaX = fieldX + fieldWidth + 6f + HUE_WIDTH + 5f
        if (color.supportsAlpha && Draw.inside(mouseX, mouseY, alphaX, fieldY, HUE_WIDTH, FIELD_HEIGHT)) {
            draggingAlpha = true
            updateAlpha(mouseY, fieldY)
            return true
        }

        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float) {
        val fieldX = x + LABEL_INSET
        val fieldY = y + ROW_HEIGHT + 2f
        val fieldWidth = fieldWidth()

        if (draggingField) updateField(mouseX, mouseY, fieldX, fieldY, fieldWidth)
        if (draggingHue) updateHue(mouseY, fieldY)
        if (draggingAlpha) updateAlpha(mouseY, fieldY)
    }

    override fun mouseReleased(button: Int) {
        draggingField = false
        draggingHue = false
        draggingAlpha = false
    }

    override fun keyPressed(keyCode: Int): Boolean {
        if (!editingHex) return false
        when (keyCode) {
            TextWidget.KEY_ENTER -> commitHex()
            TextWidget.KEY_ESCAPE -> editingHex = false
            TextWidget.KEY_BACKSPACE -> if (hexBuffer.isNotEmpty()) hexBuffer = hexBuffer.dropLast(1)
        }
        return true
    }

    override fun charTyped(character: Char): Boolean {
        if (!editingHex) return false
        val limit = if (color.supportsAlpha) 8 else 6
        if (hexBuffer.length < limit && (character.isDigit() || character.lowercaseChar() in 'a'..'f')) {
            hexBuffer += character.uppercaseChar()
        }
        return true
    }

    override fun loseFocus() {
        commitHex()
        expanded = false
    }

    private fun updateField(mouseX: Float, mouseY: Float, fieldX: Float, fieldY: Float, fieldWidth: Float) {
        saturation = ((mouseX - fieldX) / fieldWidth).coerceIn(0f, 1f)
        brightness = 1f - ((mouseY - fieldY) / FIELD_HEIGHT).coerceIn(0f, 1f)
        pushValue()
    }

    private fun updateHue(mouseY: Float, fieldY: Float) {
        hue = ((mouseY - fieldY) / FIELD_HEIGHT).coerceIn(0f, 1f)
        pushValue()
    }

    private fun updateAlpha(mouseY: Float, fieldY: Float) {
        val fraction = 1f - ((mouseY - fieldY) / FIELD_HEIGHT).coerceIn(0f, 1f)
        opacity = (fraction * 255f).toInt().coerceIn(1, 255)
        pushValue()
    }

    private fun pushValue() {
        color.apply(AwtColor.HSBtoRGB(hue, saturation, brightness) and 0xFFFFFF, opacity)
    }

    private fun syncFromValue() {
        val rgb = color.rgb
        val hsb = AwtColor.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
        hue = hsb[0]
        saturation = hsb[1]
        brightness = hsb[2]
        opacity = color.alphaByte
        synced = true
    }

    companion object {
        const val FIELD_HEIGHT = 40f
        const val HUE_WIDTH = 10f
        const val PICKER_HEIGHT = FIELD_HEIGHT + 20f
    }
}

class ActionWidget(
    setting: Setting<*>,
    private val label: String,
    private val action: () -> Unit,
) : Widget(setting) {

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val anim = drawRow(graphics, mouseX, mouseY)

        val buttonWidth = Draw.width(label) + 14f
        val buttonHeight = ROW_HEIGHT - 6f
        val buttonX = x + width - RIGHT_INSET - buttonWidth
        val buttonY = y + 3f

        if (anim > 0.01f) {
            graphics.dropShadow(
                buttonX,
                buttonY,
                buttonWidth,
                buttonHeight,
                FIELD_RADIUS,
                5f,
                Theme.withAlpha(Theme.accent, (80 * anim).toInt()),
            )
        }
        graphics.roundPanel(
            buttonX,
            buttonY,
            buttonWidth,
            buttonHeight,
            FIELD_RADIUS,
            Theme.withAlpha(Theme.accent, (30 + 45 * anim).toInt()),
            Theme.lerp(Theme.opaque(Theme.border), Theme.opaque(Theme.accent), anim),
        )
        graphics.string(
            label,
            buttonX + 7f,
            buttonY + (buttonHeight - Draw.LINE_HEIGHT) / 2f,
            Theme.opaque(Theme.accent),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0 || !hovered(mouseX, mouseY)) return false
        UiSound.click()
        action()
        return true
    }
}

object WidgetFactory {

    fun create(setting: Setting<*>): Widget? = when (setting) {
        is ToggleSetting -> ToggleWidget(setting)
        is IntSliderSetting -> IntSliderWidget(setting)
        is FloatSliderSetting -> FloatSliderWidget(setting)
        is DropdownSetting<*> -> DropdownWidget(setting)
        is TextSetting -> TextWidget(setting)
        is ColorSetting -> ColorWidget(setting)
        is ButtonSetting -> ActionWidget(setting, setting.label, setting.action)
        is WaypointListSetting -> WaypointListWidget(setting)
        is SoundListSetting -> SoundListWidget(setting)
        is PacketLogSetting -> PacketLogWidget(setting)
        is GuideSetting -> GuideWidget(setting)
        is PositionSetting -> ActionWidget(setting, setting.label, setting.action)
        else -> null
    }
}
