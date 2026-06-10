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

public class BTTVClient {

    private static final String CHANNEL_URL = "https://api.betterttv.net/3/cached/users/twitch/%s";
    private static final String GLOBAL_URL = "https://api.betterttv.net/3/cached/emotes/global";
    private static final String CDN = "https://cdn.betterttv.net/emote/%s/1x";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<Emote> fetchChannelEmotes(String twitchUserId) {
        try {
            String body = get(CHANNEL_URL.formatted(twitchUserId));
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            List<Emote> result = new ArrayList<>();
            result.addAll(parseArray(root.getAsJsonArray("channelEmotes"), EmoteSource.BTTV_CHANNEL));
            result.addAll(parseArray(root.getAsJsonArray("sharedEmotes"), EmoteSource.BTTV_CHANNEL));
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Emote> fetchGlobalEmotes() {
        try {
            String body = get(GLOBAL_URL);
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            return parseArray(arr, EmoteSource.BTTV_GLOBAL);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Emote> parseArray(JsonArray arr, EmoteSource source) {
        List<Emote> result = new ArrayList<>();
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String id = obj.get("id").getAsString();
            String code = obj.get("code").getAsString();
            result.add(new Emote(code, id, source, CDN.formatted(id)));
        }
        return result;
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
