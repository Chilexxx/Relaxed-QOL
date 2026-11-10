package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.Colors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * (Lunar-style chat): far longer chat history (scroll back thousands of lines instead of 100), timestamps in
 * the format and colour you like, repeated messages stacked into one line with a counter ("x3"), and a highlight +
 * ping when someone says your name. Works through ChatComponentMixin (optional - if it cannot hook in, chat just
 * stays vanilla).
 */
public class Chat extends Module {
    private final Setting<Boolean> longHistory = addSetting(new Setting<>("Unlimited History", true),
        "Keep many more chat lines to scroll back through (vanilla keeps 100)");
    private final Setting<Integer> historyLines = addSetting(new Setting<>("History Lines", 10000, 100, 50000, 100),
        "How many lines \"Unlimited History\" keeps (memory stays small: text only)");
    private final Setting<Boolean> keepOnLeave = addSetting(new Setting<>("Keep On Leave", true),
        "Keep your chat when you leave a server or switch servers - it is only cleared when the game restarts"
            + " (or when History Lines is full, the oldest lines go)");
    private final Setting<Boolean> timestamps = addSetting(new Setting<>("Timestamps", true));
    private final Setting<String> timeFormat = addSetting(Setting.mode("Time Format", "14:05", "14:05", "14:05:09", "2:05 PM"));
    private final Setting<String> timeColor = addSetting(Colors.setting("Time Colour", "Grey"),
        "Click the swatch to pick a colour (Slate = the soft grey vanilla uses for hints)");
    private final Setting<Boolean> brackets = addSetting(new Setting<>("Time Brackets", true), "[14:05] or just 14:05");
    private final Setting<Boolean> stack = addSetting(new Setting<>("Stack Repeats", true),
        "The same message again replaces the earlier one with a counter (x2, x3...) instead of spamming chat");
    /** Much more aggressive stacking - not only the very last line, and small differences do not count. */
    private final Setting<String> stackMode = addSetting(Setting.mode("Stack Mode", "Smart", "Smart", "Exact"),
        "Smart: ignores colours, capitals, extra spaces, numbers and timestamps (\"Bob joined (3)\" stacks with"
            + " \"Bob joined (4)\"). Exact: only the very same text");
    private final Setting<Integer> stackLookback = addSetting(new Setting<>("Stack Look Back", 12, 1, 50, 1),
        "How many recent messages a repeat may stack onto (1 = only the last one, like vanilla-style stacking)");
    private final Setting<Integer> stackSeconds = addSetting(new Setting<>("Stack Within (s)", 120, 5, 900, 5),
        "A repeat only stacks onto a copy that came at most this long ago");

    private long lastSound;
    private long lastAlertSound;

    /**
     * One recent chat message the mixin remembers (newest first) so a repeat can find and replace it, even
     * when other messages came in between. message = the vanilla GuiMessage object, lines = how many chat rows it took.
     */
    public static final class RecentLine {
        public final Object message;
        public final int lines;
        public final String key;
        public final int count;
        public final long time;

        public RecentLine(Object message, int lines, String key, int count, long time) {
            this.message = message;
            this.lines = lines;
            this.key = key;
            this.count = count;
            this.time = time;
        }
    }

    public Chat() {
        super("Chat", "Longer chat history, timestamps and stacked repeats", ModuleCategory.RENDER);
    }

    @Override
    public boolean worksInScreens() {
        return true;
    }

    public int historyLimit(int vanilla) {
        return longHistory.getValue() ? Math.max(vanilla, historyLines.getValue()) : vanilla;
    }

    public boolean stacking() {
        return stack.getValue();
    }

    /** How many recent messages a repeat may stack onto. */
    public int stackLookback() {
        return stackLookback.getValue();
    }

    public long stackWindowMs() {
        return stackSeconds.getValue() * 1000L;
    }

    private static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile("\\[?\\b\\d{1,2}:\\d{2}(:\\d{2})?( ?[ap]m)?\\]?", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern NUMBERS = java.util.regex.Pattern.compile("\\d+([.,]\\d+)*");
    private static final java.util.regex.Pattern COUNTER = java.util.regex.Pattern.compile("\\s*[x×]\\d+\\s*$");

    /**
     * What two messages must share to stack (null = never stacks). Smart: no colour codes, capitals, timestamps,
     * numbers, repeated spaces or a trailing "x3" from the server's own stacking.
     */
    /** Separators between a player's name and what they say: "<Steve> hi", "Steve: hi", "[VIP] Steve » hi", "Steve -> hi". */
    private static final java.util.regex.Pattern SAYS = java.util.regex.Pattern.compile("^\\s*(?:[>:»›|]|->|-|>>)\\s?");

    /**
     * A chat line written by a player: the name of someone on the tab list (or "<name>") near the start of the
     * line, followed by what chat plugins put between a name and the message (":", "»", ">", "->").
     */
    /** Set while the game is leaving a server (ChatLeaveMixin) - the chat clear that comes with it is skipped. */
    public static int leaving;
    /** A backup was taken when leaving: put back once in a world again (if the game wiped the chat anyway). */
    private static boolean restorePending;

    /** The game's chat (gui.hud.getChat()). */
    private static Object chatComponent() {
        try {
            return net.minecraft.client.Minecraft.getInstance().gui.hud.getChat();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Leaving a server (ChatLeaveMixin): take the backup. */
    public static void saveForLeave() {
        if (com.relaxedqol.client.RelaxedQolClient.moduleManager == null) return;
        Chat chat = com.relaxedqol.client.RelaxedQolClient.moduleManager.getModule(Chat.class);
        if (chat == null || !chat.isEnabled() || !chat.keepOnLeave.getValue()) return;
        if (chatComponent() instanceof com.relaxedqol.client.util.ChatKeep keep) {
            keep.relaxed$save();
            restorePending = true;
        }
    }

    @Override
    public void onTick() {
        // In a world again after leaving: the backup goes back if the chat was wiped
        if (restorePending && mc.player != null && mc.level != null && leaving <= 0) {
            restorePending = false;
            if (chatComponent() instanceof com.relaxedqol.client.util.ChatKeep keep) keep.relaxed$restoreIfLost();
        }
    }

    /** True = do not clear the chat now (we are leaving a server and Keep On Leave is on). */
    public static boolean keepChatNow() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // Any clear while you are not in a world (leaving, on the menu, joining) is the game's own reset; a clear
        // while you play (F3 + D) is yours and still works
        boolean outside = leaving > 0 || mc.player == null || mc.level == null;
        if (!outside) return false;
        if (com.relaxedqol.client.RelaxedQolClient.moduleManager == null) return false;
        Chat chat = com.relaxedqol.client.RelaxedQolClient.moduleManager.getModule(Chat.class);
        return chat != null && chat.isEnabled() && chat.keepOnLeave.getValue();
    }

    public static boolean isPlayerMessage(String plain) {
        return splitPlayerMessage(plain) != null;
    }

    private static final java.util.regex.Pattern ANGLE_CHAT = java.util.regex.Pattern.compile("(?s)^\\s*<([A-Za-z0-9_.*~!+]{2,17})>\\s(.*)");

    /**
     * A player's chat line split into {who, what they said} (the rank tags, badges and the separator left
     * out), or null when the line is not a player talking.
     */
    public static String[] splitPlayerMessage(String plain) {
        if (plain == null) return null;
        String t = plain.replaceAll("§.", "");
        java.util.regex.Matcher angle = ANGLE_CHAT.matcher(t);
        if (angle.matches()) return new String[]{angle.group(1), angle.group(2)};
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getConnection() == null) return null;
        String head = t.length() > 60 ? t.substring(0, 60) : t;
        String lower = head.toLowerCase(Locale.ROOT);
        String[] best = null;
        int bestAt = Integer.MAX_VALUE;
        for (net.minecraft.client.multiplayer.PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (name == null || name.length() < 2) continue;
            int at = lower.indexOf(name.toLowerCase(Locale.ROOT));
            while (at >= 0) {
                int end = at + name.length();
                boolean wordStart = at == 0 || !(Character.isLetterOrDigit(head.charAt(at - 1)) || head.charAt(at - 1) == '_');
                // A nickname suffix like "Steve ✦" is allowed: skip a few symbols before the separator
                String rest = head.substring(end).replaceFirst("^[^\\p{L}\\p{N}:»›>|-]{0,3}", "");
                java.util.regex.Matcher says = SAYS.matcher(rest);
                if (wordStart && says.find()) {
                    // The earliest name followed by a separator is the sender ("Bob: Steve: hi" was said by Bob)
                    if (at < bestAt) {
                        bestAt = at;
                        int bodyStart = end + (head.substring(end).length() - rest.length()) + says.end();
                        best = new String[]{name, bodyStart <= t.length() ? t.substring(bodyStart) : ""};
                    }
                    break;
                }
                at = lower.indexOf(name.toLowerCase(Locale.ROOT), end);
            }
        }
        return best;
    }

    public String stackKey(String plain) {
        if (!stack.getValue() || plain == null || plain.isBlank()) return null;
        // Only what PLAYERS say is stacked ("x2") - server messages, plugin lines, broadcasts never are
        if (!isPlayerMessage(plain)) return null;
        if (stackMode.getValue().equals("Exact")) return plain;
        String t = plain.replaceAll("§.", "");
        t = COUNTER.matcher(t).replaceAll("");
        t = TIME.matcher(t).replaceAll("");
        t = NUMBERS.matcher(t).replaceAll("#");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00A0]+", " ").trim();
        // Very short messages ("hi", "#") only stack when they are exactly the same
        if (t.replace("#", "").length() < 3) return "=" + plain;
        return t;
    }

    /** When the last new chat line came in, and how close lines must be to count as one message. */
    private long lastLineAt;
    private static final long BURST_MS = 250;

    /** Called for every incoming chat line (before it is added). count = how many times it came (1 = new). */
    public Component decorate(Component message, int count) {
        if (com.relaxedqol.client.util.Privacy.hidden()) return message;
        boolean isRepeat = count > 1;
        Component out = message;
        if (isRepeat) {
            out = Component.empty().append(message).append(Component.literal(" x" + count).withStyle(ChatFormatting.GRAY));
        }
        if (timestamps.getValue()) {
            String t = LocalTime.now().format(switch (timeFormat.getValue()) {
                case "14:05:09" -> DateTimeFormatter.ofPattern("HH:mm:ss");
                case "2:05 PM" -> DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
                default -> DateTimeFormatter.ofPattern("HH:mm");
            });
            String shown = brackets.getValue() ? "[" + t + "] " : t + "  ";
            // One message = one time: lines that arrive together (a server sends e.g. a whole CoreProtect page as
            // separate lines at once) only show the time on the first one; the rest are indented to line up
            long now = System.currentTimeMillis();
            boolean sameBurst = !isRepeat && now - lastLineAt < BURST_MS;
            if (!isRepeat) lastLineAt = now;
            if (sameBurst) {
                net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
                int spaces = Math.max(1, Math.round(font.width(shown) / (float) Math.max(1, font.width(" "))));
                return Component.empty().append(Component.literal(" ".repeat(spaces))).append(out);
            }
            int c = timeColor.getValue().equals("Black") ? 0xFF6E6E7A : Colors.of(timeColor.getValue());
            // The time and the message side by side - the message used to be a child of the grey time text, so
            // everything without its own colour (player heads!) was drawn tinted grey and looked dim
            out = Component.empty()
                .append(Component.literal(shown).withStyle(st -> st.withColor(TextColor.fromRgb(c & 0xFFFFFF)).withBold(false).withItalic(false)))
                .append(out);
        }
        return out;
    }

    /** Chat was cleared / rebuilt: never stack onto a line that is gone (the mixin forgets its list). */
    public void resetRepeat() {
        // state lives in the mixin
    }

    private static boolean containsWord(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            boolean startOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)) && text.charAt(i - 1) != '_';
            int end = i + word.length();
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end)) && text.charAt(end) != '_';
            if (startOk && endOk) return true;
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    @Override
    public String getInfo() {
        return timestamps.getValue() ? timeFormat.getValue() : null;
    }
}
