package com.personal.client.font;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4f;

/**
 * A BakedGlyph that reserves horizontal space for an emote but renders nothing.
 * The render overlay draws the actual emote image on top. The advance is set
 * per emote so wide emotes reserve proportionally more space.
 */
public class EmoteSpacerGlyph implements BakedGlyph {

    private final float advance;
    private final GlyphInfo info;
    private final GlyphRenderTypes renderTypes;
    private final GpuTextureView textureView;

    public EmoteSpacerGlyph(float advance, GlyphRenderTypes renderTypes, GpuTextureView textureView) {
        this.advance = advance;
        this.info = GlyphInfo.simple(advance);
        this.renderTypes = renderTypes;
        this.textureView = textureView;
    }

    @Override
    public GlyphInfo info() { return info; }

    @Override
    public TextRenderable.Styled createGlyph(float x, float y, int color, int bgColor,
                                             Style style, float boldOffset, float shadowOffset) {
        return new SpacerInstance(x, y, style);
    }

    private class SpacerInstance implements TextRenderable.Styled {
        private final float x, y;
        private final Style style;

        SpacerInstance(float x, float y, Style style) {
            this.x = x;
            this.y = y;
            this.style = style;
        }

        // Render nothing — the overlay mixin draws the emote image.
        @Override
        public void render(Matrix4f matrix, VertexConsumer consumer, int light, boolean seeThrough) {}

        @Override
        public RenderType renderType(Font.DisplayMode mode) { return renderTypes.select(mode); }

        @Override
        public GpuTextureView textureView() { return EmoteSpacerGlyph.this.textureView; }

        @Override
        public RenderPipeline guiPipeline() { return renderTypes.guiPipeline(); }

        // Bounding box (used for hit-testing and background fill).
        @Override public float left()   { return x; }
        @Override public float top()    { return y - 7; }   // 7 = typical ascent
        @Override public float right()  { return x + advance; }
        @Override public float bottom() { return y + 2; }   // 2 = typical descent

        // ActiveArea (for click/hover detection on the glyph area).
        @Override public Style style()        { return style; }
        @Override public float activeLeft()   { return left(); }
        @Override public float activeTop()    { return top(); }
        @Override public float activeRight()  { return right(); }
        @Override public float activeBottom() { return bottom(); }
    }
}
