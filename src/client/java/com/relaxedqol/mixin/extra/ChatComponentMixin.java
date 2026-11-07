package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.Chat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Chat module: longer history (the 100-line limits), timestamps / mention highlight (the message is changed
 * before it is added) and stacking repeats (the previous copy is taken out first). Optional: if any of these hooks
 * does not fit this game version, that part of the Chat module simply does nothing.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements com.relaxedqol.client.util.ChatKeep {
    @Shadow
    @Final
    private List<GuiMessage.Line> trimmedMessages;

    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    /** How many screen lines the newest message took (counted while vanilla splits it into rows). */
    @Unique
    private int relaxed$lastLines = -1;
    /**
     * The newest messages we added, newest first (message object, rows, stack key, count), so a repeat can
     * stack onto an earlier copy even with other lines in between. Checked against vanilla's own lists before use.
     */
    @Unique
    private final java.util.ArrayList<Chat.RecentLine> relaxed$recent = new java.util.ArrayList<>();
    @Unique
    private String relaxed$pendingKey;
    @Unique
    private int relaxed$pendingCount = 1;

    @Inject(require = 0, method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"))
    private void relaxed$onAddMessage(Component contentsDontUse, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag,
                                      CallbackInfo ci, @Local(argsOnly = true) LocalRef<Component> contents) {
        relaxed$pendingKey = null;
        relaxed$pendingCount = 1;
        relaxed$lastLines = -1;
        Chat chat = RelaxedQolClient.enabled(Chat.class);
        if (chat == null) {
            relaxed$recent.clear();
            return;
        }
        Component message = contents.get();
        if (message == null) return;
        String key = chat.stackKey(message.getString());
        int count = 1;
        if (key != null) {
            long now = System.currentTimeMillis();
            int look = Math.min(chat.stackLookback(), relaxed$recent.size());
            int offset = 0;
            for (int k = 0; k < look; k++) {
                Chat.RecentLine line = relaxed$recent.get(k);
                // Our list must still match vanilla's: the k-th newest message is the one we remember
                if (k >= allMessages.size() || allMessages.get(k) != line.message || line.lines <= 0) {
                    relaxed$recent.clear();
                    break;
                }
                if (now - line.time > chat.stackWindowMs()) break;
                if (key.equals(line.key) && trimmedMessages.size() >= offset + line.lines) {
                    for (int i = 0; i < line.lines; i++) trimmedMessages.remove(offset);
                    allMessages.remove(k);
                    relaxed$recent.remove(k);
                    count = line.count + 1;
                    break;
                }
                offset += line.lines;
            }
        }
        relaxed$pendingKey = key;
        relaxed$pendingCount = count;
        contents.set(chat.decorate(message, count));
    }

    @Inject(require = 0, method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("RETURN"))
    private void relaxed$afterAddMessage(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        if (RelaxedQolClient.enabled(Chat.class) == null || allMessages.isEmpty()) {
            relaxed$recent.clear();
            return;
        }
        relaxed$recent.add(0, new Chat.RecentLine(allMessages.get(0), relaxed$lastLines, relaxed$pendingKey,
            relaxed$pendingCount, System.currentTimeMillis()));
        while (relaxed$recent.size() > 60) relaxed$recent.remove(relaxed$recent.size() - 1);
    }

    @Inject(require = 0, method = "addMessageToDisplayQueue", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z"))
    private void relaxed$countLines(GuiMessage message, CallbackInfo ci, @Local(name = "lines") List<FormattedCharSequence> lines) {
        relaxed$lastLines = lines.size();
    }

    @Inject(require = 0, method = "clearMessages", at = @At("HEAD"), cancellable = true)
    private void relaxed$onClear(boolean history, CallbackInfo ci) {
        // Leaving a server: Chat's Keep On Leave keeps everything (only a restart clears it)
        if (Chat.keepChatNow()) {
            ci.cancel();
            return;
        }
        relaxed$forget();
    }

    @Inject(require = 0, method = "refreshTrimmedMessages", at = @At("HEAD"))
    private void relaxed$onRefresh(CallbackInfo ci) {
        relaxed$forget();
    }

    /** Keep On Leave backup: the chat lines as they were when you left (null = none). */
    @Unique
    private List<GuiMessage> relaxed$saved;

    @Override
    public void relaxed$save() {
        // Never replaced by an emptier copy (the game may already have wiped part of it by a later step)
        if (allMessages.isEmpty()) return;
        if (relaxed$saved == null || allMessages.size() >= relaxed$saved.size()) relaxed$saved = new java.util.ArrayList<>(allMessages);
    }

    @Override
    public void relaxed$restoreIfLost() {
        List<GuiMessage> saved = relaxed$saved;
        relaxed$saved = null;
        if (saved == null || allMessages.size() >= saved.size()) return; // nothing was wiped
        // What came in since joining stays on top, the older lines go back underneath (newest is first)
        java.util.Set<GuiMessage> have = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        have.addAll(allMessages);
        for (GuiMessage m : saved) if (!have.contains(m)) allMessages.add(m);
        try {
            java.lang.reflect.Method refresh = ChatComponent.class.getDeclaredMethod("refreshTrimmedMessages");
            refresh.setAccessible(true);
            refresh.invoke(this);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // shows again from the next new line on
        }
    }

    @Unique
    private void relaxed$forget() {
        relaxed$lastLines = -1;
        relaxed$recent.clear();
    }

    @ModifyExpressionValue(require = 0, method = "addMessageToQueue", at = @At(value = "CONSTANT", args = "intValue=100"))
    private int relaxed$historyLimit(int vanilla) {
        Chat chat = RelaxedQolClient.enabled(Chat.class);
        return chat == null ? vanilla : chat.historyLimit(vanilla);
    }

    @ModifyExpressionValue(require = 0, method = "addMessageToDisplayQueue", at = @At(value = "CONSTANT", args = "intValue=100"))
    private int relaxed$visibleLimit(int vanilla) {
        Chat chat = RelaxedQolClient.enabled(Chat.class);
        return chat == null ? vanilla : chat.historyLimit(vanilla);
    }
}
