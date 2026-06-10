package com.personal.emote.api;

import com.personal.emote.Emote;
import com.personal.emote.EmoteSource;

import java.util.List;

public class TwitchEmoteList {

    private static final String CDN = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/1.0";

    // Hardcoded Forsen subscriber emotes. Update IDs when the sub raffle roster changes.
    public static List<Emote> get() {
        return List.of(
            tw("forsenSmug",      "304412445"),
            tw("forsenPls",       "emotesv2_2f9a36844b054423833c817b5f8d4225"),
            tw("forsenBB",        "300799759"),
            tw("forsenGASM",      "173378"),
            tw("forsenJoy",       "300235054"),
            tw("forsenO",         "118074"),
            tw("forsen3",         "116053"),
            tw("forsenWhat",      "300603032"),
            tw("forsenParty",     "emotesv2_ee52e9d2f1344320bad5e93dd2a6e570"),
            tw("forsenX",         "60257"),
            tw("forsenBee",       "555437"),
            tw("forsenSven",      "305851381"),
            tw("forsenKek",       "684688"),
            tw("forsenD",         "301428004"),
            tw("forsenGun",       "89650"),
            tw("forsenY",         "173372"),
            tw("forsenW",         "31021"),
            tw("forsenE",         "521050"),
            tw("forsenFeels",     "116273"),
            tw("forsenBased",     "304412430"),
            tw("forsenOkay",      "306982829"),
            tw("forsenKnife",     "90377"),
            tw("forsenL",         "684692"),
            tw("forsen4",         "116055"),
            tw("forsenWeird",     "1479466"),
            tw("forsenH",         "115996"),
            tw("forsenRedSonic",  "432489"),
            tw("forsen1k",        "emotesv2_a0292167295e449c81f0faafec204679"),
            tw("forsenHobo",      "1572725")
        );
    }

    private static Emote tw(String code, String id) {
        return new Emote(code, id, EmoteSource.TWITCH_SUB, CDN.formatted(id));
    }
}
