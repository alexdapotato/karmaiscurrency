package akisei.karmacurrency;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.core.Holder;

/** Shared shop service. Shops spend social currencies, never Karma. */
public final class KarmaShopCatalog {
	private static final String[] CYCLING_STEW_EFFECTS = {
		"minecraft:night_vision",
		"minecraft:jump_boost",
		"minecraft:fire_resistance",
		"minecraft:water_breathing",
		"minecraft:saturation",
		"minecraft:regeneration"
	};
	private static final String[] CYCLING_ENCHANTMENTS = {
		"minecraft:efficiency",
		"minecraft:fortune",
		"minecraft:protection",
		"minecraft:sharpness",
		"minecraft:unbreaking",
		"minecraft:mending"
	};

    private static String stockFamily(String id) {
        return id.equals("ender_pearl_small") ? "ender_pearl" : id;
    }

	private KarmaShopCatalog() {}

	public static void reload() { KarmaShopConfig.load(); }
	public static List<ShopReward> rewardsFor(ShopCategory category) {
		return rewardsFor(category, KicCycle.currentCycleId());
	}

	/** Deterministic 8-10 item catalog for one shop side. Locked items remain as teasers.
	 * The selected snapshot is persisted per cycle and is guaranteed to differ from
	 * the preceding cycle when an alternative reward exists.
	 */
	public static List<ShopReward> rewardsFor(ShopCategory category, long cycle) {
		if (category == null) return List.of();
		java.util.Map<String, ShopReward> uniqueItems = new java.util.LinkedHashMap<>();
		for (ShopReward reward : KarmaShopConfig.rewardsFor(category)) {
			if (reward.enabled() && reward.shrineType() != ShrineType.MONUMENT) {
				uniqueItems.putIfAbsent(reward.id(), reward);
			}
		}
        // Keep only one pearl bundle eligible per side and never duplicate it across sides.
        if (uniqueItems.containsKey("ender_pearl") && uniqueItems.containsKey("ender_pearl_small"))
            uniqueItems.remove(Math.floorMod(cycle, 2) == 0 ? "ender_pearl_small" : "ender_pearl");
		if (category == ShopCategory.VILLAIN) {
            Set<String> commonItems = rewardsFor(ShopCategory.COMMON, cycle).stream()
                .map(reward -> stockFamily(reward.id())).collect(java.util.stream.Collectors.toSet());
            List<ShopReward> distinct = uniqueItems.values().stream()
                .filter(reward -> !commonItems.contains(stockFamily(reward.id()))).toList();
            if (distinct.size() >= 10) {
                uniqueItems.clear(); distinct.forEach(reward -> uniqueItems.put(reward.id(), reward));
            }
        }
        List<ShopReward> candidates = uniqueItems.values().stream()
			.sorted(java.util.Comparator.comparing(ShopReward::id)).toList();
		if (candidates.isEmpty()) return List.of();

		int targetSize = candidates.size() <= 8 ? candidates.size()
			: 8 + KicCycle.randomInt(cycle, "shop-size-" + category.name(), Math.min(3, candidates.size() - 7));
		return KicCycle.shopPool(category, candidates, cycle, targetSize,
			ShopReward::id, ShopReward::weight, ShopReward::itemCategory);
	}

	/** Combined helper retained for compatibility/debugging; production UIs should resolve a specific side. */
	public static List<ShopReward> catalogForCycle(long cycle) {
		List<ShopReward> result = new ArrayList<>();
		result.addAll(rewardsFor(ShopCategory.COMMON, cycle));
		result.addAll(rewardsFor(ShopCategory.VILLAIN, cycle));
		return List.copyOf(result);
	}
	public static ShopReward find(String id) { return KarmaShopConfig.find(id); }

	public static PurchaseResult purchase(ServerPlayer player, ShopReward reward) {
		return purchase(player, reward, KicCycle.currentCycleId());
	}

	/** A captured screen may finish a transaction against its original cycle catalog. */
	public static PurchaseResult purchase(ServerPlayer player, ShopReward submitted, long catalogCycle) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) return PurchaseResult.failure("The shop is disabled for this world.");
		if (submitted == null) return PurchaseResult.failure("That shop item is unavailable.");
		ShopReward reward = rewardsFor(submitted.category(), catalogCycle).stream()
			.filter(value -> value.id().equals(submitted.id())).findFirst().orElse(null);
		if (reward == null || !reward.enabled()) return PurchaseResult.failure("That item was not in this shop cycle.");
		if (CommunityProgressionService.stage(player) < reward.unlockStage()) {
			return PurchaseResult.failure("Requires World Progression Stage " + reward.unlockStage() + ".");
		}
		ShopPurchaseState purchases = ShopPurchaseState.get(player.level().getServer());
		int purchased = purchases.count(player.getUUID(), reward.category(), reward.id(), catalogCycle);
		if (reward.maxPurchases() > 0 && purchased >= reward.maxPurchases()) {
			return PurchaseResult.failure("You have reached the purchase limit for " + reward.displayName() + " this shop cycle.");
		}
		int cost = scaledCost(player, reward);
		if (!canAfford(player, reward, cost)) {
			player.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
			return PurchaseResult.failure(costMessage(reward, cost));
		}
		ItemStack rewardStack = createStack(player, reward, catalogCycle);
		if (rewardStack.isEmpty()) return PurchaseResult.failure("This shop reward is currently unavailable.");
		if (!hasInventoryCapacity(player, rewardStack)) return PurchaseResult.failure("Your inventory does not have enough space.");
        int[] tokensSpent = new int[SocialTokenService.Origin.values().length];
        if (usesInfluence(reward)) {
            int remaining = cost;
            for (SocialTokenService.Origin origin : SocialTokenService.Origin.values()) {
                int removed = SocialTokenService.remove(player, Math.min(remaining, SocialTokenService.count(player, origin)), origin);
                tokensSpent[origin.ordinal()] = removed;
                remaining -= removed;
            }
            if (remaining != 0) {
                refundTokens(player, tokensSpent);
                return PurchaseResult.failure("Your physical Influence changed. Try again.");
            }
        } else spend(player, reward, cost);
		if (!player.addItem(rewardStack)) {
			// The capacity check above should make this unreachable, but never consume
			// currency if a last-second inventory change invalidates it.
			if (usesInfluence(reward)) refundTokens(player, tokensSpent);
            else refund(player, reward, cost);
			return PurchaseResult.failure("Your inventory no longer has enough space.");
		}
        if (usesInfluence(reward)) {
            KicHistoryService.recordInfluence(player, -cost, "bought " + reward.displayName());
            player.sendSystemMessage(Component.literal("Influence -" + cost + " (bought " + reward.displayName() + ")"));
        }
		purchases.increment(player.getUUID(), reward.category(), reward.id(), catalogCycle);
		KicHistoryService.recordEvent(player, "Purchased " + reward.displayName() + " x" + reward.count() + " from " + reward.category().displayName());
		KicUiText.success(player);
		return PurchaseResult.success("Purchased " + reward.count() + " " + reward.displayName() + ".");
	}

	public static ItemStack previewStack(ServerPlayer player, ShopReward reward) {
		return previewStack(player, reward, KicCycle.currentCycleId());
	}

	public static ItemStack previewStack(ServerPlayer player, ShopReward reward, long cycle) {
		return createStack(player, reward, cycle);
	}

    private static boolean usesInfluence(ShopReward reward) {
        return reward.item() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING;
    }

    private static void refundTokens(ServerPlayer player, int[] spent) {
        for (SocialTokenService.Origin origin : SocialTokenService.Origin.values())
            if (spent[origin.ordinal()] > 0) SocialTokenService.insert(player, spent[origin.ordinal()], origin);
    }

	public static int scaledCost(ServerPlayer player, ShopReward reward) {
		return usesInfluence(reward) ? reward.cost() : CommunityProgressionService.scaleShopCost(player, reward.cost());
	}

	public static String priceLabel(ServerPlayer player, ShopReward reward) {
		int cost = scaledCost(player, reward);
        if (usesInfluence(reward)) return cost + " Influence";
		return switch (reward.shrineType()) {
			case LIGHT, NEUTRAL -> cost + " Reputation";
			case DARK -> cost + " Infamy";
			case MONUMENT -> cost + " currency";
			case null -> cost + " currency";
		};
	}

	public static String balanceLabel(ServerPlayer player, ShopReward reward) {
        boolean dark = reward.shrineType() == ShrineType.DARK;
        int balance = usesInfluence(reward) ? SocialTokenService.count(player) : dark ? KarmaService.getInfamyPoints(player) : SocialCurrencyService.getReputation(player);
        int cost = scaledCost(player, reward);
        return (balance >= cost ? "Ready: " : "Missing: ") + (usesInfluence(reward) ? "Influence " : dark ? "Infamy " : "Reputation ") + balance + "/" + cost;
    }

    public static String purchaseBlocker(ServerPlayer player, ShopReward reward, long cycle) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) return "The shop is disabled.";
        String lock = lockLabel(player, reward);
        if (!lock.isEmpty()) return lock;
        int bought = ShopPurchaseState.get(player.level().getServer()).count(player.getUUID(), reward.category(), reward.id(), cycle);
        if (reward.maxPurchases() > 0 && bought >= reward.maxPurchases()) return "Purchase limit reached for this cycle.";
        if (!canAfford(player, reward, scaledCost(player, reward))) return balanceLabel(player, reward);
        ItemStack stack = createStack(player, reward, cycle);
        if (stack.isEmpty()) return "This reward is unavailable.";
        if (!hasInventoryCapacity(player, stack)) return "Not enough inventory space for " + reward.count() + " items.";
        return "";
    }

	public static String lockLabel(ServerPlayer player, ShopReward reward) {
		int stage = CommunityProgressionService.stage(player);
		return stage >= reward.unlockStage() ? "" : "LOCKED — Requires World Progression Stage " + reward.unlockStage();
	}

	private static ItemStack createStack(ServerPlayer player, ShopReward reward, long cycle) {
		ItemStack stack = new ItemStack(reward.item(), reward.count());
		if ("suspicious_stew_cycle".equals(reward.mode())) {
			int index = Math.floorMod(cycle, CYCLING_STEW_EFFECTS.length);
			Identifier effectId = Identifier.parse(CYCLING_STEW_EFFECTS[index]);
			Holder<MobEffect> effect = player.level().registryAccess()
				.lookupOrThrow(Registries.MOB_EFFECT)
				.get(effectId).orElse(null);
			if (effect == null) return ItemStack.EMPTY;
			stack.set(net.minecraft.core.component.DataComponents.SUSPICIOUS_STEW_EFFECTS,
				new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(effect, 20 * 12))));
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				Component.literal("Suspicious Stew: " + effect.value().getDisplayName().getString()));
		} else if ("random_enchantment".equals(reward.mode()) || "fixed_enchantment".equals(reward.mode())) {
			int index = Math.floorMod(cycle, CYCLING_ENCHANTMENTS.length);
			Holder<Enchantment> enchantment = player.level().registryAccess()
				.lookupOrThrow(Registries.ENCHANTMENT)
				.get(Identifier.parse("fixed_enchantment".equals(reward.mode()) ? reward.variant() : CYCLING_ENCHANTMENTS[index])).orElse(null);
			if (enchantment == null) return ItemStack.EMPTY;
			stack.enchant(enchantment, reward.level());
            if ("fixed_enchantment".equals(reward.mode()) && reward.level() > enchantment.value().getMaxLevel())
                OverlevelBookService.mark(stack, reward.variant());
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				Component.literal("Enchanted Book: " + enchantment.value().description().getString() + " " + reward.level()));
		}
		return stack;
	}

	private static boolean canAfford(ServerPlayer player, ShopReward reward, int cost) {
        if (usesInfluence(reward)) return SocialTokenService.count(player) >= cost;
		return switch (reward.shrineType()) {
			case LIGHT, NEUTRAL -> SocialCurrencyService.getReputation(player) >= cost;
			case DARK -> KarmaService.getInfamyPoints(player) >= cost;
			case MONUMENT -> false;
			case null -> false;
		};
	}

	private static void spend(ServerPlayer player, ShopReward reward, int cost) {
		switch (reward.shrineType()) {
			case LIGHT, NEUTRAL -> SocialCurrencyService.addReputation(player, -cost, "bought " + reward.displayName());
			case DARK -> KarmaService.spendInfamyPoints(player, cost);
			case MONUMENT -> throw new IllegalStateException("Monument shop rewards must use the Light or Dark shop sections");
			case null -> throw new IllegalStateException("Shop reward has no shrine section");
		}
	}

	private static void refund(ServerPlayer player, ShopReward reward, int cost) {
		switch (reward.shrineType()) {
			case LIGHT, NEUTRAL -> SocialCurrencyService.addReputation(player, cost, "shop purchase refund");
			case DARK -> KarmaService.addInfamyPoints(player, cost, "shop purchase refund");
			case MONUMENT -> { }
			case null -> { }
		}
	}

	private static String costMessage(ShopReward reward, int cost) {
        if (usesInfluence(reward)) return "You need " + cost + " physical Influence of any origin.";
		return switch (reward.shrineType()) {
			case LIGHT, NEUTRAL -> "You need " + cost + " Reputation.";
			case DARK -> "You need " + cost + " Infamy.";
			case MONUMENT -> "This item is not assigned to a valid shop.";
			case null -> "This shop item has an invalid currency section.";
		};
	}

	private static boolean hasInventoryCapacity(ServerPlayer player, ItemStack stack) {
		int remaining = stack.getCount();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack existing = player.getInventory().getItem(slot);
			if (existing.isEmpty()) remaining -= stack.getMaxStackSize();
			else if (ItemStack.isSameItemSameComponents(existing, stack)) remaining -= existing.getMaxStackSize() - existing.getCount();
			if (remaining <= 0) return true;
		}
		return false;
	}

	public record PurchaseResult(boolean successful, String message) {
		public static PurchaseResult success(String message) { return new PurchaseResult(true, message); }
		public static PurchaseResult failure(String message) { return new PurchaseResult(false, message); }
		public Component asComponent() { return Component.literal(message); }
	}
}
