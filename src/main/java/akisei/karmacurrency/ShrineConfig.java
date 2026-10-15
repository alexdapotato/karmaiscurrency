package akisei.karmacurrency;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Server-side configuration for shrines and the Karma/social economy. */
public final class ShrineConfig {
	private static final Path FILE = KicConfigPaths.file("karmaiscurrency.properties");
	private static final String CONFIG_VERSION_KEY = "kic.configVersion";
	private static final int CONFIG_VERSION = 2;
	private static ShrineConfig current = defaults();

	public final boolean enabled;
	public final boolean preGenerationDiscovery;
	public final int ringDistance, ringSpread, shrinesPerRing, ringCount;
	public final int commandSpawnDefaultRadius, commandSpawnMaxRadius, commandSpawnMaxAttempts;
	public final int lightWeight, neutralWeight, darkWeight, minimumDistance;

	public final int karmaTrade, karmaTradeBatch, karmaCureZombieVillager, karmaEnderDragon, karmaWither;
	public final int karmaVillagerKill, karmaPlayerKillBase, karmaPlayerKillMax, karmaPlayerKillVictimBonusDivisor;
	public final int karmaPlayerKillCooldownMinutes;
	public final int karmaCap, karmaHeartThreshold, karmaMaxBonusHalfHearts, karmaDecayIntervalMinutes, karmaDecayPercent;
	public final int reputationDecayIntervalMinutes, reputationDecayPerInterval;
	public final int infamyDecayIntervalMinutes, infamyDecayPerInterval;
	public final int socialAlignedGainPercent, socialNeutralGainPercent, socialOpposedGainPercent, socialDiminishingReturnWindowMinutes;
	public final int socialTradeGain, socialVillagerKillGain, socialCureGain, socialEnderDragonGain, socialWitherGain;
	public final int socialPlayerKillGain, socialRaidHeroGain, socialRaidCaptainGain, socialMercyTrialGain, socialProtectionTrialGain;
	public final int socialMaterializationPercent, socialNeutralRedemptionPercent, socialAlignedRedemptionPercent;
	public final int socialDematerializationCooldownSeconds;
	public final int shopCycleHours;
	public final String shopCycleTimezone;
	public final int darkContractCost, darkContractReward, darkContractTarget, darkContractDurationMinutes;
	public final int darkContractCooldownMinutes, darkCurseCooldownMinutes, darkCurseRuinCost, darkCurseSightCost;
	public final int[] lodestoneOffsetX = new int[4];
	public final int[] lodestoneOffsetY = new int[4];
	public final int[] lodestoneOffsetZ = new int[4];

	private ShrineConfig(
		boolean enabled, boolean preGenerationDiscovery, int ringDistance, int ringSpread, int shrinesPerRing, int ringCount,
		int commandSpawnDefaultRadius, int commandSpawnMaxRadius, int commandSpawnMaxAttempts,
		int lightWeight, int neutralWeight, int darkWeight, int minimumDistance,
		int karmaTrade, int karmaTradeBatch, int karmaCureZombieVillager, int karmaEnderDragon, int karmaWither,
		int karmaVillagerKill, int karmaPlayerKillBase, int karmaPlayerKillMax, int karmaPlayerKillVictimBonusDivisor,
		int karmaPlayerKillCooldownMinutes,
		int karmaCap, int karmaHeartThreshold, int karmaMaxBonusHalfHearts, int karmaDecayIntervalMinutes, int karmaDecayPercent,
		int reputationDecayIntervalMinutes, int reputationDecayPerInterval,
		int infamyDecayIntervalMinutes, int infamyDecayPerInterval,
		int socialAlignedGainPercent, int socialNeutralGainPercent, int socialOpposedGainPercent, int socialDiminishingReturnWindowMinutes,
		int socialTradeGain, int socialVillagerKillGain, int socialCureGain, int socialEnderDragonGain, int socialWitherGain,
		int socialPlayerKillGain, int socialRaidHeroGain, int socialRaidCaptainGain, int socialMercyTrialGain, int socialProtectionTrialGain,
		int socialMaterializationPercent, int socialNeutralRedemptionPercent, int socialAlignedRedemptionPercent, int socialDematerializationCooldownSeconds, int shopCycleHours, String shopCycleTimezone,
		int darkContractCost, int darkContractReward, int darkContractTarget, int darkContractDurationMinutes,
		int darkContractCooldownMinutes, int darkCurseCooldownMinutes, int darkCurseRuinCost, int darkCurseSightCost,
		int[] lodestoneOffsetX, int[] lodestoneOffsetY, int[] lodestoneOffsetZ) {
		this.enabled = enabled;
		this.preGenerationDiscovery = preGenerationDiscovery;
		this.ringDistance = ringDistance;
		this.ringSpread = ringSpread;
		this.shrinesPerRing = shrinesPerRing;
		this.ringCount = ringCount;
		this.commandSpawnDefaultRadius = commandSpawnDefaultRadius;
		this.commandSpawnMaxRadius = commandSpawnMaxRadius;
		this.commandSpawnMaxAttempts = commandSpawnMaxAttempts;
		this.lightWeight = lightWeight;
		this.neutralWeight = neutralWeight;
		this.darkWeight = darkWeight;
		this.minimumDistance = minimumDistance;
		this.karmaTrade = karmaTrade;
		this.karmaTradeBatch = karmaTradeBatch;
		this.karmaCureZombieVillager = karmaCureZombieVillager;
		this.karmaEnderDragon = karmaEnderDragon;
		this.karmaWither = karmaWither;
		this.karmaVillagerKill = karmaVillagerKill;
		this.karmaPlayerKillBase = karmaPlayerKillBase;
		this.karmaPlayerKillMax = karmaPlayerKillMax;
		this.karmaPlayerKillVictimBonusDivisor = karmaPlayerKillVictimBonusDivisor;
		this.karmaPlayerKillCooldownMinutes = karmaPlayerKillCooldownMinutes;
		this.karmaCap = karmaCap;
		this.karmaHeartThreshold = karmaHeartThreshold;
		this.karmaMaxBonusHalfHearts = karmaMaxBonusHalfHearts;
		this.karmaDecayIntervalMinutes = karmaDecayIntervalMinutes;
		this.karmaDecayPercent = karmaDecayPercent;
		this.reputationDecayIntervalMinutes = reputationDecayIntervalMinutes;
		this.reputationDecayPerInterval = reputationDecayPerInterval;
		this.infamyDecayIntervalMinutes = infamyDecayIntervalMinutes;
		this.infamyDecayPerInterval = infamyDecayPerInterval;
		this.socialAlignedGainPercent = socialAlignedGainPercent;
		this.socialNeutralGainPercent = socialNeutralGainPercent;
		this.socialOpposedGainPercent = socialOpposedGainPercent;
		this.socialDiminishingReturnWindowMinutes = socialDiminishingReturnWindowMinutes;
		this.socialTradeGain = socialTradeGain;
		this.socialVillagerKillGain = socialVillagerKillGain;
		this.socialCureGain = socialCureGain;
		this.socialEnderDragonGain = socialEnderDragonGain;
		this.socialWitherGain = socialWitherGain;
		this.socialPlayerKillGain = socialPlayerKillGain;
		this.socialRaidHeroGain = socialRaidHeroGain;
		this.socialRaidCaptainGain = socialRaidCaptainGain;
		this.socialMercyTrialGain = socialMercyTrialGain;
		this.socialProtectionTrialGain = socialProtectionTrialGain;
		this.socialMaterializationPercent = socialMaterializationPercent;
		this.socialNeutralRedemptionPercent = socialNeutralRedemptionPercent;
		this.socialAlignedRedemptionPercent = socialAlignedRedemptionPercent;
		this.socialDematerializationCooldownSeconds = socialDematerializationCooldownSeconds;
		this.shopCycleHours = shopCycleHours;
		this.shopCycleTimezone = shopCycleTimezone == null || shopCycleTimezone.isBlank() ? "Asia/Manila" : shopCycleTimezone;
		this.darkContractCost = darkContractCost;
		this.darkContractReward = darkContractReward;
		this.darkContractTarget = darkContractTarget;
		this.darkContractDurationMinutes = darkContractDurationMinutes;
		this.darkContractCooldownMinutes = darkContractCooldownMinutes;
		this.darkCurseCooldownMinutes = darkCurseCooldownMinutes;
		this.darkCurseRuinCost = darkCurseRuinCost;
		this.darkCurseSightCost = darkCurseSightCost;
		System.arraycopy(lodestoneOffsetX, 0, this.lodestoneOffsetX, 0, 4);
		System.arraycopy(lodestoneOffsetY, 0, this.lodestoneOffsetY, 0, 4);
		System.arraycopy(lodestoneOffsetZ, 0, this.lodestoneOffsetZ, 0, 4);
	}

	public static ShrineConfig load() {
		Properties properties = new Properties();
		try {
			Files.createDirectories(FILE.getParent());
			if (Files.notExists(FILE)) {
				try (Writer writer = Files.newBufferedWriter(FILE)) {
					defaultProperties().store(writer, "Karma Is Currency configuration");
				}
			}
			try (Reader reader = Files.newBufferedReader(FILE)) {
				properties.load(reader);
			}
			Properties defaults = defaultProperties();
			boolean changed = migrateConfig(properties);
			for (String key : defaults.stringPropertyNames()) {
				if (!properties.containsKey(key)) {
					properties.setProperty(key, defaults.getProperty(key));
					changed = true;
				}
			}
			if (changed) {
				try (Writer writer = Files.newBufferedWriter(FILE)) {
					properties.store(writer, "Karma Is Currency configuration");
				}
			}
		} catch (IOException exception) {
			KarmaIsCurrency.LOGGER.error("Could not read config {}", FILE, exception);
		}

		current = new ShrineConfig(
			getBoolean(properties, "shrines.enabled", true),
			getBoolean(properties, "shrines.preGenerationDiscovery", true),
			getInt(properties, "shrines.ringDistance", 1000, 1, 30000),
			getInt(properties, "shrines.ringSpread", 100, 0, 10000),
			getInt(properties, "shrines.shrinesPerRing", 8, 1, 64),
			getInt(properties, "shrines.ringCount", 20, 1, 100),
			getInt(properties, "shrines.commandSpawnDefaultRadius", 500, 16, 10000),
			getInt(properties, "shrines.commandSpawnMaxRadius", 10000, 16, 1000000),
			getInt(properties, "shrines.commandSpawnMaxAttempts", 64, 1, 10000),
			getInt(properties, "shrines.lightWeight", 45, 0, 10000),
			getInt(properties, "shrines.neutralWeight", 35, 0, 10000),
			getInt(properties, "shrines.darkWeight", 20, 0, 10000),
			getInt(properties, "shrines.minimumDistance", 128, 1, 10000),
			getInt(properties, "karma.trade", 1, 0, 100000),
			getInt(properties, "karma.tradeBatch", 12, 1, 100000),
			getInt(properties, "karma.cureZombieVillager", 4, 0, 100000),
			getInt(properties, "karma.enderDragon", 30, 0, 100000),
			getInt(properties, "karma.wither", 30, 0, 100000),
			getInt(properties, "karma.villagerKill", 5, 0, 100000),
			getInt(properties, "karma.playerKillBase", 10, 0, 100000),
			getInt(properties, "karma.playerKillMax", 25, 0, 100000),
			getInt(properties, "karma.playerKillVictimBonusDivisor", 5, 1, 100000),
			getInt(properties, "karma.playerKillCooldownMinutes", 30, 0, 1000000),
			getInt(properties, "karma.cap", 1000, 1, 1000000),
			getInt(properties, "karma.heartThreshold", 75, 1, 1000000),
			getInt(properties, "karma.maxBonusHalfHearts", 10, 0, 10),
			getInt(properties, "karma.decayIntervalMinutes", 360, 1, 1000000),
			getInt(properties, "karma.decayPercent", 5, 0, 100),
			getInt(properties, "social.reputationDecayIntervalMinutes", 120, 1, 1000000),
			getInt(properties, "social.reputationDecayPerInterval", 0, 0, 1000000),
			getInt(properties, "social.infamyDecayIntervalMinutes", 120, 1, 1000000),
			getInt(properties, "social.infamyDecayPerInterval", 0, 0, 1000000),
			getInt(properties, "social.alignedGainPercent", 125, 0, 1000),
			getInt(properties, "social.neutralGainPercent", 100, 0, 1000),
			getInt(properties, "social.opposedGainPercent", 75, 0, 1000),
			getInt(properties, "social.diminishingReturnWindowMinutes", 30, 1, 1000000),
			getInt(properties, "social.tradeGain", 2, 0, 100000),
			getInt(properties, "social.villagerKillGain", 1, 0, 100000),
			getInt(properties, "social.cureGain", 5, 0, 100000),
			getInt(properties, "social.enderDragonGain", 80, 0, 100000),
			getInt(properties, "social.witherGain", 80, 0, 100000),
			getInt(properties, "social.playerKillGain", 10, -100000, 100000),
			getInt(properties, "social.raidHeroGain", 25, 0, 100000),
			getInt(properties, "social.raidCaptainGain", 10, 0, 100000),
			getInt(properties, "social.mercyTrialGain", 25, 0, 100000),
			getInt(properties, "social.protectionTrialGain", 10, 0, 100000),
			getInt(properties, "social.materializationPercent", SocialCurrencyService.DEFAULT_MATERIALIZATION_PERCENT, 1, 100),
			getInt(properties, "social.neutralRedemptionPercent", SocialCurrencyService.DEFAULT_NEUTRAL_REDEMPTION_PERCENT, 1, 100),
			getInt(properties, "social.alignedRedemptionPercent", SocialCurrencyService.DEFAULT_ALIGNED_REDEMPTION_PERCENT, 1, 100),
			getInt(properties, "social.dematerializationCooldownSeconds", 0, 0, 1000000),
			getInt(properties, "shop.cycleHours", 12, 1, 168),
			properties.getProperty("shop.cycleTimezone", "Asia/Manila"),
			getInt(properties, "dark.contractCost", 25, 0, 1000000),
			getInt(properties, "dark.contractReward", 50, 0, 1000000),
			getInt(properties, "dark.contractTarget", 10, 1, 1000000),
			getInt(properties, "dark.contractDurationMinutes", 60, 1, 1000000),
			getInt(properties, "dark.contractCooldownMinutes", 30, 0, 1000000),
			getInt(properties, "dark.curseCooldownMinutes", 60, 0, 1000000),
			getInt(properties, "dark.curseRuinCost", 50, 0, 1000000),
			getInt(properties, "dark.curseSightCost", 100, 0, 1000000),
			offsets(properties, "X", 0), offsets(properties, "Y", 2), offsets(properties, "Z", 0)
		);
		return current;
	}

	public static ShrineConfig current() { return current; }

	private static ShrineConfig defaults() {
		return new ShrineConfig(true, true, 1000, 100, 8, 20, 500, 10000, 64,
			45, 35, 20, 128, 1, 12, 4, 30, 30, 5, 10, 25, 5, 30,
			1000, 75, 10, 360, 5, 120, 0, 120, 0, 125, 100, 75, 30,
			2, 1, 5, 80, 80, 10, 25, 10, 25, 10, 100, 100, 100, 0, 12,
			"Asia/Manila",
			25, 50, 10, 60, 30, 60, 50, 100,
			new int[] {0,0,0,0}, new int[] {2,2,2,3}, new int[] {0,0,0,0});
	}

	private static Properties defaultProperties() {
		Properties p = new Properties();
		p.setProperty(CONFIG_VERSION_KEY, Integer.toString(CONFIG_VERSION));
		p.setProperty("shrines.enabled", "true"); p.setProperty("shrines.preGenerationDiscovery", "true");
		p.setProperty("shrines.ringDistance", "1000"); p.setProperty("shrines.ringSpread", "100");
		p.setProperty("shrines.shrinesPerRing", "8"); p.setProperty("shrines.ringCount", "20");
		p.setProperty("shrines.commandSpawnDefaultRadius", "500"); p.setProperty("shrines.commandSpawnMaxRadius", "10000"); p.setProperty("shrines.commandSpawnMaxAttempts", "64");
		p.setProperty("shrines.lightWeight", "45"); p.setProperty("shrines.neutralWeight", "35"); p.setProperty("shrines.darkWeight", "20"); p.setProperty("shrines.minimumDistance", "128");
		p.setProperty("karma.trade", "1"); p.setProperty("karma.tradeBatch", "12"); p.setProperty("karma.cureZombieVillager", "4"); p.setProperty("karma.enderDragon", "30"); p.setProperty("karma.wither", "30");
		p.setProperty("karma.villagerKill", "5"); p.setProperty("karma.playerKillBase", "10"); p.setProperty("karma.playerKillMax", "25"); p.setProperty("karma.playerKillVictimBonusDivisor", "5"); p.setProperty("karma.playerKillCooldownMinutes", "30");
				p.setProperty("karma.cap", "1000"); p.setProperty("karma.heartThreshold", "75"); p.setProperty("karma.maxBonusHalfHearts", "10"); p.setProperty("karma.decayIntervalMinutes", "360"); p.setProperty("karma.decayPercent", "5");
		p.setProperty("social.reputationDecayIntervalMinutes", "120"); p.setProperty("social.reputationDecayPerInterval", "0");
		p.setProperty("social.infamyDecayIntervalMinutes", "120"); p.setProperty("social.infamyDecayPerInterval", "0");
		p.setProperty("social.alignedGainPercent", "125"); p.setProperty("social.neutralGainPercent", "100"); p.setProperty("social.opposedGainPercent", "75"); p.setProperty("social.diminishingReturnWindowMinutes", "30"); p.setProperty("social.tradeGain", "2"); p.setProperty("social.villagerKillGain", "1"); p.setProperty("social.cureGain", "5"); p.setProperty("social.enderDragonGain", "80"); p.setProperty("social.witherGain", "80");
		p.setProperty("social.playerKillGain", "10"); p.setProperty("social.raidHeroGain", "25"); p.setProperty("social.raidCaptainGain", "10"); p.setProperty("social.mercyTrialGain", "25"); p.setProperty("social.protectionTrialGain", "10");
		p.setProperty("social.materializationPercent", "100"); p.setProperty("social.neutralRedemptionPercent", "100"); p.setProperty("social.alignedRedemptionPercent", "100"); p.setProperty("social.dematerializationCooldownSeconds", "0");
		p.setProperty("shop.cycleHours", "12"); p.setProperty("shop.cycleTimezone", "Asia/Manila");
		p.setProperty("dark.contractCost", "25"); p.setProperty("dark.contractReward", "50"); p.setProperty("dark.contractTarget", "10"); p.setProperty("dark.contractDurationMinutes", "60"); p.setProperty("dark.contractCooldownMinutes", "30"); p.setProperty("dark.curseCooldownMinutes", "60"); p.setProperty("dark.curseRuinCost", "50"); p.setProperty("dark.curseSightCost", "100");
		p.setProperty("shrines.light.lodestoneOffsetX", "0"); p.setProperty("shrines.light.lodestoneOffsetY", "2"); p.setProperty("shrines.light.lodestoneOffsetZ", "0");
		p.setProperty("shrines.neutral.lodestoneOffsetX", "0"); p.setProperty("shrines.neutral.lodestoneOffsetY", "2"); p.setProperty("shrines.neutral.lodestoneOffsetZ", "0");
		p.setProperty("shrines.dark.lodestoneOffsetX", "0"); p.setProperty("shrines.dark.lodestoneOffsetY", "2"); p.setProperty("shrines.dark.lodestoneOffsetZ", "0");
		p.setProperty("shrines.monument.lodestoneOffsetX", "0"); p.setProperty("shrines.monument.lodestoneOffsetY", "3"); p.setProperty("shrines.monument.lodestoneOffsetZ", "0");
		return p;
	}

	/** Updates only known shipped defaults; non-default operator values are retained. */
	private static boolean migrateConfig(Properties properties) {
		int storedVersion = getInt(properties, CONFIG_VERSION_KEY, 0, 0, Integer.MAX_VALUE);
		if (storedVersion >= CONFIG_VERSION) return false;

		String mercyReward = properties.getProperty("social.mercyTrialGain");
		if (storedVersion < 1 && mercyReward != null && mercyReward.trim().equals("5")) {
			properties.setProperty("social.mercyTrialGain", "25");
			KarmaIsCurrency.LOGGER.info("Migrated default Mercy Reputation reward from 5 to 25; customized values were preserved.");
		}
		String healthCap = properties.getProperty("karma.maxBonusHalfHearts");
		if (storedVersion < 2 && healthCap != null && healthCap.trim().equals("6")) {
			properties.setProperty("karma.maxBonusHalfHearts", "10");
			KarmaIsCurrency.LOGGER.info("Migrated default Karma health cap from 3 to 5 hearts.");
		}
		properties.setProperty(CONFIG_VERSION_KEY, Integer.toString(CONFIG_VERSION));
		return true;
	}

	private static int getInt(Properties p, String key, int fallback, int min, int max) {
		try { return Math.clamp(Integer.parseInt(p.getProperty(key, Integer.toString(fallback))), min, max); }
		catch (NumberFormatException e) { return fallback; }
	}
	private static boolean getBoolean(Properties p, String key, boolean fallback) { return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(fallback))); }
	private static int[] offsets(Properties p, String axis, int fallback) {
		return new int[] {
			getInt(p, "shrines.light.lodestoneOffset" + axis, fallback, -100, 100),
			getInt(p, "shrines.neutral.lodestoneOffset" + axis, fallback, -100, 100),
			getInt(p, "shrines.dark.lodestoneOffset" + axis, fallback, -100, 100),
			getInt(p, "shrines.monument.lodestoneOffset" + axis, fallback, -100, 100)
		};
	}
}
