package akisei.karmacurrency;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Balancing values for quest rewards and progression-based bonus frequency. */
public final class QuestConfig {
    private static final Path FILE = KicConfigPaths.file("karmaiscurrency_quests.properties");
    private static final String CONFIG_VERSION_KEY = "quest.configVersion";
    private static final int CONFIG_VERSION = 3;
    private static QuestConfig current = defaults();

    public final int abundanceKarmaPerDifficulty;
    public final int collectorKarmaPerDifficulty;
    public final int duelKarmaPerDifficulty;
    public final int survivorFinalKarma;
    public final int mercyKarma;
    public final int trialKarmaPerDifficulty;
    public final int trialReputationPerDifficulty;
    public final int abundanceReputationPerDifficulty;
    public final int contractInfamyPerDifficulty;
    public final int collectorInfamyPerDifficulty;
    public final int bloodPriceMaxInfamy;
    public final int contractKarmaPerDifficulty;
    public final int bloodPriceInfamyPerKill;
    public final int survivorIntervalReputation;
    public final int survivorFinalReputation;
    public final int wardenInfamy;
    public final int wardenKarma;
    public final int bonusUntouchableReputation;
    public final int bonusSticksAndStonesReputation;
    public final int bonusUntouchableInfamy;
    public final int bonusSticksAndStonesInfamy;
    private final List<Integer> bonusChanceByStage;

    private QuestConfig(Properties properties) {
        abundanceKarmaPerDifficulty = getInt(properties, "quest.reward.abundanceKarmaPerDifficulty", 3, 0, 100000);
        collectorKarmaPerDifficulty = getInt(properties, "quest.reward.collectorKarmaPerDifficulty", -3, -100000, 100000);
        duelKarmaPerDifficulty = getInt(properties, "quest.reward.duelKarmaPerDifficulty", -2, -100000, 100000);
        survivorFinalKarma = getInt(properties, "quest.reward.survivorFinalKarma", 40, 0, 100000);
        mercyKarma = getInt(properties, "quest.reward.mercyKarma", 30, 0, 100000);
        trialKarmaPerDifficulty = getInt(properties, "quest.reward.trialKarmaPerDifficulty", 5, 0, 100000);
        trialReputationPerDifficulty = getInt(properties, "quest.reward.trialReputationPerDifficulty", 6, 0, 100000);
        abundanceReputationPerDifficulty = getInt(properties, "quest.reward.abundanceReputationPerDifficulty", 7, 0, 100000);
        contractInfamyPerDifficulty = getInt(properties, "quest.reward.contractInfamyPerDifficulty", 8, 0, 100000);
        collectorInfamyPerDifficulty = getInt(properties, "quest.reward.collectorInfamyPerDifficulty", 4, 0, 100000);
        bloodPriceMaxInfamy = getInt(properties, "quest.reward.bloodPriceMaxInfamy", 150, 0, 1000000);
        contractKarmaPerDifficulty = getInt(properties, "quest.reward.contractKarmaPerDifficulty", -5, -100000, 100000);
        bloodPriceInfamyPerKill = getInt(properties, "quest.reward.bloodPriceInfamyPerKill", 2, 0, 100000);
        survivorIntervalReputation = getInt(properties, "quest.reward.survivorIntervalReputation", 8, 0, 100000);
        survivorFinalReputation = getInt(properties, "quest.reward.survivorFinalReputation", 50, 0, 1000000);
        wardenInfamy = getInt(properties, "quest.reward.wardenInfamy", 150, 0, 1000000);
        wardenKarma = getInt(properties, "quest.reward.wardenKarma", -50, -100000, 100000);
        bonusUntouchableReputation = getInt(properties, "quest.bonus.untouchableReputation", 10, 0, 100000);
        bonusSticksAndStonesReputation = getInt(properties, "quest.bonus.sticksAndStonesReputation", 10, 0, 100000);
        bonusUntouchableInfamy = getInt(properties, "quest.bonus.untouchableInfamy", 10, 0, 100000);
        bonusSticksAndStonesInfamy = getInt(properties, "quest.bonus.sticksAndStonesInfamy", 10, 0, 100000);
        bonusChanceByStage = parsePercentList(properties.getProperty("quest.bonus.frequencyByStage", "0,5,10,15,20,20"));
    }

    public static QuestConfig load() {
        Properties properties = new Properties();
        Properties defaults = defaultProperties();
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.notExists(FILE)) {
                try (Writer writer = Files.newBufferedWriter(FILE)) {
                    defaults.store(writer, "Karma Is Currency - Quest reward and bonus configuration");
                }
            }
            try (Reader reader = Files.newBufferedReader(FILE)) {
                properties.load(reader);
            }
            boolean changed = migrateConfig(properties);
            for (String key : defaults.stringPropertyNames()) {
                if (!properties.containsKey(key)) {
                    properties.setProperty(key, defaults.getProperty(key));
                    changed = true;
                }
            }
            if (changed) {
                try (Writer writer = Files.newBufferedWriter(FILE)) {
                    properties.store(writer, "Karma Is Currency - Quest reward and bonus configuration");
                }
            }
        } catch (IOException exception) {
            KarmaIsCurrency.LOGGER.error("Could not read quest config {}", FILE, exception);
        }
        current = new QuestConfig(properties);
        return current;
    }

    public static QuestConfig current() { return current; }

    public int bonusChancePercent(int progressionStage) {
        if (bonusChanceByStage.isEmpty()) return 0;
        return bonusChanceByStage.get(Math.clamp(progressionStage, 0, bonusChanceByStage.size() - 1));
    }

    private static QuestConfig defaults() { return new QuestConfig(defaultProperties()); }

    private static Properties defaultProperties() {
        Properties p = new Properties();
        p.setProperty(CONFIG_VERSION_KEY, Integer.toString(CONFIG_VERSION));
        p.setProperty("quest.reward.abundanceKarmaPerDifficulty", "3");
        p.setProperty("quest.reward.collectorKarmaPerDifficulty", "-3");
        p.setProperty("quest.reward.duelKarmaPerDifficulty", "-2");
        p.setProperty("quest.reward.survivorFinalKarma", "40");
        p.setProperty("quest.reward.mercyKarma", "30");
        p.setProperty("quest.reward.trialKarmaPerDifficulty", "5");
        p.setProperty("quest.reward.trialReputationPerDifficulty", "6");
        p.setProperty("quest.reward.abundanceReputationPerDifficulty", "7");
        p.setProperty("quest.reward.contractInfamyPerDifficulty", "8");
        p.setProperty("quest.reward.collectorInfamyPerDifficulty", "4");
        p.setProperty("quest.reward.bloodPriceMaxInfamy", "150");
        p.setProperty("quest.reward.contractKarmaPerDifficulty", "-5");
        p.setProperty("quest.reward.bloodPriceInfamyPerKill", "2");
        p.setProperty("quest.reward.survivorIntervalReputation", "8");
        p.setProperty("quest.reward.survivorFinalReputation", "50");
        p.setProperty("quest.reward.wardenInfamy", "150");
        p.setProperty("quest.reward.wardenKarma", "-50");
        p.setProperty("quest.bonus.untouchableReputation", "10");
        p.setProperty("quest.bonus.sticksAndStonesReputation", "10");
        p.setProperty("quest.bonus.untouchableInfamy", "10");
        p.setProperty("quest.bonus.sticksAndStonesInfamy", "10");
        p.setProperty("quest.bonus.frequencyByStage", "0,5,10,15,20,20");
        return p;
    }

    public int contractRewardPerDifficulty(QuestService.QuestId quest) {
        return quest == QuestService.QuestId.COLLECTOR ? collectorInfamyPerDifficulty : contractInfamyPerDifficulty;
    }

    public int trialKarmaRate(QuestService.QuestId quest) {
        return quest == QuestService.QuestId.ABUNDANCE ? abundanceKarmaPerDifficulty : trialKarmaPerDifficulty;
    }

    public int contractKarmaRate(QuestService.QuestId quest) {
        return switch (quest) {
            case COLLECTOR -> collectorKarmaPerDifficulty;
            case DUEL -> duelKarmaPerDifficulty;
            default -> contractKarmaPerDifficulty;
        };
    }

    // Internal completion reward, deliberately omitted from player-facing previews.
    public static int bloodPriceKarmaReward(long kills) {
        return (int) Math.min(4L, Math.max(0L, kills) / 35L) * 10;
    }

    public int bloodPriceReward(long kills) {
        long cappedKills = Math.min(Integer.MAX_VALUE, Math.max(0L, kills));
        return (int) Math.min(bloodPriceMaxInfamy, cappedKills * bloodPriceInfamyPerKill);
    }

    /** Upgrade shipped defaults once, preserving non-default custom values. */
    private static boolean migrateConfig(Properties properties) {
        int storedVersion = getInt(properties, CONFIG_VERSION_KEY, 0, 0, Integer.MAX_VALUE);
        if (storedVersion >= CONFIG_VERSION) return false;

        String abundanceReward = properties.getProperty("quest.reward.abundanceReputationPerDifficulty");
        if (storedVersion < 1 && abundanceReward != null && abundanceReward.trim().equals("6")) {
            properties.setProperty("quest.reward.abundanceReputationPerDifficulty", "7");
            KarmaIsCurrency.LOGGER.info("Migrated default Abundance Reputation reward from 6 to 7; customized values were preserved.");
        }
        String bloodPrice = properties.getProperty("quest.reward.bloodPriceInfamyPerKill");
        if (storedVersion < 2 && bloodPrice != null && bloodPrice.trim().equals("10")) {
            properties.setProperty("quest.reward.bloodPriceInfamyPerKill", "2");
        }
        if (storedVersion < 3) {
            migrateDefault(properties, "quest.reward.trialKarmaPerDifficulty", "1", "5");
            migrateDefault(properties, "quest.reward.contractKarmaPerDifficulty", "-1", "-5");
            migrateDefault(properties, "quest.reward.wardenKarma", "-25", "-50");
        }
        properties.setProperty(CONFIG_VERSION_KEY, Integer.toString(CONFIG_VERSION));
        return true;
    }

    private static void migrateDefault(Properties properties, String key, String oldValue, String newValue) {
        String value = properties.getProperty(key);
        if (value != null && value.trim().equals(oldValue)) properties.setProperty(key, newValue);
    }

    private static List<Integer> parsePercentList(String raw) {
        List<Integer> values = new ArrayList<>();
        if (raw != null) for (String token : raw.split(",")) {
            try { values.add(Math.clamp(Integer.parseInt(token.trim()), 0, 100)); }
            catch (NumberFormatException ignored) { }
        }
        return List.copyOf(values);
    }

    private static int getInt(Properties properties, String key, int fallback, int min, int max) {
        try { return Math.clamp(Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))), min, max); }
        catch (NumberFormatException exception) { return fallback; }
    }
}
