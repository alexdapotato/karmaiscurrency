package akisei.karmacurrency;

import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public final class KicMessages {
    private KicMessages() {}
    private record Key(UUID player, String reason) {}
    private static final Map<UUID, String> contexts = new HashMap<>();
    public static <T> T transaction(ServerPlayer p, String reason, java.util.function.Supplier<T> action) {
        String previous = contexts.put(p.getUUID(), reason);
        try { return action.get(); } finally { if (previous == null) contexts.remove(p.getUUID()); else contexts.put(p.getUUID(), previous); }
    }
    private static final Map<Key, Map<String, Integer>> pending = new LinkedHashMap<>();
    private static final Set<Key> milestones = new HashSet<>();
    public static void change(ServerPlayer p, String currency, int delta, String reason) {
        if (delta == 0) return;
        String actualReason=contexts.getOrDefault(p.getUUID(),reason);
        Key key=new Key(p.getUUID(),actualReason);
        pending.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(currency, delta, Integer::sum);
        String normalized=actualReason.toLowerCase(Locale.ROOT);
        boolean earned=delta>0 && !currency.equals("Karma") && !normalized.contains("debug")
            && !normalized.contains("materializ") && !normalized.contains("redemption")
            && !normalized.contains("redeem") && !normalized.contains("conversion") && !normalized.contains("set ");
        if(earned) {
            long remainder=KarmaPlayerState.get(p,"influence_flavor_remainder");
            long total=remainder+delta;
            KarmaPlayerState.set(p,"influence_flavor_remainder",total%100);
            if(total>=100)milestones.add(key);
        }
    }
    public static void flush(MinecraftServer server) {
        if (pending.isEmpty()) return;
        var entries = new LinkedHashMap<>(pending);
        pending.clear();
        var announcements=new HashSet<>(milestones);
        milestones.clear();
        entries.forEach((key, changes) -> {
            ServerPlayer p = server.getPlayerList().getPlayer(key.player());
            if (p == null) return;
            boolean decay = key.reason().equals("natural decay");
            String flavor = decay ? pick(changes.size() == 1 && changes.containsKey("Karma")
                ? new String[]{"Your past loosens its hold.", "Time softens the weight of your deeds.", "Your past grows quieter."}
                : new String[]{"Even legends fade with time.", "Your name slips from memory.", "The world remembers a little less."}) : "";
            if (!decay && announcements.contains(key)) {
                if (changes.getOrDefault("Reputation",0)>0) flavor = pick(new String[]{"Your deeds earn another voice in your favor.", "Your good name travels further.", "Your deeds are remembered.", "Another village speaks kindly of you.", "Trust follows in your footsteps."});
                else if (changes.getOrDefault("Infamy",0)>0) flavor = pick(new String[]{"Your name is spoken with growing unease.", "The whispers around you deepen.", "Your infamy spreads.", "Your shadow reaches a little further.", "Even strangers have heard the warnings."});
                else flavor="Your name carries further.";
            }
            var message = Component.literal(flavor.isEmpty() ? "" : flavor + " ").withStyle(ChatFormatting.GRAY);
            message.append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY));
            boolean first = true;
            for (var change : changes.entrySet()) {
                int delta = change.getValue(); if (delta == 0) continue;
                if (!first) message.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                first = false;
                String amount = change.getKey().equals("Karma") ? KarmaService.format(delta) : Integer.toString(delta);
                ChatFormatting color = change.getKey().equals("Karma") ? ChatFormatting.AQUA : change.getKey().equals("Reputation") ? ChatFormatting.GOLD : change.getKey().equals("Infamy") ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.YELLOW;
                message.append(Component.literal((delta > 0 ? "+" : "") + amount + " " + change.getKey()).withStyle(color));
            }
            if (first) return;
            message.append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
            if (decay && changes.containsKey("Karma")) message.append(Component.literal(" · Karma: " + KarmaService.format(KarmaService.get(p))).withStyle(ChatFormatting.AQUA));

            p.sendSystemMessage(message);
        });
    }
    private static String pick(String[] lines) { return lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)]; }
}
