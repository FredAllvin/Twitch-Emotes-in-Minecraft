package com.personal.client.mixin;

import com.personal.client.emote.EmoteManager;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow private int chatScrollbarPos;
    @Shadow private List<GuiMessage.Line> trimmedMessages;

    @Shadow private double getScale() { throw new AssertionError(); }
    @Shadow private int getLineHeight() { throw new AssertionError(); }
    @Shadow public abstract int getLinesPerPage();

    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component onAddMessage(Component message) {
        return EmoteManager.INSTANCE.processMessage(message);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void renderEmoteImages(GuiGraphics guiGraphics, Font font,
                                   int tickCount, int mouseX, int mouseY,
                                   boolean focused, boolean withoutBg,
                                   CallbackInfo ci) {
        EmoteManager em = EmoteManager.INSTANCE;
        if (!em.isReady()) return;

        long nowMs = System.currentTimeMillis();
        float scale = (float) getScale();
        int lineH = getLineHeight();
        // Mirror vanilla ChatComponent.render(): chat is drawn in pose space
        // scale(s) then translate(4, 0); the bottom entry sits at
        // floor((guiHeight - 40) / s) and text is raised 8*(spacing+1) - 4*spacing.
        double spacing = minecraft.options.chatLineSpacing().get();
        int bottomToTextY = (int) Math.round(8.0 * (spacing + 1.0) - 4.0 * spacing);
        int chatBottom = Mth.floor((guiGraphics.guiHeight() - 40) / scale);
        float textOpacity = minecraft.options.chatOpacity().get().floatValue() * 0.9f + 0.1f;

        int maxLines = Math.min(getLinesPerPage(), trimmedMessages.size() - chatScrollbarPos);
        for (int i = 0; i < maxLines; i++) {
            GuiMessage.Line line = trimmedMessages.get(chatScrollbarPos + i);
            int ticksAlive = tickCount - line.addedTime();
            if (!focused && ticksAlive > 200) break;

            double opacity = focused ? 1.0 : opacityFor(ticksAlive);
            int alpha = (int) (255.0 * opacity * textOpacity);
            if (alpha <= 3) continue;

            // Collect PUA placeholder chars and their string positions.
            record EmotePos(int strIdx, int codePoint) {}
            List<EmotePos> emotePosns = new ArrayList<>();
            StringBuilder sb = new StringBuilder();

            line.content().accept((charIdx, style, cp) -> {
                if (cp >= 0xE000 && em.getEmoteCodeForChar(cp) != null) {
                    emotePosns.add(new EmotePos(sb.length(), cp));
                }
                sb.appendCodePoint(cp);
                return true;
            });

            if (emotePosns.isEmpty()) continue;

            int textY = chatBottom - i * lineH - bottomToTextY;
            int color = (alpha << 24) | 0x00FFFFFF;

            for (EmotePos ep : emotePosns) {
                String code = em.getEmoteCodeForChar(ep.codePoint());
                EmoteManager.EmoteTexture tex = code != null ? em.getEmoteTexture(code) : null;
                if (tex == null) continue;

                // Pixel position = width of everything before this PUA char.
                int prefixPx = font.width(sb.substring(0, ep.strIdx()));
                int x = Math.round((4 + prefixPx) * scale);
                int y = Math.round(textY * scale);
                int h = Math.round(EmoteManager.EMOTE_ADVANCE * scale);
                int w = Math.round(EmoteManager.EMOTE_ADVANCE * tex.aspect() * scale);

                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, tex.frameAt(nowMs),
                        x, y,
                        0.0f, 0.0f,
                        w, h,
                        tex.width(), tex.height(),
                        tex.width(), tex.height(),
                        color);
            }
        }
    }

    private static double opacityFor(int ticksAlive) {
        double t = Mth.clamp((1.0 - ticksAlive / 200.0) * 10.0, 0.0, 1.0);
        return t * t;
    }
}
