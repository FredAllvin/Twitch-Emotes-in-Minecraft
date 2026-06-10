package com.personal.client.mixin;

import com.personal.client.emote.EmoteManager;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {

    @ModifyVariable(method = "showMessageToPlayer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component processPlayerChatComponent(Component component) {
        return EmoteManager.INSTANCE.processMessage(component);
    }
}
