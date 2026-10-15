package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Central Quest registry/state facade. Normal Quest availability rotates on the
 * same KIC 12-hour cycle used by the shop. Hunt participation is stored separately.
 *
 * Objective/reward logic is intentionally limited to mechanics already agreed
 * upon; unfinished quest-specific rules are represented here without inventing
 * new rewards or completion conditions.
 */
public final class QuestService {
    public static final int MAX_ACTIVE_QUESTS = 1;
    public static final int TRIALS_PER_CYCLE = 3;
    public static final int CONTRACTS_PER_CYCLE = 3;
    private static final int ACTIVE_STATE_SLOTS = 3;
    public static final long SURVIVOR_TARGET_TICKS = 3L * 60L * 60L * 20L;
    public static final long UNIVERSAL_COOLDOWN_MILLIS = 90L * 60L * 1000L;
    public static final long BLOOD_PRICE_DURATION_MILLIS = 30L * 60L * 1000L;

    private static final String ACTIVE_PREFIX = "quest_active_";
    private static final String STARTED_AT = "quest_started_at";
    private static final String SURVIVOR_TICKS = "quest_survivor_ticks";
    private static final String UNIVERSAL_COOLDOWN_UNTIL = "quest_universal_cooldown_until";
    private static final String QUEST_COOLDOWN_TIME_PREFIX = "quest_cooldown_until_";
    private static final String QUEST_COOLDOWN_CYCLE_PREFIX = "quest_cooldown_cycle_";
    private static final String LAST_ENDED_QUEST = "quest_last_ended_id";
    private static final String LAST_ENDED_PROGRESS = "quest_last_ended_progress";
    private static final String LAST_ENDED_AT = "quest_last_ended_at";
    private static final String LAST_DYNAMIC_REWARD = "quest_last_dynamic_reward";
    private static final String OBJECTIVE_INDEX_PREFIX = "quest_objective_index_";
    private static final String BONUS_PREFIX = "quest_bonus_";
    private static final String BONUS_FAILED_PREFIX = "quest_bonus_failed_";
    private static final String SURVIVOR_REWARD_INTERVALS = "quest_survivor_reward_intervals";
    private static final String DUEL_ACTIVE = "quest_duel_active";
    private static final String DUEL_OPPONENT_MSB = "quest_duel_opponent_msb";
    private static final String DUEL_OPPONENT_LSB = "quest_duel_opponent_lsb";
    private static final String DUEL_PENDING_MSB = "quest_duel_pending_target_msb";
    private static final String DUEL_PENDING_LSB = "quest_duel_pending_target_lsb";
    private static final String DUEL_PENDING_AT = "quest_duel_pending_at";
    private static final String DUEL_INVITE_MSB = "quest_duel_invite_from_msb";
    private static final String DUEL_INVITE_LSB = "quest_duel_invite_from_lsb";
    private static final String DUEL_WON = "quest_duel_won";
    private static final String COURAGE_END_SEEN = "quest_courage_end_seen";
    private static final String COURAGE_ANCIENT_CITY_FOUND = "quest_courage_ancient_city_found";
    private static final String COURAGE_TRIAL_VAULT_OPENED = "quest_courage_trial_vault_opened";
    private static final String COURAGE_FORTRESS_WITH_ROD = "quest_courage_fortress_with_rod";

    public enum Type { TRIAL, CONTRACT }
    public enum EndReason { COMPLETED, FAILED, CANCELLED }

    public enum QuestId {
        ABUNDANCE("abundance", Type.TRIAL, "Trial of Abundance", Items.CHEST,
            "Submit and consume a bulk crop, resource, log or fishing bundle.", "Configurable Karma and Reputation based on objective difficulty."),
        MERCY("mercy", Type.TRIAL, "Trial of Mercy", Items.POPPY,
            "Cure 1 Zombie Villager before the timer expires.", "Configurable Mercy Karma and Reputation rewards."),
        COURAGE("courage", Type.TRIAL, "Trial of Courage", Items.SHIELD,
            "Complete the selected server-tracked exploration objective.", "Standard configurable Trial reward based on objective difficulty."),
        HUNT("hunt", Type.TRIAL, "The Hunt", Items.COMPASS,
            "Participate separately as the Hunted or an eligible Hunter.", "Hunt-specific rewards; participation does not use the normal Quest slot."),
        OFFERING("offering", Type.TRIAL, "Trial of Offering", Items.GOLD_INGOT,
            "Submit and consume the selected accepted items.", "95% of bundle value plus difficulty bonus and signed Karma."),
        SURVIVOR("survivor", Type.TRIAL, "Endurance", Items.CLOCK,
            "Explore and survive during a 12-hour window.", "Movement-gated online time and eligible hostile kills; Untouchable bonus."),
        BLOOD_PRICE("blood_price", Type.CONTRACT, "Blood Price", Items.IRON_SWORD,
            "Make valid kills during the 30-minute active timer.",
            "Configured Infamy per valid kill during the 30-minute timer.", 3),
        EXECUTIONER("executioner", Type.CONTRACT, "The Executioner", Items.NETHERITE_AXE,
            "Kill the selected target.", "Standard configurable Contract reward based on objective difficulty."),
        COLLECTOR("collector", Type.CONTRACT, "The Collector", Items.HOPPER,
            "Submit and consume the selected themed item bundle.", "Standard configurable Contract reward based on objective difficulty."),
        WARDEN("warden", Type.CONTRACT, "The Warden", Items.SCULK_SHRIEKER,
            "Kill a Warden.", "High-difficulty Warden contract reward is configurable."),
        PILGRIMAGE("pilgrimage", Type.TRIAL, "Pilgrimage", Items.COMPASS, "Visit three shrines and return to the first.", "24–64 Reputation and 15 Karma."),
        CHALLENGER("challenger", Type.TRIAL, "Challenger", Items.TRIAL_KEY, "Complete 4–18 distinct Trial Spawners.", "8 social currency and 2 signed Karma per spawner, plus group bonus."),
        DUEL("duel", Type.CONTRACT, "Duel", Items.IRON_SWORD,
            "Explicitly agree to a designated player duel.", "Only the winner receives the configured Duel reward.");

        private final String id;
        private final Type type;
        private final String displayName;
        private final Item icon;
        private final String description;
        private final String rewardDescription;
        private final long cooldownMillis;
        private final int cooldownCycles;

        QuestId(String id, Type type, String displayName, Item icon, String description) {
            this(id, type, displayName, icon, description, "Reward is not finalized.", 0L, 0);
        }

        QuestId(String id, Type type, String displayName, Item icon, String description,
                String rewardDescription) {
            this(id, type, displayName, icon, description, rewardDescription, 0L, 0);
        }

        QuestId(String id, Type type, String displayName, Item icon, String description, int cooldownCycles) {
            this(id, type, displayName, icon, description, "Reward is not finalized.", 0L, cooldownCycles);
        }

        QuestId(String id, Type type, String displayName, Item icon, String description,
                String rewardDescription, int cooldownCycles) {
            this(id, type, displayName, icon, description, rewardDescription, 0L, cooldownCycles);
        }

        QuestId(String id, Type type, String displayName, Item icon, String description,
                long cooldownMillis, int cooldownCycles) {
            this(id, type, displayName, icon, description, "Reward is not finalized.", cooldownMillis, cooldownCycles);
        }

        QuestId(String id, Type type, String displayName, Item icon, String description,
                String rewardDescription, long cooldownMillis, int cooldownCycles) {
            this.id = id;
            this.type = type;
            this.displayName = displayName;
            this.icon = icon;
            this.description = description;
            this.rewardDescription = rewardDescription;
            this.cooldownMillis = Math.max(0L, cooldownMillis);
            this.cooldownCycles = Math.max(0, cooldownCycles);
        }

        public String id() { return id; }
        public Type type() { return type; }
        public String displayName() { return displayName; }
        public Item icon() { return icon; }
        public String description() { return description; }
        public String rewardDescription() { return rewardDescription; }
        public long cooldownMillis() { return cooldownMillis; }
        public int cooldownCycles() { return cooldownCycles; }

        public static QuestId byId(String id) {
            if (id == null) return null;
            if (id.equals("endurance")) return SURVIVOR;
            for (QuestId value : values()) if (value.id.equals(id)) return value;
            return null;
        }
    }

    private QuestService() {}

    public static List<QuestId> currentTrials(ServerPlayer player) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) return List.of();
        return currentPool(player, Type.TRIAL);
    }

    public static List<QuestId> currentContracts(ServerPlayer player) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)) return List.of();
        return currentPool(player, Type.CONTRACT);
    }

    private static List<QuestId> currentPool(ServerPlayer player, Type type) {
        long cycle = KicCycle.currentCycleId();
        MinecraftServer server = player.level().getServer();
        KicRotationState rotation = server == null ? null : KicRotationState.get(server);
        List<String> stored = rotation == null ? List.of() : type == Type.TRIAL
            ? rotation.trialQuestIdsForCycle(cycle) : rotation.contractQuestIdsForCycle(cycle);
        List<QuestId> restored = fromQuestIds(stored, type);
        if (restored.size() == countFor(type)) return restored;

        List<QuestId> candidates = new ArrayList<>();
        for (QuestId quest : QuestId.values()) {
            if (QuestRevamp.inPool(quest, type) && quest != QuestId.HUNT && quest != QuestId.DUEL) candidates.add(quest);
        }
        String scope = type == Type.TRIAL ? "quests.trials" : "quests.contracts";
        int count = countFor(type);
        List<QuestId> selected = KicCycle.select(candidates, cycle, scope,
            Math.min(count, candidates.size()), QuestId::id, quest -> 1);
        if (rotation != null && selected.size() == count) {
            List<String> previousIds = type == Type.TRIAL
                ? rotation.trialQuestIdsForCycle(cycle - 1L) : rotation.contractQuestIdsForCycle(cycle - 1L);
            List<QuestId> previous = fromQuestIds(previousIds, type);
            if (previous.size() == selected.size() && selected.containsAll(previous)) {
                List<QuestId> chosen = selected;
                List<QuestId> alternatives = candidates.stream()
                    .filter(quest -> !chosen.contains(quest))
                    .toList();
                int replaceIndex = 0;
                if (!alternatives.isEmpty() && replaceIndex >= 0) {
                    QuestId replacement = KicCycle.select(alternatives, cycle, scope + ".cycle-change",
                        1, QuestId::id, quest -> 1).getFirst();
                    selected = new ArrayList<>(selected);
                    selected.set(replaceIndex, replacement);
                }
            }
        }
        if (rotation != null) {
            List<String> ids = selected.stream().map(QuestId::id).toList();
            if (type == Type.TRIAL) rotation.recordTrialQuestRotation(cycle, ids);
            else rotation.recordContractQuestRotation(cycle, ids);
        }
        return List.copyOf(selected);
    }

    private static List<QuestId> fromQuestIds(List<String> ids, Type type) {
        List<QuestId> result = new ArrayList<>();
        for (String id : ids) {
            QuestId quest = QuestId.byId(id);
            if (quest != null && QuestRevamp.inPool(quest, type) && quest != QuestId.HUNT && quest != QuestId.DUEL
                    && !result.contains(quest)) result.add(quest);
        }
        return List.copyOf(result);
    }

    private static int countFor(Type type) {
        return type == Type.TRIAL ? TRIALS_PER_CYCLE : CONTRACTS_PER_CYCLE;
    }

    public static List<QuestId> currentAvailable(ServerPlayer player) {
        LinkedHashSet<QuestId> result = new LinkedHashSet<>(currentTrials(player));
        result.addAll(currentContracts(player));
        return List.copyOf(result);
    }

    /** Returns only the normal rotating Quest slot. Hunt participation is separate. */
    public static List<QuestId> active(ServerPlayer player) {
        reconcile(player);
        LinkedHashSet<QuestId> result = new LinkedHashSet<>();
        for (int i = 0; i < MAX_ACTIVE_QUESTS; i++) {
            QuestId parsed = QuestId.byId(stringState(player, ACTIVE_PREFIX + i));
            if (parsed != null) result.add(parsed);
        }
        if (result.contains(QuestId.BLOOD_PRICE) && KarmaPlayerState.get(player, STARTED_AT) <= 0L) {
            KarmaPlayerState.set(player, STARTED_AT, System.currentTimeMillis());
        }
        return List.copyOf(result);
    }

    public static int activeCount(ServerPlayer player) {
        return active(player).size();
    }

    public static boolean isActive(ServerPlayer player, QuestId quest) {
        if (quest == QuestId.HUNT) {
            MinecraftServer server = player.level().getServer();
            return isHuntParticipant(server, player);
        }
        return active(player).contains(quest);
    }

    private static boolean isHuntParticipant(MinecraftServer server, ServerPlayer player) {
        return server != null && (HuntService.isHunted(server, player)
            || HuntState.get(server).findHuntByHunter(player.getUUID()) != null);
    }

    public static boolean debugAssign(ServerPlayer player,QuestId quest,String objective) {
        if(quest==null||quest==QuestId.DUEL||quest==QuestId.HUNT||activeQuest(player)!=null)return false;
        List<QuestObjectiveCatalog.Objective> choices=QuestObjectiveCatalog.objectives(quest);
        int index=-1;for(int i=0;i<choices.size();i++)if(choices.get(i).key().equals(objective))index=i;
        if(index<0)return false;
        KarmaPlayerState.set(player,OBJECTIVE_INDEX_PREFIX+quest.id(),index+1L);
        QuestRevamp.put(player,"assign_override",1);
        try {boolean ok=accept(player,quest);if(ok)QuestRevamp.put(player,"debug_hold",1);return ok;}
        finally {QuestRevamp.put(player,"assign_override",0);if(!isStoredActive(player,quest))KarmaPlayerState.clear(player,OBJECTIVE_INDEX_PREFIX+quest.id());}
    }

    public static boolean canAccept(ServerPlayer player, QuestId quest) {
        if (quest == null || quest == QuestId.DUEL) return false;
        if (quest == QuestId.HUNT) return canBecomeHunted(player) || canJoinHunt(player);
        if (quest != QuestId.DUEL && !KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) return false;
        if (quest == QuestId.DUEL && !KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) return false;
        if (QuestRevamp.side(player, quest) > 0 && quest != QuestId.HUNT
                && !KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) return false;
        if (QuestRevamp.side(player, quest) < 0 && quest != QuestId.DUEL
                && !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)) return false;
        if (quest == QuestId.OFFERING && QuestObjectiveCatalog.availableObjectiveIndexes(
                quest, CommunityProgressionService.stage(player)).isEmpty()) return false;
        if (!canStartQuest(player, quest)) return false;
        if (!alignmentBlockReason(player, quest).isBlank()) return false;
        if (quest == QuestId.HUNT) return canBecomeHunted(player) || canJoinHunt(player);
        if (quest == QuestId.DUEL) return true;
        if (!isQuestAvailableForAlignment(player, quest)) return false;
        if (QuestRevamp.side(player, quest) > 0) return CurseBlessingService.canUseLightShrine(player);
        return CurseBlessingService.canUseDarkShrine(player);
    }

    private static boolean canStartQuest(ServerPlayer player, QuestId quest) {
        return quest != QuestId.HUNT && activeCount(player) < MAX_ACTIVE_QUESTS
            && QuestRevamp.available(player,quest,activeObjective(player,quest));
    }

    private static boolean questFeatureEnabled(ServerPlayer player, QuestId quest) {
        if (player == null || quest == null) return false;
        if (quest == QuestId.DUEL) return false;
        if (quest == QuestId.HUNT) return KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED);
        if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) return false;
        return QuestRevamp.side(player,quest) > 0
            ? KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)
            : KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED);
    }

    public static boolean canAccessPool(ServerPlayer player, Type pool) {
        return switch (SocialCurrencyService.getStance(player)) {
            case LIGHT -> pool == Type.TRIAL;
            case DARK -> pool == Type.CONTRACT;
            case NEUTRAL -> false;
        };
    }

    /** Player-facing alignment explanation. Hunt is role-based and is intentionally exempt. */
    public static String alignmentBlockReason(ServerPlayer player, QuestId quest) {
        if (player == null || quest == null) return "";
        if (quest == QuestId.DUEL) return "Duels are currently unavailable.";
        if (SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.NEUTRAL) return "Neutral players cannot access Trials or Contracts.";
        if (quest == QuestId.HUNT) return "";
        return QuestService.alignmentCode(player)==QuestRevamp.side(player,quest)?"":"Quest paused: return to its accepted alignment. Timers continue.";
    }

    private static boolean isQuestAvailableForAlignment(ServerPlayer player, QuestId quest) {
        if (quest == QuestId.DUEL || !alignmentBlockReason(player,quest).isBlank()) return false;
        if (quest == QuestId.HUNT) return canBecomeHunted(player) || canJoinHunt(player);
        return QuestRevamp.get(player,"assign_override")==1 || (QuestRevamp.side(player,quest)>0?currentTrials(player):currentContracts(player)).contains(quest);
    }

    public static boolean canBecomeHunted(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;
        int maxHunts = Math.max(0, server.getGameRules().get(KarmaGameRules.MAX_ONGOING_HUNTS));
        HuntState state = HuntState.get(server);
        return KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
            && KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)
            && SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.LIGHT
            && CurseBlessingService.canUseLightShrine(player)
            && !isHuntParticipant(server, player)
            && state.huntedCooldownUntil(player.getUUID()) <= HuntService.now()
            && maxHunts > 0 && state.activeHuntCount() < maxHunts;
    }

    public static boolean canJoinHunt(ServerPlayer player) {
        if (alignmentCode(player)==0) return false;
        MinecraftServer server = player.level().getServer();
        if (server == null || !KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)
                || !CurseBlessingService.canUseDarkShrine(player)
                || isHuntParticipant(server, player) || !HuntService.hasActiveHunt(server)) return false;
        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        return HuntState.get(server).activeHunts().stream().anyMatch(hunt -> {
            int huntedAlignment = hunt.huntedAlignment();
            if (huntedAlignment == 0) {
                ServerPlayer hunted = server.getPlayerList().getPlayer(hunt.hunted());
                if (hunted != null) huntedAlignment = alignmentCode(hunted);
            }
            return huntedAlignment == 1 && (stance == SocialCurrencyService.Stance.DARK
                    || stance == SocialCurrencyService.Stance.NEUTRAL)
                || huntedAlignment == -1 && (stance == SocialCurrencyService.Stance.LIGHT
                    || stance == SocialCurrencyService.Stance.NEUTRAL);
        });
    }

    public static List<HuntState.HuntRecord> availableHuntsForHunter(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null || !KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)
                || !CurseBlessingService.canUseDarkShrine(player)
                || isHuntParticipant(server, player)) return List.of();
        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        return HuntState.get(server).activeHunts().stream().filter(hunt -> {
            int huntedAlignment = hunt.huntedAlignment();
            if (huntedAlignment == 0) {
                ServerPlayer hunted = server.getPlayerList().getPlayer(hunt.hunted());
                if (hunted != null) huntedAlignment = alignmentCode(hunted);
            }
            return huntedAlignment == 1 && (stance == SocialCurrencyService.Stance.DARK
                    || stance == SocialCurrencyService.Stance.NEUTRAL)
                || huntedAlignment == -1 && (stance == SocialCurrencyService.Stance.LIGHT
                    || stance == SocialCurrencyService.Stance.NEUTRAL);
        }).toList();
    }

    public static boolean accept(ServerPlayer player, QuestId quest) {
        if (quest == QuestId.DUEL && !KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
            player.sendSystemMessage(Component.literal("Duels are currently disabled."));
            return false;
        }
        if (quest == QuestId.HUNT) {
            if (!canAccept(player, quest)) {
                player.sendSystemMessage(Component.literal(acceptanceBlockReason(player, quest)));
                return false;
            }
            if (canBecomeHunted(player)) {
                return HuntService.acceptHunt(player.level().getServer(), player);
            }
            player.sendSystemMessage(Component.literal("Select an active Hunt to join as a Hunter."));
            return false;
        }

        if (!canAccept(player, quest)) {
            player.sendSystemMessage(Component.literal(acceptanceBlockReason(player, quest)));
            return false;
        }

        if (quest == QuestId.MERCY) {
            if (!LightShrineService.startQuestTrial(player, LightShrineService.TRIAL_MERCY)) return false;
        }
        KarmaPlayerState.set(player, OBJECTIVE_INDEX_PREFIX + quest.id(), selectedObjectiveIndex(player, quest) + 1L);
        KarmaPlayerState.set(player, BONUS_PREFIX + quest.id(), selectedBonus(player, quest) + 1L);
        KarmaPlayerState.clear(player, BONUS_FAILED_PREFIX + quest.id());
        if (quest == QuestId.DUEL) KarmaPlayerState.clear(player, DUEL_WON);
        storeActive(player, quest);
        KarmaPlayerState.set(player, STARTED_AT, System.currentTimeMillis());
        KarmaPlayerState.clear(player, progressKey(quest));
        KarmaPlayerState.clear(player, LAST_DYNAMIC_REWARD);
        if (quest == QuestId.SURVIVOR) KarmaPlayerState.clear(player, SURVIVOR_REWARD_INTERVALS);
        QuestRevamp.start(player, quest);
        initializeItemBaselines(player, quest);
        if (quest == QuestId.COURAGE) {
            KarmaPlayerState.clear(player, "courage_item_baseline");
            KarmaPlayerState.clear(player, "courage_inside");
            KarmaPlayerState.clear(player, COURAGE_END_SEEN);
            KarmaPlayerState.clear(player, COURAGE_ANCIENT_CITY_FOUND);
            KarmaPlayerState.clear(player, COURAGE_TRIAL_VAULT_OPENED);
            KarmaPlayerState.clear(player, COURAGE_FORTRESS_WITH_ROD);
        }
        enforceBonusEquipment(player, quest);
        player.sendSystemMessage(Component.literal(quest.displayName() + " accepted. Goal: " + activeObjective(player, quest).description()));
        if (quest == QuestId.DUEL) {
            player.sendSystemMessage(Component.literal("Invite an opponent with /quest duel invite <player>. The Duel starts only after they consent."));
        }
        refreshOpenMenu(player);
        return true;
    }

    public static String acceptanceBlockReason(ServerPlayer player, QuestId quest) {
        if (quest == null) return "That quest does not exist.";
        if (quest == QuestId.DUEL) return "Duels are currently unavailable.";
        if (!QuestRevamp.available(player,quest,activeObjective(player,quest))) return quest==QuestId.SURVIVOR?"Endurance may be started once per 12 hours.":"This objective has reached its completion limit for this cycle.";
        if (quest == QuestId.DUEL && !KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
            return "Duels are currently disabled by the duels_enabled gamerule.";
        }
        if (quest != QuestId.DUEL && !KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)) {
            return "Quests are disabled by the quests_enabled gamerule.";
        }
        if (quest == QuestId.HUNT) {
            if (isActive(player, QuestId.HUNT)) return "You are already participating in The Hunt.";
            if (canBecomeHunted(player)) return "Choose whether to become the Hunted.";
            if (canJoinHunt(player)) return "Choose an active Hunt to join as a Hunter.";
            if (SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.LIGHT) {
                if (!KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) return "Hunted participation is disabled by trials_enabled.";
                long remaining = huntedCooldownRemaining(player);
                if (remaining > 0L) return "Hunted participation cooldown: " + formatDuration(remaining) + " remaining.";
                MinecraftServer server = player.level().getServer();
                if (server != null && HuntState.get(server).activeHuntCount()
                        >= Math.max(0, server.getGameRules().get(KarmaGameRules.MAX_ONGOING_HUNTS))) {
                    return "The maximum number of ongoing Hunts has been reached.";
                }
            }
            if (!KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)) return "Hunter participation is disabled by contracts_enabled.";
            if (!CurseBlessingService.canUseDarkShrine(player)) return "Requires Dark Shrine access to join as a Hunter.";
            return "Hunt role requirements are not met.";
        }
        if (QuestRevamp.side(player, quest) > 0 && quest != QuestId.HUNT) {
            if (!KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) return "Trials are disabled for this world.";
        }
        if (QuestRevamp.side(player, quest) < 0 && quest != QuestId.DUEL
                && !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)) {
            return "Contracts are disabled for this world.";
        }
        if (quest == QuestId.COURAGE
                && player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return "Courage must begin in the Overworld so the End trip can be tracked reliably.";
        }
        if (quest == QuestId.OFFERING && QuestObjectiveCatalog.availableObjectiveIndexes(
                quest, CommunityProgressionService.stage(player)).isEmpty()) {
            return "No Offering objective meets the configured item values and 32-value minimum.";
        }
        String alignmentBlock = alignmentBlockReason(player, quest);
        if (!alignmentBlock.isBlank()) return alignmentBlock;
        if (activeCount(player) >= MAX_ACTIVE_QUESTS) return activeLimitMessage();
        long universalRemaining = universalCooldownRemaining(player);
        String specificCooldown = specificCooldownStatus(player, quest);
        if (universalRemaining > 0L || !specificCooldown.isBlank()) {
            List<String> cooldowns = new ArrayList<>();
            if (universalRemaining > 0L) cooldowns.add("Cooldown: " + formatDuration(universalRemaining));
            if (!specificCooldown.isBlank()) cooldowns.add(specificCooldown);
            return String.join("; ", cooldowns) + ".";
        }
        if (!isQuestAvailableForAlignment(player, quest)) return "That quest is not available to your alignment in this cycle.";
        if (quest == QuestId.HUNT) return "Hunt role requirements are not met.";
        if (QuestRevamp.side(player, quest) > 0 && !CurseBlessingService.canUseLightShrine(player)) return "Requires Light Shrine access.";
        if (QuestRevamp.side(player, quest) < 0 && quest != QuestId.DUEL
                && !CurseBlessingService.canUseDarkShrine(player)) return "Requires Dark Shrine access.";
        return "That quest cannot be accepted right now.";
    }

    public static boolean joinHunt(ServerPlayer player, String huntedName) {
        if (!canJoinHunt(player)) {
            player.sendSystemMessage(Component.literal(acceptanceBlockReason(player, QuestId.HUNT)));
            return false;
        }
        boolean targetAvailable = availableHuntsForHunter(player).stream().anyMatch(hunt -> {
            ServerPlayer hunted = player.level().getServer().getPlayerList().getPlayer(hunt.hunted());
            String name = hunted == null ? hunt.huntedName() : hunted.getName().getString();
            return name.equalsIgnoreCase(huntedName) || hunt.hunted().toString().equalsIgnoreCase(huntedName);
        });
        if (!targetAvailable) {
            player.sendSystemMessage(Component.literal("That Hunt is not available to your alignment."));
            return false;
        }
        boolean joined = HuntService.joinHunter(player.level().getServer(), player, huntedName);
        if (joined) player.sendSystemMessage(Component.literal("You joined The Hunt as a Hunter. Goal: track and defeat " + huntedName + " before their Hunt ends."));
        return joined;
    }

    public static String status(ServerPlayer player, QuestId quest) {
        if (quest == null) return "";
        String revamped = QuestRevamp.status(player,quest);
        if (!revamped.isBlank()) return revamped;
        MinecraftServer server = player.level().getServer();
        if (quest == QuestId.HUNT && server != null) return HuntService.status(server);
        QuestObjectiveCatalog.Objective objective = activeObjective(player, quest);
        if (objective.kind() == QuestObjectiveCatalog.Kind.COLLECT || objective.kind() == QuestObjectiveCatalog.Kind.OFFER) {
            return collectionProgress(player, quest, objective);
        }
        if (objective.kind() == QuestObjectiveCatalog.Kind.KILL && quest != QuestId.BLOOD_PRICE) {
            return "Valid target kills: " + KarmaPlayerState.get(player, progressKey(quest)) + "/" + objective.targetCount();
        }
        if (quest == QuestId.BLOOD_PRICE) {
            long started = KarmaPlayerState.get(player, STARTED_AT);
            long remaining = Math.max(0L, BLOOD_PRICE_DURATION_MILLIS - (System.currentTimeMillis() - started));
            return "Valid hostile kills: " + KarmaPlayerState.get(player, progressKey(quest))
                + " | Time remaining: " + formatDuration(remaining);
        }
        if (quest == QuestId.DUEL) {
            String opponent = duelOpponentName(player);
            return opponent.isBlank() ? "Waiting for an opponent to explicitly consent. Use /quest duel invite <player>."
                : "Active Duel against " + opponent + ". Only a kill between the designated participants counts.";
        }
        if (quest == QuestId.COURAGE) {
            return KarmaPlayerState.get(player, courageFlag(objective.key())) == 1L
                ? "Expedition objective secured. Return alive within 8 blocks of a shrine core with your item. Items are kept."
                : objective.description() + " | Expedition objective not yet secured.";
        }
        if (quest == QuestId.MERCY) {
            return LightShrineService.hasActiveMercyTrial(player)
                ? "Cure 1 Zombie Villager. Time remaining: "
                    + formatDuration(LightShrineService.trialRemainingMillis(player))
                : "Cure 1 Zombie Villager: apply Weakness, then use a Golden Apple and wait for conversion.";
        }
        return objective.description();
    }

    public static ServerPlayer pendingDuelChallenger(ServerPlayer invitee) {
        return null;
    }

    public static boolean inviteDuel(ServerPlayer challenger, ServerPlayer opponent) {
        return false;
    }

    public static boolean acceptDuel(ServerPlayer opponent, ServerPlayer challenger) {
        return false;
    }

    public static boolean declineDuel(ServerPlayer invitee, ServerPlayer challenger) {
        if (invitee == null || challenger == null || pendingDuelChallenger(invitee) != challenger) return false;
        clearDuelInvitePair(challenger, invitee);
        invitee.sendSystemMessage(Component.literal("You declined the Duel invitation."));
        challenger.sendSystemMessage(Component.literal(invitee.getName().getString() + " declined the Duel."));
        refreshOpenMenu(invitee);
        return true;
    }

    private static void setDuelOpponent(ServerPlayer player, java.util.UUID opponent) {
        KarmaPlayerState.set(player, DUEL_OPPONENT_MSB, opponent.getMostSignificantBits());
        KarmaPlayerState.set(player, DUEL_OPPONENT_LSB, opponent.getLeastSignificantBits());
    }

    private static java.util.UUID duelOpponent(ServerPlayer player) {
        long most = KarmaPlayerState.get(player, DUEL_OPPONENT_MSB);
        long least = KarmaPlayerState.get(player, DUEL_OPPONENT_LSB);
        return most == 0L && least == 0L ? null : new java.util.UUID(most, least);
    }

    private static String duelOpponentName(ServerPlayer player) {
        java.util.UUID opponentId = duelOpponent(player);
        if (opponentId == null) return "";
        MinecraftServer server = player.level().getServer();
        ServerPlayer opponent = server == null ? null : server.getPlayerList().getPlayer(opponentId);
        return opponent == null ? opponentId.toString() : opponent.getName().getString();
    }

    private static void clearDuelInvitePair(ServerPlayer challenger, ServerPlayer invitee) {
        KarmaPlayerState.clear(challenger, DUEL_PENDING_MSB);
        KarmaPlayerState.clear(challenger, DUEL_PENDING_LSB);
        KarmaPlayerState.clear(challenger, DUEL_PENDING_AT);
        KarmaPlayerState.clear(invitee, DUEL_INVITE_MSB);
        KarmaPlayerState.clear(invitee, DUEL_INVITE_LSB);
    }

    private static void clearDuelBindings(ServerPlayer player) {
        KarmaPlayerState.clear(player, DUEL_ACTIVE);
        KarmaPlayerState.clear(player, DUEL_OPPONENT_MSB);
        KarmaPlayerState.clear(player, DUEL_OPPONENT_LSB);
        KarmaPlayerState.clear(player, DUEL_PENDING_MSB);
        KarmaPlayerState.clear(player, DUEL_PENDING_LSB);
        KarmaPlayerState.clear(player, DUEL_PENDING_AT);
        KarmaPlayerState.clear(player, DUEL_INVITE_MSB);
        KarmaPlayerState.clear(player, DUEL_INVITE_LSB);
        KarmaPlayerState.clear(player, DUEL_WON);
    }

    private static void resolveDuel(ServerPlayer winner, ServerPlayer loser) {
        clearDuelBindings(winner);
        clearDuelBindings(loser);
        KarmaPlayerState.set(winner, DUEL_WON, 1L);
        complete(winner, QuestId.DUEL);
        fail(loser, QuestId.DUEL);
        winner.sendSystemMessage(Component.literal("You won the designated Duel."));
        loser.sendSystemMessage(Component.literal("You lost the designated Duel. No contract reward was granted."));
    }

    private static boolean cancelDuel(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        java.util.UUID opponentId = duelOpponent(player);
        ServerPlayer opponent = server == null || opponentId == null ? null : server.getPlayerList().getPlayer(opponentId);
        java.util.UUID pendingTargetId = new java.util.UUID(
            KarmaPlayerState.get(player, DUEL_PENDING_MSB), KarmaPlayerState.get(player, DUEL_PENDING_LSB));
        ServerPlayer pendingTarget = server == null ? null : server.getPlayerList().getPlayer(pendingTargetId);
        if (pendingTarget != null) clearDuelInvitePair(player, pendingTarget);
        clearDuelBindings(player);
        boolean cancelled = finishActive(player, QuestId.DUEL, EndReason.CANCELLED, null);
        if (opponent != null && activeQuest(opponent) == QuestId.DUEL) {
            clearDuelBindings(opponent);
            finishActive(opponent, QuestId.DUEL, EndReason.CANCELLED, null);
            opponent.sendSystemMessage(Component.literal("The designated Duel was cancelled. No reward was granted."));
        }
        player.sendSystemMessage(Component.literal("The Duel was cancelled. No reward was granted."));
        return cancelled;
    }

    private static String itemHandlingDescription(QuestId quest) {
        return QuestRevamp.submission(quest)?" Already-owned items count. Submit explicitly; confirmation consumes the full bundle.":"";
    }

    public static String objectiveDescription(QuestId quest) {
        if (quest == null) return "No objective is registered.";
        if (quest == QuestId.MERCY) return LightShrineService.mercyQuestObjective();
        return objectiveForIndex(quest, selectedObjectiveIndex(null, quest)).displayDescription() + itemHandlingDescription(quest);
    }

    public static String objectiveDescription(ServerPlayer player, QuestId quest) {
        if (quest == null) return "No objective is registered.";
        if (quest == QuestId.MERCY) return LightShrineService.mercyQuestObjective();
        return activeObjective(player, quest).displayDescription() + itemHandlingDescription(quest);
    }

    public static String rewardDescription(QuestId quest) {
        if (quest == null) return "No reward is registered.";
        if (quest == QuestId.MERCY) return LightShrineService.mercyQuestRewardDescription();
        if (quest == QuestId.HUNT) {
            HuntConfig config = HuntConfig.current();
            return "Existing Hunt rules: Hunters receive " + config.hunterRewardInfamy
                + " Infamy; the Hunted receives " + config.huntedBaseRewardInfamy
                + " base Infamy plus " + config.huntedRewardPerActiveHourInfamy + " per active hour.";
        }
        return rewardDescription(null, quest);
    }

    public static String rewardDescription(ServerPlayer player, QuestId quest) {
        if (quest == null) return "No reward is registered.";
        if (quest == QuestId.HUNT) {
            HuntConfig config = HuntConfig.current();
            return "Hunters receive " + config.hunterRewardInfamy + " Infamy; the Hunted receives "
                + config.huntedBaseRewardInfamy + " base Infamy plus "
                + config.huntedRewardPerActiveHourInfamy + " per active hour, and +8 Reputation per active 30 minutes.";
        }
        QuestObjectiveCatalog.Objective objective = player == null
            ? objectiveForIndex(quest, 0) : activeObjective(player, quest);
        if (QuestRevamp.submission(quest)||quest==QuestId.SURVIVOR||quest==QuestId.CHALLENGER||quest==QuestId.PILGRIMAGE) {
            String preview=QuestRevamp.preview(player,quest,objective);
            if(quest!=QuestId.SURVIVOR && selectedBonus(player,quest)>0) {
                QuestConfig cfg=QuestConfig.current();boolean light=QuestRevamp.side(player,quest)>0;
                int amount=selectedBonus(player,quest)==1?(light?cfg.bonusUntouchableReputation:cfg.bonusUntouchableInfamy):(light?cfg.bonusSticksAndStonesReputation:cfg.bonusSticksAndStonesInfamy);
                preview+="; modifier bonus +"+amount+(light?" Reputation":" Infamy");
            }
            return preview;
        }
        String base;
        QuestConfig config = QuestConfig.current();
        if (quest == QuestId.MERCY) {
            base = LightShrineService.mercyQuestRewardDescription();
        } else if (quest == QuestId.BLOOD_PRICE) {
            base = "Dynamic: +" + config.bloodPriceInfamyPerKill + " Infamy per valid kill during the 30-minute timer (maximum " + config.bloodPriceMaxInfamy + " Infamy before bonuses).";
        } else if (quest == QuestId.WARDEN) {
            base = signedReward(config.wardenKarma, "Karma") + ", +" + config.wardenInfamy + " Infamy.";
        } else if (quest == QuestId.OFFERING) {
            double gross = 0;
            for (QuestObjectiveCatalog.Requirement requirement : objective.requirements()) {
                gross = Math.min(Integer.MAX_VALUE, gross + KarmaOfferingConfig.socialPerItem(requirement.item(),
                    player == null ? 0 : CommunityProgressionService.stage(player)) * requirement.count());
            }
            int net = QuestObjectiveCatalog.fixedOfferingNet(objective) >= 0
                ? QuestObjectiveCatalog.fixedOfferingNet(objective) : SocialCurrencyService.offeringNet(gross);
            String proceeds = switch (player == null ? SocialCurrencyService.Stance.LIGHT : KarmaService.rewardAlignment(player)) {
                case LIGHT -> "+" + net + " Reputation";
                case DARK -> "+" + net + " Infamy";
                case NEUTRAL -> net + " Neutral Influence";
            };
            base = proceeds + " from the existing Shrine offering transaction, plus +"
                + config.trialKarmaRate(quest) * objective.difficulty() + " Karma.";
        } else if (quest == QuestId.MERCY) {
            base = LightShrineService.mercyQuestRewardDescription();
        } else if (quest.type() == Type.TRIAL) {
            base = "+" + config.trialKarmaRate(quest) * objective.difficulty() + " Karma and +"
                + (quest == QuestId.ABUNDANCE ? config.abundanceReputationPerDifficulty : config.trialReputationPerDifficulty) * objective.difficulty() + " Reputation.";
        } else {
            base = signedReward(config.contractKarmaRate(quest) * objective.difficulty(), "Karma")
                + ", +" + contractInfamyReward(quest, objective) + " Infamy.";
        }
        long bonus = selectedBonus(player, quest);
        String bonusCurrency = quest.type() == Type.TRIAL ? "Reputation" : "Infamy";
        int untouchableBonus = quest.type() == Type.TRIAL ? config.bonusUntouchableReputation : config.bonusUntouchableInfamy;
        int equipmentBonus = quest.type() == Type.TRIAL ? config.bonusSticksAndStonesReputation : config.bonusSticksAndStonesInfamy;
        if (bonus == 1) base += " Bonus: +" + untouchableBonus + " " + bonusCurrency + ".";
        else if (bonus == 2) base += " Bonus: +" + equipmentBonus + " " + bonusCurrency + ".";
        return base;
    }

    private static String signedReward(int amount, String currency) {
        return (amount > 0 ? "+" : "") + amount + " " + currency;
    }

    public static QuestObjectiveCatalog.Objective activeObjective(ServerPlayer player, QuestId quest) {
        if (quest == null) {
            return new QuestObjectiveCatalog.Objective("none", "No objective is active.", 1,
                QuestObjectiveCatalog.Kind.EXISTING, List.of(), null, 1);
        }
        List<QuestObjectiveCatalog.Objective> choices = QuestObjectiveCatalog.objectives(quest);
        if (choices.isEmpty()) return new QuestObjectiveCatalog.Objective("none", quest.description(), 1,
            QuestObjectiveCatalog.Kind.EXISTING, List.of(), null, 1);
        return choices.get(Math.clamp(selectedObjectiveIndex(player, quest), 0, choices.size() - 1));
    }

    private static QuestObjectiveCatalog.Objective objectiveForIndex(QuestId quest, int index) {
        if (quest == null) return new QuestObjectiveCatalog.Objective("none", "No objective is active.", 1,
            QuestObjectiveCatalog.Kind.EXISTING, List.of(), null, 1);
        List<QuestObjectiveCatalog.Objective> choices = QuestObjectiveCatalog.objectives(quest);
        if (choices.isEmpty()) return new QuestObjectiveCatalog.Objective("none", quest.description(), 1,
            QuestObjectiveCatalog.Kind.EXISTING, List.of(), null, 1);
        return choices.get(Math.clamp(index, 0, choices.size() - 1));
    }

    public static String rerollBlocker(ServerPlayer player, QuestId quest) {
        return "Rerolls removed; limits apply to each individual objective.";
    }
    public static boolean rerollObjective(ServerPlayer player, QuestId quest) {
        return false;
    }
    public static void recognizeCompletion(ServerPlayer player, QuestId quest) {
            if (KarmaPlayerState.get(player, "quest_first_" + quest.id()) == 0L) {
                KarmaPlayerState.set(player, "quest_first_" + quest.id(), 1L);
                String recognition = "First completion: " + quest.displayName() + ".";
                KicHistoryService.recordRecognition(player, recognition);
                player.sendSystemMessage(Component.literal(recognition));
            }
    }
    public static java.util.List<String> recognition(ServerPlayer player) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (QuestId quest : QuestId.values()) if (KarmaPlayerState.get(player, "quest_first_" + quest.id()) != 0L)
            lines.add("First completion: " + quest.displayName());
        lines.add(CommunityProgressionService.personalContributionSummary(player));
        return lines;
    }

    private static int selectedObjectiveIndex(ServerPlayer player, QuestId quest) {
        if (quest == null) return 0;
        List<QuestObjectiveCatalog.Objective> choices = QuestObjectiveCatalog.objectives(quest);
        if (choices.size() <= 1) return 0;
        List<Integer> available = QuestObjectiveCatalog.availableObjectiveIndexes(
            quest, player == null ? 0 : CommunityProgressionService.stage(player));
        if (available.isEmpty()) return 0;
        if (player == null) return available.getFirst();
        String key = OBJECTIVE_INDEX_PREFIX + quest.id();
        long stored = KarmaPlayerState.get(player, key);
        if (stored > 0L) return (int) Math.clamp(stored - 1L, 0L, choices.size() - 1L);
        boolean active = isStoredActive(player, quest);
        long anchor = active ? KarmaPlayerState.get(player, STARTED_AT) : KicCycle.currentCycleId();
        long seed = player.getUUID().getMostSignificantBits() ^ Long.rotateLeft(player.getUUID().getLeastSignificantBits(), 13)
            ^ anchor ^ ((long) quest.ordinal() * 0x9E3779B97F4A7C15L);
        int index = available.get((int) Math.floorMod(seed, available.size()));
        if (!active) {
            int first=available.indexOf(index);
            for(int n=0;n<available.size();n++) {
                int candidate=available.get((first+n)%available.size());
                if(QuestRevamp.available(player,quest,choices.get(candidate))){index=candidate;break;}
            }
        }
        if (active) KarmaPlayerState.set(player, key, index + 1L);
        return index;
    }

    private static long selectedBonus(ServerPlayer player, QuestId quest) {
        if (player == null || quest == null || quest == QuestId.HUNT || quest == QuestId.DUEL) return 0L;
        if (!KarmaGameRules.enabled(player, KarmaGameRules.BONUS_OBJECTIVES_ENABLED)) return 0L;
        if (quest == QuestId.SURVIVOR) return 1L;
        if (QuestRevamp.submission(quest) || quest==QuestId.MERCY) return 0L;
        String key = BONUS_PREFIX + quest.id();
        long stored = KarmaPlayerState.get(player, key);
        if (stored > 0L) return stored - 1L;
        int stage = CommunityProgressionService.stage(player);
        int chance = QuestConfig.current().bonusChancePercent(stage);
        long anchor = isStoredActive(player, quest) ? KarmaPlayerState.get(player, STARTED_AT) : KicCycle.currentCycleId();
        long seed = player.getUUID().getLeastSignificantBits() ^ Long.rotateLeft(anchor, 17)
            ^ ((long) quest.ordinal() * 0xD6E8FEB86659FD93L);
        if (Math.floorMod(seed, 100L) >= chance) return 0L;
        return 1L + Math.floorMod(Long.rotateLeft(seed, 29), 2L);
    }

    private static boolean isWithinStructure(ServerLevel level, BlockPos pos, String structureId) {
        if (level == null || pos == null || structureId == null || structureId.isBlank()) return false;
        Identifier id = Identifier.tryParse(structureId);
        if (id == null) return false;
        java.util.Optional<Structure> structure = java.util.Optional.empty();
        try {
            structure = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getOptional(id);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (structure.isEmpty()) return false;
        return level.structureManager().getStructureWithPieceAt(pos, structure.get()).isValid();
    }

    static boolean isStoredActive(ServerPlayer player, QuestId quest) {
        for (int slot = 0; slot < MAX_ACTIVE_QUESTS; slot++) {
            if (quest.id().equals(stringState(player, ACTIVE_PREFIX + slot))) return true;
        }
        return false;
    }

    private static String itemProgressKey(QuestId quest, int index) {
        return "quest_item_progress_" + quest.id() + "_" + index;
    }

    private static String itemBaselineKey(QuestId quest, int index) {
        return "quest_item_baseline_" + quest.id() + "_" + index;
    }

    private static void initializeItemBaselines(ServerPlayer player, QuestId quest) {
        QuestObjectiveCatalog.Objective objective = activeObjective(player, quest);
        for (int i = 0; i < objective.requirements().size(); i++) {
            QuestObjectiveCatalog.Requirement requirement = objective.requirements().get(i);
            KarmaPlayerState.set(player, itemBaselineKey(quest, i), inventoryCount(player, requirement.item()));
            KarmaPlayerState.clear(player, itemProgressKey(quest, i));
        }
    }

    private static int inventoryCount(ServerPlayer player, Item item) {
        return QuestRevamp.count(player,item);
    }

    private static String collectionProgress(ServerPlayer player, QuestId quest,
            QuestObjectiveCatalog.Objective objective) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < objective.requirements().size(); i++) {
            QuestObjectiveCatalog.Requirement requirement = objective.requirements().get(i);
            long count = inventoryCount(player, requirement.item());
            String name = new ItemStack(requirement.item()).getHoverName().getString();
            parts.add(name + ": " + Math.min(requirement.count(), count) + "/" + requirement.count());
        }
        return String.join(" | ", parts);
    }

    private static boolean trackCollection(ServerPlayer player, QuestId quest,
            QuestObjectiveCatalog.Objective objective) {
        return objective.requirements().stream().allMatch(r -> inventoryCount(player,r.item())>=r.count());
    }

    public static String durationDescription(QuestId quest) {
        if (quest == null) return "No duration is registered.";
        if (quest == QuestId.BLOOD_PRICE) return "Active timer: 30 minutes.";
        if (quest == QuestId.SURVIVOR) return "12 real hours; online movement earns up to 6 rewarded hours. Offline time earns nothing.";
        if (quest == QuestId.MERCY) return "Existing Mercy Trial timer: " + LightShrineService.mercyTrialDurationMinutes() + " minutes.";
        if (quest == QuestId.HUNT) return "Existing Hunt duration: " + HuntConfig.current().durationHours + " hours.";
        return "No duration or timer has been finalized.";
    }

    public static String bonusDescription(ServerPlayer player, QuestId quest) {
        return switch ((int) selectedBonus(player, quest)) {
            case 1 -> "Untouchable: complete without taking damage." + (player!=null && KarmaPlayerState.get(player,BONUS_FAILED_PREFIX+quest.id())!=0?" [BONUS FORFEITED]":"");
            case 2 -> "Lightly Equipped: keep your chest slot empty, including elytra; equipping it only forfeits the bonus." + (player!=null && KarmaPlayerState.get(player,BONUS_FAILED_PREFIX+quest.id())!=0?" [BONUS FORFEITED]":"");
            default -> "No Bonus modifier selected.";
        };
    }

    public static String cooldownRuleDescription(QuestId quest) {
        if(quest==QuestId.HUNT)return "Hunt participation uses its own cooldowns.";
        if(quest==QuestId.SURVIVOR)return "One start per 12 hours, including cancelled runs.";
        return QuestRevamp.limit(quest)+" completions per individual objective per cycle. Cancelling spends no completion and does not reroll.";
    }

    public static String nextCycleStatus() {
        return "Next cycle in: " + formatDuration(KicCycle.remainingMillis());
    }

    public static long universalCooldownRemaining(ServerPlayer player) {
        return 0L;
    }

    public static long questCooldownCyclesRemaining(ServerPlayer player, QuestId quest) {
        return 0L;
    }

    public static long questCooldownMillisRemaining(ServerPlayer player, QuestId quest) {
        return quest==QuestId.SURVIVOR?Math.max(0,QuestRevamp.get(player,"endurance_next")-System.currentTimeMillis()):0;
    }

    public static String cooldownStatus(ServerPlayer player, QuestId quest) {
        if (quest == QuestId.HUNT) {
            long remaining = huntedCooldownRemaining(player);
            return remaining > 0L
                ? "Hunted participation cooldown: " + formatDuration(remaining)
                : "No Hunted participation cooldown is active.";
        }
        List<String> status = new ArrayList<>();
        long universal = universalCooldownRemaining(player);
        if (universal > 0L) status.add("Cooldown: " + formatDuration(universal));
        String specific = specificCooldownStatus(player, quest);
        if (!specific.isBlank()) status.add(specific);
        return String.join(" | ", status);
    }

    private static String specificCooldownStatus(ServerPlayer player, QuestId quest) {
        if (quest == null) return "";
        List<String> status = new ArrayList<>();
        long timeRemaining = questCooldownMillisRemaining(player, quest);
        long cycleRemaining = questCooldownCyclesRemaining(player, quest);
        if (timeRemaining > 0L) status.add("CD for " + formatDuration(timeRemaining));
        if (cycleRemaining > 0L) status.add("CD for " + cycleRemaining + (cycleRemaining == 1L ? " cycle" : " cycles"));
        return String.join(" and ", status);
    }

    public static String perQuestCooldownStatus(ServerPlayer player) {
        List<String> status = new ArrayList<>();
        for (QuestId quest : QuestId.values()) {
            String specific = specificCooldownStatus(player, quest);
            if (!specific.isBlank()) status.add(quest.displayName() + ": " + specific);
        }
        return status.isEmpty() ? "No quest-specific cooldowns are active." : String.join("; ", status);
    }

    private static long huntedCooldownRemaining(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0L : Math.max(0L,
            HuntState.get(server).huntedCooldownUntil(player.getUUID()) - HuntService.now());
    }

    public static String generalCooldownStatus(ServerPlayer player) {
        long universal = universalCooldownRemaining(player);
        return universal > 0L ? "Cooldown: " + formatDuration(universal) : "Cooldown: Ready";
    }

    public static boolean complete(ServerPlayer player, QuestId quest) {
        if (player == null || quest == null || QuestRevamp.get(player,"debug_hold")==1) return false;
        if (quest == QuestId.HUNT || !questFeatureEnabled(player, quest)) return false;
        if (!completionRequirementsMet(player, quest)) return false;
        return finishActive(player, quest, EndReason.COMPLETED, null);
    }

    /** Called by the established Mercy completion integration only after its cure condition succeeds. */
    public static void authorizeMercyCompletion(ServerPlayer player) {
        if (player != null && questFeatureEnabled(player, QuestId.MERCY)
                && activeQuest(player) == QuestId.MERCY) {
            KarmaPlayerState.set(player, "quest_mercy_completion_ready", 1L);
        }
    }

    /** Allows finalized dynamic-reward quests to persist their computed value without defining a currency curve here. */
    public static boolean completeWithDynamicReward(ServerPlayer player, QuestId quest, long rewardValue) {
        if (player == null || QuestRevamp.get(player,"debug_hold")==1 || !questFeatureEnabled(player, quest)) return false;
        if (quest != QuestId.BLOOD_PRICE || !completionRequirementsMet(player, quest)) return false;
        return finishActive(player, quest, EndReason.COMPLETED, Math.min(QuestConfig.current().bloodPriceMaxInfamy, Math.max(0L, rewardValue)));
    }

    private static boolean completionRequirementsMet(ServerPlayer player, QuestId quest) {
        if (player == null || quest == null || activeQuest(player) != quest || !QuestRevamp.eligible(player,quest)) return false;
        if (QuestRevamp.submission(quest)||quest==QuestId.SURVIVOR||quest==QuestId.CHALLENGER||quest==QuestId.PILGRIMAGE) return QuestRevamp.ready(player,quest);
        if (quest == QuestId.MERCY) return KarmaPlayerState.get(player, "quest_mercy_completion_ready") == 1L;
        if (quest == QuestId.DUEL) return KarmaPlayerState.get(player, DUEL_WON) == 1L;
        if (quest == QuestId.BLOOD_PRICE) {
            long started = KarmaPlayerState.get(player, STARTED_AT);
            return started > 0L && System.currentTimeMillis() - started >= BLOOD_PRICE_DURATION_MILLIS;
        }
        QuestObjectiveCatalog.Objective objective = activeObjective(player, quest);
        if (quest == QuestId.COURAGE) {
            return KarmaPlayerState.get(player, courageFlag(objective.key())) == 1L
                && (courageItem(objective.key()) == null || inventoryCount(player, courageItem(objective.key())) > 0)
                && nearReturnShrine(player);
        }
        if (objective.kind() == QuestObjectiveCatalog.Kind.KILL) {
            return KarmaPlayerState.get(player, progressKey(quest)) >= objective.targetCount();
        }
        if (objective.kind() == QuestObjectiveCatalog.Kind.COLLECT) return trackCollection(player, quest, objective);
        if (objective.kind() == QuestObjectiveCatalog.Kind.OFFER) {
            return KarmaPlayerState.get(player, "quest_offering_paid") == 1L;
        }
        return false;
    }

    public static boolean fail(ServerPlayer player, QuestId quest) {
        return finishActive(player, quest, EndReason.FAILED, null);
    }

    public static boolean cancelActive(ServerPlayer player) {
        QuestId quest = activeQuest(player);
        if (quest == null) return false;
        if (quest == QuestId.DUEL) return cancelDuel(player);
        if (quest == QuestId.MERCY) LightShrineService.cancelQuestTrial(player);
        return finishActive(player, quest, EndReason.CANCELLED, null);
    }

    public static QuestId activeQuest(ServerPlayer player) {
        List<QuestId> quests = active(player);
        return quests.isEmpty() ? null : quests.getFirst();
    }

    private static String progressKey(QuestId quest) {
        return quest == QuestId.SURVIVOR ? SURVIVOR_TICKS : "quest_progress_" + quest.id();
    }

    private static boolean finishActive(ServerPlayer player, QuestId quest, EndReason reason, Long dynamicReward) {
        if (player == null || quest == null || activeQuest(player) != quest) return false;
        int objectiveIndex = selectedObjectiveIndex(player, quest);
        if(reason==EndReason.COMPLETED) QuestRevamp.completed(player,quest,objectiveForIndex(quest,objectiveIndex));
        long bonus = selectedBonus(player, quest);
        boolean bonusCompleted = KarmaPlayerState.get(player, BONUS_FAILED_PREFIX + quest.id()) == 0L;
        long progress = KarmaPlayerState.get(player, progressKey(quest));
        long endedAt = System.currentTimeMillis();
        KarmaPlayerState.set(player, LAST_ENDED_QUEST, stableStateCode(quest));
        KarmaPlayerState.set(player, LAST_ENDED_PROGRESS, progress);
        KarmaPlayerState.set(player, LAST_ENDED_AT, endedAt);
        if (dynamicReward == null) KarmaPlayerState.clear(player, LAST_DYNAMIC_REWARD);
        else KarmaPlayerState.set(player, LAST_DYNAMIC_REWARD, dynamicReward);
        for (int i = 0; i < ACTIVE_STATE_SLOTS; i++) KarmaPlayerState.clear(player, ACTIVE_PREFIX + i);
        KarmaPlayerState.clear(player, STARTED_AT);
        KarmaPlayerState.clear(player, progressKey(quest));
        KarmaPlayerState.clear(player, OBJECTIVE_INDEX_PREFIX + quest.id());
        KarmaPlayerState.clear(player, BONUS_PREFIX + quest.id());
        KarmaPlayerState.clear(player, BONUS_FAILED_PREFIX + quest.id());
        KarmaPlayerState.clear(player, SURVIVOR_REWARD_INTERVALS);
        KarmaPlayerState.clear(player, "quest_offering_paid");
        KarmaPlayerState.clear(player, "quest_mercy_completion_ready");
        KarmaPlayerState.clear(player, "quest_courage_end_seen");
        for (int i = 0; i < 8; i++) {
            KarmaPlayerState.clear(player, itemBaselineKey(quest, i));
            KarmaPlayerState.clear(player, itemProgressKey(quest, i));
        }
        if (reason == EndReason.COMPLETED) {
            if (!QuestRevamp.reward(player,quest,objectiveForIndex(quest,objectiveIndex)))
                applyCompletionReward(player, quest, objectiveIndex, bonus, bonusCompleted, dynamicReward, progress);
            else if (quest!=QuestId.SURVIVOR && bonusCompleted) applyBonusReward(player,quest,bonus);
            recognizeCompletion(player, quest);
            KicUiText.success(player);
        }
        player.sendSystemMessage(Component.literal(quest.displayName() + " " + reason.name().toLowerCase()
            + ". " + cooldownRuleDescription(quest)));
        refreshOpenMenu(player);
        return true;
    }

    public static void onPlayerJoin(ServerPlayer player) {
        QuestRevamp.migrate(player);
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        // Earlier Hunt endings used the normal Quest cooldown store. Consume those
        // legacy entries without applying them; Hunt now has separate participation cooldowns.
        KicRotationState.get(server).consumePendingQuestCooldown(player.getUUID());
    }

    public static int alignmentCode(ServerPlayer player) {
        return switch (SocialCurrencyService.getStance(player)) {
            case LIGHT -> 1;
            case DARK -> -1;
            case NEUTRAL -> 0;
        };
    }

    public static void refreshPlayerUi(ServerPlayer player) {
        refreshOpenMenu(player);
    }

    /** Called by the existing kill event; the target rule stays isolated for later tuning. */
    public static void recordKill(ServerPlayer killer, LivingEntity victim) {
        QuestId quest = activeQuest(killer);
        if (quest == null || quest == QuestId.HUNT) return;
        if (!questFeatureEnabled(killer, quest) || !QuestRevamp.eligible(killer,quest)) return;
        if (quest==QuestId.SURVIVOR) {if(victim instanceof Monster) QuestRevamp.kill(killer);return;}
        if (quest == QuestId.DUEL) {
            if (!(victim instanceof ServerPlayer opponent)
                    || KarmaPlayerState.get(killer, DUEL_ACTIVE) != 1L
                    || KarmaPlayerState.get(opponent, DUEL_ACTIVE) != 1L
                    || !opponent.getUUID().equals(duelOpponent(killer))
                    || !killer.getUUID().equals(duelOpponent(opponent))) return;
            resolveDuel(killer, opponent);
            return;
        }
        if (quest != QuestId.BLOOD_PRICE) {
            QuestObjectiveCatalog.Objective objective = activeObjective(killer, quest);
            if (objective.kind() == QuestObjectiveCatalog.Kind.KILL
                    && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString().equals(objective.targetId())) {
                KarmaPlayerState.increment(killer, progressKey(quest));
                if (KarmaPlayerState.get(killer, progressKey(quest)) >= objective.targetCount()) complete(killer, quest);
            }
            return;
        }
        long started = KarmaPlayerState.get(killer, STARTED_AT);
        if (started <= 0L || System.currentTimeMillis() - started >= BLOOD_PRICE_DURATION_MILLIS) {
            long kills = KarmaPlayerState.get(killer, progressKey(QuestId.BLOOD_PRICE));
            long reward = QuestConfig.current().bloodPriceReward(kills);
            completeWithDynamicReward(killer, QuestId.BLOOD_PRICE, reward);
            return;
        }
        if (isValidBloodPriceKill(victim)) KarmaPlayerState.increment(killer, progressKey(QuestId.BLOOD_PRICE));
    }

    private static boolean isValidBloodPriceKill(LivingEntity victim) {
        return victim instanceof Monster;
    }

    private static void applyCompletionReward(ServerPlayer player, QuestId quest, int objectiveIndex,
            long bonus, boolean bonusCompleted, Long dynamicReward, long completedProgress) {
        QuestConfig config = QuestConfig.current();
        if (quest == QuestId.MERCY) {
            if (bonusCompleted) applyBonusReward(player, quest, bonus);
            KarmaPlayerState.clear(player, "quest_mercy_completion_ready");
            return;
        }
        if (quest == QuestId.BLOOD_PRICE) {
            int amount = config.bloodPriceReward(completedProgress);
            if (amount > 0) KarmaService.addInfamyPoints(player, amount, "Blood Price quest");
            int karma = QuestConfig.bloodPriceKarmaReward(completedProgress);
            if (karma > 0) KarmaService.addQuietly(player, KarmaService.karma(karma), "Quest completion");
        } else if (quest == QuestId.WARDEN) {
            if (config.wardenKarma != 0) KarmaService.add(player, KarmaService.karma(config.wardenKarma), "The Warden contract", "quest_warden");
            if (config.wardenInfamy > 0) KarmaService.addInfamyPoints(player, config.wardenInfamy, "The Warden contract");
        } else {
            QuestObjectiveCatalog.Objective objective = objectiveForIndex(quest, objectiveIndex);
            int difficulty = objective.difficulty();
            if (quest.type() == Type.TRIAL) {
                int karma = config.trialKarmaRate(quest) * difficulty;
                if (karma != 0) KarmaService.add(player, KarmaService.karma(karma), quest.displayName(), "quest_" + quest.id());
                int reputationPerDifficulty = quest == QuestId.ABUNDANCE
                    ? config.abundanceReputationPerDifficulty : config.trialReputationPerDifficulty;
                int reputation = reputationPerDifficulty * difficulty;
                if (reputation > 0 && quest != QuestId.OFFERING) {
                    SocialCurrencyService.addReputation(player, reputation, quest.displayName());
                }
            } else {
                int karma = config.contractKarmaRate(quest) * difficulty;
                if (karma != 0) KarmaService.add(player, KarmaService.karma(karma), quest.displayName(), "quest_" + quest.id());
                int infamy = contractInfamyReward(quest, objective);
                if (infamy > 0) KarmaService.addInfamyPoints(player, infamy, quest.displayName());
            }
        }
        if (bonusCompleted) applyBonusReward(player, quest, bonus);
    }

    private static int contractInfamyReward(QuestId quest, QuestObjectiveCatalog.Objective objective) {
        return quest == QuestId.EXECUTIONER && "evoker".equals(objective.key()) ? 30
            : QuestConfig.current().contractRewardPerDifficulty(quest) * objective.difficulty();
    }

    private static void applyBonusReward(ServerPlayer player, QuestId quest, long bonus) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.BONUS_OBJECTIVES_ENABLED)) return;
        QuestConfig config = QuestConfig.current();
        boolean trial = QuestRevamp.get(player,"side") > 0;
        int amount = bonus == 1L
            ? (trial ? config.bonusUntouchableReputation : config.bonusUntouchableInfamy)
            : bonus == 2L ? (trial ? config.bonusSticksAndStonesReputation : config.bonusSticksAndStonesInfamy) : 0;
        if (amount <= 0) return;
        if (trial) SocialCurrencyService.addReputation(player, amount, "Quest Bonus");
        else KarmaService.addInfamyPoints(player, amount, "Quest Bonus");
    }

    /** Marks an Untouchable modifier failed as soon as server-side damage reaches the player. */
    public static void recordDamage(ServerPlayer player) {
        QuestRevamp.damage(player);
        QuestId quest = activeQuest(player);
        if (quest != null && !questFeatureEnabled(player, quest)) return;
        if (quest != null && selectedBonus(player, quest) == 1L) {
            KarmaPlayerState.set(player, BONUS_FAILED_PREFIX + quest.id(), 1L);
        }
    }

    /** A Courage run must survive its trip through the End; death resets the recorded visit. */
    public static void recordDeath(ServerPlayer player) {
        recordDamage(player);
        if (player != null && activeQuest(player) == QuestId.COURAGE) {
            for (String flag : new String[]{COURAGE_END_SEEN, COURAGE_ANCIENT_CITY_FOUND,
                    COURAGE_TRIAL_VAULT_OPENED, COURAGE_FORTRESS_WITH_ROD, "courage_inside", "courage_item_baseline"})
                KarmaPlayerState.clear(player, flag);
        }
    }

    private static String courageFlag(String key) {
        return switch (key) {
            case "ancient_city" -> COURAGE_ANCIENT_CITY_FOUND;
            case "trial_vault" -> COURAGE_TRIAL_VAULT_OPENED;
            case "fortress_blaze_rod" -> COURAGE_FORTRESS_WITH_ROD;
            default -> COURAGE_END_SEEN;
        };
    }
    private static Item courageItem(String key) {
        return switch (key) {
            case "ancient_city" -> Items.ECHO_SHARD;
            case "trial_vault" -> Items.TRIAL_KEY;
            case "fortress_blaze_rod" -> Items.BLAZE_ROD;
            default -> null;
        };
    }
    private static boolean nearReturnShrine(ServerPlayer player) {
        if (!"minecraft:overworld".equals(player.level().dimension().identifier().toString())) return false;
        return ShrineState.get(player.level().getServer()).sites().stream().anyMatch(site -> {
            BlockPos core = site.actualLodestonePosition();
            return player.distanceToSqr(core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5) <= 64.0;
        });
    }
    private static void tickCourage(ServerPlayer player) {
        String key = activeObjective(player, QuestId.COURAGE).key();
        String flag = courageFlag(key);
        if (KarmaPlayerState.get(player, flag) != 1L) {
            if ("end_return".equals(key)) {
                if ("minecraft:the_end".equals(player.level().dimension().identifier().toString())) KarmaPlayerState.set(player, flag, 1L);
            } else {
                String structure = switch (key) {
                    case "ancient_city" -> "minecraft:ancient_city";
                    case "trial_vault" -> "minecraft:trial_chambers";
                    default -> "minecraft:fortress";
                };
                boolean inside = isWithinStructure(player.level(), player.blockPosition(), structure);
                int carried = inventoryCount(player, courageItem(key));
                if (!inside || KarmaPlayerState.get(player, "courage_inside") == 0L) {
                    KarmaPlayerState.set(player, "courage_item_baseline", carried);
                } else if (carried > KarmaPlayerState.get(player, "courage_item_baseline")) {
                    KarmaPlayerState.set(player, flag, 1L);
                    player.sendSystemMessage(Component.literal("Expedition item secured. Return to a shrine alive with it."));
                }
                KarmaPlayerState.set(player, "courage_inside", inside ? 1L : 0L);
            }
        }
        if (completionRequirementsMet(player, QuestId.COURAGE)) complete(player, QuestId.COURAGE);
    }

    /** Equipment violations forfeit the modifier; attacks remain vanilla. */
    public static boolean allowBonusAttack(ServerPlayer attacker, net.minecraft.world.entity.Entity causingEntity) {
        QuestId quest=activeQuest(attacker);
        if(quest!=null) enforceBonusEquipment(attacker,quest);
        return true;
    }

    private static boolean isStrongArmor(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return isArmorId(id) && !id.startsWith("minecraft:leather_");
    }

    private static boolean isArmorId(String id) {
        return id.endsWith("_helmet") || id.endsWith("_chestplate")
            || id.endsWith("_leggings") || id.endsWith("_boots");
    }

    private static boolean isWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.endsWith("_sword") || id.endsWith("_axe") || id.endsWith("_bow")
            || id.endsWith("_crossbow") || id.endsWith("_trident") || id.endsWith("_mace");
    }

    private static void enforceBonusEquipment(ServerPlayer player, QuestId quest) {
        if (selectedBonus(player,quest)==2 && !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty())
            KarmaPlayerState.set(player,BONUS_FAILED_PREFIX+quest.id(),1);
    }

    private static void unequipToInventory(ServerPlayer player, EquipmentSlot slot, ItemStack worn) {
        ItemStack stashed = worn.copy();
        player.setItemSlot(slot, ItemStack.EMPTY);
        if (!player.getInventory().add(stashed)) player.drop(stashed, false);
    }

    /** Tick quest timers; sampling and submission run through the shared server rules. */
    public static void tick(MinecraftServer server) {
        if(server==null)return;
        if(server.getTickCount()%200==0)QuestRevamp.pruneSamples(server.getPlayerList().getPlayers());
        for(ServerPlayer player:server.getPlayerList().getPlayers()) {
            QuestRevamp.migrate(player);
            QuestId quest=activeQuest(player);
            if(quest==null)continue;
            if(quest==QuestId.DUEL){cancelDuel(player);continue;}
            enforceBonusEquipment(player,quest);
            boolean eligible=questFeatureEnabled(player,quest)&&QuestRevamp.eligible(player,quest);
            QuestRevamp.tick(player,quest,eligible);
            if(quest==QuestId.MERCY) LightShrineService.trial(player); // expiration continues while paused
            if(!eligible)continue;
            if(quest==QuestId.SURVIVOR && QuestRevamp.expired(player)) complete(player,quest);
            else if(quest==QuestId.BLOOD_PRICE && System.currentTimeMillis()-KarmaPlayerState.get(player,STARTED_AT)>=BLOOD_PRICE_DURATION_MILLIS)
                completeWithDynamicReward(player,quest,QuestConfig.current().bloodPriceReward(KarmaPlayerState.get(player,progressKey(quest))));
            else if(quest==QuestId.COURAGE)tickCourage(player);
        }
    }

    public static void resetPlayer(MinecraftServer server, ServerPlayer player) {
        if (player == null) return;
        for (int i = 0; i < ACTIVE_STATE_SLOTS; i++) KarmaPlayerState.clear(player, ACTIVE_PREFIX + i);
        clearPlayerQuestState(player);
        if (server != null) KicRotationState.get(server).clearPendingQuestCooldown(player.getUUID());
        HuntService.resetPlayer(server, player);
        KarmaPlayerState.clear(player, LightShrineService.TRIAL_OBJECTIVE);
        KarmaPlayerState.clear(player, LightShrineService.TRIAL_EXPIRY_OBJECTIVE);
        KarmaPlayerState.clear(player, LightShrineService.TRIAL_COOLDOWN_OBJECTIVE);
        clearDuelBindings(player);
    }

    public static void resetAll(MinecraftServer server) {
        HuntService.resetAll(server);
        KicRotationState.get(server).clearPendingQuestCooldowns();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < ACTIVE_STATE_SLOTS; i++) KarmaPlayerState.clear(player, ACTIVE_PREFIX + i);
            clearPlayerQuestState(player);
            KarmaPlayerState.clear(player, LightShrineService.TRIAL_OBJECTIVE);
            KarmaPlayerState.clear(player, LightShrineService.TRIAL_EXPIRY_OBJECTIVE);
            KarmaPlayerState.clear(player, LightShrineService.TRIAL_COOLDOWN_OBJECTIVE);
        }
    }

    private static void storeActive(ServerPlayer player, QuestId quest) {
        for (int i = 0; i < MAX_ACTIVE_QUESTS; i++) {
            if (stringState(player, ACTIVE_PREFIX + i).isBlank()) {
                setStringState(player, ACTIVE_PREFIX + i, quest.id());
                return;
            }
        }
    }

    private static void reconcile(ServerPlayer player) {
        long[] stored = new long[ACTIVE_STATE_SLOTS];
        boolean hasStoredQuest = false;
        for (int i = 0; i < ACTIVE_STATE_SLOTS; i++) {
            stored[i] = KarmaPlayerState.get(player, ACTIVE_PREFIX + i);
            hasStoredQuest |= stored[i] > 0L;
        }
        if (!hasStoredQuest) return;

        QuestId retained = null;
        for (int i = 0; i < ACTIVE_STATE_SLOTS; i++) {
            QuestId quest = QuestId.byId(stringState(player, ACTIVE_PREFIX + i));
            if (retained == null && quest != null) retained = quest;
        }

        boolean slotZeroMatches = retained == null ? stored[0] == 0L
            : stored[0] == stableStateCode(retained);
        if (slotZeroMatches && stored[1] == 0L && stored[2] == 0L) return;

        setStringState(player, ACTIVE_PREFIX + 0, retained == null ? "" : retained.id());
        for (int i = 1; i < ACTIVE_STATE_SLOTS; i++) {
            setStringState(player, ACTIVE_PREFIX + i, "");
        }
    }

    public static String activeLimitMessage() {
        return MAX_ACTIVE_QUESTS == 1 ? "You already have an active quest."
            : "You already have " + MAX_ACTIVE_QUESTS + " active quests.";
    }

    /* KarmaPlayerState is long-only; use permanent per-quest codes, preserving existing values. */
    private static String stringState(ServerPlayer player, String key) {
        long value = KarmaPlayerState.get(player, key);
        if (value <= 0L) return "";
        QuestId quest = questByStableStateCode(value);
        return quest == null ? "" : quest.id();
    }

    private static void setStringState(ServerPlayer player, String key, String questId) {
        QuestId quest = QuestId.byId(questId);
        KarmaPlayerState.set(player, key, quest == null ? 0L : stableStateCode(quest));
    }

    /** These codes intentionally match the old ordinal encoding and must never be reassigned. */
    private static int stableStateCode(QuestId quest) {
        return switch (quest) {
            case ABUNDANCE -> 1;
            case MERCY -> 2;
            case COURAGE -> 3;
            case HUNT -> 4;
            case OFFERING -> 5;
            case SURVIVOR -> 6;
            case BLOOD_PRICE -> 7;
            case EXECUTIONER -> 8;
            case COLLECTOR -> 9;
            case WARDEN -> 10;
            case DUEL -> 11;
            case PILGRIMAGE -> 12;
            case CHALLENGER -> 13;
        };
    }

    private static QuestId questByStableStateCode(long code) {
        return switch ((int) code) {
            case 1 -> QuestId.ABUNDANCE;
            case 2 -> QuestId.MERCY;
            case 3 -> QuestId.COURAGE;
            case 4 -> QuestId.HUNT;
            case 5 -> QuestId.OFFERING;
            case 6 -> QuestId.SURVIVOR;
            case 7 -> QuestId.BLOOD_PRICE;
            case 8 -> QuestId.EXECUTIONER;
            case 9 -> QuestId.COLLECTOR;
            case 10 -> QuestId.WARDEN;
            case 11 -> QuestId.DUEL;
            case 12 -> QuestId.PILGRIMAGE;
            case 13 -> QuestId.CHALLENGER;
            default -> null;
        };
    }

    static void clearPlayerQuestState(ServerPlayer player) {
        KarmaPlayerState.clear(player, STARTED_AT);
        KarmaPlayerState.clear(player, SURVIVOR_TICKS);
        KarmaPlayerState.clear(player, UNIVERSAL_COOLDOWN_UNTIL);
        KarmaPlayerState.clear(player, LAST_ENDED_QUEST);
        KarmaPlayerState.clear(player, LAST_ENDED_PROGRESS);
        KarmaPlayerState.clear(player, LAST_ENDED_AT);
        KarmaPlayerState.clear(player, LAST_DYNAMIC_REWARD);
        for (QuestId quest : QuestId.values()) {
            KarmaPlayerState.clear(player, progressKey(quest));
            KarmaPlayerState.clear(player, OBJECTIVE_INDEX_PREFIX + quest.id());
            KarmaPlayerState.clear(player, BONUS_PREFIX + quest.id());
            KarmaPlayerState.clear(player, BONUS_FAILED_PREFIX + quest.id());
            KarmaPlayerState.clear(player, QUEST_COOLDOWN_TIME_PREFIX + quest.id());
            KarmaPlayerState.clear(player, QUEST_COOLDOWN_CYCLE_PREFIX + quest.id());
        }
        KarmaPlayerState.clear(player, SURVIVOR_REWARD_INTERVALS);
        KarmaPlayerState.clear(player, "quest_offering_paid");
        KarmaPlayerState.clear(player, "quest_mercy_completion_ready");
        clearDuelBindings(player);
    }

    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds / 60L) % 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) return hours + "h " + minutes + "m";
        if (minutes > 0L) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private static void refreshOpenMenu(ServerPlayer player) {
        QuestMenu.refreshIfOpen(player);
    }

    private static String formatDurationTicks(long ticks) {
        long totalSeconds = Math.max(0L, ticks / 20L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds / 60L) % 60L;
        long seconds = totalSeconds % 60L;
        return hours + "h " + minutes + "m " + seconds + "s";
    }
}
