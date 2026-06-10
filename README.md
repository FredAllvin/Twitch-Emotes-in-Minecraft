# ChatMod

A client-side Fabric mod for Minecraft **1.21.11** that renders Twitch emotes directly in the chat — type an emote code like `forsenKek` or `OMEGALUL` and the image appears inline, just like in Twitch chat: transparent background, correct colors, and proportional size (wide emotes stay wide).

## Emote sources

- **Twitch** — global emotes (`Kappa`, `LUL`, ...) plus Forsen and Nymn channel emotes (`forsenE`, `nymnDank`, ...)
- **7TV** — Forsen and Nymn channel emotes + global emotes
- **BTTV** — Forsen channel emotes + global emotes

When the same code exists in several sets, channel emotes win over globals.

Animated GIF emotes are supported and play with their real frame timing. Emotes are fetched on game launch and cached in `config/chatmod/emote_cache`, so later launches work offline.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) (0.19.2+) for Minecraft 1.21.11
2. Download the **[latest chatmod jar](https://github.com/FredAllvin/Twitch-Emotes-in-Minecraft/releases/latest)** and drop it, together with [Fabric API](https://modrinth.com/mod/fabric-api), into your `mods` folder
3. Launch the game — emotes load in the background and start rendering once ready

> First launch downloads a few thousand emote images, so chat emotes can take a couple of minutes to appear. After that they load from cache.

The mod is fully client-side; it works on any server.

## Building from source

Requires Java 21.

```
./gradlew build
```

The jar ends up in `build/libs/`.

## How it works

Emote codes in incoming chat messages are replaced with Private-Use-Area characters. A font mixin gives those characters an invisible glyph with the emote's exact width, and a chat-render mixin draws the emote texture over that reserved space, matching vanilla chat scale, opacity, and fade.

## License

CC0 — feel free to learn from it and incorporate it in your own projects.
