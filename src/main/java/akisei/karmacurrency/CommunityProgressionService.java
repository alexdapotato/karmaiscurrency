package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Shared server progression based on explicitly submitted Influence.
 * Reputation and Infamy remain player currencies, but World Progression uses one
 * shared Influence pool regardless of token origin. The pool is cumulative and
 * contributions are never removed by spending or natural decay.
 */
public final class CommunityProgressionService extends SavedData {
    /** Influence required to reach each stage. Values above the final threshold remain in the pool. */
    public static final int[] STAGE_THRESHOLDS = {512, 1_536, 3_072, 6_144, 10_240};
    public static final int MAX_STAGE = STAGE_THRESHOLDS.length;
    /** Shrine-cost multiplier by stage: Stage 0..5. Stage 3 and above cap at 120%. */
    public static final int[] SHRINE_COST_MULTIPLIERS_PERCENT = {100, 105, 110, 120, 120, 120};

    private static final String REPUTATION_EARNED = "reputation_earned";
    private static final String INFAMY_EARNED = "infamy_earned";
    private static final String INFLUENCE_CONTRIBUTED = "influence_contributed";
    private static final String LEGACY_COMMUNITY_POOL = "legacy_community_pool";
    // Legacy fields are read only to migrate existing worlds into the shared pool.
    private static final String LEGACY_REPUTATION_CONTRIBUTED = "reputation_contributed";
    private static final String LEGACY_INFAMY_CONTRIBUTED = "infamy_contributed";
    private static final String LEGACY_NEUTRAL_CONTRIBUTED = "neutral_contributed";
    private static final String LEGACY_REPUTATION_POOL = "legacy_reputation_pool";
    private static final String LEGACY_INFAMY_POOL = "legacy_infamy_pool";
    private static final Codec<CommunityProgressionService> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT)
        .xmap(CommunityProgressionService::fromMap, CommunityProgressionService::toMap);
    public static final SavedDataType<CommunityProgressionService> TYPE = new SavedDataType<>(
        KarmaIsCurrency.id("community_progression"), CommunityProgressionService::new, CODEC, null
    );

    private final int[] thresholds = STAGE_THRESHOLDS.clone();
    private int reputationEarned;
    private int infamyEarned;
    private int influenceContributed;
    private int legacyCommunityPool;

    public CommunityProgressionService() {
    }

    private CommunityProgressionService(int reputationEarned, int infamyEarned,
            int influenceContributed, int legacyCommunityPool) {
        this.reputationEarned = Math.max(0, reputationEarned);
        this.infamyEarned = Math.max(0, infamyEarned);
        this.influenceContributed = Math.max(0, influenceContributed);
        this.legacyCommunityPool = Math.max(0, legacyCommunityPool);
    }

    private static CommunityProgressionService fromMap(Map<String, Integer> values) {
        int reputationEarned = values.getOrDefault(REPUTATION_EARNED, 0);
        int infamyEarned = values.getOrDefault(INFAMY_EARNED, 0);

        // Migrate the old separate contribution buckets into one shared Influence total.
        int oldContributed = safeAdd(
            safeAdd(values.getOrDefault(LEGACY_REPUTATION_CONTRIBUTED, 0),
                    values.getOrDefault(LEGACY_NEUTRAL_CONTRIBUTED, 0)),
            values.getOrDefault(LEGACY_INFAMY_CONTRIBUTED, 0));
        int influenceContributed = values.containsKey(INFLUENCE_CONTRIBUTED)
            ? values.getOrDefault(INFLUENCE_CONTRIBUTED, 0)
            : oldContributed;

        // Preserve any already-reached progression from older saves.
        int legacyCommunityPool;
        if (values.containsKey(LEGACY_COMMUNITY_POOL)) {
            legacyCommunityPool = values.getOrDefault(LEGACY_COMMUNITY_POOL, 0);
        } else {
            int oldLightPool = values.getOrDefault(LEGACY_REPUTATION_POOL, reputationEarned);
            int oldDarkPool = values.getOrDefault(LEGACY_INFAMY_POOL, infamyEarned);
            legacyCommunityPool = safeAdd(oldLightPool, oldDarkPool);
        }

        CommunityProgressionService data = new CommunityProgressionService(reputationEarned, infamyEarned, influenceContributed, legacyCommunityPool);
        for (int i = 0; i < MAX_STAGE; i++) data.thresholds[i] = values.getOrDefault("threshold_" + (i + 1), STAGE_THRESHOLDS[i]);
        return data;
    }

    private Map<String, Integer> toMap() {
        Map<String, Integer> values = new HashMap<>();
        for (int i = 0; i < MAX_STAGE; i++) values.put("threshold_" + (i + 1), thresholds[i]);
        values.put(REPUTATION_EARNED, reputationEarned);
        values.put(INFAMY_EARNED, infamyEarned);
        values.put(INFLUENCE_CONTRIBUTED, influenceContributed);
        values.put(LEGACY_COMMUNITY_POOL, legacyCommunityPool);
        return values;
    }

    public static CommunityProgressionService get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Tracks earned Reputation for status displays; this does not add to the shared pool. */
    public static void recordReputation(ServerPlayer player, int amount) {
        if (amount <= 0) return;
        record(player.level().getServer(), amount, 0);
    }

    /** Tracks earned Infamy for status displays; this does not add to the shared pool. */
    public static void recordInfamy(ServerPlayer player, int amount) {
        if (amount <= 0) return;
        record(player.level().getServer(), 0, amount);
    }

    /**
     * Contributes the preferred stored social balance to the shared world pool.
     */
    public static boolean contributeInfluence(ServerPlayer player, int amount) {
        if (amount <= 0 || !KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) return false;

        if (availableInfluence(player) < amount || (long)get(player.level().getServer()).pool() + amount > Integer.MAX_VALUE) return false;
        if (KarmaService.rewardAlignment(player) == SocialCurrencyService.Stance.DARK)
            KarmaService.addInfamyPoints(player, -amount, "World contribution");
        else SocialCurrencyService.addReputation(player, -amount, "World contribution");

        CommunityProgressionService data = get(player.level().getServer());
        data.influenceContributed = safeAdd(data.influenceContributed, amount);
        data.setDirty();
        KicHistoryService.recordContribution(player, amount);
        recordPersonalContribution(player, amount);

        KicUiText.success(player);
        return true;
    }

    private static final int[] PERSONAL_MILESTONES = {128, 512, 2048, 8192};
    private static final String[] PERSONAL_TITLES = {"Supporter", "Patron", "Worldbuilder", "Beacon"};
    public static long personalContribution(ServerPlayer player) {
        return KarmaPlayerState.get(player, "personal_world_contribution");
    }
    static int contributionRank(long total) {
        int rank = 0;
        for (int threshold : PERSONAL_MILESTONES) if (total >= threshold) rank++;
        return rank;
    }
    private static void recordPersonalContribution(ServerPlayer player, int amount) {
        long old = personalContribution(player);
        long total = old > Long.MAX_VALUE - amount ? Long.MAX_VALUE : old + amount;
        KarmaPlayerState.set(player, "personal_world_contribution", total);
        if (contributionRank(total) > contributionRank(old)) {
            String message = "Contribution recognition: " + PERSONAL_TITLES[contributionRank(total) - 1] + ".";
            player.sendSystemMessage(Component.literal(message));
            KicHistoryService.recordRecognition(player, message);
        }
    }
    public static String personalContributionSummary(ServerPlayer player) {
        long total = personalContribution(player);
        int rank = contributionRank(total);
        return "Your contributions: " + total + " | " + (rank == 0 ? "No milestone yet" : PERSONAL_TITLES[rank - 1])
            + (rank < PERSONAL_MILESTONES.length ? " | Next: " + PERSONAL_MILESTONES[rank] : " | All milestones reached")
            + " (cosmetic; tracked since this update)";
    }
    public static java.util.List<String> nextStagePreview(ServerPlayer player) {
        int current = stage(player);
        if (current >= MAX_STAGE) return java.util.List.of("Maximum world stage reached.");
        int next = current + 1;
        int cap = next < KarmaService.PERSONAL_KARMA_THRESHOLDS.length
            ? KarmaService.PERSONAL_KARMA_THRESHOLDS[next] : ShrineConfig.current().karmaCap;
        cap = Math.min(cap, ShrineConfig.current().karmaCap);
        int override = KarmaPlayerState.getInt(player, "karma_cap_override");
        if (override > 0) cap = Math.min(cap, override);
        String tier = next <= 1 ? "Early" : next == 2 ? "Mid" : next == 3 ? "Late" : "Endgame";
        String unlocks = KarmaShopConfig.allRewards().stream().filter(r -> r.enabled() && r.unlockStage() == next)
            .map(ShopReward::displayName).distinct().collect(java.util.stream.Collectors.joining(", "));
        return java.util.List.of("Stage " + next + ": Karma cap ±" + cap,
            "Shared stack slots: " + CurseBlessingService.stackSlotLimit(next),
            "Offering / Collector objectives: through " + tier,
            "Shop price increase: " + (next * 0.5) + "% over base (rounded up)",
            "New shop eligibility: " + (unlocks.isBlank() ? "None" : unlocks), "Stock still follows shop rotations.");
    }

    private static int safeAdd(int a, int b) {
        long sum = (long) a + b;
        return (int) Math.clamp(sum, 0L, Integer.MAX_VALUE);
    }

    private static void record(MinecraftServer server, int reputation, int infamy) {
        if (server == null || (reputation <= 0 && infamy <= 0)) return;
        CommunityProgressionService data = get(server);
        if (reputation > 0) data.reputationEarned = safeAdd(data.reputationEarned, reputation);
        if (infamy > 0) data.infamyEarned = safeAdd(data.infamyEarned, infamy);
        data.setDirty();
    }

    public int reputationEarned() { return reputationEarned; }
    public int infamyEarned() { return infamyEarned; }
    public int influenceContributed() { return influenceContributed; }
    public int pool() { return safeAdd(legacyCommunityPool, influenceContributed); }

    public int stage() {
        return KicBalanceRules.stage(pool(), thresholds);
    }

    public static int stageForPool(int pool) {
        int stage = 0;
        for (int i = 0; i < STAGE_THRESHOLDS.length; i++) {
            if (pool < STAGE_THRESHOLDS[i]) break;
            stage = i + 1;
        }
        return stage;
    }

    public int thresholdForStage(int stage) {
        return stage <= 0 ? 0 : thresholds[Math.clamp(stage - 1, 0, MAX_STAGE - 1)];
    }

    public int nextStageThreshold(int stage) {
        return stage < MAX_STAGE
            ? thresholds[Math.clamp(stage, 0, MAX_STAGE - 1)]
            : thresholds[MAX_STAGE - 1];
    }

    public static String contributionCurrency(ServerPlayer player) {
        return KarmaService.rewardAlignment(player) == SocialCurrencyService.Stance.DARK ? "Infamy" : "Reputation";
    }
    public static String contributionReview(ServerPlayer player) {
        var data = get(player.level().getServer());
        return data.pool() + ":" + java.util.Arrays.toString(data.thresholds) + ":" + contributionCurrency(player);
    }
    public static int availableInfluence(ServerPlayer player) {
        return KarmaService.rewardAlignment(player) == SocialCurrencyService.Stance.DARK
            ? KarmaService.getInfamyPoints(player) : SocialCurrencyService.getReputation(player);
    }
    public void setPool(int amount) {
        legacyCommunityPool = Math.max(0, amount);
        influenceContributed = 0;
        setDirty();
    }
    public boolean setThreshold(int stage, int threshold) {
        if (!KicBalanceRules.validThreshold(stage, threshold, thresholds)) return false;
        if (stage == 0) return true;
        thresholds[stage - 1] = threshold;
        setDirty();
        return true;
    }
    public String debugSummary() {
        return "World stage: " + stage() + "/" + MAX_STAGE + "\nCommunity Influence: " + formatInfluence(pool())
            + "\nStage thresholds: " + java.util.Arrays.toString(thresholds);
    }

    public static String formatInfluence(int amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount);
    }

    /** Shop prices rise by 0.5 percentage points per stage, rounded up to whole currency. */
    public static int scaleShopCost(int baseCost, int stage) {
        if (baseCost <= 0) return baseCost;
        int safeStage = Math.max(0, Math.min(MAX_STAGE, stage));
        long scaled = ((long) baseCost * (200 + safeStage) + 199) / 200;
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }

    public static int scaleShopCost(ServerPlayer player, int baseCost) {
        return scaleShopCost(baseCost, stage(player));
    }

    public static int scaleShrineCost(int baseCost, int stage) {
        if (baseCost <= 0) return baseCost;
        int safeStage = Math.clamp(stage, 0, SHRINE_COST_MULTIPLIERS_PERCENT.length - 1);
        return Math.max(1, (int) Math.ceil(baseCost * SHRINE_COST_MULTIPLIERS_PERCENT[safeStage] / 100.0));
    }

    public static int scaleShrineCost(ServerPlayer player, int baseCost) {
        return scaleShrineCost(baseCost, stage(player));
    }

    public static int stage(ServerPlayer player) {
        return get(player.level().getServer()).stage();
    }

    public static String stageLabel(ServerPlayer player) {
        CommunityProgressionService data = get(player.level().getServer());
        return "Community Stage " + data.stage() + "/" + MAX_STAGE;
    }

    public static void sendStageIfChanged(ServerPlayer player, int oldStage) {
        int newStage = stage(player);
        if (newStage != oldStage) {
            player.sendSystemMessage(Component.literal("Community progression reached Stage " + newStage + "."));
        }
    }
}
