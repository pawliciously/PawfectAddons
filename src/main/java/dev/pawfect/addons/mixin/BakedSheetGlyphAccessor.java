package dev.pawfect.addons.mixin;

import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BakedSheetGlyph.class)
public interface BakedSheetGlyphAccessor {

    @Accessor("left")
    float pawfectaddons$left();

    @Accessor("right")
    float pawfectaddons$right();

    @Accessor("up")
    float pawfectaddons$up();

    @Accessor("down")
    float pawfectaddons$down();
}
