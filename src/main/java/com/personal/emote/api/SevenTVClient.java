package com.personal.emote.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.personal.emote.Emote;
import com.personal.emote.EmoteSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SevenTVClient {

    private static final String CHANNEL_URL = "https://7tv.io/v3/users/twitch/%s";
    private static final String GLOBAL_URL = "https://7tv.io/v3/emote-sets/global";
    private static final String CDN_BASE = "https://cdn.7tv.app/emote/%s/";

    private static final Set<String> ALLOWED_CDN_HOSTS = Set.of(
            "cdn.7tv.app", "7tv.app", "cdn.7tv.io", "7tv.io"
    );

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<Emote> fetchChannelEmotes(String twitchUserId) {
        try {
            String body = get(CHANNEL_URL.formatted(twitchUserId));
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject emoteSet = root.getAsJsonObject("emote_set");
            if (emoteSet == null) return List.of();
            return parseEmoteArray(emoteSet.getAsJsonArray("emotes"), EmoteSource.SEVENTV_CHANNEL);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Emote> fetchGlobalEmotes() {
        try {
            String body = get(GLOBAL_URL);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            return parseEmoteArray(root.getAsJsonArray("emotes"), EmoteSource.SEVENTV_GLOBAL);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Emote> parseEmoteArray(JsonArray emotes, EmoteSource source) {
        List<Emote> result = new ArrayList<>();
        if (emotes == null) return result;
        for (JsonElement el : emotes) {
            JsonObject entry = el.getAsJsonObject();
            String name = entry.get("name").getAsString();
            JsonObject data = entry.has("data") ? entry.getAsJsonObject("data") : entry;
            String id = data.get("id").getAsString();
            String url = resolveUrl(id, data);
            result.add(new Emote(name, id, source, url));
        }
        return result;
    }

    // Priority: GIF (animated emotes) → PNG (static emotes) → WebP (static fallback).
    // Animated WebP is deliberately avoided: TwelveMonkeys decodes static WebP fine
    // but fails on the ANIM chunk used by animated emotes on 7TV.
    private String resolveUrl(String id, JsonObject data) {
        try {
            JsonObject host = data.getAsJsonObject("host");
            String rawBase = host.get("url").getAsString();
            if (rawBase.startsWith("//")) rawBase = "https:" + rawBase;
            String host_ = URI.create(rawBase).getHost();
            if (host_ == null || !ALLOWED_CDN_HOSTS.contains(host_.toLowerCase())) {
                return CDN_BASE.formatted(id) + "1x.gif";
            }
            String base = rawBase;
            JsonArray files = host.getAsJsonArray("files");
            String gif = null, png = null, webp = null;
            for (JsonElement f : files) {
                String name = f.getAsJsonObject().get("name").getAsString();
                if (!name.matches("[a-zA-Z0-9_.+-]+")) continue;
                if (gif  == null && name.equals("1x.gif"))              gif  = base + "/" + name;
                if (png  == null && (name.equals("1x.png") || name.equals("1x"))) png = base + "/" + name;
                if (webp == null && name.equals("1x.webp"))             webp = base + "/" + name;
            }
            if (gif  != null) return gif;
            if (png  != null) return png;
            if (webp != null) return webp;
        } catch (Exception ignored) {}
        return CDN_BASE.formatted(id) + "1x.gif";
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "chatmod/1.0")
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
