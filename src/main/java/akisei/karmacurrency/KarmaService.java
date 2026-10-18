package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Core Karma system. Karma is alignment/progression; it is never spent as currency. */
public final class KarmaService {
	public static final String OBJECTIVE_NAME = "karma";
	public static final String REPUTATION_POINTS_OBJECTIVE = "reputation_points";
	public static final String INFAMY_POINTS_OBJECTIVE = "infamy_points";
	public static final String REPUTATION_DECAY_OBJECTIVE = "reputation_decay";
	public static final String KARMA_DECAY_OBJECTIVE = "karma_decay";
	public static final String INFAMY_DECAY_OBJECTIVE = "infamy_decay";
	public static final String TRADE_COUNT_OBJECTIVE = "karma_trade_count";
	public static final String ALIGNMENT_CHOICE_OBJECTIVE = "karma_alignment_choice";
	public static final String LIGHT_PROGRESS_OBJECTIVE = "karma_light_progress";
	public static final String DARK_PROGRESS_OBJECTIVE = "karma_dark_progress";
	public static final String SHRINE_COMPASS_COOLDOWN_OBJECTIVE = "shrine_compass_cd";
	public static final String PLAYER_HEAD_COOLDOWN_OBJECTIVE = "player_head_cd";
	public static final int POINTS_PER_KARMA = 100;
	public static final int STARTING_ALIGNMENT_KARMA = 100;
	/** Personal Karma bands also set the Karma cap at World Stages 0..3; Influence thresholds live in CommunityProgressionService. */
	public static final int[] PERSONAL_KARMA_THRESHOLDS = {100, 300, 600, 1000};
	public static final int SHRINE_COMPASS_COOLDOWN_MINUTES = 10;
	public static final int PLAYER_HEAD_COOLDOWN_MINUTES = 60;
	public static final int SOCIAL_DR_THRESHOLD = 5;

	private static final Identifier KARMA_HEALTH_MODIFIER_ID =
		Identifier.fromNamespaceAndPath(KarmaIsCurrency.MOD_ID, "karma_health");

	private KarmaService() {}

	public static int get(ServerPlayer player) { return score(player).get(); }

	/** Adds Karma only. Karma itself never automatically creates social currency. */
	public static void add(ServerPlayer player, int points, String reason) {
		add(player, points, reason, "general");
	}

	/** Adds Karma only. Use addWithSocial when an event has an explicit social reward. */
	public static void add(ServerPlayer player, int points, String reason, String activityId) {
        addInternal(player, points, reason, true);
    }

    /** Legacy entry point; actual Karma changes now appear in chat at the owner's request. */
    public static void addQuietly(ServerPlayer player, int points, String reason) {
        addInternal(player, points, reason, false);
    }

    private static void addInternal(ServerPlayer player, int points, String reason, boolean announce) {
		if (points == 0) return;
		int current = get(player);
		int bounded = Math.clamp(current + points, -currentCap(player), currentCap(player));
		SocialCurrencyService.getStance(player); // Capture alignment before crossing a threshold.
		score(player).set(bounded);
		SocialCurrencyService.getStance(player);
		KicHistoryService.recordKarma(player, bounded - current, reason);
		updateProgression(player, bounded);
		updateHealthBonus(player, bounded, ShrineConfig.current());
		if (bounded == current) return;
		String sign = bounded > current ? "+" : "";
		KicMessages.change(player, "Karma", bounded - current, reason);
	}

	public static void recordVillagerTrade(ServerPlayer player) {
		int count = KarmaPlayerState.getInt(player, "trade_count") + 1;
		if (count < ShrineConfig.current().karmaTradeBatch) {
			KarmaPlayerState.setInt(player, "trade_count", count);
			return;
		}
		KarmaPlayerState.setInt(player, "trade_count", 0);
		awardAlignedEvent(player, "trade", 0, "completing " + ShrineConfig.current().karmaTradeBatch + " villager trades");
	}

    /** Current stance wins; Neutral falls back to the player's saved choice. */
    public static SocialCurrencyService.Stance rewardAlignment(ServerPlayer player) {
        return rewardAlignment(SocialCurrencyService.getStance(player), alignmentChoice(player));
    }

    static SocialCurrencyService.Stance rewardAlignment(SocialCurrencyService.Stance current, int preferred) {
        if (current != SocialCurrencyService.Stance.NEUTRAL) return current;
        return preferred < 0 ? SocialCurrencyService.Stance.DARK
            : preferred > 0 ? SocialCurrencyService.Stance.LIGHT : SocialCurrencyService.Stance.NEUTRAL;
    }

    record EventReward(int karma, int social) {}

    static EventReward eventReward(String event, boolean dark, int bossSocial, ShrineConfig config) {
        return switch (event) {
            case "trade" -> new EventReward(dark ? 1 : 2, dark ? 0 : config.socialTradeGain);
            case "cure" -> new EventReward(dark ? -6 : config.karmaCureZombieVillager, dark ? 2 : config.socialCureGain);
            case "villager_kill" -> new EventReward(dark ? -4 : -5, dark ? 3 : 0);
            case "trial_spawner" -> new EventReward(dark ? -2 : 2, 8);
            case "raid_captain" -> new EventReward(5, config.socialRaidCaptainGain);
            case "ender_dragon" -> new EventReward((dark ? -1 : 1) * config.karmaEnderDragon, bossSocial);
            case "wither" -> new EventReward((dark ? -1 : 1) * config.karmaWither, bossSocial);
            default -> throw new IllegalArgumentException("Unknown reward event: " + event);
        };
    }

    public static void awardAlignedEvent(ServerPlayer player, String event, int bossSocial, String reason) {
        boolean dark = rewardAlignment(player) == SocialCurrencyService.Stance.DARK;
        EventReward reward = eventReward(event, dark, bossSocial, ShrineConfig.current());
        // Resolve and pay currency before Karma can change the player's reward alignment.
        if (reward.social() > 0) awardSocialCurrency(player, !dark, reward.social(), event, reason);
        add(player, karma(reward.karma()), reason, event);
    }

	/** Applies an explicit social reward independently from the Karma amount. */
	public static void addWithSocial(ServerPlayer player, int karmaDelta, int socialBase, String reason, String activityId) {
		add(player, karmaDelta, reason, activityId);
		if (socialBase <= 0) return;
		awardSocialCurrency(player, karmaDelta > 0, socialBase, activityId, reason);
	}

	/** Awards Neutral-origin Influence without changing Karma. Used by explicitly neutral events. */
	public static void awardNeutralInfluence(ServerPlayer player, int baseAmount, String reason, String activityId) {
		if (baseAmount <= 0) return;
		String key = shortActivityKey(activityId);
		int count = KarmaPlayerState.getInt(player, "dr_count_" + key);
		int window = KarmaPlayerState.getInt(player, "dr_window_" + key);
		int now = currentMinute(player);
		if (count > 0 && now - window >= ShrineConfig.current().socialDiminishingReturnWindowMinutes) count = 0;
		if (count == 0) {
			window = now;
			KarmaPlayerState.setInt(player, "dr_window_" + key, window);
		}

		double dr = diminishingReturnMultiplier(count);
		int amount = Math.max(1, (int) Math.round(baseAmount * dr));
		SocialTokenService.insert(player, amount, SocialTokenService.Origin.NEUTRAL);
		KicHistoryService.recordInfluence(player, amount, reason);
		KarmaPlayerState.setInt(player, "dr_count_" + key, count + 1);
		KicMessages.change(player, "Physical Influence", amount, reason);
	}

	/** Awards social currency without changing Karma. Used by offerings and other social-only events. */
	public static void awardSocialOnly(ServerPlayer player, boolean lightAction, int socialBase, String reason, String activityId) {
		if (socialBase <= 0) return;
		awardSocialCurrency(player, lightAction, socialBase, activityId, reason);
	}

	private static void awardSocialCurrency(ServerPlayer player, boolean lightAction, int baseAmount, String activityId, String reason) {
		String key = shortActivityKey(activityId);
		int count = KarmaPlayerState.getInt(player, "dr_count_" + key);
		int window = KarmaPlayerState.getInt(player, "dr_window_" + key);
		int now = currentMinute(player);
		if (count > 0 && now - window >= ShrineConfig.current().socialDiminishingReturnWindowMinutes) count = 0;
		if (count == 0) {
			window = now;
			KarmaPlayerState.setInt(player, "dr_window_" + key, window);
		}

		double dr = diminishingReturnMultiplier(count);
		SocialCurrencyService.Stance stance = rewardAlignment(player);
        int amount = socialPayout(baseAmount, dr, socialAlignmentPercent(stance, lightAction));

		if (amount >= 0) {
			if (lightAction) SocialCurrencyService.addReputation(player, amount, reason);
			else addInfamyPoints(player, amount, reason);
		} else {
			// Penalties always come from the player's current social side.
			// This is especially important for PvP: a Light player who kills an
			// aligned/neutral player loses Reputation, while a Dark player loses Infamy.
			if (stance == SocialCurrencyService.Stance.LIGHT) {
				SocialCurrencyService.addReputation(player, amount, reason);
			} else if (stance == SocialCurrencyService.Stance.DARK) {
				addInfamyPoints(player, amount, reason);
			} else {
				int removed = SocialTokenService.removeUsable(player, -amount);
				if (removed > 0) KicHistoryService.recordInfluence(player, -removed, reason);
				KicMessages.change(player, "Physical Influence", -removed, reason);
			}
		}
		KarmaPlayerState.setInt(player, "dr_count_" + key, count + 1);
	}

    private static int socialAlignmentPercent(SocialCurrencyService.Stance stance, boolean lightAction) {
		return lightAction
			? switch (stance) {
				case LIGHT -> ShrineConfig.current().socialAlignedGainPercent;
				case NEUTRAL -> ShrineConfig.current().socialNeutralGainPercent;
				case DARK -> ShrineConfig.current().socialOpposedGainPercent;
			}
			: switch (stance) {
				case DARK -> ShrineConfig.current().socialAlignedGainPercent;
				case NEUTRAL -> ShrineConfig.current().socialNeutralGainPercent;
				case LIGHT -> ShrineConfig.current().socialOpposedGainPercent;
			};
    }
    static int socialPayout(int base, double repetition, int alignmentPercent) {
        if (base == 0) return 0;
        int amount = Math.max(1, (int) Math.round(Math.abs((double) base) * repetition * alignmentPercent / 100.0));
        return base < 0 ? -amount : amount;
    }
    public static java.util.List<String> rewardBreakdown(ServerPlayer player) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean dark = rewardAlignment(player) == SocialCurrencyService.Stance.DARK;
        for (String event : new String[]{"trade", "cure", "villager_kill", "raid_captain", "trial_spawner", "ender_dragon", "wither"}) {
            int bossBase = "ender_dragon".equals(event) ? ShrineConfig.current().socialEnderDragonGain
                : "wither".equals(event) ? ShrineConfig.current().socialWitherGain : 0;
            EventReward reward = eventReward(event, dark, bossBase, ShrineConfig.current());
            String key = shortActivityKey(event);
            int count = KarmaPlayerState.getInt(player, "dr_count_" + key);
            int window = KarmaPlayerState.getInt(player, "dr_window_" + key);
            if (currentMinute(player) - window >= ShrineConfig.current().socialDiminishingReturnWindowMinutes) count = 0;
            double dr = diminishingReturnMultiplier(count);
            int alignment = socialAlignmentPercent(rewardAlignment(player), !dark);
            lines.add(event.replace('_', ' ') + ": " + reward.karma() + " Karma; base " + reward.social()
                + " × " + alignment + "% × " + Math.round(dr * 100) + "% = "
                + socialPayout(reward.social(), dr, alignment) + (dark ? " Infamy" : " Reputation"));
        }
        lines.add("Next occurrence; rounded to nearest whole currency. Karma is not multiplied.");
        lines.add("Trade = one configured batch; spawners also require eligibility and location cooldown.");
        lines.add("Boss figures assume one nearby player. Base loses 5 per extra active player within 256 blocks (minimum 0).");
        lines.add("Offerings: gross × " + SocialCurrencyService.OFFERING_NET_PERCENT + "% rounded down; fixed quest payouts stay fixed.");
        return lines;
    }

	private static double diminishingReturnMultiplier(int count) {
		if (count < SOCIAL_DR_THRESHOLD) return 1.0;
		if (count < SOCIAL_DR_THRESHOLD * 2) return 0.85;
		if (count < SOCIAL_DR_THRESHOLD * 3) return 0.50;
		return 0.35;
	}

	private static String shortActivityKey(String activityId) {
		return switch (activityId) {
			case "villager_kill" -> "vkill";
			case "player_kill" -> "pkill";
			case "ender_dragon" -> "dragon";
			case "protection_trial" -> "ptrial";
			case "mercy_trial" -> "mtrial";
			case "raid_hero" -> "rhero";
			default -> activityId.length() <= 12 ? activityId : activityId.substring(0, 12);
		};
	}

	public static void resetDiminishingReturns(ServerPlayer player) {
		String[] activities = {"trade", "cure", "vkill", "pkill", "dragon", "wither", "offer", "general"};
		for (String activity : activities) {
			KarmaPlayerState.clear(player, "dr_count_" + activity);
			KarmaPlayerState.clear(player, "dr_window_" + activity);
		}
	}

	public static void addInfamyPoints(ServerPlayer player, int amount, String reason) {
		if (amount == 0) return;
		ScoreAccess infamy = score(player, INFAMY_POINTS_OBJECTIVE, "Infamy");
		int before = infamy.get();
		int total = (int) Math.clamp((long) before + amount, 0L, Integer.MAX_VALUE);
		infamy.set(total);
		KicHistoryService.recordSocial(player, "Infamy", total - before, reason);
		if (amount > 0 && !reason.toLowerCase(java.util.Locale.ROOT).startsWith("debug") && !"Influence redemption".equals(reason) && !"shop purchase refund".equals(reason)) {
			CommunityProgressionService.recordInfamy(player, total - before);
		}
		KicMessages.change(player, "Infamy", total - before, reason);
	}

	public static void setInfamyPoints(ServerPlayer player, int amount) {
		ScoreAccess score = score(player, INFAMY_POINTS_OBJECTIVE, "Infamy");
		int before = score.get();
		int bounded = Math.max(0, amount);
		score.set(bounded);
		KicHistoryService.recordSocial(player, "Infamy", bounded - before, "set Infamy");
        if (bounded != before) player.sendSystemMessage(Component.literal("Infamy " + (bounded > before ? "+" : "") + (bounded - before) + " (set Infamy) | Total: " + bounded));
	}

	public static int getInfamyPoints(ServerPlayer player) { return score(player, INFAMY_POINTS_OBJECTIVE, "Infamy").get(); }
	public static void spendInfamyPoints(ServerPlayer player, int amount) {
		if (amount <= 0 || getInfamyPoints(player) < amount) throw new IllegalArgumentException("Insufficient Infamy");
		score(player, INFAMY_POINTS_OBJECTIVE, "Infamy").add(-amount);
		KicHistoryService.recordSocial(player, "Infamy", -amount, "spent Infamy");
        KicMessages.change(player, "Infamy", -amount, "purchase / conversion");
	}

	public static void set(ServerPlayer player, int points) {
		int before = get(player);
		int bounded = Math.clamp(points, -currentCap(player), currentCap(player));
		SocialCurrencyService.getStance(player); // Capture alignment before crossing a threshold.
		score(player).set(bounded);
		SocialCurrencyService.getStance(player);
		KicHistoryService.recordKarma(player, bounded - before, "set Karma");
        if (bounded != before) player.sendSystemMessage(Component.literal("Karma " + (bounded > before ? "+" : "") + format(bounded - before) + " (set Karma) | Total: " + format(bounded)));
		updateProgression(player, bounded);
		updateHealthBonus(player, bounded, ShrineConfig.current());
	}

	/**
	 * Progression titles form soft Karma walls: reaching a title threshold unlocks
	 * the next band. Operators can set a per-player cap to test or pace a world
	 * without changing the server configuration; zero clears that override.
	 */
	public static int currentCap(ServerPlayer player) {
		int configuredCap = karma(ShrineConfig.current().karmaCap);
		int worldStage = CommunityProgressionService.stage(player);
		int stageCapWholeKarma = worldStage < PERSONAL_KARMA_THRESHOLDS.length
			? PERSONAL_KARMA_THRESHOLDS[worldStage] : ShrineConfig.current().karmaCap;
		int worldCap = Math.min(configuredCap, karma(stageCapWholeKarma));
		int override = KarmaPlayerState.getInt(player, "karma_cap_override");
		if (override > 0) return Math.min(worldCap, karma(override));
		return worldCap;
	}

	public static void setCapOverride(ServerPlayer player, int wholeKarma) {
		if (wholeKarma <= 0) KarmaPlayerState.clear(player, "karma_cap_override");
		else KarmaPlayerState.setInt(player, "karma_cap_override", Math.min(wholeKarma, ShrineConfig.current().karmaCap));
	}
	public static int karma(int wholeKarma) { return wholeKarma * POINTS_PER_KARMA; }
	/** Exact Karma change for administrative/system adjustments. Does not award social currency. */
	public static void changeKarmaExact(ServerPlayer player, int delta, String reason) {
		if (delta == 0) return;
		int before = get(player);
        int bounded = Math.clamp(before + delta, -currentCap(player), currentCap(player));
		SocialCurrencyService.getStance(player); // Capture alignment before crossing a threshold.
		score(player).set(bounded);
		SocialCurrencyService.getStance(player);
		updateProgression(player, bounded);
		updateHealthBonus(player, bounded, ShrineConfig.current());
		KicHistoryService.recordKarma(player, bounded - before, reason);
        String sign = bounded > before ? "+" : "";
		KicMessages.change(player, "Karma", bounded - before, reason);
	}

	public static int getShrineCompassCooldownMinutes(ServerPlayer player) {
		return Math.max(0, KarmaPlayerState.getInt(player, "shrine_compass_until") - currentMinute(player));
	}
	public static boolean canReceiveShrineCompass(ServerPlayer player) { return getShrineCompassCooldownMinutes(player) == 0; }
	public static void startShrineCompassCooldown(ServerPlayer player) { KarmaPlayerState.setInt(player, "shrine_compass_until", currentMinute(player) + SHRINE_COMPASS_COOLDOWN_MINUTES); }
	public static int getPlayerHeadCooldownMinutes(ServerPlayer player) { return Math.max(0, KarmaPlayerState.getInt(player, "player_head_until") - currentMinute(player)); }
	public static void startPlayerHeadCooldown(ServerPlayer player) { KarmaPlayerState.setInt(player, "player_head_until", currentMinute(player) + PLAYER_HEAD_COOLDOWN_MINUTES); }

	public static int alignmentChoice(ServerPlayer player) { return KarmaPlayerState.getInt(player, "alignment_choice"); }
	public static boolean chooseAlignment(ServerPlayer player, SocialCurrencyService.Stance stance) {
		if (stance == SocialCurrencyService.Stance.NEUTRAL) return false;
		if (alignmentChoice(player) != 0) return false;
		if (Math.abs(get(player)) >= karma(STARTING_ALIGNMENT_KARMA)) return false;
		int karma = stance == SocialCurrencyService.Stance.LIGHT ? STARTING_ALIGNMENT_KARMA : -STARTING_ALIGNMENT_KARMA;
		SocialCurrencyService.getStance(player); // Capture alignment before crossing a threshold.
		score(player).set(karma(karma));
		SocialCurrencyService.getStance(player);
		KarmaPlayerState.setInt(player, "alignment_choice", stance == SocialCurrencyService.Stance.LIGHT ? 1 : -1);
		updateProgression(player, get(player));
		updateHealthBonus(player);
		return true;
	}

	public static void initializeAlignmentChoiceFromExistingKarma(ServerPlayer player) {
		if (alignmentChoice(player) != 0) return;
		int current = get(player);
		if (current >= karma(STARTING_ALIGNMENT_KARMA)) KarmaPlayerState.setInt(player, "alignment_choice", 1);
		else if (current <= -karma(STARTING_ALIGNMENT_KARMA)) KarmaPlayerState.setInt(player, "alignment_choice", -1);
	}

	/** Community progression stage shared by the whole server. */
	public static int progressionStage(ServerPlayer player) {
		return CommunityProgressionService.stage(player);
	}

	/** Individual Karma stage retained for health/title-band calculations. */
	public static int individualKarmaStage(ServerPlayer player) {
		int current = Math.abs(get(player)) / POINTS_PER_KARMA;
		int stage = 0;
		for (int threshold : PERSONAL_KARMA_THRESHOLDS) if (current >= threshold) stage++;
		return stage;
	}

	private static void updateProgression(ServerPlayer player, int karmaPoints) {
		int whole = Math.abs(karmaPoints) / POINTS_PER_KARMA;
		int best = KarmaPlayerState.getInt(player, karmaPoints >= 0 ? "light_progress" : "dark_progress");
		int reached = best;
		for (int i = 0; i < PERSONAL_KARMA_THRESHOLDS.length; i++) if (whole >= PERSONAL_KARMA_THRESHOLDS[i]) reached = Math.max(reached, PERSONAL_KARMA_THRESHOLDS[i]);
		if (reached != best) KarmaPlayerState.setInt(player, karmaPoints >= 0 ? "light_progress" : "dark_progress", reached);
	}

	public static int naturalDecayFloor(ServerPlayer player) {
		int current = get(player);
		int best = current >= 0
			? KarmaPlayerState.getInt(player, "light_progress")
			: KarmaPlayerState.getInt(player, "dark_progress");
		int floor = 0;
		for (int i = 0; i < PERSONAL_KARMA_THRESHOLDS.length; i++) {
			if (best >= PERSONAL_KARMA_THRESHOLDS[i]) {
				floor = i == 0 ? 0 : PERSONAL_KARMA_THRESHOLDS[i - 1];
			}
		}
		return current >= 0 ? karma(floor) : -karma(floor);
	}

	public static void maintain(ServerPlayer player) {
		decayKarma(player);
		maintainSocialCurrencies(player);
	}

	public static void maintainSocialCurrencies(ServerPlayer player) {
		decaySocial(player, true);
		decaySocial(player, false);
	}

	/** Applies exactly one configured Karma-decay interval immediately for testing. */
	public static int debugDecayKarmaOnce(ServerPlayer player) {
		ShrineConfig config = ShrineConfig.current();
		int current = get(player);
		if (current == 0) return 0;

		int floor = naturalDecayFloor(player);
		int distance = Math.max(0, Math.abs(current - floor));
		int decayPerInterval = Math.max(1, (int) Math.ceil(distance * config.karmaDecayPercent / 100.0));
		int next = current > 0
			? Math.max(floor, current - decayPerInterval)
			: Math.min(floor, current + decayPerInterval);
		if (next != current) {
			SocialCurrencyService.getStance(player); // Capture alignment before crossing a threshold.
			score(player).set(next);
                KicHistoryService.recordKarma(player, next - current, "natural decay");
                KicMessages.change(player, "Karma", next - current, "natural decay");
			SocialCurrencyService.getStance(player);
			updateHealthBonus(player, next, config);
		}
		return next - current;
	}

	private static void decayKarma(ServerPlayer player) {
		ShrineConfig config = ShrineConfig.current();
		int minute = decayMinute(player);
		int last = KarmaPlayerState.getInt(player, "karma_decay_last");
		if (KarmaPlayerState.getInt(player, "karma_decay_playtime_v1") == 0 || minute < last) {
            KarmaPlayerState.setInt(player, "karma_decay_playtime_v1", 1);
			KarmaPlayerState.setInt(player, "karma_decay_last", minute);
			return;
		}
		int interval = config.karmaDecayIntervalMinutes;
		if (minute - last < interval) return;
		int intervals = (minute - last) / interval;
		int current = get(player);
		if (current != 0) {
			int floor = naturalDecayFloor(player);
			int distance = Math.max(0, Math.abs(current - floor));
			int decayPerInterval = (int) Math.ceil(distance * config.karmaDecayPercent / 100.0);
			decayPerInterval = Math.max(1, decayPerInterval);
			int delta = intervals * decayPerInterval;
			int next = current > 0
				? Math.max(floor, current - delta)
				: Math.min(floor, current + delta);
			if (next != current) {
				SocialCurrencyService.getStance(player); // Capture alignment before crossing a threshold.
				score(player).set(next);
                KicHistoryService.recordKarma(player, next - current, "natural decay");
                KicMessages.change(player, "Karma", next - current, "natural decay");
				SocialCurrencyService.getStance(player);
				updateHealthBonus(player, next, config);
			}
		}
		KarmaPlayerState.setInt(player, "karma_decay_last", minute);
	}

    public static int socialDecayAmount(int balance, int percent) {
        return KicBalanceRules.socialDecay(balance, percent);
    }
    public static int applySocialDecay(ServerPlayer player, boolean reputation) {
        var config = ShrineConfig.current();
        int balance = reputation ? SocialCurrencyService.getReputation(player) : getInfamyPoints(player);
        int loss = socialDecayAmount(balance, reputation ? config.reputationDecayPercent : config.infamyDecayPercent);
        if (loss > 0) {
            if (reputation) SocialCurrencyService.addReputation(player, -loss, "natural decay");
            else addInfamyPoints(player, -loss, "natural decay");
        }
        return loss;
    }
    private static void decaySocial(ServerPlayer player, boolean reputation) {
        var config = ShrineConfig.current();
        int interval = reputation ? config.reputationDecayIntervalMinutes : config.infamyDecayIntervalMinutes;
        String key = reputation ? "reputation_decay_last" : "infamy_decay_last";
        int minute = decayMinute(player);
        if (KarmaPlayerState.getInt(player, key + "_playtime_v1") == 0 || minute < KarmaPlayerState.getInt(player, key)) {
            KarmaPlayerState.setInt(player, key + "_playtime_v1", 1);
            KarmaPlayerState.setInt(player, key, minute);
            return;
        }
        int last = KarmaPlayerState.getInt(player, key);
        int intervals = Math.max(0, (minute - last) / interval);
        for (int i = 0; i < intervals; i++) if (applySocialDecay(player, reputation) == 0) break;
        if (intervals > 0) KarmaPlayerState.setInt(player, key, last + intervals * interval);
    }
    public static String decayPreview(ServerPlayer player) {
        var c = ShrineConfig.current();
        StringBuilder result = new StringBuilder("Decay for " + player.getName().getString());
        for (String type : new String[]{"karma", "reputation", "infamy"}) {
            int interval = type.equals("karma") ? c.karmaDecayIntervalMinutes : type.equals("reputation") ? c.reputationDecayIntervalMinutes : c.infamyDecayIntervalMinutes;
            int last = KarmaPlayerState.getInt(player, type + "_decay_last");
            String marker = type.equals("karma") ? "karma_decay_playtime_v1" : type + "_decay_last_playtime_v1";
            int remaining = KarmaPlayerState.getInt(player, marker) == 0 ? interval
                : Math.max(0, interval - Math.max(0, decayMinute(player) - last));
            int loss = type.equals("karma") ? Math.min(Math.abs(get(player) - naturalDecayFloor(player)), Math.max(1, (int)Math.ceil(Math.abs(get(player) - naturalDecayFloor(player)) * c.karmaDecayPercent / 100.0)))
                : socialDecayAmount(type.equals("reputation") ? SocialCurrencyService.getReputation(player) : getInfamyPoints(player), type.equals("reputation") ? c.reputationDecayPercent : c.infamyDecayPercent);
            result.append("\n").append(type).append(": ").append(type.equals("karma") ? format(loss) : loss).append(" decay; due in ").append(remaining).append(" online minutes");
        }
        return result.toString();
    }


	/** Each full interval above 100 absolute Karma grants one health point (half a heart). */
	static int healthBonusHalfHearts(int karmaPoints, int intervalKarma, int maxHalfHearts) {
		if (intervalKarma <= 0 || maxHalfHearts <= 0) return 0;
		long eligiblePoints = Math.max(0L, Math.abs((long) karmaPoints) - (long) STARTING_ALIGNMENT_KARMA * POINTS_PER_KARMA);
		long earned = eligiblePoints / ((long) intervalKarma * POINTS_PER_KARMA);
		return (int) Math.min(earned, Math.min(10, maxHalfHearts));
	}

	public static void updateHealthBonus(ServerPlayer player) { updateHealthBonus(player, get(player), ShrineConfig.current()); }
	private static void updateHealthBonus(ServerPlayer player, int karmaPoints, ShrineConfig config) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute == null) return;
		int halfHearts = healthBonusHalfHearts(karmaPoints, config.karmaHeartThreshold, config.karmaMaxBonusHalfHearts);
		attribute.addOrUpdateTransientModifier(new AttributeModifier(KARMA_HEALTH_MODIFIER_ID, halfHearts, AttributeModifier.Operation.ADD_VALUE));
		player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
	}

	public static String format(int points) {
		long value = points; boolean negative = value < 0; long absolute = Math.abs(value); long fraction = absolute % POINTS_PER_KARMA;
		return (negative ? "-" : "") + (absolute / POINTS_PER_KARMA) + "." + (fraction < 10 ? "0" : "") + fraction;
	}

	private static ScoreAccess score(ServerPlayer player) { return score(player, OBJECTIVE_NAME, "Karma"); }
	private static ScoreAccess score(ServerPlayer player, String objectiveName, String displayName) {
		MinecraftServer server = player.level().getServer();
		if (server == null) throw new IllegalStateException("Karma can only be changed on the server");
		return server.getScoreboard().getOrCreatePlayerScore(player, objective(server.getScoreboard(), objectiveName, displayName));
	}
	private static Objective objective(Scoreboard scoreboard, String name, String displayName) {
		Objective objective = scoreboard.getObjective(name);
		if (objective == null) objective = scoreboard.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(displayName), ObjectiveCriteria.RenderType.INTEGER, false, null);
		return objective;
	}
    /** Decay alone uses accumulated online ticks; other cooldown clocks stay unchanged. */
    private static int decayMinute(ServerPlayer player) {
        return player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME)) / 1200;
    }

	private static int currentMinute(ServerPlayer player) { return (int) (player.level().getGameTime() / 1200L); }
}
