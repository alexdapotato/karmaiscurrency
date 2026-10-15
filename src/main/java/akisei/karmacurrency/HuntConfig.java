package akisei.karmacurrency;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configuration for the optional three-day Dark Contract: The Hunt. */
public final class HuntConfig {
    private static final Path FILE = KicConfigPaths.file("karmaiscurrency_hunt.properties");
    private static HuntConfig current = defaults();

    public final int durationHours;
    public final int huntGraceMinutes;
    public final int loginGraceMinutes;
    public final int compassPerDay;
    public final int compassLifetimeMinutes;
    public final int compassErrorBlocks;
    public final int maxHunters;
    public final int hunterRewardInfamy;
    public final int huntedBaseRewardInfamy;
    public final int huntedRewardPerActiveHourInfamy;
    public final int participationCooldownDays;
    public final int hunterPairCooldownDays;

    private HuntConfig(int durationHours, int huntGraceMinutes, int loginGraceMinutes,
                       int compassPerDay, int compassLifetimeMinutes, int compassErrorBlocks,
                       int maxHunters, int hunterRewardInfamy, int huntedBaseRewardInfamy,
                       int huntedRewardPerActiveHourInfamy, int participationCooldownDays,
                       int hunterPairCooldownDays) {
        this.durationHours = durationHours;
        this.huntGraceMinutes = huntGraceMinutes;
        this.loginGraceMinutes = loginGraceMinutes;
        this.compassPerDay = compassPerDay;
        this.compassLifetimeMinutes = compassLifetimeMinutes;
        this.compassErrorBlocks = compassErrorBlocks;
        this.maxHunters = maxHunters;
        this.hunterRewardInfamy = hunterRewardInfamy;
        this.huntedBaseRewardInfamy = huntedBaseRewardInfamy;
        this.huntedRewardPerActiveHourInfamy = huntedRewardPerActiveHourInfamy;
        this.participationCooldownDays = participationCooldownDays;
        this.hunterPairCooldownDays = hunterPairCooldownDays;
    }

    public static HuntConfig load() {
        Properties properties = new Properties();
        Properties defaults = defaultProperties();
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.notExists(FILE)) {
                try (Writer writer = Files.newBufferedWriter(FILE)) {
                    defaults.store(writer, "Karma Is Currency - Hunt configuration");
                }
            }
            try (Reader reader = Files.newBufferedReader(FILE)) {
                properties.load(reader);
            }
            boolean changed = false;
            for (String key : defaults.stringPropertyNames()) {
                if (!properties.containsKey(key)) {
                    properties.setProperty(key, defaults.getProperty(key));
                    changed = true;
                }
            }
            if (changed) {
                try (Writer writer = Files.newBufferedWriter(FILE)) {
                    properties.store(writer, "Karma Is Currency - Hunt configuration");
                }
            }
        } catch (IOException exception) {
            KarmaIsCurrency.LOGGER.error("Could not read hunt config {}", FILE, exception);
        }

        current = new HuntConfig(
            getInt(properties, "hunt.durationHours", 72, 1, 168),
            getInt(properties, "hunt.huntGraceMinutes", 60, 0, 10080),
            getInt(properties, "hunt.loginGraceMinutes", 10, 0, 1440),
            getInt(properties, "hunt.compassPerDay", 2, 0, 32),
            getInt(properties, "hunt.compassLifetimeMinutes", 20, 1, 1440),
            getInt(properties, "hunt.compassErrorBlocks", 150, 0, 1000),
            getInt(properties, "hunt.maxHunters", 5, 1, 64),
            getInt(properties, "hunt.hunterRewardInfamy", 50, 0, 1000000),
            getInt(properties, "hunt.huntedBaseRewardInfamy", 32, 0, 1000000),
            getInt(properties, "hunt.huntedRewardPerActiveHourInfamy", 4, 0, 1000000),
            getInt(properties, "hunt.participationCooldownDays", 7, 0, 3650),
            getInt(properties, "hunt.hunterPairCooldownDays", 7, 0, 3650)
        );
        return current;
    }

    public static HuntConfig current() {
        return current;
    }

    private static HuntConfig defaults() {
        return new HuntConfig(72, 60, 10, 2, 20, 150, 5, 50, 32, 4, 7, 7);
    }

    private static Properties defaultProperties() {
        Properties p = new Properties();
        p.setProperty("hunt.durationHours", "72");
        p.setProperty("hunt.huntGraceMinutes", "60");
        p.setProperty("hunt.loginGraceMinutes", "10");
        p.setProperty("hunt.compassPerDay", "2");
        p.setProperty("hunt.compassLifetimeMinutes", "20");
        p.setProperty("hunt.compassErrorBlocks", "150");
        p.setProperty("hunt.maxHunters", "5");
        p.setProperty("hunt.hunterRewardInfamy", "50");
        p.setProperty("hunt.huntedBaseRewardInfamy", "32");
        p.setProperty("hunt.huntedRewardPerActiveHourInfamy", "4");
        p.setProperty("hunt.participationCooldownDays", "7");
        p.setProperty("hunt.hunterPairCooldownDays", "7");
        return p;
    }

    private static int getInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))), min, max);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
