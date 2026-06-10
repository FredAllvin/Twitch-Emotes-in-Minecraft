package com.personal.client.mixin;

import com.personal.client.emote.EmoteManager;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts FontSet.Source.getGlyph() for Private-Use-Area codepoints (U+E000..U+F8FF)
 * that we assign to emote codes, and returns an invisible spacer glyph with the correct
 * advance width. The render overlay in ChatComponentMixin draws the actual emote image.
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontSet$Source")
public abstract class FontSetSourceMixin {

    @Inject(
        method = "getGlyph(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void interceptEmoteGlyph(int codePoint, CallbackInfoReturnable<BakedGlyph> cir) {
        if (codePoint < 0xE000) return;
        BakedGlyph spacer = EmoteManager.INSTANCE.getSpacerGlyph(codePoint);
        if (spacer != null) {
            cir.setReturnValue(spacer);
        }
    }
}
