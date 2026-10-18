package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Dark Shrine contracts and Infamy-powered curses. */
public final class DarkShrineService {
	public static final int CONTRACT_NONE = 0;
	public static final int CONTRACT_HUNT = 1;
	public static final int CONTRACT_RAID = 2;
	private DarkShrineService() {
	}

	public static void open(ServerPlayer player, ShrineSite site) {
		if (site != null) ShrineMenu.open(player, site);
	}

	public static int contract(ServerPlayer player) {
		int value = KarmaPlayerState.getInt(player, "dark_contract");
		if (value == CONTRACT_NONE) return CONTRACT_NONE;
		if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
				|| !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)) {
			KarmaPlayerState.clear(player, "dark_contract");
			KarmaPlayerState.clear(player, "dark_contract_count");
			KarmaPlayerState.clear(player, "dark_contract_expiry");
			player.sendSystemMessage(Component.literal("Your legacy Dark contract was cancelled because Contracts are disabled."));
			return CONTRACT_NONE;
		}
		if (currentMinute(player) > KarmaPlayerState.getInt(player, "dark_contract_expiry")) {
			KarmaPlayerState.clear(player, "dark_contract");
			KarmaPlayerState.clear(player, "dark_contract_count");
			KarmaPlayerState.setInt(player, "dark_contract_cd", currentMinute(player) + ShrineConfig.current().darkContractCooldownMinutes);
			player.sendSystemMessage(Component.literal("Your Dark contract expired."));
			return CONTRACT_NONE;
		}
		return value;
	}

	public static boolean startContract(ServerPlayer player, int contract) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
				|| !KarmaGameRules.enabled(player, KarmaGameRules.CONTRACTS_ENABLED)) {
			player.sendSystemMessage(Component.literal("Dark contracts are disabled by quests_enabled or contracts_enabled."));
			return false;
		}
		ShrineConfig config = ShrineConfig.current();
		if (contract != CONTRACT_HUNT && contract != CONTRACT_RAID) return false;
		if (contract(player) != CONTRACT_NONE) {
			player.sendSystemMessage(Component.literal("You already have an active Dark contract."));
			return false;
		}
		int cost = CommunityProgressionService.scaleShrineCost(player, config.darkContractCost);
		if (KarmaService.getInfamyPoints(player) < cost) {
			player.sendSystemMessage(Component.literal("You need " + cost + " Infamy to accept a Dark contract at your current community stage."));
			return false;
		}
		int cooldown = Math.max(0, KarmaPlayerState.getInt(player, "dark_contract_cd") - currentMinute(player));
		if (cooldown > 0) {
			player.sendSystemMessage(Component.literal("Dark contracts are on cooldown for " + cooldown + " minute(s)."));
			return false;
		}
		KarmaService.spendInfamyPoints(player, cost);
		KarmaPlayerState.setInt(player, "dark_contract", contract);
		KarmaPlayerState.clear(player, "dark_contract_count");
		KarmaPlayerState.setInt(player, "dark_contract_expiry", currentMinute(player) + config.darkContractDurationMinutes);
		player.sendSystemMessage(Component.literal("Dark contract accepted. Hunt " + config.darkContractTarget + " hostile creatures."));
		player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.8F);
		return true;
	}

	public static void recordHostileKill(ServerPlayer player) {
		if (contract(player) == CONTRACT_NONE) return;
		int count = KarmaPlayerState.getInt(player, "dark_contract_count") + 1;
		ShrineConfig config = ShrineConfig.current();
		if (count >= config.darkContractTarget) {
			KarmaPlayerState.clear(player, "dark_contract");
			KarmaPlayerState.clear(player, "dark_contract_count");
			KarmaPlayerState.setInt(player, "dark_contract_cd", currentMinute(player) + config.darkContractCooldownMinutes);
			KarmaService.addInfamyPoints(player, config.darkContractReward, "completing a Dark contract");
			player.sendSystemMessage(Component.literal("Dark contract complete. +" + config.darkContractReward + " Infamy."));
			player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.8F);
		} else {
			KarmaPlayerState.setInt(player, "dark_contract_count", count);
		}
	}

	public static boolean claimCurse(ServerPlayer player, int curse) {
		if (!CurseBlessingService.canUseDarkShrine(player)) {
			player.sendSystemMessage(Component.literal("Devotion bars you from Dark Shrine curses."));
			return false;
		}
		ShrineConfig config = ShrineConfig.current();
		int baseRequired = curse == 1 ? config.darkCurseRuinCost : curse == 2 ? config.darkCurseSightCost : Integer.MAX_VALUE;
		int required = CommunityProgressionService.scaleShrineCost(player, baseRequired);
		if (KarmaService.getInfamyPoints(player) < required) {
			player.sendSystemMessage(Component.literal("You need " + required + " Infamy."));
			return false;
		}
		int cooldown = Math.max(0, KarmaPlayerState.getInt(player, "dark_curse_cd") - currentMinute(player));
		if (cooldown > 0) {
			player.sendSystemMessage(Component.literal("Dark curses are on cooldown for " + cooldown + " minute(s)."));
			return false;
		}
		KarmaService.spendInfamyPoints(player, required);
		int duration = 20 * 60 * 20;
		if (curse == 1) {
			player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, duration, 0));
			player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, duration, 0));
			player.sendSystemMessage(Component.literal("Curse of Ruin granted for 20 minutes."));
		} else if (curse == 2) {
			player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, duration, 0));
			player.addEffect(new MobEffectInstance(MobEffects.LUCK, duration, 0));
			player.sendSystemMessage(Component.literal("Curse of Sight granted for 20 minutes."));
		} else {
			return false;
		}
		KarmaPlayerState.setInt(player, "dark_curse_cd", currentMinute(player) + config.darkCurseCooldownMinutes);
		return true;
	}

	private static int currentMinute(ServerPlayer player) {
		return (int) (player.level().getGameTime() / 1200L);
	}


}
