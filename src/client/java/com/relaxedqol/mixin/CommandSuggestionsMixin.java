package com.relaxedqol.mixin;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.CommandManager;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Tab completion for the client's "." commands in the chat box (commands, modules, settings, players...). */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Shadow @Final private boolean commandsOnly;
    @Shadow @Final private List<FormattedCharSequence> commandUsage;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private boolean keepSuggestions;
    @Shadow private boolean currentParseIsMessage;

    @Shadow
    public abstract void showSuggestions(boolean immediateNarration);

    @Inject(method = "updateCommandInfo", at = @At("TAIL"))
    private void relaxed$clientCommands(CallbackInfo ci) {
        if (commandsOnly || RelaxedQolClient.commandManager == null) return;
        String text = input.getValue();
        if (!text.startsWith(CommandManager.PREFIX)) return;
        commandUsage.clear();
        currentParseIsMessage = false;
        if (keepSuggestions) return; // the player is cycling through the list with Tab
        int cursor = Math.min(input.getCursorPosition(), text.length());
        String partial = text.substring(0, cursor);
        CommandManager.Completion completion = RelaxedQolClient.commandManager.complete(partial);
        SuggestionsBuilder builder = new SuggestionsBuilder(partial, completion.start());
        for (String candidate : completion.candidates()) builder.suggest(candidate);
        pendingSuggestions = builder.buildFuture();
        if (Minecraft.getInstance().options.autoSuggestions().get()) showSuggestions(false);
    }
}
