package akisei.karmacurrency;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/** Per-world switches for Karma-facing KIC features. */
public final class KarmaGameRules {
	public static final GameRule<Boolean> KARMA_MENU_ENABLED = booleanRule("karma_menu_enabled", true);
	public static final GameRule<Boolean> TITLES_ENABLED = booleanRule("titles_enabled", true);
	public static final GameRule<Boolean> SHOP_ENABLED = booleanRule("shop_enabled", true);
	public static final GameRule<Boolean> SOCIAL_CURRENCY_ENABLED = booleanRule("social_currency_enabled", true);
	public static final GameRule<Boolean> SIGILS_ENABLED = booleanRule("sigils_enabled", true);
	public static final GameRule<Boolean> SHRINE_COMPASS_ENABLED = booleanRule("shrine_compass_enabled", true);
	public static final GameRule<Boolean> OFFERINGS_ENABLED = booleanRule("offerings_enabled", true);
	public static final GameRule<Boolean> SHRINES_ENABLED = booleanRule("shrines_enabled", true);
	public static final GameRule<Boolean> SHRINE_PROTECTION_ENABLED = booleanRule("shrine_protection_enabled", true);
	public static final GameRule<Boolean> BLESSINGS_ENABLED = booleanRule("blessings_enabled", true);
	public static final GameRule<Boolean> CURSES_ENABLED = booleanRule("curses_enabled", true);
	public static final GameRule<Boolean> COMPASS_COOLDOWN_ENABLED = booleanRule("compass_cooldown_enabled", true);
	public static final GameRule<Boolean> HUNT_ENABLED = booleanRule("hunt_enabled", true);
	public static final GameRule<Boolean> QUESTS_ENABLED = booleanRule("quests_enabled", true);
	public static final GameRule<Boolean> CONTRACTS_ENABLED = booleanRule("contracts_enabled", true);
	public static final GameRule<Boolean> DUELS_ENABLED = booleanRule("duels_enabled", false);
	public static final GameRule<Boolean> BONUS_OBJECTIVES_ENABLED = booleanRule("bonus_objectives_enabled", true);
	public static final GameRule<Boolean> TRIALS_ENABLED = booleanRule("trials_enabled", true);
	/** Maximum number of simultaneous voluntary Hunt contracts. */
	public static final GameRule<Integer> MAX_ONGOING_HUNTS = integerRule("max_ongoing_hunts", 2);

	private KarmaGameRules() {
	}

	public static boolean enabled(Level level, GameRule<Boolean> rule) {
		return level instanceof ServerLevel serverLevel && serverLevel.getGameRules().get(rule);
	}

	public static boolean enabled(ServerPlayer player, GameRule<Boolean> rule) {
		return enabled(player.level(), rule);
	}

	private static GameRule<Boolean> booleanRule(String path, boolean defaultValue) {
		return GameRuleBuilder.forBoolean(defaultValue)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(KarmaIsCurrency.id(path));
	}

	private static GameRule<Integer> integerRule(String path, int defaultValue) {
		return GameRuleBuilder.forInteger(defaultValue)
			.range(0, 16)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(KarmaIsCurrency.id(path));
	}
}
