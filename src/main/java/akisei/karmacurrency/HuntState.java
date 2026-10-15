package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent server-wide state for active Hunt contracts and their cooldowns. */
public final class HuntState extends SavedData {
    private static final String ACTIVE_HUNTS = "active_hunts";
    private static final String HUNT_PREFIX = "hunt.";
    private static final String HUNTED = ".hunted";
    private static final String HUNTED_NAME = ".hunted_name";
    private static final String HUNTED_ALIGNMENT = ".hunted_alignment";
    private static final String START = ".start";
    private static final String END = ".end";
    private static final String GRACE = ".grace";
    private static final String ACTIVE_TICKS = ".active_ticks";
    private static final String HUNTED_REWARD_INTERVALS = ".hunted_reward_intervals";
    private static final String LAST_X = ".last_x";
    private static final String LAST_Y = ".last_y";
    private static final String LAST_Z = ".last_z";
    private static final String LAST_DIMENSION = ".last_dimension";
    private static final String HUNTERS = ".hunters";
    private static final String HUNTED_COOLDOWN_PREFIX = "hunted_cooldown_";
    private static final String PAIR_COOLDOWN_PREFIX = "pair_cooldown_";
    private static final String PENDING_REWARD_PREFIX = "pending_reward_";
    private static final String SEPARATOR = ",";

    private static final Codec<HuntState> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING)
        .xmap(HuntState::fromMap, HuntState::toMap);

    public static final SavedDataType<HuntState> TYPE = new SavedDataType<>(
        KarmaIsCurrency.id("hunt_state"), HuntState::new, CODEC, null
    );

    private final LinkedHashMap<UUID, HuntRecord> hunts = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Long> huntedCooldowns = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> hunterPairCooldowns = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Integer> pendingRewards = new LinkedHashMap<>();

    public HuntState() {}

    private static HuntState fromMap(Map<String, String> values) {
        HuntState state = new HuntState();
        String encodedHunts = values.getOrDefault(ACTIVE_HUNTS, "");
        for (String value : encodedHunts.split(SEPARATOR, -1)) {
            UUID huntId = parseUuid(value);
            if (huntId == null) continue;
            String prefix = HUNT_PREFIX + huntId;
            UUID hunted = parseUuid(values.get(prefix + HUNTED));
            if (hunted == null) continue;
            HuntRecord record = new HuntRecord(
                huntId,
                hunted,
                values.getOrDefault(prefix + HUNTED_NAME, hunted.toString()),
                parseLong(values.get(prefix + START)),
                parseLong(values.get(prefix + END)),
                parseLong(values.get(prefix + GRACE)),
                parseLong(values.get(prefix + ACTIVE_TICKS)),
                (int) parseLong(values.get(prefix + HUNTED_REWARD_INTERVALS)),
                (int) parseLong(values.get(prefix + LAST_X)),
                (int) parseLong(values.get(prefix + LAST_Y)),
                (int) parseLong(values.get(prefix + LAST_Z)),
                values.getOrDefault(prefix + LAST_DIMENSION, "minecraft:overworld"),
                (int) parseLong(values.get(prefix + HUNTED_ALIGNMENT))
            );
            String encodedHunters = values.getOrDefault(prefix + HUNTERS, "");
            for (String hunterValue : encodedHunters.split(SEPARATOR, -1)) {
                UUID hunter = parseUuid(hunterValue);
                if (hunter != null && !hunter.equals(hunted)) record.hunters.add(hunter);
            }
            state.hunts.put(huntId, record);
        }

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(HUNTED_COOLDOWN_PREFIX)) {
                UUID uuid = parseUuid(entry.getKey().substring(HUNTED_COOLDOWN_PREFIX.length()));
                if (uuid != null) state.huntedCooldowns.put(uuid, parseLong(entry.getValue()));
            } else if (entry.getKey().startsWith(PAIR_COOLDOWN_PREFIX)) {
                state.hunterPairCooldowns.put(entry.getKey().substring(PAIR_COOLDOWN_PREFIX.length()), parseLong(entry.getValue()));
            } else if (entry.getKey().startsWith(PENDING_REWARD_PREFIX)) {
                UUID uuid = parseUuid(entry.getKey().substring(PENDING_REWARD_PREFIX.length()));
                if (uuid != null) state.pendingRewards.put(uuid, Math.max(0, (int) parseLong(entry.getValue())));
            }
        }
        return state;
    }

    private Map<String, String> toMap() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ACTIVE_HUNTS, hunts.keySet().stream().map(UUID::toString)
            .reduce((a, b) -> a + SEPARATOR + b).orElse(""));
        for (HuntRecord hunt : hunts.values()) {
            String prefix = HUNT_PREFIX + hunt.id;
            values.put(prefix + HUNTED, hunt.hunted.toString());
            values.put(prefix + HUNTED_NAME, hunt.huntedName);
            values.put(prefix + HUNTED_ALIGNMENT, Integer.toString(hunt.huntedAlignment));
            values.put(prefix + START, Long.toString(hunt.startEpochMillis));
            values.put(prefix + END, Long.toString(hunt.endEpochMillis));
            values.put(prefix + GRACE, Long.toString(hunt.graceUntil));
            values.put(prefix + ACTIVE_TICKS, Long.toString(hunt.activeTicks));
            values.put(prefix + HUNTED_REWARD_INTERVALS, Integer.toString(hunt.huntedRewardIntervals));
            values.put(prefix + LAST_X, Integer.toString(hunt.lastX));
            values.put(prefix + LAST_Y, Integer.toString(hunt.lastY));
            values.put(prefix + LAST_Z, Integer.toString(hunt.lastZ));
            values.put(prefix + LAST_DIMENSION, hunt.lastDimension);
            values.put(prefix + HUNTERS, hunt.hunters.stream().map(UUID::toString)
                .reduce((a, b) -> a + SEPARATOR + b).orElse(""));
        }
        huntedCooldowns.forEach((uuid, until) -> values.put(HUNTED_COOLDOWN_PREFIX + uuid, Long.toString(until)));
        hunterPairCooldowns.forEach((pair, until) -> values.put(PAIR_COOLDOWN_PREFIX + pair, Long.toString(until)));
        pendingRewards.forEach((uuid, reward) -> values.put(PENDING_REWARD_PREFIX + uuid, Integer.toString(reward)));
        return values;
    }

    private static long parseLong(String value) {
        if (value == null) return 0L;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    public static HuntState get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<HuntRecord> activeHunts() { return List.copyOf(hunts.values()); }
    public int activeHuntCount() { return hunts.size(); }
    public HuntRecord get(UUID huntId) { return huntId == null ? null : hunts.get(huntId); }

    public HuntRecord findHuntByHunted(UUID uuid) {
        if (uuid == null) return null;
        for (HuntRecord hunt : hunts.values()) if (uuid.equals(hunt.hunted)) return hunt;
        return null;
    }

    public HuntRecord findHuntByHunter(UUID uuid) {
        if (uuid == null) return null;
        for (HuntRecord hunt : hunts.values()) if (hunt.hunters.contains(uuid)) return hunt;
        return null;
    }

    public boolean isHunterAnywhere(UUID uuid) { return findHuntByHunter(uuid) != null; }

    public long huntedCooldownUntil(UUID uuid) { return huntedCooldowns.getOrDefault(uuid, 0L); }
    public long hunterPairCooldownUntil(UUID hunter, UUID hunted) {
        return hunterPairCooldowns.getOrDefault(pairKey(hunter, hunted), 0L);
    }

    public int pendingReward(UUID uuid) { return pendingRewards.getOrDefault(uuid, 0); }
    public void setPendingReward(UUID uuid, int reward) {
        if (uuid == null || reward <= 0) return;
        pendingRewards.put(uuid, reward);
        setDirty();
    }
    public void clearPendingReward(UUID uuid) {
        if (pendingRewards.remove(uuid) != null) setDirty();
    }

    public void createHunt(UUID id, UUID hunted, String huntedName, long start, long end, long grace, int x, int y, int z, String dimension) {
        createHunt(id, hunted, huntedName, start, end, grace, x, y, z, dimension, 0);
    }

    public void createHunt(UUID id, UUID hunted, String huntedName, long start, long end, long grace,
            int x, int y, int z, String dimension, int huntedAlignment) {
        HuntRecord hunt = new HuntRecord(id, hunted, huntedName, start, end, grace, 0L, 0,
            x, y, z, dimension, huntedAlignment);
        hunts.put(id, hunt);
        setDirty();
    }

    public void addHunter(UUID huntId, UUID hunter) {
        HuntRecord hunt = get(huntId);
        if (hunt == null || hunter == null || hunter.equals(hunt.hunted)) return;
        if (hunt.hunters.add(hunter)) setDirty();
    }

    public void removeHunter(UUID huntId, UUID hunter) {
        HuntRecord hunt = get(huntId);
        if (hunt != null && hunter != null && hunt.hunters.remove(hunter)) setDirty();
    }

    public void removeHunt(UUID huntId, UUID hunted, long cooldownUntil) {
        if (huntId != null && hunts.remove(huntId) != null) {
            if (hunted != null) huntedCooldowns.put(hunted, cooldownUntil);
            setDirty();
        }
    }

    /** Adds active online ticks and atomically advances the persisted 30-minute reward cursor. */
    public int addActiveTicks(UUID huntId, long ticks) {
        HuntRecord hunt = get(huntId);
        if (hunt == null || ticks <= 0) return 0;
        hunt.activeTicks += ticks;
        long crossed = hunt.activeTicks / (20L * 60L * 30L);
        if (crossed <= hunt.huntedRewardIntervals) return 0;
        int due = (int) Math.min(Integer.MAX_VALUE, crossed - hunt.huntedRewardIntervals);
        hunt.huntedRewardIntervals = (int) Math.min(Integer.MAX_VALUE, crossed);
        setDirty();
        return due;
    }

    public void updateLastKnown(UUID huntId, int x, int y, int z, String dimension) {
        HuntRecord hunt = get(huntId);
        if (hunt == null) return;
        String normalized = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        if (x == hunt.lastX && y == hunt.lastY && z == hunt.lastZ && normalized.equals(hunt.lastDimension)) return;
        hunt.lastX = x;
        hunt.lastY = y;
        hunt.lastZ = z;
        hunt.lastDimension = normalized;
        setDirty();
    }

    public void setPairCooldown(UUID hunter, UUID hunted, long until) {
        if (hunter == null || hunted == null) return;
        hunterPairCooldowns.put(pairKey(hunter, hunted), until);
        setDirty();
    }

    public void clearAll() {
        hunts.clear();
        huntedCooldowns.clear();
        hunterPairCooldowns.clear();
        pendingRewards.clear();
        setDirty();
    }

    public void clearPlayer(UUID player) {
        boolean changed = false;
        if (player != null) {
            if (huntedCooldowns.remove(player) != null) changed = true;
            if (pendingRewards.remove(player) != null) changed = true;
            var iterator = hunts.entrySet().iterator();
            while (iterator.hasNext()) {
                HuntRecord hunt = iterator.next().getValue();
                if (player.equals(hunt.hunted)) {
                    iterator.remove();
                    changed = true;
                } else if (hunt.hunters.remove(player)) {
                    changed = true;
                }
            }
            String prefix = player + ":";
            changed |= hunterPairCooldowns.keySet().removeIf(key -> key.startsWith(prefix) || key.endsWith(":" + player));
        }
        if (changed) setDirty();
    }

    public boolean cleanupExpiredCooldowns(long now) {
        boolean changed = huntedCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        changed |= hunterPairCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (changed) setDirty();
        return changed;
    }

    private static String pairKey(UUID hunter, UUID hunted) { return hunter + ":" + hunted; }

    public static final class HuntRecord {
        private final UUID id;
        private final UUID hunted;
        private final String huntedName;
        private final long startEpochMillis;
        private final long endEpochMillis;
        private final long graceUntil;
        private final int huntedAlignment;
        private long activeTicks;
        private int huntedRewardIntervals;
        private int lastX;
        private int lastY;
        private int lastZ;
        private String lastDimension;
        private final java.util.LinkedHashSet<UUID> hunters = new java.util.LinkedHashSet<>();

        private HuntRecord(UUID id, UUID hunted, String huntedName, long startEpochMillis, long endEpochMillis, long graceUntil,
                           long activeTicks, int huntedRewardIntervals, int lastX, int lastY, int lastZ,
                           String lastDimension, int huntedAlignment) {
            this.id = id;
            this.hunted = hunted;
            this.huntedName = huntedName == null || huntedName.isBlank() ? hunted.toString() : huntedName;
            this.startEpochMillis = startEpochMillis;
            this.endEpochMillis = endEpochMillis;
            this.graceUntil = graceUntil;
            this.huntedAlignment = Integer.compare(huntedAlignment, 0);
            this.activeTicks = activeTicks;
            this.huntedRewardIntervals = Math.max(0, huntedRewardIntervals);
            this.lastX = lastX;
            this.lastY = lastY;
            this.lastZ = lastZ;
            this.lastDimension = lastDimension == null || lastDimension.isBlank() ? "minecraft:overworld" : lastDimension;
        }

        public UUID id() { return id; }
        public UUID hunted() { return hunted; }
        public String huntedName() { return huntedName; }
        public long startEpochMillis() { return startEpochMillis; }
        public long endEpochMillis() { return endEpochMillis; }
        public long graceUntil() { return graceUntil; }
        /** 1 for Light, -1 for Dark, and 0 for Neutral/legacy records. */
        public int huntedAlignment() { return huntedAlignment; }
        public long activeTicks() { return activeTicks; }
        public int huntedRewardIntervals() { return huntedRewardIntervals; }
        public int lastX() { return lastX; }
        public int lastY() { return lastY; }
        public int lastZ() { return lastZ; }
        public String lastDimension() { return lastDimension; }
        public List<UUID> hunters() { return List.copyOf(hunters); }
        public boolean isHunter(UUID uuid) { return hunters.contains(uuid); }
    }
}
