package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * First-pass implementation of the new social-currency model:
 * Karma = stance, Reputation = Light social currency, Infamy = Dark social
 * currency, and Influence = transferable physical value with Light/Neutral/Dark origin.
 *
 * Gain/decay rules are intentionally not moved here yet. This service only
 * implements the agreed physicalization/redemption layer so it can be tested
 * independently before the larger economy rewrite.
 */
public final class SocialCurrencyService {
	public static final String REPUTATION_OBJECTIVE = "reputation_points";
	public static final String REPUTATION_DECAY_OBJECTIVE = "reputation_decay";
	public static final int DEFAULT_MATERIALIZATION_PERCENT = 100;
	public static final int DEFAULT_NEUTRAL_REDEMPTION_PERCENT = 100;
	public static final int DEFAULT_ALIGNED_REDEMPTION_PERCENT = 100;
	public static final int OFFERING_MINIMUM_GROSS = 32;
	public static final int OFFERING_NET_PERCENT = 95;
	public static final int OFFERING_TAX_PERCENT = 100 - OFFERING_NET_PERCENT;
	private static final String DEMATERIALIZATION_COOLDOWN_UNTIL = "social_dematerialization_cooldown_until";

	public enum RedemptionTarget {
		REPUTATION("Reputation"),
		INFAMY("Infamy");

		private final String displayName;

		RedemptionTarget(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	public enum Stance {
		LIGHT,
		NEUTRAL,
		DARK
	}

	private SocialCurrencyService() {
	}

	public static int offeringNet(double gross) {
		return (int) (Math.max(0, gross) * OFFERING_NET_PERCENT / 100L);
	}

	public static boolean canDematerialize(ServerPlayer player) {
		return dematerializationCooldownRemainingSeconds(player) == 0;
	}

	public static int dematerializationCooldownRemainingSeconds(ServerPlayer player) {
		long remainingTicks = KarmaPlayerState.get(player, DEMATERIALIZATION_COOLDOWN_UNTIL)
			- player.level().getGameTime();
		return remainingTicks <= 0 ? 0 : (int) ((remainingTicks + 19L) / 20L);
	}

	public static void startDematerializationCooldown(ServerPlayer player) {
		int seconds = ShrineConfig.current().socialDematerializationCooldownSeconds;
		if (seconds > 0) {
			KarmaPlayerState.set(player, DEMATERIALIZATION_COOLDOWN_UNTIL,
				player.level().getGameTime() + seconds * 20L);
		}
	}

	public static int getReputation(ServerPlayer player) {
		return score(player, REPUTATION_OBJECTIVE, "Reputation").get();
	}

	public static void setReputation(ServerPlayer player, int amount) {
		int before = getReputation(player);
        int total = Math.max(0, amount);
        score(player, REPUTATION_OBJECTIVE, "Reputation").set(total);
        if (total != before) player.sendSystemMessage(Component.literal("Reputation " + (total > before ? "+" : "") + (total - before) + " (set Reputation) | Total: " + total));
	}

	public static int addReputation(ServerPlayer player, int amount, String reason) {
		if (amount == 0) return getReputation(player);
		ScoreAccess reputation = score(player, REPUTATION_OBJECTIVE, "Reputation");
		int before = reputation.get();
		int total = Math.max(0, before + amount);
		reputation.set(total);
		KicHistoryService.recordSocial(player, "Reputation", total - before, reason);
		if (amount > 0 && !"Influence redemption".equals(reason) && !"shop purchase refund".equals(reason)) {
			CommunityProgressionService.recordReputation(player, amount);
		}
		player.sendSystemMessage(Component.literal(
			"Reputation " + (total > before ? "+" : "") + (total - before) + " (" + reason + ") | Total: " + total
		));
		return total;
	}

	public static Stance getStance(ServerPlayer player) {
		int karma = KarmaService.get(player);
		int saved = KarmaPlayerState.getInt(player, "stance_grace_state");
		// Zero means an old save: use the original choice only when it agrees with the current sign.
		Stance previous = saved == 1 ? Stance.LIGHT : saved == -1 ? Stance.DARK : Stance.NEUTRAL;
		if (saved == 0) {
			int choice = KarmaService.alignmentChoice(player);
			if (choice < 0 && karma < 0) previous = Stance.DARK;
			else if (choice > 0 && karma > 0) previous = Stance.LIGHT;
		}
		Stance next = stanceWithGrace(karma, previous);
		int nextState = next == Stance.LIGHT ? 1 : next == Stance.DARK ? -1 : 2;
		if (saved != nextState) KarmaPlayerState.setInt(player, "stance_grace_state", nextState);
        if (next != Stance.NEUTRAL && KarmaService.alignmentChoice(player) != nextState)
            KarmaPlayerState.setInt(player, "alignment_choice", nextState);
		return next;
	}

	/** Enter at +/-100; retain an established alignment until its sign reaches zero. */
	static Stance stanceWithGrace(int karmaPoints, Stance previous) {
		Stance threshold = stanceForKarma(karmaPoints);
		if (threshold != Stance.NEUTRAL) return threshold;
		if (previous == Stance.DARK && karmaPoints < 0) return Stance.DARK;
		if (previous == Stance.LIGHT && karmaPoints > 0) return Stance.LIGHT;
		return Stance.NEUTRAL;
	}

	public static Stance stanceForKarma(int karmaPoints) {
		if (karmaPoints >= KarmaService.karma(KarmaService.STARTING_ALIGNMENT_KARMA)) return Stance.LIGHT;
		if (karmaPoints <= -KarmaService.karma(KarmaService.STARTING_ALIGNMENT_KARMA)) return Stance.DARK;
		return Stance.NEUTRAL;
	}

	public static int materializeReputation(ServerPlayer player, int reputationAmount) {
		if (reputationAmount <= 0 || getReputation(player) < reputationAmount) return 0;
		return materialize(player, reputationAmount, RedemptionTarget.REPUTATION);
	}

	public static int materializeInfamy(ServerPlayer player, int infamyAmount) {
		if (infamyAmount <= 0 || KarmaService.getInfamyPoints(player) < infamyAmount) return 0;
		return materialize(player, infamyAmount, RedemptionTarget.INFAMY);
	}

	/** Removes social currency and returns the physical token stack without inserting it. */
	public static net.minecraft.world.item.ItemStack materializeReputationToStack(ServerPlayer player, int reputationAmount) {
		if (reputationAmount <= 0 || getReputation(player) < reputationAmount) return net.minecraft.world.item.ItemStack.EMPTY;
		return materializeToStack(player, reputationAmount, RedemptionTarget.REPUTATION);
	}

	/** Removes social currency and returns the physical token stack without inserting it. */
	public static net.minecraft.world.item.ItemStack materializeInfamyToStack(ServerPlayer player, int infamyAmount) {
		if (infamyAmount <= 0 || KarmaService.getInfamyPoints(player) < infamyAmount) return net.minecraft.world.item.ItemStack.EMPTY;
		return materializeToStack(player, infamyAmount, RedemptionTarget.INFAMY);
	}

	private static net.minecraft.world.item.ItemStack materializeToStack(ServerPlayer player, int sourceAmount, RedemptionTarget source) {
		int tokenCount = (int) ((long) sourceAmount * materializationPercent() / 100L);
		if (tokenCount <= 0) return net.minecraft.world.item.ItemStack.EMPTY;
		if (source == RedemptionTarget.REPUTATION) {
			score(player, REPUTATION_OBJECTIVE, "Reputation").add(-sourceAmount);
			KicHistoryService.recordSocial(player, "Reputation", -sourceAmount, "materialized into Influence");
			KicHistoryService.recordInfluence(player, tokenCount, "materialized from Reputation");
			return SocialTokenService.createToken(tokenCount, SocialTokenService.Origin.LIGHT);
		}
		KarmaService.spendInfamyPoints(player, sourceAmount);
		KicHistoryService.recordInfluence(player, tokenCount, "materialized from Infamy");
		return SocialTokenService.createToken(tokenCount, SocialTokenService.Origin.DARK);
	}

	private static int materialize(ServerPlayer player, int sourceAmount, RedemptionTarget source) {
		var stack = materializeToStack(player, sourceAmount, source);
		if (stack.isEmpty()) return 0;
		SocialTokenService.insert(player, stack.getCount(), source == RedemptionTarget.REPUTATION
			? SocialTokenService.Origin.LIGHT : SocialTokenService.Origin.DARK);
		return stack.getCount();
	}

	public static int redeem(ServerPlayer player, int tokenAmount, RedemptionTarget target) {
		if (tokenAmount <= 0) return 0;
		// Influence Tokens are inventory items. The old bank is no longer part of the
		// economy, so redemption only considers physical tokens in the inventory.
		Stance stance = getStance(player);
		int percent;
		SocialTokenService.Origin primaryOrigin;
		switch (stance) {
			case LIGHT -> {
				if (target != RedemptionTarget.REPUTATION) return 0;
				percent = alignedRedemptionPercent();
				primaryOrigin = SocialTokenService.Origin.LIGHT;
			}
			case DARK -> {
				if (target != RedemptionTarget.INFAMY) return 0;
				percent = alignedRedemptionPercent();
				primaryOrigin = SocialTokenService.Origin.DARK;
			}
			case NEUTRAL -> {
				percent = neutralRedemptionPercent();
				primaryOrigin = SocialTokenService.Origin.NEUTRAL;
			}
			default -> throw new IllegalStateException("Unhandled stance " + stance);
		}

		int available = SocialTokenService.count(player, primaryOrigin);
		if (primaryOrigin != SocialTokenService.Origin.NEUTRAL) {
			available += SocialTokenService.count(player, SocialTokenService.Origin.NEUTRAL);
		}
		if (available < tokenAmount) return 0;

		int output = (int) ((long) tokenAmount * percent / 100L);
		if (output <= 0) return 0;

		// Light/Dark players may also redeem Neutral tokens. Consume their aligned
		// tokens first, then use Neutral tokens as the shared fallback.
		int removed = SocialTokenService.remove(player, tokenAmount, primaryOrigin);
		if (removed < tokenAmount && primaryOrigin != SocialTokenService.Origin.NEUTRAL) {
			removed += SocialTokenService.remove(player, tokenAmount - removed, SocialTokenService.Origin.NEUTRAL);
		}
		if (removed != tokenAmount) return 0;
		KicHistoryService.recordInfluence(player, -tokenAmount, "redeemed into " + target.displayName());

		if (target == RedemptionTarget.REPUTATION) {
			score(player, REPUTATION_OBJECTIVE, "Reputation").add(output);
			KicHistoryService.recordSocial(player, "Reputation", output, "Influence redemption");
		} else {
			KarmaService.addInfamyPoints(player, output, "Influence redemption");
		}
		return output;
	}

	/** Social currency now materializes 1:1. The old tax setting is retained only for config compatibility. */

	/** Dematerialization accepts tagged Influence of every origin at one gross value each. */
	public static OfferingResult dematerializeInfluence(ServerPlayer player, int amount) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)
				|| !KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED)) {
			return OfferingResult.failure("Dematerialization is disabled for this world.");
		}
		if (!canDematerialize(player)) return OfferingResult.failure("Dematerialization cooldown: "
			+ KicUiText.durationSeconds(dematerializationCooldownRemainingSeconds(player)));
		if (amount < OFFERING_MINIMUM_GROSS) return OfferingResult.failure("Dematerialization needs at least "
			+ OFFERING_MINIMUM_GROSS + " Influence.");
		if (SocialTokenService.count(player) < amount) return OfferingResult.failure("You do not have enough Influence.");
		int net = offeringNet(amount);
		SocialTokenService.remove(player, amount);
		KicHistoryService.recordInfluence(player, -amount, "Influence dematerialization");
		String target = switch (KarmaService.rewardAlignment(player)) {
			case LIGHT -> { addReputation(player, net, "Influence dematerialization"); yield "Reputation"; }
			case DARK -> { KarmaService.addInfamyPoints(player, net, "Influence dematerialization"); yield "Infamy"; }
			case NEUTRAL -> { SocialTokenService.insert(player, net, SocialTokenService.Origin.NEUTRAL); yield "Neutral Influence"; }
		};
		startDematerializationCooldown(player);
		KicUiText.success(player);
		return OfferingResult.success(amount, net, target, KicFlavorText.offering(KarmaService.rewardAlignment(player), net, target));
	}

	/** Executes a configured physical offering using the same rules as the Java offering tray. */
	public static OfferingResult offerItem(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
		if (item == null) return OfferingResult.failure("That item cannot be offered.");
		return offerItems(player, java.util.Map.of(item, amount));
	}

	/** Executes a single atomic multi-item offering through the existing Shrine offering rules. */
	public static OfferingResult offerItems(ServerPlayer player,
			java.util.Map<net.minecraft.world.item.Item, Integer> requirements) {
        return offerItems(player, requirements, -1);
    }

    public static OfferingResult offerQuestItems(ServerPlayer player,
            java.util.Map<net.minecraft.world.item.Item, Integer> requirements, QuestObjectiveCatalog.Objective objective) {
        return offerItems(player, requirements, QuestObjectiveCatalog.fixedOfferingNet(objective));
    }

    private static OfferingResult offerItems(ServerPlayer player,
            java.util.Map<net.minecraft.world.item.Item, Integer> requirements, int fixedNet) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED)) {
			return OfferingResult.failure("Offerings are disabled for this world.");
		}
		if (requirements == null || requirements.isEmpty()) return OfferingResult.failure("That offering is empty.");
		if (!canDematerialize(player)) {
			return OfferingResult.failure("Dematerialization cooldown: " + KicUiText.durationSeconds(dematerializationCooldownRemainingSeconds(player)));
		}
		java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer> requested = new java.util.LinkedHashMap<>();
		double grossLong = 0.0;
		for (var entry : requirements.entrySet()) {
			net.minecraft.world.item.Item item = entry.getKey();
			int amount = entry.getValue() == null ? 0 : entry.getValue();
			if (item == null || amount <= 0 || !KarmaOfferingConfig.accepts(item)) {
				return OfferingResult.failure("That item cannot be offered.");
			}
			requested.put(item, amount);
			int available = 0;
			for (net.minecraft.world.item.ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                if (stack.is(item) && !SocialTokenService.isToken(stack)) {
                    int taken = Math.min(stack.getCount(), Math.max(0, amount - available));
                    grossLong += KarmaOfferingConfig.socialPerStackItem(stack, player) * taken;
                    available += taken;
                }
			}
			if (available < amount) return OfferingResult.failure("You do not have enough "
				+ new net.minecraft.world.item.ItemStack(item).getHoverName().getString() + " to offer.");

		}
		int gross = (int) Math.min(Integer.MAX_VALUE, grossLong);
		if (fixedNet < 0 && gross < OFFERING_MINIMUM_GROSS) return OfferingResult.failure("Offerings need at least "
			+ OFFERING_MINIMUM_GROSS + " social value (current: " + gross + ").");
		int netValue = fixedNet >= 0 ? fixedNet : offeringNet(grossLong);
		if (netValue <= 0) return OfferingResult.failure("That offering has no social-currency value after tax.");

		for (var request : requested.entrySet()) {
			int remaining = request.getValue();
			for (net.minecraft.world.item.ItemStack stack : player.getInventory().getNonEquipmentItems()) {
				if (remaining <= 0) break;
				if (!stack.is(request.getKey()) || SocialTokenService.isToken(stack)) continue;
				int remove = Math.min(remaining, stack.getCount());
				stack.shrink(remove);
				remaining -= remove;
			}
			if (remaining != 0) return OfferingResult.failure("The offering could not be removed safely.");
		}

		Stance stance = KarmaService.rewardAlignment(player);
		String target;
		switch (stance) {
			case LIGHT -> { addReputation(player, netValue, "Shrine offering"); target = "Reputation"; }
			case DARK -> { KarmaService.addInfamyPoints(player, netValue, "Shrine offering"); target = "Infamy"; }
			case NEUTRAL -> { SocialTokenService.insert(player, netValue, SocialTokenService.Origin.NEUTRAL); target = "Neutral Influence"; }
			default -> throw new IllegalStateException("Unhandled stance " + stance);
		}
		startDematerializationCooldown(player);
		KicUiText.success(player);
		return OfferingResult.success(gross, netValue, target, KicFlavorText.offering(stance, netValue, target));
	}

	public record OfferingResult(boolean successful, int grossValue, int netValue, String target, String message) {
		static OfferingResult success(int gross, int net, String target, String message) { return new OfferingResult(true, gross, net, target, message); }
		static OfferingResult failure(String message) { return new OfferingResult(false, 0, 0, "", message); }
	}

	public static int materializationPercent() {
		return 100;
	}

	public static int neutralRedemptionPercent() {
		return ShrineConfig.current().socialNeutralRedemptionPercent;
	}

	public static int alignedRedemptionPercent() {
		return ShrineConfig.current().socialAlignedRedemptionPercent;
	}

	private static ScoreAccess score(ServerPlayer player, String objectiveName, String displayName) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			throw new IllegalStateException("Social currency can only be changed on the server");
		}

		Objective objective = server.getScoreboard().getObjective(objectiveName);
		if (objective == null) {
			objective = server.getScoreboard().addObjective(
				objectiveName,
				ObjectiveCriteria.DUMMY,
				Component.literal(displayName),
				ObjectiveCriteria.RenderType.INTEGER,
				false,
				null
			);
		}
		return server.getScoreboard().getOrCreatePlayerScore(player, objective);
	}
}
