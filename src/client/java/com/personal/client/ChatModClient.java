package com.personal.client;

import com.personal.client.emote.EmoteManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

public class ChatModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path cacheDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("chatmod").resolve("emote_cache");
        EmoteManager.INSTANCE.initialize(cacheDir);

        // Replace emote codes with PUA placeholder chars before the message is displayed.
        // The font spacer glyph reserves the correct width; the render overlay draws the image.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) return message;
            return EmoteManager.INSTANCE.processMessage(message);
        });
    }
}
