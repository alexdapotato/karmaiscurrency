package akisei.karmacurrency;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.serialization.Codec;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-player state that is not itself a visible currency.
 *
 * Minecraft 26.2's Data Attachment API is a better fit than scoreboard
 * objectives for timers, counters, cooldowns and other internal mod state.
 * The attachment is persistent and copied when a player respawns.
 */
public final class KarmaPlayerState {
    private static final Codec<Map<String, Long>> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG);

    public static final AttachmentType<Map<String, Long>> ATTACHMENT = AttachmentRegistry.create(
        KarmaIsCurrency.id("player_state"),
        builder -> builder
            .persistent(CODEC)
            .copyOnDeath()
    );

    private KarmaPlayerState() {}

    private static Map<String, Long> values(ServerPlayer player) {
        return player.getAttachedOrElse(ATTACHMENT, Map.of());
    }

    public static long get(ServerPlayer player, String key) {
        return values(player).getOrDefault(key, 0L);
    }

    public static void set(ServerPlayer player, String key, long value) {
        Map<String, Long> current = values(player);
        // Skip identical writes: they would copy the map and mark the attachment changed for nothing.
        if (current.getOrDefault(key, 0L) == value) return;
        Map<String, Long> next = new HashMap<>(current);
        if (value == 0L) next.remove(key);
        else next.put(key, value);
        player.setAttached(ATTACHMENT, Map.copyOf(next));
    }

    /** Applies several changes with a single map copy and a single write. */
    public static void update(ServerPlayer player, java.util.function.Consumer<Map<String, Long>> changes) {
        Map<String, Long> current = values(player);
        Map<String, Long> next = new HashMap<>(current);
        changes.accept(next);
        next.values().removeIf(v -> v == null || v == 0L);
        if (next.equals(current)) return;
        player.setAttached(ATTACHMENT, Map.copyOf(next));
    }

    public static void clearPrefix(ServerPlayer player, String prefix) {
        Map<String, Long> current = values(player);
        boolean any = false;
        for (String key : current.keySet()) {
            if (key.startsWith(prefix)) { any = true; break; }
        }
        if (!any) return;
        Map<String, Long> next = new HashMap<>(current);
        next.keySet().removeIf(key -> key.startsWith(prefix));
        player.setAttached(ATTACHMENT, Map.copyOf(next));
    }

    public static int getInt(ServerPlayer player, String key) {
        return Math.toIntExact(get(player, key));
    }

    public static void setInt(ServerPlayer player, String key, int value) {
        set(player, key, value);
    }

    public static void increment(ServerPlayer player, String key) {
        set(player, key, get(player, key) + 1L);
    }

    public static void clear(ServerPlayer player, String key) {
        set(player, key, 0L);
    }
}
