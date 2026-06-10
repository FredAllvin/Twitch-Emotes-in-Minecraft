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

/**
 * Fetches Twitch emote lists via the public ivr.fi API (no Twitch OAuth required).
 * Images are served from Twitch's static CDN.
 */
public class TwitchClient {

    private static final String CHANNEL_URL = "https://api.ivr.fi/v2/twitch/emotes/channel/%s";
    /** Emote set 0 is Twitch's global set (Kappa, LUL, ...). */
    private static final String GLOBAL_URL = "https://api.ivr.fi/v2/twitch/emotes/sets?set_id=0";
    private static final String CDN = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/1.0";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Returns a channel's subscriber, bit, and follower emotes. */
    public List<Emote> fetchChannelEmotes(String login) {
        try {
            JsonObject root = JsonParser.parseString(get(CHANNEL_URL.formatted(login))).getAsJsonObject();
            List<Emote> result = new ArrayList<>();
            JsonArray subProducts = root.getAsJsonArray("subProducts");
            if (subProducts != null) {
                for (JsonElement sp : subProducts) {
                    addEmotes(result, sp.getAsJsonObject().getAsJsonArray("emotes"), EmoteSource.TWITCH_SUB);
                }
            }
            addEmotes(result, root.getAsJsonArray("bitEmotes"), EmoteSource.TWITCH_SUB);
            JsonArray localEmotes = root.getAsJsonArray("localEmotes");
            if (localEmotes != null) {
                for (JsonElement le : localEmotes) {
                    addEmotes(result, le.getAsJsonObject().getAsJsonArray("emotes"), EmoteSource.TWITCH_SUB);
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Emote> fetchGlobalEmotes() {
        try {
            JsonArray sets = JsonParser.parseString(get(GLOBAL_URL)).getAsJsonArray();
            List<Emote> result = new ArrayList<>();
            for (JsonElement set : sets) {
                addEmotes(result, set.getAsJsonObject().getAsJsonArray("emoteList"), EmoteSource.TWITCH_GLOBAL);
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void addEmotes(List<Emote> out, JsonArray emotes, EmoteSource source) {
        if (emotes == null) return;
        for (JsonElement el : emotes) {
            JsonObject obj = el.getAsJsonObject();
            String code = obj.get("code").getAsString();
            String id = obj.get("id").getAsString();
            out.add(new Emote(code, id, source, CDN.formatted(id)));
        }
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
