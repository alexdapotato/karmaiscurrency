package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent server-wide rotation history used by deterministic KIC cycles. */
public final class KicRotationState extends SavedData {
    private static final String LAST_BLESSING_CYCLE = "last_blessing_cycle";
    private static final String LAST_BLESSING_IDS = "last_blessing_ids";
    private static final String LAST_CURSE_CYCLE = "last_curse_cycle";
    private static final String LAST_CURSE_IDS = "last_curse_ids";
    private static final String BLESSING_LOG_PREFIX = "blessing_log_";
    private static final String CURSE_LOG_PREFIX = "curse_log_";
    private static final String COMMON_SHOP_LOG_PREFIX = "common_shop_";
    private static final String VILLAIN_SHOP_LOG_PREFIX = "villain_shop_";
    private static final String TRIAL_QUEST_LOG_PREFIX = "trial_quest_pool_";
    private static final String CONTRACT_QUEST_LOG_PREFIX = "contract_quest_pool_";
    private static final String PENDING_QUEST_COOLDOWN_PREFIX = "pending_quest_cooldown_";
    private static final String SEPARATOR = "\u001f";
    private static final int MAX_LOG_ENTRIES = 32;

    private static final Codec<KicRotationState> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
        .xmap(KicRotationState::fromMap, KicRotationState::toMap);

    public static final SavedDataType<KicRotationState> TYPE = new SavedDataType<>(
        KarmaIsCurrency.id("rotation_state"), KicRotationState::new, CODEC, null
    );

    private long lastBlessingCycle = Long.MIN_VALUE;
    private List<String> lastBlessingIds = List.of();
    private long lastCurseCycle = Long.MIN_VALUE;
    private List<String> lastCurseIds = List.of();
    private final LinkedHashMap<Long, List<String>> blessingLog = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<String>> curseLog = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<String>> commonShopLog = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<String>> villainShopLog = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<String>> trialQuestLog = new LinkedHashMap<>();
    private final LinkedHashMap<Long, List<String>> contractQuestLog = new LinkedHashMap<>();
    private final LinkedHashMap<java.util.UUID, Long> pendingQuestCooldowns = new LinkedHashMap<>();

    public KicRotationState() {}

    private KicRotationState(long lastBlessingCycle, List<String> lastBlessingIds,
            long lastCurseCycle, List<String> lastCurseIds,
            Map<Long, List<String>> blessings, Map<Long, List<String>> curses,
            Map<Long, List<String>> commonShops, Map<Long, List<String>> villainShops,
            Map<Long, List<String>> trialQuests, Map<Long, List<String>> contractQuests,
            Map<java.util.UUID, Long> pendingCooldowns) {
        this.lastBlessingCycle = lastBlessingCycle;
        this.lastBlessingIds = List.copyOf(lastBlessingIds);
        this.lastCurseCycle = lastCurseCycle;
        this.lastCurseIds = List.copyOf(lastCurseIds);
        blessings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> putBounded(blessingLog, entry.getKey(), entry.getValue()));
        curses.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> putBounded(curseLog, entry.getKey(), entry.getValue()));
        commonShops.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> putBounded(commonShopLog, entry.getKey(), entry.getValue()));
        villainShops.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> putBounded(villainShopLog, entry.getKey(), entry.getValue()));
        trialQuests.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> putBounded(trialQuestLog, entry.getKey(), entry.getValue()));
        contractQuests.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> putBounded(contractQuestLog, entry.getKey(), entry.getValue()));
        pendingQuestCooldowns.forEach((uuid, until) -> { if (until > 0L) this.pendingQuestCooldowns.put(uuid, until); });
    }

    private static void putBounded(LinkedHashMap<Long, List<String>> map, long cycle, List<String> ids) {
        map.put(cycle, List.copyOf(ids));
        while (map.size() > MAX_LOG_ENTRIES) map.remove(map.keySet().iterator().next());
    }

    private static KicRotationState fromMap(Map<String, String> values) {
        long lastBlessing = parseLong(values.get(LAST_BLESSING_CYCLE));
        long lastCurse = parseLong(values.get(LAST_CURSE_CYCLE));
        List<String> currentBlessings = decodeIds(values.get(LAST_BLESSING_IDS));
        List<String> currentCurses = decodeIds(values.get(LAST_CURSE_IDS));
        Map<Long, List<String>> blessings = readLog(values, BLESSING_LOG_PREFIX);
        Map<Long, List<String>> curses = readLog(values, CURSE_LOG_PREFIX);
        Map<Long, List<String>> commonShops = readLog(values, COMMON_SHOP_LOG_PREFIX);
        Map<Long, List<String>> villainShops = readLog(values, VILLAIN_SHOP_LOG_PREFIX);
        Map<Long, List<String>> trialQuests = readLog(values, TRIAL_QUEST_LOG_PREFIX);
        Map<Long, List<String>> contractQuests = readLog(values, CONTRACT_QUEST_LOG_PREFIX);
        Map<java.util.UUID, Long> pendingCooldowns = readUuidLongs(values, PENDING_QUEST_COOLDOWN_PREFIX);
        if (lastBlessing != Long.MIN_VALUE && !currentBlessings.isEmpty()) blessings.putIfAbsent(lastBlessing, currentBlessings);
        if (lastCurse != Long.MIN_VALUE && !currentCurses.isEmpty()) curses.putIfAbsent(lastCurse, currentCurses);
        return new KicRotationState(lastBlessing, currentBlessings, lastCurse, currentCurses,
            blessings, curses, commonShops, villainShops, trialQuests, contractQuests, pendingCooldowns);
    }

    private static long parseLong(String value) {
        if (value == null) return Long.MIN_VALUE;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return Long.MIN_VALUE; }
    }

    private static Map<Long, List<String>> readLog(Map<String, String> values, String prefix) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            long cycle = parseLong(entry.getKey().substring(prefix.length()));
            if (cycle != Long.MIN_VALUE) result.put(cycle, decodeIds(entry.getValue()));
        }
        return result;
    }

    private static Map<java.util.UUID, Long> readUuidLongs(Map<String, String> values, String prefix) {
        Map<java.util.UUID, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            try {
                long until = Long.parseLong(entry.getValue());
                if (until > 0L) result.put(java.util.UUID.fromString(entry.getKey().substring(prefix.length())), until);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed persisted player cooldowns.
            }
        }
        return result;
    }

    private Map<String, String> toMap() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(LAST_BLESSING_CYCLE, Long.toString(lastBlessingCycle));
        values.put(LAST_BLESSING_IDS, encodeIds(lastBlessingIds));
        values.put(LAST_CURSE_CYCLE, Long.toString(lastCurseCycle));
        values.put(LAST_CURSE_IDS, encodeIds(lastCurseIds));
        for (Map.Entry<Long, List<String>> entry : blessingLog.entrySet()) values.put(BLESSING_LOG_PREFIX + entry.getKey(), encodeIds(entry.getValue()));
        for (Map.Entry<Long, List<String>> entry : curseLog.entrySet()) values.put(CURSE_LOG_PREFIX + entry.getKey(), encodeIds(entry.getValue()));
        for (Map.Entry<Long, List<String>> entry : commonShopLog.entrySet()) values.put(COMMON_SHOP_LOG_PREFIX + entry.getKey(), encodeIds(entry.getValue()));
        for (Map.Entry<Long, List<String>> entry : villainShopLog.entrySet()) values.put(VILLAIN_SHOP_LOG_PREFIX + entry.getKey(), encodeIds(entry.getValue()));
        for (Map.Entry<Long, List<String>> entry : trialQuestLog.entrySet()) values.put(TRIAL_QUEST_LOG_PREFIX + entry.getKey(), encodeIds(entry.getValue()));
        for (Map.Entry<Long, List<String>> entry : contractQuestLog.entrySet()) values.put(CONTRACT_QUEST_LOG_PREFIX + entry.getKey(), encodeIds(entry.getValue()));
        pendingQuestCooldowns.forEach((uuid, until) -> values.put(PENDING_QUEST_COOLDOWN_PREFIX + uuid, Long.toString(until)));
        return values;
    }

    public static KicRotationState get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<String> blessingIdsForCycle(long cycle) {
        if (cycle == lastBlessingCycle && !lastBlessingIds.isEmpty()) return lastBlessingIds;
        return List.copyOf(blessingLog.getOrDefault(cycle, List.of()));
    }

    public List<String> curseIdsForCycle(long cycle) {
        if (cycle == lastCurseCycle && !lastCurseIds.isEmpty()) return lastCurseIds;
        return List.copyOf(curseLog.getOrDefault(cycle, List.of()));
    }

    public void recordBlessingRotation(long cycle, List<String> ids) {
        List<String> copy = List.copyOf(ids);
        lastBlessingCycle = cycle;
        lastBlessingIds = copy;
        putBounded(blessingLog, cycle, copy);
        setDirty();
    }

    public void recordCurseRotation(long cycle, List<String> ids) {
        List<String> copy = List.copyOf(ids);
        lastCurseCycle = cycle;
        lastCurseIds = copy;
        putBounded(curseLog, cycle, copy);
        setDirty();
    }

    public List<String> shopIdsForCycle(ShopCategory category, long cycle) {
        LinkedHashMap<Long, List<String>> log = category == ShopCategory.VILLAIN ? villainShopLog : commonShopLog;
        return List.copyOf(log.getOrDefault(cycle, List.of()));
    }

    public List<String> shopIdsForPreviousCycle(ShopCategory category, long cycle) {
        return shopIdsForCycle(category, cycle - 1);
    }

    public void recordShopRotation(ShopCategory category, long cycle, List<String> ids) {
        List<String> copy = List.copyOf(ids);
        if (category == ShopCategory.VILLAIN) putBounded(villainShopLog, cycle, copy);
        else putBounded(commonShopLog, cycle, copy);
        setDirty();
    }

    public List<String> trialQuestIdsForCycle(long cycle) {
        return List.copyOf(trialQuestLog.getOrDefault(cycle, List.of()));
    }

    public List<String> contractQuestIdsForCycle(long cycle) {
        return List.copyOf(contractQuestLog.getOrDefault(cycle, List.of()));
    }

    public void recordTrialQuestRotation(long cycle, List<String> ids) {
        putBounded(trialQuestLog, cycle, ids);
        setDirty();
    }

    public void recordContractQuestRotation(long cycle, List<String> ids) {
        putBounded(contractQuestLog, cycle, ids);
        setDirty();
    }

    public void recordPendingQuestCooldown(java.util.UUID player, long until) {
        if (player == null || until <= 0L) return;
        pendingQuestCooldowns.put(player, until);
        setDirty();
    }

    public long consumePendingQuestCooldown(java.util.UUID player) {
        Long until = player == null ? null : pendingQuestCooldowns.remove(player);
        if (until != null) setDirty();
        return until == null ? 0L : until;
    }

    public void clearPendingQuestCooldown(java.util.UUID player) {
        if (player != null && pendingQuestCooldowns.remove(player) != null) setDirty();
    }

    public void clearPendingQuestCooldowns() {
        if (!pendingQuestCooldowns.isEmpty()) {
            pendingQuestCooldowns.clear();
            setDirty();
        }
    }

    private static String encodeIds(List<String> ids) { return String.join(SEPARATOR, ids); }

    private static List<String> decodeIds(String value) {
        if (value == null || value.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String id : value.split(SEPARATOR, -1)) if (!id.isBlank()) result.add(id);
        return List.copyOf(result);
    }
}
