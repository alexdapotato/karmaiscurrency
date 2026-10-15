package akisei.karmacurrency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.io.BufferedReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/** Configurable list of items that shrines accept for social-currency dematerialization. */
public final class KarmaOfferingConfig {
	private static final Path FILE = KicConfigPaths.file("karmaiscurrency-offerings.properties");
	private static final Map<Item, Double> SOCIAL_VALUES = new LinkedHashMap<>();

	private KarmaOfferingConfig() {}

	public static void load() {
		SOCIAL_VALUES.clear();
		try {
			Files.createDirectories(FILE.getParent());
			if (Files.notExists(FILE)) {
				Files.writeString(FILE, defaultText());
			}

            List<String> lines = Files.readAllLines(FILE);
            List<String> migrated = migrateValues(lines);
            if (!migrated.equals(lines)) Files.write(FILE, migrated);

			try (BufferedReader reader = Files.newBufferedReader(FILE)) {
				String line;
				int lineNumber = 0;
				while ((line = reader.readLine()) != null) {
					lineNumber++;
					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
						continue;
					}

					int separator = trimmed.indexOf('=');
					if (separator <= 0) {
						KarmaIsCurrency.LOGGER.warn("Invalid offering config line {}: '{}'", lineNumber, line);
						continue;
					}

					String key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
					String valueText = trimmed.substring(separator + 1).trim();

					Identifier id;
					try {
						id = Identifier.parse(key);
					} catch (IllegalArgumentException exception) {
						KarmaIsCurrency.LOGGER.warn("Invalid offering item ID '{}' on line {}", key, lineNumber);
						continue;
					}

					Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
					if (item == null) {
						KarmaIsCurrency.LOGGER.warn("Unknown offering item ID '{}'", key);
						continue;
					}

					try {
						double value = Double.parseDouble(valueText);
						if (Double.isFinite(value) && value > 0) {
							SOCIAL_VALUES.put(item, value);
						} else {
							KarmaIsCurrency.LOGGER.warn("Offering value must be positive for '{}'", key);
						}
					} catch (NumberFormatException exception) {
						KarmaIsCurrency.LOGGER.warn("Invalid social-currency offering value for '{}'", key);
					}
				}
			}
		} catch (IOException exception) {
			KarmaIsCurrency.LOGGER.error("Could not read offering config {}", FILE, exception);
		}

		KarmaIsCurrency.LOGGER.info("Loaded {} social-currency offering items", SOCIAL_VALUES.size());
	}

	public static double socialPerItem(Item item) { return SOCIAL_VALUES.getOrDefault(item, 0.0); }

    public static double socialPerItem(Item item, int stage) {
        return item == net.minecraft.world.item.Items.ENDER_PEARL && stage >= 2
            ? socialPerItem(item) / 2.0 : socialPerItem(item);
    }

    public static boolean isLog(Item item) {
        String id = itemId(item);
        return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae");
    }

    public static double socialPerStackItem(net.minecraft.world.item.ItemStack stack,
            net.minecraft.server.level.ServerPlayer player) {
        if (stack.is(net.minecraft.world.item.Items.ENDER_PEARL))
            return socialPerItem(stack.getItem(), CommunityProgressionService.stage(player));
        return socialPerStackItem(stack);
    }

	public static List<Item> items() { return List.copyOf(SOCIAL_VALUES.keySet()); }

	public static boolean accepts(Item item) {
		return socialPerItem(item) > 0;
	}

    static double ominousBottleValue(int amplifier) {
        int level = Math.max(0, Math.min(4, amplifier)) + 1;
        return 6 + 2.5 * (level - 1);
    }

    public static double socialPerStackItem(net.minecraft.world.item.ItemStack stack) {
        double base = socialPerItem(stack.getItem());
        if (base <= 0) return 0;
        if (stack.is(net.minecraft.world.item.Items.OMINOUS_BOTTLE)) {
            var amplifier = stack.get(net.minecraft.core.component.DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
            return ominousBottleValue(amplifier == null ? 0 : amplifier.value());
        }
        return base;
    }

    public static double inventoryGross(net.minecraft.server.level.ServerPlayer player, Item item, int amount) {
        double value = 0;
        int remaining = Math.max(0, amount);
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (remaining == 0) break;
            if (!stack.is(item) || SocialTokenService.isToken(stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            value += socialPerStackItem(stack, player) * take;
            remaining -= take;
        }
        return Math.min(Integer.MAX_VALUE, value);
    }

    public static int socialFor(net.minecraft.world.item.ItemStack stack) {
        return (int) Math.min(Integer.MAX_VALUE, socialPerStackItem(stack) * stack.getCount());
    }

	public static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

    /** Update shipped defaults once, retaining unrelated/custom offering values. */
    static List<String> migrateValues(List<String> lines) {
        if (lines.contains("# kic.offeringBalanceVersion=2")) return lines;
        lines = migrateLegacyValues(lines);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (String line : defaultText().split("\n")) {
            String[] parts = line.trim().split("=", 2);
            if (parts.length == 2 && !parts[0].startsWith("#")) defaults.put(parts[0], parts[1]);
        }
        Map<String, String> previous = Map.ofEntries(
            Map.entry("gold_ingot", "1"), Map.entry("oak_log", "1"), Map.entry("amethyst_shard", "1"),
            Map.entry("ender_pearl", "1"), Map.entry("shulker_shell", "2"), Map.entry("echo_shard", "2"),
            Map.entry("netherite_scrap", "3"), Map.entry("ancient_debris", "3"), Map.entry("heart_of_the_sea", "10"),
            Map.entry("netherite_ingot", "12"), Map.entry("dragon_head", "35"), Map.entry("nether_star", "35"),
            Map.entry("mace", "35"), Map.entry("heavy_core", "60"), Map.entry("enchanted_golden_apple", "40"),
            Map.entry("ominous_bottle", "4"));
        List<String> result = new java.util.ArrayList<>();
        result.add("# kic.offeringBalanceVersion=2");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : lines) {
            String[] parts = raw.trim().split("=", 2);
            if (parts.length != 2 || parts[0].startsWith("#")) { result.add(raw); continue; }
            String key = parts[0].trim();
            if (key.equals("minecraft:enchanted_book") || key.equals("minecraft:beacon")) continue;
            seen.add(key);
            String old = previous.get(key.replace("minecraft:", ""));
            if (old != null && parts[1].trim().equals(old)) raw = key + "=" + defaults.get(key);
            result.add(raw);
        }
        defaults.forEach((key, value) -> { if (!seen.contains(key)) result.add(key + "=" + value); });
        return result;
    }

    static List<String> migrateLegacyValues(List<String> lines) {
        String marker = "# kic.offeringBalanceVersion=1";
        if (lines.stream().anyMatch(line -> line.trim().equals(marker))) return lines;
        Map<String, String> oldValues = Map.ofEntries(
            Map.entry("minecraft:diamond", "1"),
            Map.entry("minecraft:netherite_scrap", "1"),
            Map.entry("minecraft:netherite_ingot", "4"),
            Map.entry("minecraft:diamond_block", "3"),
            Map.entry("minecraft:ominous_bottle", "3"),
            Map.entry("minecraft:ghast_tear", "2"),
            Map.entry("minecraft:ancient_debris", "2"),
            Map.entry("minecraft:enchanted_golden_apple", "7"),
            Map.entry("minecraft:heavy_core", "15"),
            Map.entry("minecraft:elytra", "20"),
            Map.entry("minecraft:mace", "20"),
            Map.entry("minecraft:nether_star", "20"),
            Map.entry("minecraft:conduit", "20"),
            Map.entry("minecraft:dragon_head", "20"),
            Map.entry("minecraft:dragon_egg", "50"));
        Map<String, String> newValues = Map.ofEntries(
            Map.entry("minecraft:diamond", "2"),
            Map.entry("minecraft:netherite_scrap", "3"),
            Map.entry("minecraft:netherite_ingot", "12"),
            Map.entry("minecraft:diamond_block", "18"),
            Map.entry("minecraft:ominous_bottle", "4"),
            Map.entry("minecraft:ghast_tear", "3"),
            Map.entry("minecraft:ancient_debris", "3"),
            Map.entry("minecraft:enchanted_golden_apple", "40"),
            Map.entry("minecraft:heavy_core", "60"),
            Map.entry("minecraft:elytra", "50"),
            Map.entry("minecraft:mace", "35"),
            Map.entry("minecraft:nether_star", "35"),
            Map.entry("minecraft:conduit", "35"),
            Map.entry("minecraft:dragon_head", "35"),
            Map.entry("minecraft:dragon_egg", "300"));
        List<String> result = new java.util.ArrayList<>();
        result.add(marker);
        for (String raw : lines) {
            String line = raw.trim();
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#") && !line.startsWith("!")) {
                String key = line.substring(0, equals).trim();
                if (line.substring(equals + 1).trim().equals(oldValues.get(key)))
                    raw = key + "=" + newValues.get(key);
            }
            result.add(raw);
        }
        return result;
    }
	private static String defaultText() {
		return """
			# Gross social value per item; 5% tax is applied to the combined offering. Only listed items can be offered.
			# Values may use decimal social-currency points; only the final payout is rounded down. Offerings never award Karma.
			# Common items support the early and mid-game Offering quest objectives.
			minecraft:iron_ingot=1
			minecraft:wheat=1
			minecraft:cooked_beef=1
			minecraft:oak_log=0.5
			minecraft:gold_ingot=1.5
			minecraft:redstone=1
			minecraft:amethyst_shard=1.5
			minecraft:ender_pearl=6
			minecraft:blaze_rod=2
			minecraft:ghast_tear=3
			minecraft:obsidian=1
			minecraft:totem_of_undying=6
			minecraft:enchanted_golden_apple=50

			minecraft:goat_horn=5
			minecraft:white_banner=1
			minecraft:diamond=2
			minecraft:diamond_block=18
			minecraft:netherite_scrap=5
			minecraft:netherite_ingot=26
			minecraft:ancient_debris=5
			minecraft:elytra=50
			minecraft:trident=10
			minecraft:mace=40
			minecraft:heavy_core=40
			minecraft:nether_star=50

			minecraft:conduit=35
			minecraft:heart_of_the_sea=20
			minecraft:shulker_shell=8
			minecraft:echo_shard=8
			minecraft:ominous_bottle=6
			minecraft:dragon_egg=300
			minecraft:dragon_head=50
			minecraft:wither_skeleton_skull=3
			minecraft:skeleton_skull=2
			minecraft:zombie_head=2
			minecraft:creeper_head=3
			minecraft:piglin_head=3
			minecraft:player_head=5
			minecraft:orange_banner=1
			minecraft:magenta_banner=1
			minecraft:light_blue_banner=1
			minecraft:yellow_banner=1
			minecraft:lime_banner=1
			minecraft:pink_banner=1
			minecraft:gray_banner=1
			minecraft:light_gray_banner=1
			minecraft:cyan_banner=1
			minecraft:purple_banner=1
			minecraft:blue_banner=1
			minecraft:brown_banner=1
			minecraft:green_banner=1
			minecraft:red_banner=1
			minecraft:black_banner=1
			minecraft:netherite_block=234
			minecraft:stripped_oak_log=0.5
			minecraft:oak_wood=0.5
			minecraft:stripped_oak_wood=0.5
			minecraft:spruce_log=0.5
			minecraft:stripped_spruce_log=0.5
			minecraft:spruce_wood=0.5
			minecraft:stripped_spruce_wood=0.5
			minecraft:birch_log=0.5
			minecraft:stripped_birch_log=0.5
			minecraft:birch_wood=0.5
			minecraft:stripped_birch_wood=0.5
			minecraft:jungle_log=0.5
			minecraft:stripped_jungle_log=0.5
			minecraft:jungle_wood=0.5
			minecraft:stripped_jungle_wood=0.5
			minecraft:acacia_log=0.5
			minecraft:stripped_acacia_log=0.5
			minecraft:acacia_wood=0.5
			minecraft:stripped_acacia_wood=0.5
			minecraft:dark_oak_log=0.5
			minecraft:stripped_dark_oak_log=0.5
			minecraft:dark_oak_wood=0.5
			minecraft:stripped_dark_oak_wood=0.5
			minecraft:mangrove_log=0.5
			minecraft:stripped_mangrove_log=0.5
			minecraft:mangrove_wood=0.5
			minecraft:stripped_mangrove_wood=0.5
			minecraft:cherry_log=0.5
			minecraft:stripped_cherry_log=0.5
			minecraft:cherry_wood=0.5
			minecraft:stripped_cherry_wood=0.5
			minecraft:pale_oak_log=0.5
			minecraft:stripped_pale_oak_log=0.5
			minecraft:pale_oak_wood=0.5
			minecraft:stripped_pale_oak_wood=0.5
			minecraft:crimson_stem=0.5
			minecraft:stripped_crimson_stem=0.5
			minecraft:crimson_hyphae=0.5
			minecraft:stripped_crimson_hyphae=0.5
			minecraft:warped_stem=0.5
			minecraft:stripped_warped_stem=0.5
			minecraft:warped_hyphae=0.5
			minecraft:stripped_warped_hyphae=0.5
			""";
	}
}
