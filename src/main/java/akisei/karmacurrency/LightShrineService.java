package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Gameplay loop for Light Shrines: reputation, trials, and blessings. */
public final class LightShrineService {
	public static final String TRIAL_OBJECTIVE = "light_trial";
	public static final String TRIAL_COOLDOWN_OBJECTIVE = "light_trial_cd";
	public static final String TRIAL_EXPIRY_OBJECTIVE = "light_trial_expiry";
	public static final String BLESSING_COOLDOWN_OBJECTIVE = "light_blessing_cd";

	public static final int TRIAL_NONE = 0;
	public static final int TRIAL_MERCY = 1;
	public static final int TRIAL_PROTECTION = 2;

	private static final int BLESSING_MERCY_COST = 25;
	private static final int BLESSING_PROTECTION_COST = 50;
	private static final int BLESSING_FORTUNE_COST = 100;

	private static final int TRIAL_REWARD_MERCY = 15;
	private static final int TRIAL_REWARD_PROTECTION = 50;
	private static final int TRIAL_DURATION_MINUTES = 60;
	private static final int TRIAL_COOLDOWN_MINUTES = 30;
	private static final int BLESSING_COOLDOWN_MINUTES = 60;

	private LightShrineService() {
	}

	public static int mercyTrialDurationMinutes() {
		return TRIAL_DURATION_MINUTES;
	}

	public static String mercyQuestObjective() {
		return "Cure 1 Zombie Villager before the " + TRIAL_DURATION_MINUTES + "-minute timer expires.";
	}

	public static String mercyQuestRewardDescription() {
		return "+" + QuestConfig.current().mercyKarma + " Karma and +"
			+ ShrineConfig.current().socialMercyTrialGain + " Reputation.";
	}

	public static void open(ServerPlayer player) {
		open(player, null);
	}

	public static void open(ServerPlayer player, ShrineSite site) {
		if (site != null) ShrineMenu.open(player, site);
	}

	public static String tier(ServerPlayer player) {
		return CommunityProgressionService.stageLabel(player);
	}

	public static boolean startTrial(ServerPlayer player, int trial) {
		return startTrial(player, trial, true);
	}

	/** Starts the Mercy integration for a quest acceptance; alignment is validated by QuestService. */
	public static boolean startQuestTrial(ServerPlayer player, int trial) {
		return startTrial(player, trial, false);
	}

	private static boolean startTrial(ServerPlayer player, int trial, boolean requireLightKarma) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
				|| !KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) {
			player.sendSystemMessage(Component.literal("Trials are disabled for this world."));
			return false;
		}
		if (requireLightKarma && KarmaService.get(player) < KarmaService.karma(100)) {
			player.sendSystemMessage(Component.literal("You need at least +100 Karma to undertake a Light trial."));
			return false;
		}
		// Protection Trial is retired from the player-facing catalog and legacy start paths.
		if (trial != TRIAL_MERCY) {
			return false;
		}
		if (trial(player) != TRIAL_NONE) {
			player.sendSystemMessage(Component.literal("You already have an active Light trial."));
			return false;
		}
		int cooldown = Math.max(0, KarmaPlayerState.getInt(player, "light_trial_cd_until") - currentMinute(player));
		if (requireLightKarma && cooldown > 0) {
			player.sendSystemMessage(Component.literal("Light trials are on cooldown for " + cooldown + " minute(s)."));
			return false;
		}

		KarmaPlayerState.setInt(player, "light_trial", trial);
		KarmaPlayerState.setInt(player, "light_trial_expiry", currentMinute(player) + TRIAL_DURATION_MINUTES);
		String name = trial == TRIAL_MERCY ? "Mercy Trial" : "Protection Trial";
		player.sendSystemMessage(Component.literal(name + " begun. Cure 1 Zombie Villager: apply Weakness, use a Golden Apple, then wait for conversion. You have " + TRIAL_DURATION_MINUTES + " minutes."));
		player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
		return true;
	}

	public static boolean claimBlessing(ServerPlayer player, int blessing) {
		if (!CurseBlessingService.canUseLightShrine(player)) {
			player.sendSystemMessage(Component.literal("The Devil bars you from Light Shrine blessings."));
			return false;
		}
		int requiredKarma = switch (blessing) {
			case 1 -> 100;
			case 2 -> 500;
			case 3 -> 1000;
			default -> Integer.MAX_VALUE;
		};
		int currentKarma = KarmaService.get(player) / KarmaService.POINTS_PER_KARMA;
		if (currentKarma < requiredKarma) {
			player.sendSystemMessage(Component.literal("Your Light standing is too low. Reach +" + requiredKarma + " Karma first."));
			return false;
		}
		int cooldown = Math.max(0, KarmaPlayerState.getInt(player, "light_blessing_until") - currentMinute(player));
		if (cooldown > 0) {
			player.sendSystemMessage(Component.literal("Light blessings are on cooldown for " + cooldown + " minute(s)."));
			return false;
		}
		int baseCost = switch (blessing) {
			case 1 -> BLESSING_MERCY_COST;
			case 2 -> BLESSING_PROTECTION_COST;
			case 3 -> BLESSING_FORTUNE_COST;
			default -> 0;
		};
		int cost = CommunityProgressionService.scaleShrineCost(player, baseCost);
		if (cost == 0 || SocialCurrencyService.getReputation(player) < cost) {
			player.sendSystemMessage(Component.literal("You do not have enough Reputation for that blessing."));
			return false;
		}

		SocialCurrencyService.addReputation(player, -cost, "Light blessing");
		int duration = 20 * 60 * 20;
		switch (blessing) {
			case 1 -> {
				player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, duration, 0));
				player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, duration, 0));
				player.sendSystemMessage(Component.literal("Blessing of Mercy granted for 20 minutes."));
			}
			case 2 -> {
				player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, duration, 0));
				player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 20, 0));
				player.sendSystemMessage(Component.literal("Blessing of Protection granted for 20 minutes."));
			}
			case 3 -> {
				player.addEffect(new MobEffectInstance(MobEffects.LUCK, duration, 0));
				player.sendSystemMessage(Component.literal("Blessing of Fortune granted for 20 minutes."));
			}
			default -> { return false; }
		}

		KarmaPlayerState.setInt(player, "light_blessing_until", currentMinute(player) + BLESSING_COOLDOWN_MINUTES);
		player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
		return true;
	}

	public static int trial(ServerPlayer player) {
		int value = KarmaPlayerState.getInt(player, "light_trial");
		if (value == TRIAL_NONE) return TRIAL_NONE;
		if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
				|| !KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) {
			KarmaPlayerState.clear(player, "light_trial");
			KarmaPlayerState.clear(player, "light_trial_expiry");
			if (value == TRIAL_MERCY && QuestService.isActive(player, QuestService.QuestId.MERCY)) {
				QuestService.cancelActive(player);
			}
			player.sendSystemMessage(Component.literal("Your Light trial was cancelled because Trials are disabled."));
			return TRIAL_NONE;
		}
		long expires = KarmaPlayerState.get(player, "light_trial_expiry");
		if (currentMinute(player) > expires) {
			KarmaPlayerState.clear(player, "light_trial");
			KarmaPlayerState.setInt(player, "light_trial_cd_until", currentMinute(player) + TRIAL_COOLDOWN_MINUTES);
			player.sendSystemMessage(Component.literal("Your Light trial expired."));
			if (value == TRIAL_MERCY) QuestService.fail(player, QuestService.QuestId.MERCY);
			return TRIAL_NONE;
		}
		return value;
	}

	public static long trialRemainingMillis(ServerPlayer player) {
		if (KarmaPlayerState.getInt(player, "light_trial") != TRIAL_MERCY) return 0L;
		long minutes = KarmaPlayerState.get(player, "light_trial_expiry") - currentMinute(player);
		return Math.max(0L, minutes) * 60_000L;
	}

	public static boolean hasActiveMercyTrial(ServerPlayer player) {
		return KarmaPlayerState.getInt(player, "light_trial") == TRIAL_MERCY;
	}

	public static void completeTrial(ServerPlayer player, int expectedTrial) {
        if (expectedTrial==TRIAL_MERCY && QuestService.isActive(player,QuestService.QuestId.MERCY) && (!QuestRevamp.eligible(player,QuestService.QuestId.MERCY) || QuestRevamp.get(player,"debug_hold")==1)) return;
		if (trial(player) != expectedTrial) {
			return;
		}
		int reward = expectedTrial == TRIAL_MERCY
            ? (QuestService.isActive(player, QuestService.QuestId.MERCY) ? QuestConfig.current().mercyKarma : TRIAL_REWARD_MERCY)
            : TRIAL_REWARD_PROTECTION;
		KarmaPlayerState.clear(player, "light_trial");
		KarmaPlayerState.setInt(player, "light_trial_cd_until", currentMinute(player) + TRIAL_COOLDOWN_MINUTES);
		int socialReward = expectedTrial == TRIAL_MERCY
			? ShrineConfig.current().socialMercyTrialGain : ShrineConfig.current().socialProtectionTrialGain;
		KarmaService.addWithSocial(player, KarmaService.karma(reward), socialReward, expectedTrial == TRIAL_MERCY ? "Mercy Trial" : "Protection Trial", expectedTrial == TRIAL_MERCY ? "mercy_trial" : "protection_trial");
		player.sendSystemMessage(Component.literal("Light trial complete. +" + reward + " Karma and +" + socialReward + " Reputation."));
		player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.2F);
		if (expectedTrial == TRIAL_MERCY) {
			QuestService.authorizeMercyCompletion(player);
			QuestService.complete(player, QuestService.QuestId.MERCY);
		}
	}

	/** Cancels the existing Mercy timer without awarding its completion reward. */
	public static void cancelQuestTrial(ServerPlayer player) {
		if (KarmaPlayerState.getInt(player, "light_trial") != TRIAL_MERCY) return;
		KarmaPlayerState.clear(player, "light_trial");
		KarmaPlayerState.clear(player, "light_trial_expiry");
		KarmaPlayerState.setInt(player, "light_trial_cd_until", currentMinute(player) + TRIAL_COOLDOWN_MINUTES);
	}

	private static int currentMinute(ServerPlayer player) {
		return (int) (player.level().getGameTime() / 1200L);
	}

}
