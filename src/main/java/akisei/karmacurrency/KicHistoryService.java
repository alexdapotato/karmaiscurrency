package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent player history plus the server-wide KIC contribution history. */
public final class KicHistoryService extends SavedData {
    private static final int MAX_PERSONAL_ENTRIES = 40;
    private static final int MAX_WORLD_ENTRIES = 80;
    private static final String WORLD_KEY = "__world_contributions";
    private static final Path ADMIN_LOG = Path.of("logs", "kic-karma.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final Codec<KicHistoryService> CODEC = Codec.unboundedMap(Codec.STRING, Codec.list(Codec.STRING))
        .xmap(KicHistoryService::fromMap, KicHistoryService::toMap);
    public static final SavedDataType<KicHistoryService> TYPE = new SavedDataType<>(
        KarmaIsCurrency.id("kic_history"), KicHistoryService::new, CODEC, null);

    private final Map<String, List<String>> entries;

    public KicHistoryService() { this(new LinkedHashMap<>()); }

    private KicHistoryService(Map<String, List<String>> entries) {
        this.entries = new LinkedHashMap<>();
        entries.forEach((key, value) -> this.entries.put(key, new ArrayList<>(value)));
    }

    private static KicHistoryService fromMap(Map<String, List<String>> values) {
        return new KicHistoryService(values);
    }

    private Map<String, List<String>> toMap() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    public static KicHistoryService get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public static void recordKarma(ServerPlayer player, int delta, String reason) {
        if (delta != 0) record(player, "Karma " + (delta > 0 ? "+" : "") + KarmaService.format(delta), reason);
    }

    public static void recordSocial(ServerPlayer player, String currency, int delta, String reason) {
        if (delta != 0) record(player, currency + " " + signed(delta), reason);
    }

    public static void recordInfluence(ServerPlayer player, int delta, String reason) {
        if (delta != 0) record(player, "Physical Influence " + signed(delta), reason);
    }

    public static void recordEvent(ServerPlayer player, String reason) {
        record(player, "Event", reason);
    }

    public static void recordRecognition(ServerPlayer player, String reason) {
        record(player, "Recognition", reason);
    }

    public static void recordContribution(ServerPlayer player, int amount) {
        record(player, "Contribution +" + amount, "World Progression");
        recordWorld(player.level().getServer(), player.getName().getString(), "Contribution +" + amount);
    }

    public static List<String> personal(ServerPlayer player) {
        KicHistoryService data = get(player.level().getServer());
        return List.copyOf(data.entries.getOrDefault(player.getUUID().toString(), List.of()));
    }

    public static List<String> contributions(MinecraftServer server) {
        KicHistoryService data = get(server);
        return List.copyOf(data.entries.getOrDefault(WORLD_KEY, List.of()));
    }

    public static void recordWorld(MinecraftServer server, String actor, String reason) {
        KicHistoryService data = get(server);
        String line = timestamp() + " | " + actor + " | " + reason;
        append(data.entries, WORLD_KEY, line, MAX_WORLD_ENTRIES);
        data.setDirty();
        appendAdmin(line);
    }

    private static void record(ServerPlayer player, String change, String reason) {
        String line = timestamp() + " | " + change + " | " + reason;
        KicHistoryService data = get(player.level().getServer());
        append(data.entries, player.getUUID().toString(), line, MAX_PERSONAL_ENTRIES);
        data.setDirty();
        appendAdmin(timestamp() + " | " + player.getName().getString() + " | " + change + " | " + reason);
    }

    private static void append(Map<String, List<String>> map, String key, String line, int limit) {
        List<String> list = map.computeIfAbsent(key, ignored -> new ArrayList<>());
        list.add(line);
        while (list.size() > limit) list.remove(0);
    }

    private static String signed(int value) { return value > 0 ? "+" + value : Integer.toString(value); }

    private static String timestamp() {
        return ZonedDateTime.now(ZoneId.of("Asia/Manila")).format(TIME);
    }

    private static synchronized void appendAdmin(String line) {
        try {
            Files.createDirectories(ADMIN_LOG.getParent());
            Files.writeString(ADMIN_LOG, line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            KarmaIsCurrency.LOGGER.warn("Could not append KIC karma log", exception);
        }
    }
}
