package com.personal.client.emote;

import com.personal.ChatMod;
import com.personal.client.font.EmoteSpacerGlyph;
import com.personal.emote.Emote;
import com.personal.emote.api.BTTVClient;
import com.personal.emote.api.SevenTVClient;
import com.personal.emote.api.TwitchClient;
import com.personal.emote.api.TwitchEmoteList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EmoteManager {

    public static final EmoteManager INSTANCE = new EmoteManager();
    private static final String FORSEN_TWITCH_ID = "22484632";
    private static final String NYMN_TWITCH_ID = "62300805";
    private static final String NYMN_LOGIN = "nymn";
    private static final int DOWNLOAD_THREADS = 8;
    /** One PUA char per emote: U+E000..U+F8FF. */
    private static final int MAX_EMOTES = 0xF8FF - 0xE000 + 1;
    /** Advance (in text units) of a square emote glyph; matches the 9 px font line height. */
    public static final float EMOTE_ADVANCE = 9.0f;

    /** Registered emote textures: code → frames + dimensions + animation timing. */
    private final Map<String, EmoteTexture> textures = new HashMap<>();

    /** PUA char index → emote code.  Populated when emotes are ready. */
    private volatile String[] charToCode = new String[0];
    /** Emote code → PUA char index (0-based, char = 0xE000 + index). */
    private volatile Map<String, Integer> codeToChar = new HashMap<>();

    /** All loaded codes sorted longest-first for greedy matching. */
    private volatile String[] sortedCodes = new String[0];
    private volatile boolean ready = false;

    /** Per-PUA-index spacer glyphs, created lazily on the render thread. */
    private EmoteSpacerGlyph[] spacerGlyphs = new EmoteSpacerGlyph[0];

    /** A registered emote texture set. Width/height are the actual texture dimensions. */
    public record EmoteTexture(Identifier[] frames, int width, int height, long frameDurationMs) {
        public Identifier frameAt(long timeMs) {
            if (frames.length > 1 && frameDurationMs > 0) {
                return frames[(int) ((timeMs / frameDurationMs) % frames.length)];
            }
            return frames[0];
        }

        public float aspect() {
            return (float) width / height;
        }
    }

    private EmoteManager() {}

    public boolean isReady() { return ready; }

    public void initialize(Path cacheDir) {
        CompletableFuture.runAsync(() -> {
            ChatMod.LOGGER.info("[ChatMod] Loading emotes...");
            SevenTVClient stv = new SevenTVClient();
            BTTVClient bttv = new BTTVClient();
            TwitchClient twitch = new TwitchClient();

            // Order = priority on duplicate codes: channel emotes beat globals.
            List<Emote> all = new ArrayList<>();
            all.addAll(TwitchEmoteList.get());
            all.addAll(twitch.fetchChannelEmotes(NYMN_LOGIN));
            all.addAll(stv.fetchChannelEmotes(FORSEN_TWITCH_ID));
            all.addAll(stv.fetchChannelEmotes(NYMN_TWITCH_ID));
            all.addAll(bttv.fetchChannelEmotes(FORSEN_TWITCH_ID));
            all.addAll(stv.fetchGlobalEmotes());
            all.addAll(bttv.fetchGlobalEmotes());
            all.addAll(twitch.fetchGlobalEmotes());

            Map<String, Emote> unique = new LinkedHashMap<>();
            for (Emote emote : all) {
                unique.putIfAbsent(emote.code(), emote);
            }
            ChatMod.LOGGER.info("[ChatMod] Fetched {} emote definitions ({} unique)", all.size(), unique.size());

            EmoteLoader loader = new EmoteLoader(cacheDir);
            Map<String, EmoteLoader.Frames> frameData = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS);
            try {
                List<CompletableFuture<Void>> downloads = new ArrayList<>();
                for (Emote emote : unique.values()) {
                    downloads.add(CompletableFuture.runAsync(() -> {
                        EmoteLoader.Frames frames = loader.loadFrames(emote);
                        if (frames != null && frames.images().length > 0) {
                            frameData.put(emote.code(), frames);
                        }
                    }, pool));
                }
                CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).join();
            } finally {
                pool.shutdown();
            }

            ChatMod.LOGGER.info("[ChatMod] Downloaded {} emote images", frameData.size());
            Minecraft.getInstance().execute(() -> registerTextures(frameData));
        });
    }

    private void registerTextures(Map<String, EmoteLoader.Frames> frameData) {
        var tm = Minecraft.getInstance().getTextureManager();
        int animated = 0;
        for (var entry : frameData.entrySet()) {
            String code = entry.getKey();
            var images = entry.getValue().images();
            // Hash suffix keeps paths unique when sanitizing collides (e.g. ":)" vs ":(").
            String safePath = code.toLowerCase().replaceAll("[^a-z0-9/._-]", "_")
                    + "_" + Integer.toHexString(code.hashCode());

            Identifier[] ids = new Identifier[images.length];
            for (int i = 0; i < images.length; i++) {
                String path = images.length == 1 ? "emote/" + safePath : "emote/" + safePath + "/f" + i;
                Identifier id = Identifier.fromNamespaceAndPath("chatmod", path);
                String debugName = "chatmod:" + path;
                tm.register(id, new DynamicTexture(() -> debugName, images[i]));
                ids[i] = id;
            }
            if (images.length > 1) animated++;
            textures.put(code, new EmoteTexture(
                    ids, images[0].getWidth(), images[0].getHeight(), entry.getValue().frameDurationMs()));
        }

        // Build sorted code list (longest-first for greedy matching).
        List<String> codes = new ArrayList<>(textures.keySet());
        codes.sort((a, b) -> b.length() - a.length());
        if (codes.size() > MAX_EMOTES) {
            ChatMod.LOGGER.warn("[ChatMod] {} emotes exceed the {} PUA slots; ignoring the rest",
                    codes.size(), MAX_EMOTES);
            codes = codes.subList(0, MAX_EMOTES);
        }
        sortedCodes = codes.toArray(new String[0]);

        // Build PUA char mappings (0xE000 + index).
        String[] cToC = new String[sortedCodes.length];
        Map<String, Integer> ctC = new HashMap<>();
        for (int i = 0; i < sortedCodes.length; i++) {
            cToC[i] = sortedCodes[i];
            ctC.put(sortedCodes[i], i);
        }
        charToCode = cToC;
        codeToChar = ctC;
        // Spacer glyphs are created lazily on first use (during rendering) so each
        // DynamicTexture has been uploaded and its GpuTextureView is non-null.
        spacerGlyphs = new EmoteSpacerGlyph[sortedCodes.length];

        ready = true;
        ChatMod.LOGGER.info("[ChatMod] Registered {} emote textures ({} animated)", textures.size(), animated);
    }

    /**
     * Returns the spacer BakedGlyph for {@code codePoint} if it is in our PUA emote range,
     * or {@code null} otherwise. The glyph's advance reserves width matching the emote's
     * aspect ratio so wide emotes are not squashed.
     */
    public BakedGlyph getSpacerGlyph(int codePoint) {
        if (!ready) return null;
        int idx = codePoint - 0xE000;
        if (idx < 0 || idx >= charToCode.length) return null;

        EmoteSpacerGlyph glyph = spacerGlyphs[idx];
        if (glyph == null) {
            glyph = createSpacerGlyph(charToCode[idx]);
            spacerGlyphs[idx] = glyph;
        }
        return glyph;
    }

    private EmoteSpacerGlyph createSpacerGlyph(String code) {
        EmoteTexture tex = textures.get(code);
        if (tex == null) return null;
        AbstractTexture first = Minecraft.getInstance().getTextureManager().getTexture(tex.frames()[0]);
        if (first == null || first.getTextureView() == null) return null;
        return new EmoteSpacerGlyph(EMOTE_ADVANCE * tex.aspect(),
                GlyphRenderTypes.createForColorTexture(tex.frames()[0]), first.getTextureView());
    }

    /**
     * Returns the emote code for a PUA codepoint, or {@code null} if not an emote placeholder.
     */
    public String getEmoteCodeForChar(int codePoint) {
        if (!ready) return null;
        int idx = codePoint - 0xE000;
        if (idx < 0 || idx >= charToCode.length) return null;
        return charToCode[idx];
    }

    /** Returns the registered texture set for an emote code, or {@code null}. */
    public EmoteTexture getEmoteTexture(String code) {
        return textures.get(code);
    }

    /**
     * Replaces emote codes in {@code message} with PUA placeholder characters so that
     * the font system reserves the correct amount of space and the render overlay can draw
     * the emote images at those positions.
     */
    public Component processMessage(Component message) {
        if (!ready || codeToChar.isEmpty()) return message;
        return replaceInComponent(message);
    }

    private Component replaceInComponent(Component c) {
        var contents = c.getContents();
        MutableComponent result;

        if (contents instanceof PlainTextContents ptc) {
            result = buildReplacedLiteral(ptc.text(), c.getStyle());
        } else if (contents instanceof TranslatableContents tc) {
            Object[] args = tc.getArgs();
            Object[] newArgs = new Object[args.length];
            boolean changed = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component argComp) {
                    Component processed = replaceInComponent(argComp);
                    newArgs[i] = processed;
                    if (processed != argComp) changed = true;
                } else {
                    newArgs[i] = args[i];
                }
            }
            if (changed) {
                result = MutableComponent.create(
                    new TranslatableContents(tc.getKey(), tc.getFallback(), newArgs)
                ).withStyle(c.getStyle());
            } else {
                result = MutableComponent.create(contents).withStyle(c.getStyle());
            }
        } else {
            result = MutableComponent.create(contents).withStyle(c.getStyle());
        }

        for (Component sibling : c.getSiblings()) {
            result.append(replaceInComponent(sibling));
        }
        return result;
    }

    private MutableComponent buildReplacedLiteral(String text, Style style) {
        List<EmoteMatch> matches = findEmotes(text);
        if (matches.isEmpty()) {
            return Component.literal(text).withStyle(style);
        }
        MutableComponent r = Component.empty().withStyle(style);
        int pos = 0;
        for (EmoteMatch m : matches) {
            if (m.start() > pos) {
                r.append(Component.literal(text.substring(pos, m.start())));
            }
            int idx = codeToChar.getOrDefault(m.code(), -1);
            if (idx >= 0) {
                r.append(Component.literal(String.valueOf((char)(0xE000 + idx))));
            } else {
                r.append(Component.literal(m.code()));
            }
            pos = m.end();
        }
        if (pos < text.length()) {
            r.append(Component.literal(text.substring(pos)));
        }
        return r;
    }

    /** Finds emote codes in plain text, sorted by start position. */
    public List<EmoteMatch> findEmotes(String text) {
        List<EmoteMatch> matches = new ArrayList<>();
        for (String code : sortedCodes) {
            int idx = 0;
            while ((idx = text.indexOf(code, idx)) != -1) {
                boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
                int end = idx + code.length();
                boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
                if (startOk && endOk) {
                    matches.add(new EmoteMatch(idx, end, code));
                    idx = end;
                } else {
                    idx++;
                }
            }
        }
        matches.sort((a, b) -> a.start - b.start);
        return matches;
    }

    public record EmoteMatch(int start, int end, String code) {}
}
