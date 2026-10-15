package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.Random;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.Map;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.minecraft.server.MinecraftServer;

/** One deterministic, world-wide cycle clock for rotating KIC content. */
public final class KicCycle {
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;
    private record RotationCache(long cycleId, List<String> ids) {}
    private static final Map<String, RotationCache> CURSE_CACHE = new HashMap<>();
    private static volatile MinecraftServer server;
    /** Temporary in-memory offset used only by the admin debug cycle command. */
    private static volatile long debugCycleOffset = 0L;

    private KicCycle() {}

    public static void bindServer(MinecraftServer value) {
        server = value;
        CURSE_CACHE.clear();
        debugCycleOffset = 0L;
    }
    public static void unbindServer(MinecraftServer value) {
        if (server == value) {
            server = null;
            CURSE_CACHE.clear();
        }
        debugCycleOffset = 0L;
    }

    /** Uses the existing shop-cycle setting for every rotating KIC category. */
    public static int durationHours() {
        return Math.max(1, ShrineConfig.current().shopCycleHours);
    }

    /** Fixed local-time boundaries in the configured cycle timezone. */
    public static long currentCycleId() {
        return cycleIdAt(ZonedDateTime.now(cycleZone())) + debugCycleOffset;
    }

    /** Advances the in-memory debug cycle by one. It resets when the server restarts. */
    public static synchronized long triggerDebugCycle() {
        debugCycleOffset++;
        CURSE_CACHE.clear();
        return currentCycleId();
    }

    /** Returns the number of temporary debug cycles applied since server start. */
    public static long debugCycleOffset() {
        return debugCycleOffset;
    }

    public static long cycleIdAt(long epochMillis) {
        return cycleIdAt(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), cycleZone()));
    }

    public static long cycleIdAt(ZonedDateTime time) {
        int hours = durationHours();
        if (hours <= 0) hours = 12;
        LocalDate date = time.toLocalDate();
        ZonedDateTime start = date.atStartOfDay(cycleZone());
        long hoursSinceMidnight = Math.max(0L, Duration.between(start, time).toHours());
        long dayNumber = date.toEpochDay();
        if (24 % hours == 0) {
            return dayNumber * (24L / hours) + Math.floorDiv(hoursSinceMidnight, hours);
        }
        // For unusual durations that do not divide a day evenly, continue the
        // deterministic sequence from a local-date epoch. KIC normally uses 12h.
        long epochHours = dayNumber * 24L + hoursSinceMidnight;
        return Math.floorDiv(epochHours, hours);
    }

    public static ZoneId cycleZone() {
        try {
            return ZoneId.of(ShrineConfig.current().shopCycleTimezone);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Manila");
        }
    }

    public static String statusLine() {
        int hours = durationHours();
        ZonedDateTime nowLocal = ZonedDateTime.now(cycleZone());
        long durationMillis = hours * MILLIS_PER_HOUR;
        long cycleId = currentCycleId();
        int cyclesPerDay = 24 % hours == 0 ? 24 / hours : 0;
        long number = cyclesPerDay > 0 ? Math.floorMod(cycleId, cyclesPerDay) + 1 : cycleId + 1;
        long minutesRemaining;
        if (24 % hours == 0) {
            int withinDay = nowLocal.getHour() % hours;
            minutesRemaining = ((hours - withinDay) * 60L) - nowLocal.getMinute();
            if (minutesRemaining <= 0) minutesRemaining += hours * 60L;
        } else {
            long now = nowLocal.toInstant().toEpochMilli();
            minutesRemaining = (durationMillis - Math.floorMod(now, durationMillis) + 59_999L) / 60_000L;
        }
        return "Current Cycle: " + number + "\nNext cycle in: " + (minutesRemaining / 60L) + "h "
            + (minutesRemaining % 60L) + "m";
    }

    /** Selects a deterministic weighted, duplicate-free selection for one cycle. */
    public static <T> List<T> select(List<T> candidates, long cycleId, String scope, int count,
                                      Function<T, String> key, ToIntFunction<T> weight) {
        return selectExcluding(candidates, cycleId, scope, count, key, weight, Set.of());
    }

    /**
     * Persistent shop snapshot for one shared server-wide cycle. The generated
     * selection is stored so config reloads and server restarts cannot reroll the
     * active shop. The next cycle is forced to differ from the previous cycle
     * whenever at least one alternative item is available.
     */
    public static synchronized <T> List<T> shopPool(ShopCategory category, List<T> eligible, long cycleId,
                                                     int targetSize, Function<T, String> key,
                                                     ToIntFunction<T> weight,
                                                     Function<T, ShopItemCategory> itemCategory) {
        if (eligible.isEmpty()) return List.of();
        String signature = eligible.stream()
            .map(value -> key.apply(value) + ":" + Math.max(1, weight.applyAsInt(value)))
            .sorted().reduce((a, b) -> a + "|" + b).orElse("");
        String cacheKey = "shop:" + category.name() + ":" + signature;
        RotationCache cache = CURSE_CACHE.get(cacheKey);
        if (cache != null && cache.cycleId() == cycleId) {
            List<T> cached = fromIds(eligible, cache.ids(), key);
            if (cached.size() == cache.ids().size()) return cached;
        }

        List<String> stored = List.of();
        Set<String> previous = new HashSet<>();
        if (server != null) {
            KicRotationState state = KicRotationState.get(server);
            stored = state.shopIdsForCycle(category, cycleId);
            if (!stored.isEmpty()) {
                List<T> restored = fromIds(eligible, stored, key);
                if (restored.size() == stored.size()) {
                    CURSE_CACHE.put(cacheKey, new RotationCache(cycleId, stored));
                    return restored;
                }
            }
            previous.addAll(state.shopIdsForPreviousCycle(category, cycleId));
        }

        int target = Math.min(Math.max(0, targetSize), eligible.size());
        List<T> selected = selectShopWithGuarantees(eligible, cycleId, category, target, key, weight, itemCategory);

        if (!previous.isEmpty() && sameIds(selected, previous, key)) {
            List<T> alternatives = eligible.stream()
                .filter(value -> !previous.contains(key.apply(value)))
                .toList();
            if (!alternatives.isEmpty() && !selected.isEmpty()) {
                T replacement = select(alternatives, cycleId,
                    "shop-change-" + category.name(), 1, key, weight).getFirst();
                ShopItemCategory replacementCategory = itemCategory.apply(replacement);
                int replaceIndex = -1;
                for (int i = 0; i < selected.size(); i++) {
                    if (itemCategory.apply(selected.get(i)) == replacementCategory) {
                        replaceIndex = i;
                        break;
                    }
                }
                if (replaceIndex < 0) replaceIndex = selected.size() - 1;
                selected = new ArrayList<>(selected);
                selected.set(replaceIndex, replacement);
                selected = List.copyOf(selected);
            }
        }

        List<String> ids = selected.stream().map(key).toList();
        if (server != null) {
            KicRotationState.get(server).recordShopRotation(category, cycleId, ids);
        }
        CURSE_CACHE.put(cacheKey, new RotationCache(cycleId, ids));
        return List.copyOf(selected);
    }

    private static <T> List<T> selectShopWithGuarantees(List<T> candidates, long cycleId, ShopCategory category,
                                                         int targetSize, Function<T, String> key,
                                                         ToIntFunction<T> weight,
                                                         Function<T, ShopItemCategory> itemCategory) {
        List<T> sorted = candidates.stream().sorted(java.util.Comparator.comparing(key)).toList();
        List<T> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        for (ShopItemCategory categoryValue : ShopItemCategory.values()) {
            List<T> categoryItems = sorted.stream()
                .filter(value -> itemCategory.apply(value) == categoryValue && !selectedIds.contains(key.apply(value)))
                .toList();
            if (categoryItems.isEmpty() || selected.size() >= targetSize) continue;
            T guaranteed = select(categoryItems, cycleId,
                "shop-guarantee-" + category.name() + "-" + categoryValue.name(), 1, key, weight).getFirst();
            selected.add(guaranteed);
            selectedIds.add(key.apply(guaranteed));
        }
        List<T> extras = sorted.stream().filter(value -> !selectedIds.contains(key.apply(value))).toList();
        int extraCount = Math.max(0, Math.min(targetSize - selected.size(), extras.size()));
        selected.addAll(select(extras, cycleId, "shop-extras-" + category.name(), extraCount, key, weight));
        return List.copyOf(selected);
    }

    private static <T> boolean sameIds(List<T> selected, Set<String> previous, Function<T, String> key) {
        if (selected.size() != previous.size()) return false;
        Set<String> current = new HashSet<>();
        for (T value : selected) current.add(key.apply(value));
        return current.equals(previous);
    }

    private static <T> List<T> selectExcluding(List<T> candidates, long cycleId, String scope, int count,
                                               Function<T, String> key, ToIntFunction<T> weight,
                                               Set<String> excluded) {
        List<T> remaining = new ArrayList<>();
        for (T candidate : candidates) {
            if (candidate != null && !excluded.contains(key.apply(candidate))) remaining.add(candidate);
        }
        List<T> selected = new ArrayList<>(Math.min(count, remaining.size()));
        SplittableRandom random = new SplittableRandom(seed(cycleId, scope));
        while (!remaining.isEmpty() && selected.size() < count) {
            long totalWeight = 0;
            for (T candidate : remaining) totalWeight += Math.max(1, weight.applyAsInt(candidate));
            long ticket = random.nextLong(totalWeight);
            int chosen = 0;
            for (; chosen < remaining.size() - 1; chosen++) {
                ticket -= Math.max(1, weight.applyAsInt(remaining.get(chosen)));
                if (ticket < 0) break;
            }
            selected.add(remaining.remove(chosen));
        }
        return List.copyOf(selected);
    }

    /** Persistent snapshot of the five Blessings for a cycle. Unlock changes take effect next cycle. */
    public static synchronized <T> List<T> blessingPool(List<T> eligible, long cycleId,
                                                        Function<T, String> key, ToIntFunction<T> weight) {
        if (eligible.isEmpty()) return List.of();
        String signature = eligible.stream().map(value -> key.apply(value) + ":" + Math.max(1, weight.applyAsInt(value)))
            .sorted().reduce((a, b) -> a + "|" + b).orElse("");
        RotationCache cache = CURSE_CACHE.get("blessing:" + signature);
        if (cache != null && cache.cycleId() == cycleId) return fromIds(eligible, cache.ids(), key);
        if (server != null) {
            KicRotationState state = KicRotationState.get(server);
            List<String> stored = state.blessingIdsForCycle(cycleId);
            if (!stored.isEmpty()) {
                List<T> storedValues = fromIds(eligible, stored, key);
                if (storedValues.size() == stored.size()) {
                    CURSE_CACHE.put("blessing:" + signature, new RotationCache(cycleId, stored));
                    return storedValues;
                }
            }
            List<T> selected = selectExcluding(eligible, cycleId, "blessings", 5, key, weight, Set.of());
            List<String> ids = selected.stream().map(key).toList();
            state.recordBlessingRotation(cycleId, ids);
            CURSE_CACHE.put("blessing:" + signature, new RotationCache(cycleId, ids));
            return List.copyOf(selected);
        }
        List<T> selected = selectExcluding(eligible, cycleId, "blessings", 5, key, weight, Set.of());
        List<String> ids = selected.stream().map(key).toList();
        CURSE_CACHE.put("blessing:" + signature, new RotationCache(cycleId, ids));
        return List.copyOf(selected);
    }

    /**
     * Curse rotations exclude the preceding cycle's selection. With ten or more
     * unlocked Curses this returns five each cycle without consecutive repeats.
     */
    public static synchronized <T> List<T> cursePool(List<T> eligible, long cycleId,
                                                     Function<T, String> key, ToIntFunction<T> weight) {
        if (eligible.isEmpty()) return List.of();
        String signature = eligible.stream().map(value -> key.apply(value) + ":" + Math.max(1, weight.applyAsInt(value)))
            .sorted().reduce((a, b) -> a + "|" + b).orElse("");
        RotationCache cache = CURSE_CACHE.get(signature);
        if (cache != null && cache.cycleId() == cycleId) return fromIds(eligible, cache.ids(), key);

        Set<String> previous = new HashSet<>();
        if (server != null) {
            KicRotationState state = KicRotationState.get(server);
            previous.addAll(state.curseIdsForCycle(cycleId - 1));
            List<String> stored = state.curseIdsForCycle(cycleId);
            if (!stored.isEmpty()) {
                List<T> storedValues = fromIds(eligible, stored, key);
                if (storedValues.size() == stored.size()) {
                    CURSE_CACHE.put(signature, new RotationCache(cycleId, stored));
                    return storedValues;
                }
            }
            List<T> selected = selectExcluding(eligible, cycleId, "curses", 5, key, weight, previous);
            List<String> ids = selected.stream().map(key).toList();
            state.recordCurseRotation(cycleId, ids);
            CURSE_CACHE.put(signature, new RotationCache(cycleId, ids));
            return List.copyOf(selected);
        }

        if (cache != null && cache.cycleId() == cycleId - 1) previous.addAll(cache.ids());
        List<T> selected = selectExcluding(eligible, cycleId, "curses", 5, key, weight, previous);
        List<String> ids = selected.stream().map(key).toList();
        CURSE_CACHE.put(signature, new RotationCache(cycleId, ids));
        return List.copyOf(selected);
    }

    private static <T> List<T> fromIds(List<T> candidates, List<String> ids, Function<T, String> key) {
        List<T> result = new ArrayList<>();
        for (String id : ids) candidates.stream().filter(value -> key.apply(value).equals(id)).findFirst().ifPresent(result::add);
        return List.copyOf(result);
    }

    public static long remainingMillis() {
        int hours = durationHours();
        ZonedDateTime now = ZonedDateTime.now(cycleZone());
        if (24 % hours == 0) {
            ZonedDateTime start = now.toLocalDate().atStartOfDay(cycleZone());
            long minutesSinceStart = Duration.between(start, now).toMinutes();
            long durationMinutes = hours * 60L;
            return (durationMinutes - Math.floorMod(minutesSinceStart, durationMinutes)) * 60_000L;
        }
        long duration = hours * MILLIS_PER_HOUR;
        return duration - Math.floorMod(now.toInstant().toEpochMilli(), duration);
    }

    public static int randomInt(long cycleId, String scope, int bound) {
        if (bound <= 1) return 0;
        return new SplittableRandom(seed(cycleId, scope)).nextInt(bound);
    }

    public static <T> List<T> shuffled(List<T> values, long cycleId, String scope) {
        List<T> result = new ArrayList<>(values);
        Collections.shuffle(result, new Random(seed(cycleId, scope)));
        return List.copyOf(result);
    }

    private static long seed(long cycleId, String scope) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < scope.length(); i++) {
            hash = (hash ^ scope.charAt(i)) * 0x100000001b3L;
        }
        long value = cycleId ^ hash;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
