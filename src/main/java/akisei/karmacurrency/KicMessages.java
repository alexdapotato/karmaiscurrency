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
    public static void change(ServerPlayer p, String currency, int delta, String reason) {
        if (delta == 0) return;
        pending.computeIfAbsent(new Key(p.getUUID(), contexts.getOrDefault(p.getUUID(), reason)), k -> new LinkedHashMap<>()).merge(currency, delta, Integer::sum);
    }
    public static void flush(MinecraftServer server) {
        var entries = new LinkedHashMap<>(pending);
        pending.clear();
        entries.forEach((key, changes) -> {
            ServerPlayer p = server.getPlayerList().getPlayer(key.player());
            if (p == null) return;
            boolean decay = key.reason().equals("natural decay");
            String flavor = decay ? pick(changes.size() == 1 && changes.containsKey("Karma")
                ? new String[]{"Your past loosens its hold.", "Time softens the weight of your deeds.", "Your past grows quieter."}
                : new String[]{"Even legends fade with time.", "Your name slips from memory.", "The world remembers a little less."}) : "";
            if (!decay && changes.entrySet().stream().filter(e -> !e.getKey().equals("Karma")).allMatch(e -> e.getValue() > 0) && !key.reason().toLowerCase(java.util.Locale.ROOT).contains("debug")) {
                if (changes.containsKey("Reputation")) flavor = pick(new String[]{"Your deeds earn another voice in your favor.", "Your good name travels further.", "Your deeds are remembered."});
                else if (changes.containsKey("Infamy")) flavor = pick(new String[]{"Your name is spoken with growing unease.", "The whispers around you deepen.", "Your infamy spreads."});
            }
            if (key.reason().equals("Shrine offering") || key.reason().equals("Influence dematerialization")) flavor = KicFlavorText.offeringFlavor(KarmaService.rewardAlignment(p));
            var message = Component.literal(flavor.isEmpty() ? "" : flavor + " ").withStyle(ChatFormatting.GRAY);
            boolean first = true;
            for (var change : changes.entrySet()) {
                int delta = change.getValue(); if (delta == 0) continue;
                if (!first) message.append(Component.literal(" · ").withStyle(ChatFormatting.GRAY));
                first = false;
                String amount = change.getKey().equals("Karma") ? KarmaService.format(delta) : Integer.toString(delta);
                ChatFormatting color = change.getKey().equals("Karma") ? ChatFormatting.AQUA : delta > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                message.append(Component.literal((delta > 0 ? "+" : "") + amount + " " + change.getKey()).withStyle(color));
            }
            if (first) return;
            if (decay && changes.containsKey("Karma")) message.append(Component.literal(" · Karma: " + KarmaService.format(KarmaService.get(p))).withStyle(ChatFormatting.AQUA));
            if (!decay) message.append(Component.literal(" — " + key.reason()).withStyle(ChatFormatting.GRAY));
            p.sendSystemMessage(message);
        });
    }
    private static String pick(String[] lines) { return lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)]; }
}
