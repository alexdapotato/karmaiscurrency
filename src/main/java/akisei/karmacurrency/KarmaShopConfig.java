package akisei.karmacurrency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Human-editable sectioned shop configuration. */
public final class KarmaShopConfig {
	private static final Path FILE = KicConfigPaths.file("karmaiscurrency-shop.properties");
	private static List<ShopReward> rewards = List.of();

	private KarmaShopConfig() {
	}

	public static void load() {
		try {
			Files.createDirectories(FILE.getParent());
			if (Files.notExists(FILE)) {
				Files.writeString(FILE, defaultText());
			}
			List<String> lines = Files.readAllLines(FILE);
			List<String> migrated = migratePrices(lines);
			if (!migrated.equals(lines)) Files.write(FILE, migrated);
			rewards = parse(migrated);
		} catch (IOException exception) {
			KarmaIsCurrency.LOGGER.error("Could not read shop configuration {}", FILE, exception);
			rewards = List.of();
		}
		for (ShopCategory category : ShopCategory.values()) {
			long count = rewards.stream().filter(reward -> reward.category() == category).count();
			KarmaIsCurrency.LOGGER.info("Loaded Karma shop {}: {} items", category.displayName(), count);
		}
	}

	public static List<ShopReward> rewardsFor(ShopCategory category) {
		return rewards.stream()
			.filter(reward -> reward.category() == category && reward.enabled())
			.toList();
	}

	public static ShopReward find(String id) {
		return rewards.stream()
			.filter(reward -> reward.id().equalsIgnoreCase(id))
			.findFirst().orElse(null);
	}

	public static List<ShopReward> allRewards() {
		return rewards;
	}

	private static List<ShopReward> parse(List<String> lines) {
		Map<String, Map<String, String>> sections = new LinkedHashMap<>();
		String section = "";
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
				continue;
			}
			if (line.startsWith("[") && line.endsWith("]")) {
				section = line.substring(1, line.length() - 1).trim();
				sections.putIfAbsent(section, new LinkedHashMap<>());
				continue;
			}
			int equals = line.indexOf('=');
			if (equals > 0 && !section.isEmpty()) {
				sections.get(section).put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
			}
		}

		List<ShopReward> loaded = new ArrayList<>();
		for (Map.Entry<String, Map<String, String>> sectionEntry : sections.entrySet()) {
			ShrineType type = typeFor(sectionEntry.getKey());
			if (type == null) {
				KarmaIsCurrency.LOGGER.warn("Unknown shop section [{}]; skipping it", sectionEntry.getKey());
				continue;
			}
			Properties properties = new Properties();
			sectionEntry.getValue().forEach(properties::setProperty);
			Map<String, Map<String, String>> entries = new LinkedHashMap<>();
			for (String key : properties.stringPropertyNames()) {
				int dot = key.indexOf('.');
				if (dot <= 0) {
					KarmaIsCurrency.LOGGER.warn("Invalid shop key '{}' in [{}]", key, sectionEntry.getKey());
					continue;
				}
				entries.computeIfAbsent(key.substring(0, dot), ignored -> new LinkedHashMap<>())
					.put(key.substring(dot + 1), properties.getProperty(key));
			}
			for (Map.Entry<String, Map<String, String>> entry : entries.entrySet()) {
				ShopReward reward = parseReward(entry.getKey(), entry.getValue(), type);
				if (reward != null) {
					loaded.add(reward);
				}
			}
		}
		return List.copyOf(loaded);
	}

	private static ShopReward parseReward(String id, Map<String, String> values, ShrineType type) {
		Identifier identifier;
		try {
			identifier = Identifier.parse(values.getOrDefault("item", id));
		} catch (IllegalArgumentException exception) {
			KarmaIsCurrency.LOGGER.warn("Invalid item ID '{}' in shop section [{}]", id, type.configName());
			return null;
		}
		String mode = values.getOrDefault("mode", "item").trim().toLowerCase(Locale.ROOT);
		Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		if (mode.equals("suspicious_stew_cycle")) {
			item = BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:suspicious_stew")).orElse(null);
		} else if ((mode.equals("random_enchantment") || mode.equals("fixed_enchantment"))) {
			item = BuiltInRegistries.ITEM.getOptional(Identifier.parse("minecraft:enchanted_book")).orElse(null);
		}
		if (item == null) {
			KarmaIsCurrency.LOGGER.warn("Unknown item ID '{}' in shop section [{}]", id, type.configName());
			return null;
		}
		int cost = integer(values, "cost", -1);
		int amount = integer(values, "amount", 1);
		int maxPurchases = integer(values, "maxPurchases", 0);
		int weight = integer(values, "weight", 1);
		int unlockStage = integer(values, "unlockStage", 0);
		if (cost < 0 || amount <= 0 || maxPurchases < 0 || weight <= 0 || unlockStage < 0) {
			KarmaIsCurrency.LOGGER.warn("Invalid shop values for '{}' in [{}]; skipping entry", id, type.configName());
			return null;
		}
		boolean enabled = Boolean.parseBoolean(values.getOrDefault("enabled", "true"));
		String variant = values.getOrDefault("variant", "").trim();
		int level = Math.max(1, integer(values, "level", 1));
		String displayName = values.getOrDefault("name", new ItemStack(item).getHoverName().getString());
		ShopItemCategory itemCategory = ShopItemCategory.parse(values.get("category"));
		if (itemCategory == null) itemCategory = inferCategory(id, item, mode);
		return new ShopReward(id, ShopCategory.forShrineType(type), type, cost, item, amount, displayName,
			enabled, maxPurchases, mode, variant, level, weight, unlockStage, itemCategory);
	}


	private static ShopItemCategory inferCategory(String id, Item item, String mode) {
		String normalized = id.toLowerCase(Locale.ROOT);
		if (normalized.equals("stone") || normalized.equals("cobblestone") || normalized.equals("deepslate")
			|| normalized.equals("cobbled_deepslate") || normalized.equals("bricks") || normalized.equals("glass")
			|| normalized.equals("glass_pane") || normalized.equals("sandstone") || normalized.equals("red_sandstone")
			|| normalized.equals("smooth_stone") || normalized.equals("calcite") || normalized.equals("tuff")
			|| normalized.equals("quartz_block") || normalized.equals("ice") || normalized.equals("packed_ice")
			|| normalized.equals("blue_ice") || normalized.equals("moss_block")) {
			return ShopItemCategory.BUILDING;
		}
		if (normalized.contains("banner_pattern") || normalized.contains("pottery_sherd")
			|| normalized.contains("armor_trim_smithing_template") || normalized.startsWith("music_disc_")
			|| normalized.equals("froglight") || normalized.equals("painting")) {
			return ShopItemCategory.DECORATION;
		}
		if ((mode.equals("random_enchantment") || mode.equals("fixed_enchantment")) || mode.equals("suspicious_stew_cycle")
			|| normalized.equals("heart_of_the_sea") || normalized.equals("totem_of_undying")
			|| normalized.equals("mace")) {
			return ShopItemCategory.RARE;
		}
		if (normalized.equals("tnt") || normalized.equals("fire_charge")
			|| normalized.contains("spawn_egg")) {
			return ShopItemCategory.COMBAT;
		}
		if (normalized.equals("recovery_compass") || normalized.equals("saddle")
			|| normalized.equals("ender_pearl") || normalized.equals("goat_horn")) {
			return ShopItemCategory.UTILITY;
		}
		return ShopItemCategory.RESOURCES;
	}

	private static int integer(Map<String, String> values, String key, int fallback) {
		try {
			return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static ShrineType typeFor(String value) {
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
		return switch (normalized) {
			case "light" -> ShrineType.LIGHT;
			case "dark" -> ShrineType.DARK;
			case "neutral" -> ShrineType.NEUTRAL;
			case "monument" -> ShrineType.MONUMENT;
			default -> null;
		};
	}

    static List<String> migratePrices(List<String> lines) {
        if (lines.contains("# kic.shopBalanceVersion=5")) return lines;
        List<String> previous = lines.contains("# kic.shopBalanceVersion=4") ? lines : balanceV4(migrateBalanceV3(lines));
        return balanceV5(previous);
    }

    static Map<String, Map<String, String>> shopSections(List<String> lines) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String side = "";
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                side = line.substring(1, line.length()-1).toLowerCase(Locale.ROOT);
                result.computeIfAbsent(side, ignored -> new LinkedHashMap<>());
            } else if (!side.isEmpty() && !line.startsWith("#") && !line.startsWith(";") && line.contains("=")) {
                String[] kv = line.split("=", 2); result.get(side).put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }

    static List<String> balanceV5(List<String> lines) {
        var actual = shopSections(lines);
        var original = shopSections(lines);
        var proposed = shopSections(java.util.Arrays.asList(defaultText().split("\n")));
        var previous = shopSections(balanceV4(java.util.Arrays.asList(balanceV3Text().split("\n"))));
        // Remove old placements of managed entries; preserve unrelated custom entries.
        previous.forEach((side, fields) -> {
            var values = actual.get(side);
            if (values != null) values.keySet().removeIf(key -> fields.keySet().stream()
                .anyMatch(old -> old.split("\\.", 2)[0].equals(key.split("\\.", 2)[0]))
                && !proposed.getOrDefault(side, Map.of()).containsKey(key));
        });
        proposed.forEach((side, fields) -> {
            var values = actual.computeIfAbsent(side, ignored -> new LinkedHashMap<>());
            fields.forEach((key, value) -> {
                String field = key.substring(key.indexOf('.') + 1);
                if (field.equals("cost") || field.equals("amount") || field.equals("maxPurchases") || field.equals("unlockStage"))
                    values.put(key, value);
                else {
                    String inherited = value;
                    for (var oldFields : original.values()) {
                        if (oldFields.containsKey(key)) { inherited = oldFields.get(key); break; }
                    }
                    values.putIfAbsent(key, inherited);
                }
            });
        });
        List<String> result = new ArrayList<>(); result.add("# kic.shopBalanceVersion=5");
        for (String line : lines) if ((line.trim().startsWith("#") || line.trim().startsWith(";"))
                && !line.contains("kic.shopBalanceVersion=")) result.add(line);
        actual.forEach((side, fields) -> { result.add("[" + side + "]"); fields.forEach((key,value) -> result.add(key + "=" + value)); });
        return result;
    }

    private static String defaultText() {
        return """
            # kic.shopBalanceVersion=5
            [light]
            stone.cost=10
            stone.amount=64
            stone.enabled=true
            stone.maxPurchases=7
            stone.category=BUILDING
            stone.weight=4
            cobblestone.cost=8
            cobblestone.amount=64
            cobblestone.enabled=true
            cobblestone.maxPurchases=7
            cobblestone.category=BUILDING
            cobblestone.weight=4
            sand.cost=12
            sand.amount=64
            sand.enabled=true
            sand.maxPurchases=5
            glass.cost=16
            glass.amount=32
            glass.enabled=true
            glass.maxPurchases=5
            glass.category=BUILDING
            glass.weight=3
            blackstone.cost=12
            blackstone.amount=64
            blackstone.enabled=true
            blackstone.maxPurchases=7
            blackstone.category=BUILDING
            blackstone.weight=4
            basalt.cost=12
            basalt.amount=64
            basalt.enabled=true
            basalt.maxPurchases=7
            basalt.category=BUILDING
            basalt.weight=4
            packed_ice.cost=32
            packed_ice.amount=16
            packed_ice.enabled=true
            packed_ice.maxPurchases=5
            packed_ice.category=BUILDING
            packed_ice.weight=2
            blue_ice.cost=64
            blue_ice.amount=8
            blue_ice.enabled=true
            blue_ice.maxPurchases=3
            blue_ice.category=BUILDING
            blue_ice.weight=1
            ochre_froglight.cost=40
            ochre_froglight.amount=8
            ochre_froglight.enabled=true
            ochre_froglight.maxPurchases=3
            redstone.cost=36
            redstone.amount=32
            redstone.enabled=true
            redstone.maxPurchases=5
            redstone.category=RESOURCES
            redstone.weight=3
            lapis_lazuli.cost=20
            lapis_lazuli.amount=32
            lapis_lazuli.enabled=true
            lapis_lazuli.maxPurchases=5
            lapis_lazuli.category=RESOURCES
            lapis_lazuli.weight=3
            amethyst_shard.cost=28
            amethyst_shard.amount=16
            amethyst_shard.enabled=true
            amethyst_shard.maxPurchases=4
            amethyst_shard.category=RESOURCES
            amethyst_shard.weight=2
            slime_ball.cost=24
            slime_ball.amount=8
            slime_ball.enabled=true
            slime_ball.maxPurchases=4
            slime_ball.category=RESOURCES
            slime_ball.weight=2
            honeycomb.cost=20
            honeycomb.amount=16
            honeycomb.enabled=true
            honeycomb.maxPurchases=4
            honeycomb.category=RESOURCES
            honeycomb.weight=2
            bone.cost=12
            bone.amount=32
            bone.enabled=true
            bone.maxPurchases=7
            bone.category=RESOURCES
            bone.weight=4
            string.cost=16
            string.amount=32
            string.enabled=true
            string.maxPurchases=7
            string.category=RESOURCES
            string.weight=4
            obsidian.cost=32
            obsidian.amount=16
            obsidian.enabled=true
            obsidian.maxPurchases=2
            recovery_compass.cost=40
            recovery_compass.amount=1
            recovery_compass.enabled=true
            recovery_compass.maxPurchases=2
            saddle.cost=20
            saddle.amount=1
            saddle.enabled=true
            saddle.maxPurchases=2
            goat_horn.cost=48
            goat_horn.amount=1
            goat_horn.enabled=true
            goat_horn.maxPurchases=3
            rabbit_foot.cost=32
            rabbit_foot.amount=1
            rabbit_foot.enabled=true
            rabbit_foot.maxPurchases=2
            rabbit_foot.unlockStage=0
            phantom_membrane.cost=40
            phantom_membrane.amount=4
            phantom_membrane.enabled=true
            phantom_membrane.maxPurchases=3
            phantom_membrane.category=COMBAT
            phantom_membrane.weight=1
            sponge.cost=80
            sponge.amount=4
            sponge.enabled=true
            sponge.maxPurchases=3
            sponge.category=UTILITY
            sponge.weight=1
            golden_apple.cost=32
            golden_apple.amount=1
            golden_apple.enabled=true
            golden_apple.maxPurchases=3
            golden_apple.category=COMBAT
            golden_apple.weight=1
            ender_eye.cost=32
            ender_eye.amount=1
            ender_eye.enabled=true
            ender_eye.maxPurchases=3
            ender_eye.category=UTILITY
            ender_eye.weight=1
            wheat_seeds.cost=8
            wheat_seeds.amount=32
            wheat_seeds.enabled=true
            wheat_seeds.maxPurchases=5
            beetroot_seeds.cost=8
            beetroot_seeds.amount=32
            beetroot_seeds.enabled=true
            beetroot_seeds.maxPurchases=5
            horse_spawn_egg.cost=80
            horse_spawn_egg.amount=1
            horse_spawn_egg.enabled=true
            horse_spawn_egg.maxPurchases=2
            copper_nautilus_armor.cost=32
            copper_nautilus_armor.amount=1
            copper_nautilus_armor.enabled=true
            copper_nautilus_armor.maxPurchases=2
            copper_nautilus_armor.unlockStage=0
            iron_nautilus_armor.cost=48
            iron_nautilus_armor.amount=1
            iron_nautilus_armor.enabled=true
            iron_nautilus_armor.maxPurchases=2
            iron_nautilus_armor.unlockStage=0
            golden_nautilus_armor.cost=48
            golden_nautilus_armor.amount=1
            golden_nautilus_armor.enabled=true
            golden_nautilus_armor.maxPurchases=2
            golden_nautilus_armor.unlockStage=0
            torch.cost=8
            torch.amount=64
            torch.enabled=true
            torch.maxPurchases=0
            sea_lantern.cost=40
            sea_lantern.amount=16
            sea_lantern.enabled=true
            sea_lantern.maxPurchases=5
            heart_of_the_sea.cost=120
            heart_of_the_sea.amount=1
            heart_of_the_sea.enabled=true
            heart_of_the_sea.maxPurchases=2
            suspicious_stew.cost=16
            suspicious_stew.amount=1
            suspicious_stew.enabled=true
            suspicious_stew.maxPurchases=3
            suspicious_stew.mode=suspicious_stew_cycle
            torchflower_seeds.cost=24
            torchflower_seeds.amount=8
            torchflower_seeds.enabled=true
            torchflower_seeds.maxPurchases=3
            pitcher_pod.cost=24
            pitcher_pod.amount=8
            pitcher_pod.enabled=true
            pitcher_pod.maxPurchases=3
            enchanted_golden_apple.cost=60
            enchanted_golden_apple.amount=1
            enchanted_golden_apple.enabled=true
            enchanted_golden_apple.maxPurchases=1
            enchanted_golden_apple.category=RARE
            enchanted_golden_apple.weight=1
            enchanted_golden_apple.unlockStage=2
            shulker_shell.cost=120
            shulker_shell.amount=1
            shulker_shell.enabled=true
            shulker_shell.maxPurchases=2
            shulker_shell.category=RARE
            shulker_shell.weight=1
            shulker_shell.unlockStage=3
            ancient_debris.cost=35
            ancient_debris.amount=1
            ancient_debris.enabled=true
            ancient_debris.maxPurchases=3
            ancient_debris.category=RARE
            ancient_debris.weight=1
            ancient_debris.unlockStage=3
            netherite_scrap.cost=35
            netherite_scrap.amount=1
            netherite_scrap.enabled=true
            netherite_scrap.maxPurchases=3
            netherite_scrap.category=RARE
            netherite_scrap.weight=1
            netherite_scrap.unlockStage=3
            totem_of_undying.cost=100
            totem_of_undying.amount=1
            totem_of_undying.enabled=true
            totem_of_undying.maxPurchases=1
            totem_of_undying.unlockStage=2
            ender_pearl.cost=28
            ender_pearl.amount=4
            ender_pearl.enabled=true
            ender_pearl.maxPurchases=3
            cow_spawn_egg.cost=40
            cow_spawn_egg.amount=2
            cow_spawn_egg.enabled=true
            cow_spawn_egg.maxPurchases=2
            pig_spawn_egg.cost=40
            pig_spawn_egg.amount=2
            pig_spawn_egg.enabled=true
            pig_spawn_egg.maxPurchases=2
            sheep_spawn_egg.cost=40
            sheep_spawn_egg.amount=2
            sheep_spawn_egg.enabled=true
            sheep_spawn_egg.maxPurchases=2
            chicken_spawn_egg.cost=40
            chicken_spawn_egg.amount=2
            chicken_spawn_egg.enabled=true
            chicken_spawn_egg.maxPurchases=2
            diamond_nautilus_armor.cost=96
            diamond_nautilus_armor.amount=1
            diamond_nautilus_armor.enabled=true
            diamond_nautilus_armor.maxPurchases=1
            diamond_nautilus_armor.unlockStage=2
            netherite_nautilus_armor.cost=160
            netherite_nautilus_armor.amount=1
            netherite_nautilus_armor.enabled=true
            netherite_nautilus_armor.maxPurchases=1
            netherite_nautilus_armor.unlockStage=3
            deepslate.cost=16
            deepslate.amount=64
            deepslate.enabled=true
            deepslate.maxPurchases=7
            deepslate.category=BUILDING
            deepslate.weight=4
            flower_banner_pattern.cost=24
            flower_banner_pattern.amount=1
            flower_banner_pattern.enabled=true
            flower_banner_pattern.maxPurchases=2
            creeper_banner_pattern.cost=40
            creeper_banner_pattern.amount=1
            creeper_banner_pattern.enabled=true
            creeper_banner_pattern.maxPurchases=2
            globe_banner_pattern.cost=48
            globe_banner_pattern.amount=1
            globe_banner_pattern.enabled=true
            globe_banner_pattern.maxPurchases=2
            flow_banner_pattern.cost=48
            flow_banner_pattern.amount=1
            flow_banner_pattern.enabled=true
            flow_banner_pattern.maxPurchases=2
            guster_banner_pattern.cost=48
            guster_banner_pattern.amount=1
            guster_banner_pattern.enabled=true
            guster_banner_pattern.maxPurchases=2
            dune_armor_trim_smithing_template.cost=48
            dune_armor_trim_smithing_template.amount=1
            dune_armor_trim_smithing_template.enabled=true
            dune_armor_trim_smithing_template.maxPurchases=2
            wild_armor_trim_smithing_template.cost=48
            wild_armor_trim_smithing_template.amount=1
            wild_armor_trim_smithing_template.enabled=true
            wild_armor_trim_smithing_template.maxPurchases=2
            vex_armor_trim_smithing_template.cost=64
            vex_armor_trim_smithing_template.amount=1
            vex_armor_trim_smithing_template.enabled=true
            vex_armor_trim_smithing_template.maxPurchases=2
            coast_armor_trim_smithing_template.cost=48
            coast_armor_trim_smithing_template.amount=1
            coast_armor_trim_smithing_template.enabled=true
            coast_armor_trim_smithing_template.maxPurchases=2
            ward_armor_trim_smithing_template.cost=64
            ward_armor_trim_smithing_template.amount=1
            ward_armor_trim_smithing_template.enabled=true
            ward_armor_trim_smithing_template.maxPurchases=2
            rib_armor_trim_smithing_template.cost=64
            rib_armor_trim_smithing_template.amount=1
            rib_armor_trim_smithing_template.enabled=true
            rib_armor_trim_smithing_template.maxPurchases=2
            tide_armor_trim_smithing_template.cost=80
            tide_armor_trim_smithing_template.amount=1
            tide_armor_trim_smithing_template.enabled=true
            tide_armor_trim_smithing_template.maxPurchases=2
            wayfinder_armor_trim_smithing_template.cost=80
            wayfinder_armor_trim_smithing_template.amount=1
            wayfinder_armor_trim_smithing_template.enabled=true
            wayfinder_armor_trim_smithing_template.maxPurchases=2
            shaper_armor_trim_smithing_template.cost=80
            shaper_armor_trim_smithing_template.amount=1
            shaper_armor_trim_smithing_template.enabled=true
            shaper_armor_trim_smithing_template.maxPurchases=2
            raiser_armor_trim_smithing_template.cost=80
            raiser_armor_trim_smithing_template.amount=1
            raiser_armor_trim_smithing_template.enabled=true
            raiser_armor_trim_smithing_template.maxPurchases=2
            host_armor_trim_smithing_template.cost=80
            host_armor_trim_smithing_template.amount=1
            host_armor_trim_smithing_template.enabled=true
            host_armor_trim_smithing_template.maxPurchases=2
            flow_armor_trim_smithing_template.cost=96
            flow_armor_trim_smithing_template.amount=1
            flow_armor_trim_smithing_template.enabled=true
            flow_armor_trim_smithing_template.maxPurchases=2
            bolt_armor_trim_smithing_template.cost=96
            bolt_armor_trim_smithing_template.amount=1
            bolt_armor_trim_smithing_template.enabled=true
            bolt_armor_trim_smithing_template.maxPurchases=2
            eye_armor_trim_smithing_template.cost=96
            eye_armor_trim_smithing_template.amount=1
            eye_armor_trim_smithing_template.enabled=true
            eye_armor_trim_smithing_template.maxPurchases=2
            spire_armor_trim_smithing_template.cost=120
            spire_armor_trim_smithing_template.amount=1
            spire_armor_trim_smithing_template.enabled=true
            spire_armor_trim_smithing_template.maxPurchases=2
            silence_armor_trim_smithing_template.cost=200
            silence_armor_trim_smithing_template.amount=1
            silence_armor_trim_smithing_template.enabled=true
            silence_armor_trim_smithing_template.maxPurchases=2
            angler_pottery_sherd.cost=24
            angler_pottery_sherd.amount=1
            angler_pottery_sherd.enabled=true
            angler_pottery_sherd.maxPurchases=3
            prize_pottery_sherd.cost=24
            prize_pottery_sherd.amount=1
            prize_pottery_sherd.enabled=true
            prize_pottery_sherd.maxPurchases=3
            arms_up_pottery_sherd.cost=24
            arms_up_pottery_sherd.amount=1
            arms_up_pottery_sherd.enabled=true
            arms_up_pottery_sherd.maxPurchases=3
            blade_pottery_sherd.cost=24
            blade_pottery_sherd.amount=1
            blade_pottery_sherd.enabled=true
            blade_pottery_sherd.maxPurchases=3
            brewer_pottery_sherd.cost=24
            brewer_pottery_sherd.amount=1
            brewer_pottery_sherd.enabled=true
            brewer_pottery_sherd.maxPurchases=3
            burn_pottery_sherd.cost=24
            burn_pottery_sherd.amount=1
            burn_pottery_sherd.enabled=true
            burn_pottery_sherd.maxPurchases=3
            danger_pottery_sherd.cost=24
            danger_pottery_sherd.amount=1
            danger_pottery_sherd.enabled=true
            danger_pottery_sherd.maxPurchases=3
            explorer_pottery_sherd.cost=24
            explorer_pottery_sherd.amount=1
            explorer_pottery_sherd.enabled=true
            explorer_pottery_sherd.maxPurchases=3
            friend_pottery_sherd.cost=24
            friend_pottery_sherd.amount=1
            friend_pottery_sherd.enabled=true
            friend_pottery_sherd.maxPurchases=3
            heart_pottery_sherd.cost=24
            heart_pottery_sherd.amount=1
            heart_pottery_sherd.enabled=true
            heart_pottery_sherd.maxPurchases=3
            heartbreak_pottery_sherd.cost=24
            heartbreak_pottery_sherd.amount=1
            heartbreak_pottery_sherd.enabled=true
            heartbreak_pottery_sherd.maxPurchases=3
            howl_pottery_sherd.cost=24
            howl_pottery_sherd.amount=1
            howl_pottery_sherd.enabled=true
            howl_pottery_sherd.maxPurchases=3
            miner_pottery_sherd.cost=24
            miner_pottery_sherd.amount=1
            miner_pottery_sherd.enabled=true
            miner_pottery_sherd.maxPurchases=3
            mourn_pottery_sherd.cost=24
            mourn_pottery_sherd.amount=1
            mourn_pottery_sherd.enabled=true
            mourn_pottery_sherd.maxPurchases=3
            plenty_pottery_sherd.cost=24
            plenty_pottery_sherd.amount=1
            plenty_pottery_sherd.enabled=true
            plenty_pottery_sherd.maxPurchases=3
            scrape_pottery_sherd.cost=24
            scrape_pottery_sherd.amount=1
            scrape_pottery_sherd.enabled=true
            scrape_pottery_sherd.maxPurchases=3
            sheaf_pottery_sherd.cost=24
            sheaf_pottery_sherd.amount=1
            sheaf_pottery_sherd.enabled=true
            sheaf_pottery_sherd.maxPurchases=3
            shelter_pottery_sherd.cost=24
            shelter_pottery_sherd.amount=1
            shelter_pottery_sherd.enabled=true
            shelter_pottery_sherd.maxPurchases=3
            skull_pottery_sherd.cost=24
            skull_pottery_sherd.amount=1
            skull_pottery_sherd.enabled=true
            skull_pottery_sherd.maxPurchases=3
            snort_pottery_sherd.cost=24
            snort_pottery_sherd.amount=1
            snort_pottery_sherd.enabled=true
            snort_pottery_sherd.maxPurchases=3
            music_disc_13.cost=48
            music_disc_13.amount=1
            music_disc_13.enabled=true
            music_disc_13.maxPurchases=2
            music_disc_cat.cost=48
            music_disc_cat.amount=1
            music_disc_cat.enabled=true
            music_disc_cat.maxPurchases=2
            music_disc_blocks.cost=64
            music_disc_blocks.amount=1
            music_disc_blocks.enabled=true
            music_disc_blocks.maxPurchases=2
            music_disc_chirp.cost=64
            music_disc_chirp.amount=1
            music_disc_chirp.enabled=true
            music_disc_chirp.maxPurchases=2
            book_efficiency_1.cost=32
            book_efficiency_1.amount=1
            book_efficiency_1.enabled=true
            book_efficiency_1.maxPurchases=2
            book_efficiency_1.unlockStage=0
            book_efficiency_1.mode=fixed_enchantment
            book_efficiency_1.variant=minecraft:efficiency
            book_efficiency_1.level=1
            book_efficiency_1.name=Efficiency 1
            book_efficiency_2.cost=40
            book_efficiency_2.amount=1
            book_efficiency_2.enabled=true
            book_efficiency_2.maxPurchases=2
            book_efficiency_2.unlockStage=0
            book_efficiency_2.mode=fixed_enchantment
            book_efficiency_2.variant=minecraft:efficiency
            book_efficiency_2.level=2
            book_efficiency_2.name=Efficiency 2
            book_efficiency_3.cost=48
            book_efficiency_3.amount=1
            book_efficiency_3.enabled=true
            book_efficiency_3.maxPurchases=2
            book_efficiency_3.unlockStage=0
            book_efficiency_3.mode=fixed_enchantment
            book_efficiency_3.variant=minecraft:efficiency
            book_efficiency_3.level=3
            book_efficiency_3.name=Efficiency 3
            book_efficiency_4.cost=64
            book_efficiency_4.amount=1
            book_efficiency_4.enabled=true
            book_efficiency_4.maxPurchases=2
            book_efficiency_4.unlockStage=0
            book_efficiency_4.mode=fixed_enchantment
            book_efficiency_4.variant=minecraft:efficiency
            book_efficiency_4.level=4
            book_efficiency_4.name=Efficiency 4
            book_efficiency_5.cost=80
            book_efficiency_5.amount=1
            book_efficiency_5.enabled=true
            book_efficiency_5.maxPurchases=2
            book_efficiency_5.unlockStage=0
            book_efficiency_5.mode=fixed_enchantment
            book_efficiency_5.variant=minecraft:efficiency
            book_efficiency_5.level=5
            book_efficiency_5.name=Efficiency 5
            book_efficiency_6.cost=160
            book_efficiency_6.amount=1
            book_efficiency_6.enabled=true
            book_efficiency_6.maxPurchases=1
            book_efficiency_6.unlockStage=3
            book_efficiency_6.mode=fixed_enchantment
            book_efficiency_6.variant=minecraft:efficiency
            book_efficiency_6.level=6
            book_efficiency_6.name=Efficiency 6
            book_fortune_1.cost=48
            book_fortune_1.amount=1
            book_fortune_1.enabled=true
            book_fortune_1.maxPurchases=2
            book_fortune_1.unlockStage=0
            book_fortune_1.mode=fixed_enchantment
            book_fortune_1.variant=minecraft:fortune
            book_fortune_1.level=1
            book_fortune_1.name=Fortune 1
            book_fortune_2.cost=64
            book_fortune_2.amount=1
            book_fortune_2.enabled=true
            book_fortune_2.maxPurchases=2
            book_fortune_2.unlockStage=0
            book_fortune_2.mode=fixed_enchantment
            book_fortune_2.variant=minecraft:fortune
            book_fortune_2.level=2
            book_fortune_2.name=Fortune 2
            book_fortune_3.cost=96
            book_fortune_3.amount=1
            book_fortune_3.enabled=true
            book_fortune_3.maxPurchases=2
            book_fortune_3.unlockStage=0
            book_fortune_3.mode=fixed_enchantment
            book_fortune_3.variant=minecraft:fortune
            book_fortune_3.level=3
            book_fortune_3.name=Fortune 3
            book_fortune_4.cost=200
            book_fortune_4.amount=1
            book_fortune_4.enabled=true
            book_fortune_4.maxPurchases=1
            book_fortune_4.unlockStage=3
            book_fortune_4.mode=fixed_enchantment
            book_fortune_4.variant=minecraft:fortune
            book_fortune_4.level=4
            book_fortune_4.name=Fortune 4
            book_protection_1.cost=32
            book_protection_1.amount=1
            book_protection_1.enabled=true
            book_protection_1.maxPurchases=2
            book_protection_1.unlockStage=0
            book_protection_1.mode=fixed_enchantment
            book_protection_1.variant=minecraft:protection
            book_protection_1.level=1
            book_protection_1.name=Protection 1
            book_protection_2.cost=48
            book_protection_2.amount=1
            book_protection_2.enabled=true
            book_protection_2.maxPurchases=2
            book_protection_2.unlockStage=0
            book_protection_2.mode=fixed_enchantment
            book_protection_2.variant=minecraft:protection
            book_protection_2.level=2
            book_protection_2.name=Protection 2
            book_protection_3.cost=64
            book_protection_3.amount=1
            book_protection_3.enabled=true
            book_protection_3.maxPurchases=2
            book_protection_3.unlockStage=0
            book_protection_3.mode=fixed_enchantment
            book_protection_3.variant=minecraft:protection
            book_protection_3.level=3
            book_protection_3.name=Protection 3
            book_protection_4.cost=80
            book_protection_4.amount=1
            book_protection_4.enabled=true
            book_protection_4.maxPurchases=2
            book_protection_4.unlockStage=0
            book_protection_4.mode=fixed_enchantment
            book_protection_4.variant=minecraft:protection
            book_protection_4.level=4
            book_protection_4.name=Protection 4
            book_protection_5.cost=160
            book_protection_5.amount=1
            book_protection_5.enabled=true
            book_protection_5.maxPurchases=1
            book_protection_5.unlockStage=3
            book_protection_5.mode=fixed_enchantment
            book_protection_5.variant=minecraft:protection
            book_protection_5.level=5
            book_protection_5.name=Protection 5
            book_unbreaking_1.cost=32
            book_unbreaking_1.amount=1
            book_unbreaking_1.enabled=true
            book_unbreaking_1.maxPurchases=2
            book_unbreaking_1.unlockStage=0
            book_unbreaking_1.mode=fixed_enchantment
            book_unbreaking_1.variant=minecraft:unbreaking
            book_unbreaking_1.level=1
            book_unbreaking_1.name=Unbreaking 1
            book_unbreaking_2.cost=48
            book_unbreaking_2.amount=1
            book_unbreaking_2.enabled=true
            book_unbreaking_2.maxPurchases=2
            book_unbreaking_2.unlockStage=0
            book_unbreaking_2.mode=fixed_enchantment
            book_unbreaking_2.variant=minecraft:unbreaking
            book_unbreaking_2.level=2
            book_unbreaking_2.name=Unbreaking 2
            book_unbreaking_3.cost=64
            book_unbreaking_3.amount=1
            book_unbreaking_3.enabled=true
            book_unbreaking_3.maxPurchases=2
            book_unbreaking_3.unlockStage=0
            book_unbreaking_3.mode=fixed_enchantment
            book_unbreaking_3.variant=minecraft:unbreaking
            book_unbreaking_3.level=3
            book_unbreaking_3.name=Unbreaking 3
            book_unbreaking_4.cost=160
            book_unbreaking_4.amount=1
            book_unbreaking_4.enabled=true
            book_unbreaking_4.maxPurchases=1
            book_unbreaking_4.unlockStage=3
            book_unbreaking_4.mode=fixed_enchantment
            book_unbreaking_4.variant=minecraft:unbreaking
            book_unbreaking_4.level=4
            book_unbreaking_4.name=Unbreaking 4
            book_sharpness_1.cost=32
            book_sharpness_1.amount=1
            book_sharpness_1.enabled=true
            book_sharpness_1.maxPurchases=2
            book_sharpness_1.unlockStage=0
            book_sharpness_1.mode=fixed_enchantment
            book_sharpness_1.variant=minecraft:sharpness
            book_sharpness_1.level=1
            book_sharpness_1.name=Sharpness 1
            book_sharpness_2.cost=40
            book_sharpness_2.amount=1
            book_sharpness_2.enabled=true
            book_sharpness_2.maxPurchases=2
            book_sharpness_2.unlockStage=0
            book_sharpness_2.mode=fixed_enchantment
            book_sharpness_2.variant=minecraft:sharpness
            book_sharpness_2.level=2
            book_sharpness_2.name=Sharpness 2
            book_sharpness_3.cost=48
            book_sharpness_3.amount=1
            book_sharpness_3.enabled=true
            book_sharpness_3.maxPurchases=2
            book_sharpness_3.unlockStage=0
            book_sharpness_3.mode=fixed_enchantment
            book_sharpness_3.variant=minecraft:sharpness
            book_sharpness_3.level=3
            book_sharpness_3.name=Sharpness 3
            book_sharpness_4.cost=64
            book_sharpness_4.amount=1
            book_sharpness_4.enabled=true
            book_sharpness_4.maxPurchases=2
            book_sharpness_4.unlockStage=0
            book_sharpness_4.mode=fixed_enchantment
            book_sharpness_4.variant=minecraft:sharpness
            book_sharpness_4.level=4
            book_sharpness_4.name=Sharpness 4
            book_sharpness_5.cost=80
            book_sharpness_5.amount=1
            book_sharpness_5.enabled=true
            book_sharpness_5.maxPurchases=2
            book_sharpness_5.unlockStage=0
            book_sharpness_5.mode=fixed_enchantment
            book_sharpness_5.variant=minecraft:sharpness
            book_sharpness_5.level=5
            book_sharpness_5.name=Sharpness 5
            book_sharpness_6.cost=160
            book_sharpness_6.amount=1
            book_sharpness_6.enabled=true
            book_sharpness_6.maxPurchases=1
            book_sharpness_6.unlockStage=3
            book_sharpness_6.mode=fixed_enchantment
            book_sharpness_6.variant=minecraft:sharpness
            book_sharpness_6.level=6
            book_sharpness_6.name=Sharpness 6
            book_mending_1.cost=96
            book_mending_1.amount=1
            book_mending_1.enabled=true
            book_mending_1.maxPurchases=2
            book_mending_1.unlockStage=0
            book_mending_1.mode=fixed_enchantment
            book_mending_1.variant=minecraft:mending
            book_mending_1.level=1
            book_mending_1.name=Mending 1
            book_aqua_affinity_1.cost=32
            book_aqua_affinity_1.amount=1
            book_aqua_affinity_1.enabled=true
            book_aqua_affinity_1.maxPurchases=2
            book_aqua_affinity_1.unlockStage=0
            book_aqua_affinity_1.mode=fixed_enchantment
            book_aqua_affinity_1.variant=minecraft:aqua_affinity
            book_aqua_affinity_1.level=1
            book_aqua_affinity_1.name=Aqua Affinity 1
            book_silk_touch_1.cost=64
            book_silk_touch_1.amount=1
            book_silk_touch_1.enabled=true
            book_silk_touch_1.maxPurchases=2
            book_silk_touch_1.unlockStage=0
            book_silk_touch_1.mode=fixed_enchantment
            book_silk_touch_1.variant=minecraft:silk_touch
            book_silk_touch_1.level=1
            book_silk_touch_1.name=Silk Touch 1
            book_infinity_1.cost=80
            book_infinity_1.amount=1
            book_infinity_1.enabled=true
            book_infinity_1.maxPurchases=2
            book_infinity_1.unlockStage=0
            book_infinity_1.mode=fixed_enchantment
            book_infinity_1.variant=minecraft:infinity
            book_infinity_1.level=1
            book_infinity_1.name=Infinity 1
            book_swift_sneak_1.cost=48
            book_swift_sneak_1.amount=1
            book_swift_sneak_1.enabled=true
            book_swift_sneak_1.maxPurchases=2
            book_swift_sneak_1.unlockStage=0
            book_swift_sneak_1.mode=fixed_enchantment
            book_swift_sneak_1.variant=minecraft:swift_sneak
            book_swift_sneak_1.level=1
            book_swift_sneak_1.name=Swift Sneak 1
            book_swift_sneak_2.cost=64
            book_swift_sneak_2.amount=1
            book_swift_sneak_2.enabled=true
            book_swift_sneak_2.maxPurchases=2
            book_swift_sneak_2.unlockStage=0
            book_swift_sneak_2.mode=fixed_enchantment
            book_swift_sneak_2.variant=minecraft:swift_sneak
            book_swift_sneak_2.level=2
            book_swift_sneak_2.name=Swift Sneak 2
            book_swift_sneak_3.cost=96
            book_swift_sneak_3.amount=1
            book_swift_sneak_3.enabled=true
            book_swift_sneak_3.maxPurchases=2
            book_swift_sneak_3.unlockStage=0
            book_swift_sneak_3.mode=fixed_enchantment
            book_swift_sneak_3.variant=minecraft:swift_sneak
            book_swift_sneak_3.level=3
            book_swift_sneak_3.name=Swift Sneak 3
            book_swift_sneak_4.cost=160
            book_swift_sneak_4.amount=1
            book_swift_sneak_4.enabled=true
            book_swift_sneak_4.maxPurchases=1
            book_swift_sneak_4.unlockStage=3
            book_swift_sneak_4.mode=fixed_enchantment
            book_swift_sneak_4.variant=minecraft:swift_sneak
            book_swift_sneak_4.level=4
            book_swift_sneak_4.name=Swift Sneak 4
            [dark]
            stone.cost=10
            stone.amount=64
            stone.enabled=true
            stone.maxPurchases=7
            stone.category=BUILDING
            stone.weight=4
            cobblestone.cost=8
            cobblestone.amount=64
            cobblestone.enabled=true
            cobblestone.maxPurchases=7
            cobblestone.category=BUILDING
            cobblestone.weight=4
            sand.cost=12
            sand.amount=64
            sand.enabled=true
            sand.maxPurchases=5
            glass.cost=16
            glass.amount=32
            glass.enabled=true
            glass.maxPurchases=5
            glass.category=BUILDING
            glass.weight=3
            blackstone.cost=12
            blackstone.amount=64
            blackstone.enabled=true
            blackstone.maxPurchases=7
            blackstone.category=BUILDING
            blackstone.weight=4
            basalt.cost=12
            basalt.amount=64
            basalt.enabled=true
            basalt.maxPurchases=7
            basalt.category=BUILDING
            basalt.weight=4
            packed_ice.cost=32
            packed_ice.amount=16
            packed_ice.enabled=true
            packed_ice.maxPurchases=5
            packed_ice.category=BUILDING
            packed_ice.weight=2
            blue_ice.cost=64
            blue_ice.amount=8
            blue_ice.enabled=true
            blue_ice.maxPurchases=3
            blue_ice.category=BUILDING
            blue_ice.weight=1
            ochre_froglight.cost=40
            ochre_froglight.amount=8
            ochre_froglight.enabled=true
            ochre_froglight.maxPurchases=3
            redstone.cost=36
            redstone.amount=32
            redstone.enabled=true
            redstone.maxPurchases=5
            redstone.category=RESOURCES
            redstone.weight=3
            lapis_lazuli.cost=20
            lapis_lazuli.amount=32
            lapis_lazuli.enabled=true
            lapis_lazuli.maxPurchases=5
            lapis_lazuli.category=RESOURCES
            lapis_lazuli.weight=3
            amethyst_shard.cost=28
            amethyst_shard.amount=16
            amethyst_shard.enabled=true
            amethyst_shard.maxPurchases=4
            amethyst_shard.category=RESOURCES
            amethyst_shard.weight=2
            slime_ball.cost=24
            slime_ball.amount=8
            slime_ball.enabled=true
            slime_ball.maxPurchases=4
            slime_ball.category=RESOURCES
            slime_ball.weight=2
            honeycomb.cost=20
            honeycomb.amount=16
            honeycomb.enabled=true
            honeycomb.maxPurchases=4
            honeycomb.category=RESOURCES
            honeycomb.weight=2
            bone.cost=12
            bone.amount=32
            bone.enabled=true
            bone.maxPurchases=7
            bone.category=RESOURCES
            bone.weight=4
            string.cost=16
            string.amount=32
            string.enabled=true
            string.maxPurchases=7
            string.category=RESOURCES
            string.weight=4
            obsidian.cost=32
            obsidian.amount=16
            obsidian.enabled=true
            obsidian.maxPurchases=2
            recovery_compass.cost=40
            recovery_compass.amount=1
            recovery_compass.enabled=true
            recovery_compass.maxPurchases=2
            saddle.cost=20
            saddle.amount=1
            saddle.enabled=true
            saddle.maxPurchases=2
            goat_horn.cost=48
            goat_horn.amount=1
            goat_horn.enabled=true
            goat_horn.maxPurchases=3
            rabbit_foot.cost=32
            rabbit_foot.amount=1
            rabbit_foot.enabled=true
            rabbit_foot.maxPurchases=2
            rabbit_foot.unlockStage=0
            phantom_membrane.cost=40
            phantom_membrane.amount=4
            phantom_membrane.enabled=true
            phantom_membrane.maxPurchases=3
            phantom_membrane.category=COMBAT
            phantom_membrane.weight=1
            sponge.cost=80
            sponge.amount=4
            sponge.enabled=true
            sponge.maxPurchases=3
            sponge.category=UTILITY
            sponge.weight=1
            golden_apple.cost=32
            golden_apple.amount=1
            golden_apple.enabled=true
            golden_apple.maxPurchases=3
            golden_apple.category=COMBAT
            golden_apple.weight=1
            ender_eye.cost=32
            ender_eye.amount=1
            ender_eye.enabled=true
            ender_eye.maxPurchases=3
            ender_eye.category=UTILITY
            ender_eye.weight=1
            wheat_seeds.cost=8
            wheat_seeds.amount=32
            wheat_seeds.enabled=true
            wheat_seeds.maxPurchases=5
            beetroot_seeds.cost=8
            beetroot_seeds.amount=32
            beetroot_seeds.enabled=true
            beetroot_seeds.maxPurchases=5
            horse_spawn_egg.cost=80
            horse_spawn_egg.amount=1
            horse_spawn_egg.enabled=true
            horse_spawn_egg.maxPurchases=2
            copper_nautilus_armor.cost=32
            copper_nautilus_armor.amount=1
            copper_nautilus_armor.enabled=true
            copper_nautilus_armor.maxPurchases=2
            copper_nautilus_armor.unlockStage=0
            iron_nautilus_armor.cost=48
            iron_nautilus_armor.amount=1
            iron_nautilus_armor.enabled=true
            iron_nautilus_armor.maxPurchases=2
            iron_nautilus_armor.unlockStage=0
            golden_nautilus_armor.cost=48
            golden_nautilus_armor.amount=1
            golden_nautilus_armor.enabled=true
            golden_nautilus_armor.maxPurchases=2
            golden_nautilus_armor.unlockStage=0
            torch.cost=8
            torch.amount=64
            torch.enabled=true
            torch.maxPurchases=0
            nether_bricks.cost=16
            nether_bricks.amount=32
            nether_bricks.enabled=true
            nether_bricks.maxPurchases=5
            nether_bricks.category=BUILDING
            nether_bricks.weight=3
            gilded_blackstone.cost=24
            gilded_blackstone.amount=32
            gilded_blackstone.enabled=true
            gilded_blackstone.maxPurchases=3
            rotten_flesh.cost=8
            rotten_flesh.amount=32
            rotten_flesh.enabled=true
            rotten_flesh.maxPurchases=7
            rotten_flesh.category=RESOURCES
            rotten_flesh.weight=4
            gunpowder.cost=32
            gunpowder.amount=16
            gunpowder.enabled=true
            gunpowder.maxPurchases=5
            gunpowder.category=RESOURCES
            gunpowder.weight=3
            blaze_powder.cost=32
            blaze_powder.amount=16
            blaze_powder.enabled=true
            blaze_powder.maxPurchases=5
            blaze_powder.category=COMBAT
            blaze_powder.weight=2
            magma_cream.cost=32
            magma_cream.amount=8
            magma_cream.enabled=true
            magma_cream.maxPurchases=4
            magma_cream.category=COMBAT
            magma_cream.weight=2
            blaze_rod.cost=48
            blaze_rod.amount=8
            blaze_rod.enabled=true
            blaze_rod.maxPurchases=4
            blaze_rod.category=COMBAT
            blaze_rod.weight=1
            ghast_tear.cost=32
            ghast_tear.amount=2
            ghast_tear.enabled=true
            ghast_tear.maxPurchases=3
            ghast_tear.category=COMBAT
            ghast_tear.weight=1
            nether_wart.cost=16
            nether_wart.amount=32
            nether_wart.enabled=true
            nether_wart.maxPurchases=5
            tnt.cost=40
            tnt.amount=4
            tnt.enabled=true
            tnt.maxPurchases=3
            wither_skeleton_skull.cost=160
            wither_skeleton_skull.amount=1
            wither_skeleton_skull.enabled=true
            wither_skeleton_skull.maxPurchases=2
            wither_skeleton_skull.category=RARE
            wither_skeleton_skull.weight=1
            wither_skeleton_skull.unlockStage=3
            music_disc_pigstep.cost=120
            music_disc_pigstep.amount=1
            music_disc_pigstep.enabled=true
            music_disc_pigstep.maxPurchases=2
            crying_obsidian.cost=48
            crying_obsidian.amount=8
            crying_obsidian.enabled=true
            crying_obsidian.maxPurchases=4
            magma_block.cost=24
            magma_block.amount=16
            magma_block.enabled=true
            magma_block.maxPurchases=4
            enchanted_golden_apple.cost=60
            enchanted_golden_apple.amount=1
            enchanted_golden_apple.enabled=true
            enchanted_golden_apple.maxPurchases=1
            enchanted_golden_apple.category=RARE
            enchanted_golden_apple.weight=1
            enchanted_golden_apple.unlockStage=2
            shulker_shell.cost=120
            shulker_shell.amount=1
            shulker_shell.enabled=true
            shulker_shell.maxPurchases=2
            shulker_shell.category=RARE
            shulker_shell.weight=1
            shulker_shell.unlockStage=3
            ancient_debris.cost=35
            ancient_debris.amount=1
            ancient_debris.enabled=true
            ancient_debris.maxPurchases=3
            ancient_debris.category=RARE
            ancient_debris.weight=1
            ancient_debris.unlockStage=3
            netherite_scrap.cost=35
            netherite_scrap.amount=1
            netherite_scrap.enabled=true
            netherite_scrap.maxPurchases=3
            netherite_scrap.category=RARE
            netherite_scrap.weight=1
            netherite_scrap.unlockStage=3
            totem_of_undying.cost=100
            totem_of_undying.amount=1
            totem_of_undying.enabled=true
            totem_of_undying.maxPurchases=1
            totem_of_undying.unlockStage=2
            ender_pearl.cost=28
            ender_pearl.amount=4
            ender_pearl.enabled=true
            ender_pearl.maxPurchases=3
            ender_pearl_small.cost=16
            ender_pearl_small.amount=2
            ender_pearl_small.enabled=true
            ender_pearl_small.maxPurchases=3
            ender_pearl_small.item=minecraft:ender_pearl
            ender_pearl_small.name=Ender Pearls (small bundle)
            cow_spawn_egg.cost=40
            cow_spawn_egg.amount=2
            cow_spawn_egg.enabled=true
            cow_spawn_egg.maxPurchases=2
            pig_spawn_egg.cost=40
            pig_spawn_egg.amount=2
            pig_spawn_egg.enabled=true
            pig_spawn_egg.maxPurchases=2
            sheep_spawn_egg.cost=40
            sheep_spawn_egg.amount=2
            sheep_spawn_egg.enabled=true
            sheep_spawn_egg.maxPurchases=2
            chicken_spawn_egg.cost=40
            chicken_spawn_egg.amount=2
            chicken_spawn_egg.enabled=true
            chicken_spawn_egg.maxPurchases=2
            diamond_nautilus_armor.cost=96
            diamond_nautilus_armor.amount=1
            diamond_nautilus_armor.enabled=true
            diamond_nautilus_armor.maxPurchases=1
            diamond_nautilus_armor.unlockStage=2
            netherite_nautilus_armor.cost=160
            netherite_nautilus_armor.amount=1
            netherite_nautilus_armor.enabled=true
            netherite_nautilus_armor.maxPurchases=1
            netherite_nautilus_armor.unlockStage=3
            deepslate.cost=16
            deepslate.amount=64
            deepslate.enabled=true
            deepslate.maxPurchases=7
            deepslate.category=BUILDING
            deepslate.weight=4
            flower_banner_pattern.cost=24
            flower_banner_pattern.amount=1
            flower_banner_pattern.enabled=true
            flower_banner_pattern.maxPurchases=2
            creeper_banner_pattern.cost=40
            creeper_banner_pattern.amount=1
            creeper_banner_pattern.enabled=true
            creeper_banner_pattern.maxPurchases=2
            snout_banner_pattern.cost=40
            snout_banner_pattern.amount=1
            snout_banner_pattern.enabled=true
            snout_banner_pattern.maxPurchases=2
            globe_banner_pattern.cost=48
            globe_banner_pattern.amount=1
            globe_banner_pattern.enabled=true
            globe_banner_pattern.maxPurchases=2
            flow_banner_pattern.cost=48
            flow_banner_pattern.amount=1
            flow_banner_pattern.enabled=true
            flow_banner_pattern.maxPurchases=2
            guster_banner_pattern.cost=48
            guster_banner_pattern.amount=1
            guster_banner_pattern.enabled=true
            guster_banner_pattern.maxPurchases=2
            dune_armor_trim_smithing_template.cost=48
            dune_armor_trim_smithing_template.amount=1
            dune_armor_trim_smithing_template.enabled=true
            dune_armor_trim_smithing_template.maxPurchases=2
            wild_armor_trim_smithing_template.cost=48
            wild_armor_trim_smithing_template.amount=1
            wild_armor_trim_smithing_template.enabled=true
            wild_armor_trim_smithing_template.maxPurchases=2
            vex_armor_trim_smithing_template.cost=64
            vex_armor_trim_smithing_template.amount=1
            vex_armor_trim_smithing_template.enabled=true
            vex_armor_trim_smithing_template.maxPurchases=2
            coast_armor_trim_smithing_template.cost=48
            coast_armor_trim_smithing_template.amount=1
            coast_armor_trim_smithing_template.enabled=true
            coast_armor_trim_smithing_template.maxPurchases=2
            ward_armor_trim_smithing_template.cost=64
            ward_armor_trim_smithing_template.amount=1
            ward_armor_trim_smithing_template.enabled=true
            ward_armor_trim_smithing_template.maxPurchases=2
            rib_armor_trim_smithing_template.cost=64
            rib_armor_trim_smithing_template.amount=1
            rib_armor_trim_smithing_template.enabled=true
            rib_armor_trim_smithing_template.maxPurchases=2
            tide_armor_trim_smithing_template.cost=80
            tide_armor_trim_smithing_template.amount=1
            tide_armor_trim_smithing_template.enabled=true
            tide_armor_trim_smithing_template.maxPurchases=2
            wayfinder_armor_trim_smithing_template.cost=80
            wayfinder_armor_trim_smithing_template.amount=1
            wayfinder_armor_trim_smithing_template.enabled=true
            wayfinder_armor_trim_smithing_template.maxPurchases=2
            shaper_armor_trim_smithing_template.cost=80
            shaper_armor_trim_smithing_template.amount=1
            shaper_armor_trim_smithing_template.enabled=true
            shaper_armor_trim_smithing_template.maxPurchases=2
            raiser_armor_trim_smithing_template.cost=80
            raiser_armor_trim_smithing_template.amount=1
            raiser_armor_trim_smithing_template.enabled=true
            raiser_armor_trim_smithing_template.maxPurchases=2
            host_armor_trim_smithing_template.cost=80
            host_armor_trim_smithing_template.amount=1
            host_armor_trim_smithing_template.enabled=true
            host_armor_trim_smithing_template.maxPurchases=2
            flow_armor_trim_smithing_template.cost=96
            flow_armor_trim_smithing_template.amount=1
            flow_armor_trim_smithing_template.enabled=true
            flow_armor_trim_smithing_template.maxPurchases=2
            bolt_armor_trim_smithing_template.cost=96
            bolt_armor_trim_smithing_template.amount=1
            bolt_armor_trim_smithing_template.enabled=true
            bolt_armor_trim_smithing_template.maxPurchases=2
            eye_armor_trim_smithing_template.cost=96
            eye_armor_trim_smithing_template.amount=1
            eye_armor_trim_smithing_template.enabled=true
            eye_armor_trim_smithing_template.maxPurchases=2
            spire_armor_trim_smithing_template.cost=120
            spire_armor_trim_smithing_template.amount=1
            spire_armor_trim_smithing_template.enabled=true
            spire_armor_trim_smithing_template.maxPurchases=2
            silence_armor_trim_smithing_template.cost=200
            silence_armor_trim_smithing_template.amount=1
            silence_armor_trim_smithing_template.enabled=true
            silence_armor_trim_smithing_template.maxPurchases=2
            angler_pottery_sherd.cost=24
            angler_pottery_sherd.amount=1
            angler_pottery_sherd.enabled=true
            angler_pottery_sherd.maxPurchases=3
            prize_pottery_sherd.cost=24
            prize_pottery_sherd.amount=1
            prize_pottery_sherd.enabled=true
            prize_pottery_sherd.maxPurchases=3
            arms_up_pottery_sherd.cost=24
            arms_up_pottery_sherd.amount=1
            arms_up_pottery_sherd.enabled=true
            arms_up_pottery_sherd.maxPurchases=3
            blade_pottery_sherd.cost=24
            blade_pottery_sherd.amount=1
            blade_pottery_sherd.enabled=true
            blade_pottery_sherd.maxPurchases=3
            brewer_pottery_sherd.cost=24
            brewer_pottery_sherd.amount=1
            brewer_pottery_sherd.enabled=true
            brewer_pottery_sherd.maxPurchases=3
            burn_pottery_sherd.cost=24
            burn_pottery_sherd.amount=1
            burn_pottery_sherd.enabled=true
            burn_pottery_sherd.maxPurchases=3
            danger_pottery_sherd.cost=24
            danger_pottery_sherd.amount=1
            danger_pottery_sherd.enabled=true
            danger_pottery_sherd.maxPurchases=3
            explorer_pottery_sherd.cost=24
            explorer_pottery_sherd.amount=1
            explorer_pottery_sherd.enabled=true
            explorer_pottery_sherd.maxPurchases=3
            friend_pottery_sherd.cost=24
            friend_pottery_sherd.amount=1
            friend_pottery_sherd.enabled=true
            friend_pottery_sherd.maxPurchases=3
            heart_pottery_sherd.cost=24
            heart_pottery_sherd.amount=1
            heart_pottery_sherd.enabled=true
            heart_pottery_sherd.maxPurchases=3
            heartbreak_pottery_sherd.cost=24
            heartbreak_pottery_sherd.amount=1
            heartbreak_pottery_sherd.enabled=true
            heartbreak_pottery_sherd.maxPurchases=3
            howl_pottery_sherd.cost=24
            howl_pottery_sherd.amount=1
            howl_pottery_sherd.enabled=true
            howl_pottery_sherd.maxPurchases=3
            miner_pottery_sherd.cost=24
            miner_pottery_sherd.amount=1
            miner_pottery_sherd.enabled=true
            miner_pottery_sherd.maxPurchases=3
            mourn_pottery_sherd.cost=24
            mourn_pottery_sherd.amount=1
            mourn_pottery_sherd.enabled=true
            mourn_pottery_sherd.maxPurchases=3
            plenty_pottery_sherd.cost=24
            plenty_pottery_sherd.amount=1
            plenty_pottery_sherd.enabled=true
            plenty_pottery_sherd.maxPurchases=3
            scrape_pottery_sherd.cost=24
            scrape_pottery_sherd.amount=1
            scrape_pottery_sherd.enabled=true
            scrape_pottery_sherd.maxPurchases=3
            sheaf_pottery_sherd.cost=24
            sheaf_pottery_sherd.amount=1
            sheaf_pottery_sherd.enabled=true
            sheaf_pottery_sherd.maxPurchases=3
            shelter_pottery_sherd.cost=24
            shelter_pottery_sherd.amount=1
            shelter_pottery_sherd.enabled=true
            shelter_pottery_sherd.maxPurchases=3
            skull_pottery_sherd.cost=24
            skull_pottery_sherd.amount=1
            skull_pottery_sherd.enabled=true
            skull_pottery_sherd.maxPurchases=3
            snort_pottery_sherd.cost=24
            snort_pottery_sherd.amount=1
            snort_pottery_sherd.enabled=true
            snort_pottery_sherd.maxPurchases=3
            music_disc_13.cost=48
            music_disc_13.amount=1
            music_disc_13.enabled=true
            music_disc_13.maxPurchases=2
            music_disc_cat.cost=48
            music_disc_cat.amount=1
            music_disc_cat.enabled=true
            music_disc_cat.maxPurchases=2
            music_disc_blocks.cost=64
            music_disc_blocks.amount=1
            music_disc_blocks.enabled=true
            music_disc_blocks.maxPurchases=2
            music_disc_chirp.cost=64
            music_disc_chirp.amount=1
            music_disc_chirp.enabled=true
            music_disc_chirp.maxPurchases=2
            book_efficiency_1.cost=32
            book_efficiency_1.amount=1
            book_efficiency_1.enabled=true
            book_efficiency_1.maxPurchases=2
            book_efficiency_1.unlockStage=0
            book_efficiency_1.mode=fixed_enchantment
            book_efficiency_1.variant=minecraft:efficiency
            book_efficiency_1.level=1
            book_efficiency_1.name=Efficiency 1
            book_efficiency_2.cost=40
            book_efficiency_2.amount=1
            book_efficiency_2.enabled=true
            book_efficiency_2.maxPurchases=2
            book_efficiency_2.unlockStage=0
            book_efficiency_2.mode=fixed_enchantment
            book_efficiency_2.variant=minecraft:efficiency
            book_efficiency_2.level=2
            book_efficiency_2.name=Efficiency 2
            book_efficiency_3.cost=48
            book_efficiency_3.amount=1
            book_efficiency_3.enabled=true
            book_efficiency_3.maxPurchases=2
            book_efficiency_3.unlockStage=0
            book_efficiency_3.mode=fixed_enchantment
            book_efficiency_3.variant=minecraft:efficiency
            book_efficiency_3.level=3
            book_efficiency_3.name=Efficiency 3
            book_efficiency_4.cost=64
            book_efficiency_4.amount=1
            book_efficiency_4.enabled=true
            book_efficiency_4.maxPurchases=2
            book_efficiency_4.unlockStage=0
            book_efficiency_4.mode=fixed_enchantment
            book_efficiency_4.variant=minecraft:efficiency
            book_efficiency_4.level=4
            book_efficiency_4.name=Efficiency 4
            book_efficiency_5.cost=80
            book_efficiency_5.amount=1
            book_efficiency_5.enabled=true
            book_efficiency_5.maxPurchases=2
            book_efficiency_5.unlockStage=0
            book_efficiency_5.mode=fixed_enchantment
            book_efficiency_5.variant=minecraft:efficiency
            book_efficiency_5.level=5
            book_efficiency_5.name=Efficiency 5
            book_efficiency_6.cost=160
            book_efficiency_6.amount=1
            book_efficiency_6.enabled=true
            book_efficiency_6.maxPurchases=1
            book_efficiency_6.unlockStage=3
            book_efficiency_6.mode=fixed_enchantment
            book_efficiency_6.variant=minecraft:efficiency
            book_efficiency_6.level=6
            book_efficiency_6.name=Efficiency 6
            book_fortune_1.cost=48
            book_fortune_1.amount=1
            book_fortune_1.enabled=true
            book_fortune_1.maxPurchases=2
            book_fortune_1.unlockStage=0
            book_fortune_1.mode=fixed_enchantment
            book_fortune_1.variant=minecraft:fortune
            book_fortune_1.level=1
            book_fortune_1.name=Fortune 1
            book_fortune_2.cost=64
            book_fortune_2.amount=1
            book_fortune_2.enabled=true
            book_fortune_2.maxPurchases=2
            book_fortune_2.unlockStage=0
            book_fortune_2.mode=fixed_enchantment
            book_fortune_2.variant=minecraft:fortune
            book_fortune_2.level=2
            book_fortune_2.name=Fortune 2
            book_fortune_3.cost=96
            book_fortune_3.amount=1
            book_fortune_3.enabled=true
            book_fortune_3.maxPurchases=2
            book_fortune_3.unlockStage=0
            book_fortune_3.mode=fixed_enchantment
            book_fortune_3.variant=minecraft:fortune
            book_fortune_3.level=3
            book_fortune_3.name=Fortune 3
            book_fortune_4.cost=200
            book_fortune_4.amount=1
            book_fortune_4.enabled=true
            book_fortune_4.maxPurchases=1
            book_fortune_4.unlockStage=3
            book_fortune_4.mode=fixed_enchantment
            book_fortune_4.variant=minecraft:fortune
            book_fortune_4.level=4
            book_fortune_4.name=Fortune 4
            book_protection_1.cost=32
            book_protection_1.amount=1
            book_protection_1.enabled=true
            book_protection_1.maxPurchases=2
            book_protection_1.unlockStage=0
            book_protection_1.mode=fixed_enchantment
            book_protection_1.variant=minecraft:protection
            book_protection_1.level=1
            book_protection_1.name=Protection 1
            book_protection_2.cost=48
            book_protection_2.amount=1
            book_protection_2.enabled=true
            book_protection_2.maxPurchases=2
            book_protection_2.unlockStage=0
            book_protection_2.mode=fixed_enchantment
            book_protection_2.variant=minecraft:protection
            book_protection_2.level=2
            book_protection_2.name=Protection 2
            book_protection_3.cost=64
            book_protection_3.amount=1
            book_protection_3.enabled=true
            book_protection_3.maxPurchases=2
            book_protection_3.unlockStage=0
            book_protection_3.mode=fixed_enchantment
            book_protection_3.variant=minecraft:protection
            book_protection_3.level=3
            book_protection_3.name=Protection 3
            book_protection_4.cost=80
            book_protection_4.amount=1
            book_protection_4.enabled=true
            book_protection_4.maxPurchases=2
            book_protection_4.unlockStage=0
            book_protection_4.mode=fixed_enchantment
            book_protection_4.variant=minecraft:protection
            book_protection_4.level=4
            book_protection_4.name=Protection 4
            book_protection_5.cost=160
            book_protection_5.amount=1
            book_protection_5.enabled=true
            book_protection_5.maxPurchases=1
            book_protection_5.unlockStage=3
            book_protection_5.mode=fixed_enchantment
            book_protection_5.variant=minecraft:protection
            book_protection_5.level=5
            book_protection_5.name=Protection 5
            book_unbreaking_1.cost=32
            book_unbreaking_1.amount=1
            book_unbreaking_1.enabled=true
            book_unbreaking_1.maxPurchases=2
            book_unbreaking_1.unlockStage=0
            book_unbreaking_1.mode=fixed_enchantment
            book_unbreaking_1.variant=minecraft:unbreaking
            book_unbreaking_1.level=1
            book_unbreaking_1.name=Unbreaking 1
            book_unbreaking_2.cost=48
            book_unbreaking_2.amount=1
            book_unbreaking_2.enabled=true
            book_unbreaking_2.maxPurchases=2
            book_unbreaking_2.unlockStage=0
            book_unbreaking_2.mode=fixed_enchantment
            book_unbreaking_2.variant=minecraft:unbreaking
            book_unbreaking_2.level=2
            book_unbreaking_2.name=Unbreaking 2
            book_unbreaking_3.cost=64
            book_unbreaking_3.amount=1
            book_unbreaking_3.enabled=true
            book_unbreaking_3.maxPurchases=2
            book_unbreaking_3.unlockStage=0
            book_unbreaking_3.mode=fixed_enchantment
            book_unbreaking_3.variant=minecraft:unbreaking
            book_unbreaking_3.level=3
            book_unbreaking_3.name=Unbreaking 3
            book_unbreaking_4.cost=160
            book_unbreaking_4.amount=1
            book_unbreaking_4.enabled=true
            book_unbreaking_4.maxPurchases=1
            book_unbreaking_4.unlockStage=3
            book_unbreaking_4.mode=fixed_enchantment
            book_unbreaking_4.variant=minecraft:unbreaking
            book_unbreaking_4.level=4
            book_unbreaking_4.name=Unbreaking 4
            book_sharpness_1.cost=32
            book_sharpness_1.amount=1
            book_sharpness_1.enabled=true
            book_sharpness_1.maxPurchases=2
            book_sharpness_1.unlockStage=0
            book_sharpness_1.mode=fixed_enchantment
            book_sharpness_1.variant=minecraft:sharpness
            book_sharpness_1.level=1
            book_sharpness_1.name=Sharpness 1
            book_sharpness_2.cost=40
            book_sharpness_2.amount=1
            book_sharpness_2.enabled=true
            book_sharpness_2.maxPurchases=2
            book_sharpness_2.unlockStage=0
            book_sharpness_2.mode=fixed_enchantment
            book_sharpness_2.variant=minecraft:sharpness
            book_sharpness_2.level=2
            book_sharpness_2.name=Sharpness 2
            book_sharpness_3.cost=48
            book_sharpness_3.amount=1
            book_sharpness_3.enabled=true
            book_sharpness_3.maxPurchases=2
            book_sharpness_3.unlockStage=0
            book_sharpness_3.mode=fixed_enchantment
            book_sharpness_3.variant=minecraft:sharpness
            book_sharpness_3.level=3
            book_sharpness_3.name=Sharpness 3
            book_sharpness_4.cost=64
            book_sharpness_4.amount=1
            book_sharpness_4.enabled=true
            book_sharpness_4.maxPurchases=2
            book_sharpness_4.unlockStage=0
            book_sharpness_4.mode=fixed_enchantment
            book_sharpness_4.variant=minecraft:sharpness
            book_sharpness_4.level=4
            book_sharpness_4.name=Sharpness 4
            book_sharpness_5.cost=80
            book_sharpness_5.amount=1
            book_sharpness_5.enabled=true
            book_sharpness_5.maxPurchases=2
            book_sharpness_5.unlockStage=0
            book_sharpness_5.mode=fixed_enchantment
            book_sharpness_5.variant=minecraft:sharpness
            book_sharpness_5.level=5
            book_sharpness_5.name=Sharpness 5
            book_sharpness_6.cost=160
            book_sharpness_6.amount=1
            book_sharpness_6.enabled=true
            book_sharpness_6.maxPurchases=1
            book_sharpness_6.unlockStage=3
            book_sharpness_6.mode=fixed_enchantment
            book_sharpness_6.variant=minecraft:sharpness
            book_sharpness_6.level=6
            book_sharpness_6.name=Sharpness 6
            book_mending_1.cost=96
            book_mending_1.amount=1
            book_mending_1.enabled=true
            book_mending_1.maxPurchases=2
            book_mending_1.unlockStage=0
            book_mending_1.mode=fixed_enchantment
            book_mending_1.variant=minecraft:mending
            book_mending_1.level=1
            book_mending_1.name=Mending 1
            book_aqua_affinity_1.cost=32
            book_aqua_affinity_1.amount=1
            book_aqua_affinity_1.enabled=true
            book_aqua_affinity_1.maxPurchases=2
            book_aqua_affinity_1.unlockStage=0
            book_aqua_affinity_1.mode=fixed_enchantment
            book_aqua_affinity_1.variant=minecraft:aqua_affinity
            book_aqua_affinity_1.level=1
            book_aqua_affinity_1.name=Aqua Affinity 1
            book_silk_touch_1.cost=64
            book_silk_touch_1.amount=1
            book_silk_touch_1.enabled=true
            book_silk_touch_1.maxPurchases=2
            book_silk_touch_1.unlockStage=0
            book_silk_touch_1.mode=fixed_enchantment
            book_silk_touch_1.variant=minecraft:silk_touch
            book_silk_touch_1.level=1
            book_silk_touch_1.name=Silk Touch 1
            book_infinity_1.cost=80
            book_infinity_1.amount=1
            book_infinity_1.enabled=true
            book_infinity_1.maxPurchases=2
            book_infinity_1.unlockStage=0
            book_infinity_1.mode=fixed_enchantment
            book_infinity_1.variant=minecraft:infinity
            book_infinity_1.level=1
            book_infinity_1.name=Infinity 1
            book_soul_speed_1.cost=48
            book_soul_speed_1.amount=1
            book_soul_speed_1.enabled=true
            book_soul_speed_1.maxPurchases=2
            book_soul_speed_1.unlockStage=0
            book_soul_speed_1.mode=fixed_enchantment
            book_soul_speed_1.variant=minecraft:soul_speed
            book_soul_speed_1.level=1
            book_soul_speed_1.name=Soul Speed 1
            book_soul_speed_2.cost=64
            book_soul_speed_2.amount=1
            book_soul_speed_2.enabled=true
            book_soul_speed_2.maxPurchases=2
            book_soul_speed_2.unlockStage=0
            book_soul_speed_2.mode=fixed_enchantment
            book_soul_speed_2.variant=minecraft:soul_speed
            book_soul_speed_2.level=2
            book_soul_speed_2.name=Soul Speed 2
            book_soul_speed_3.cost=96
            book_soul_speed_3.amount=1
            book_soul_speed_3.enabled=true
            book_soul_speed_3.maxPurchases=2
            book_soul_speed_3.unlockStage=0
            book_soul_speed_3.mode=fixed_enchantment
            book_soul_speed_3.variant=minecraft:soul_speed
            book_soul_speed_3.level=3
            book_soul_speed_3.name=Soul Speed 3
            book_soul_speed_4.cost=160
            book_soul_speed_4.amount=1
            book_soul_speed_4.enabled=true
            book_soul_speed_4.maxPurchases=1
            book_soul_speed_4.unlockStage=3
            book_soul_speed_4.mode=fixed_enchantment
            book_soul_speed_4.variant=minecraft:soul_speed
            book_soul_speed_4.level=4
            book_soul_speed_4.name=Soul Speed 4
            [neutral]
            """;
    }

    static List<String> balanceV4(List<String> lines) {
        List<String> result = new ArrayList<>();
        result.add("# kic.shopBalanceVersion=4");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("# kic.shopBalanceVersion=")) continue;
            if (line.matches(".*\\.maxPurchases=[0-9]+")) {
                int split = line.indexOf('=');
                int value = Integer.parseInt(line.substring(split + 1));
                if (value > 0) raw = line.substring(0, split + 1) + (value == Integer.MAX_VALUE ? value : value + 1);
            }
            if (line.startsWith("ancient_debris.cost=")) raw = "ancient_debris.cost=30";
            if (line.startsWith("shulker_shell.unlockStage=")) raw = "shulker_shell.unlockStage=3";
            if (line.startsWith("totem_of_undying.cost=")) raw = "totem_of_undying.cost=100";
            if (line.startsWith("totem_of_undying.unlockStage=")) continue;
            result.add(raw);
            if (line.startsWith("totem_of_undying.cost=")) result.add("totem_of_undying.unlockStage=2");
        }
        return result;
    }

    static List<String> migrateBalanceV3(List<String> lines) {
        String marker = "# kic.shopBalanceVersion=3";
        if (lines.contains(marker)) return lines;
        lines = migrateLegacyPrices(lines);
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("light/recovery_compass.cost", "160");
        previous.put("light/recovery_compass.amount", "1");
        previous.put("light/recovery_compass.enabled", "true");
        previous.put("light/recovery_compass.maxPurchases", "1");
        previous.put("light/gilded_blackstone.cost", "25");
        previous.put("light/gilded_blackstone.amount", "32");
        previous.put("light/gilded_blackstone.enabled", "true");
        previous.put("light/gilded_blackstone.maxPurchases", "2");
        previous.put("light/saddle.cost", "60");
        previous.put("light/saddle.amount", "1");
        previous.put("light/saddle.enabled", "true");
        previous.put("light/saddle.maxPurchases", "1");
        previous.put("light/flower_banner_pattern.cost", "35");
        previous.put("light/flower_banner_pattern.amount", "1");
        previous.put("light/flower_banner_pattern.enabled", "true");
        previous.put("light/flower_banner_pattern.maxPurchases", "1");
        previous.put("light/creeper_banner_pattern.cost", "50");
        previous.put("light/creeper_banner_pattern.amount", "1");
        previous.put("light/creeper_banner_pattern.enabled", "true");
        previous.put("light/creeper_banner_pattern.maxPurchases", "1");
        previous.put("light/snout_banner_pattern.cost", "60");
        previous.put("light/snout_banner_pattern.amount", "1");
        previous.put("light/snout_banner_pattern.enabled", "true");
        previous.put("light/snout_banner_pattern.maxPurchases", "1");
        previous.put("light/globe_banner_pattern.cost", "70");
        previous.put("light/globe_banner_pattern.amount", "1");
        previous.put("light/globe_banner_pattern.enabled", "true");
        previous.put("light/globe_banner_pattern.maxPurchases", "1");
        previous.put("light/flow_banner_pattern.cost", "70");
        previous.put("light/flow_banner_pattern.amount", "1");
        previous.put("light/flow_banner_pattern.enabled", "true");
        previous.put("light/flow_banner_pattern.maxPurchases", "1");
        previous.put("light/guster_banner_pattern.cost", "70");
        previous.put("light/guster_banner_pattern.amount", "1");
        previous.put("light/guster_banner_pattern.enabled", "true");
        previous.put("light/guster_banner_pattern.maxPurchases", "1");
        previous.put("light/coast_armor_trim_smithing_template.cost", "80");
        previous.put("light/coast_armor_trim_smithing_template.amount", "1");
        previous.put("light/coast_armor_trim_smithing_template.enabled", "true");
        previous.put("light/coast_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/silence_armor_trim_smithing_template.cost", "250");
        previous.put("light/silence_armor_trim_smithing_template.amount", "1");
        previous.put("light/silence_armor_trim_smithing_template.enabled", "true");
        previous.put("light/silence_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/dune_armor_trim_smithing_template.cost", "60");
        previous.put("light/dune_armor_trim_smithing_template.amount", "1");
        previous.put("light/dune_armor_trim_smithing_template.enabled", "true");
        previous.put("light/dune_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/wild_armor_trim_smithing_template.cost", "65");
        previous.put("light/wild_armor_trim_smithing_template.amount", "1");
        previous.put("light/wild_armor_trim_smithing_template.enabled", "true");
        previous.put("light/wild_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/ward_armor_trim_smithing_template.cost", "85");
        previous.put("light/ward_armor_trim_smithing_template.amount", "1");
        previous.put("light/ward_armor_trim_smithing_template.enabled", "true");
        previous.put("light/ward_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/vex_armor_trim_smithing_template.cost", "75");
        previous.put("light/vex_armor_trim_smithing_template.amount", "1");
        previous.put("light/vex_armor_trim_smithing_template.enabled", "true");
        previous.put("light/vex_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/tide_armor_trim_smithing_template.cost", "100");
        previous.put("light/tide_armor_trim_smithing_template.amount", "1");
        previous.put("light/tide_armor_trim_smithing_template.enabled", "true");
        previous.put("light/tide_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/wayfinder_armor_trim_smithing_template.cost", "110");
        previous.put("light/wayfinder_armor_trim_smithing_template.amount", "1");
        previous.put("light/wayfinder_armor_trim_smithing_template.enabled", "true");
        previous.put("light/wayfinder_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/shaper_armor_trim_smithing_template.cost", "110");
        previous.put("light/shaper_armor_trim_smithing_template.amount", "1");
        previous.put("light/shaper_armor_trim_smithing_template.enabled", "true");
        previous.put("light/shaper_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/raiser_armor_trim_smithing_template.cost", "110");
        previous.put("light/raiser_armor_trim_smithing_template.amount", "1");
        previous.put("light/raiser_armor_trim_smithing_template.enabled", "true");
        previous.put("light/raiser_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/host_armor_trim_smithing_template.cost", "125");
        previous.put("light/host_armor_trim_smithing_template.amount", "1");
        previous.put("light/host_armor_trim_smithing_template.enabled", "true");
        previous.put("light/host_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/eye_armor_trim_smithing_template.cost", "150");
        previous.put("light/eye_armor_trim_smithing_template.amount", "1");
        previous.put("light/eye_armor_trim_smithing_template.enabled", "true");
        previous.put("light/eye_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/rib_armor_trim_smithing_template.cost", "90");
        previous.put("light/rib_armor_trim_smithing_template.amount", "1");
        previous.put("light/rib_armor_trim_smithing_template.enabled", "true");
        previous.put("light/rib_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/spire_armor_trim_smithing_template.cost", "180");
        previous.put("light/spire_armor_trim_smithing_template.amount", "1");
        previous.put("light/spire_armor_trim_smithing_template.enabled", "true");
        previous.put("light/spire_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/flow_armor_trim_smithing_template.cost", "140");
        previous.put("light/flow_armor_trim_smithing_template.amount", "1");
        previous.put("light/flow_armor_trim_smithing_template.enabled", "true");
        previous.put("light/flow_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/bolt_armor_trim_smithing_template.cost", "140");
        previous.put("light/bolt_armor_trim_smithing_template.amount", "1");
        previous.put("light/bolt_armor_trim_smithing_template.enabled", "true");
        previous.put("light/bolt_armor_trim_smithing_template.maxPurchases", "1");
        previous.put("light/angler_pottery_sherd.cost", "30");
        previous.put("light/angler_pottery_sherd.amount", "1");
        previous.put("light/angler_pottery_sherd.enabled", "true");
        previous.put("light/angler_pottery_sherd.maxPurchases", "2");
        previous.put("light/prize_pottery_sherd.cost", "30");
        previous.put("light/prize_pottery_sherd.amount", "1");
        previous.put("light/prize_pottery_sherd.enabled", "true");
        previous.put("light/prize_pottery_sherd.maxPurchases", "2");
        previous.put("light/arms_up_pottery_sherd.cost", "30");
        previous.put("light/arms_up_pottery_sherd.amount", "1");
        previous.put("light/arms_up_pottery_sherd.enabled", "true");
        previous.put("light/arms_up_pottery_sherd.maxPurchases", "2");
        previous.put("light/blade_pottery_sherd.cost", "30");
        previous.put("light/blade_pottery_sherd.amount", "1");
        previous.put("light/blade_pottery_sherd.enabled", "true");
        previous.put("light/blade_pottery_sherd.maxPurchases", "2");
        previous.put("light/brewer_pottery_sherd.cost", "30");
        previous.put("light/brewer_pottery_sherd.amount", "1");
        previous.put("light/brewer_pottery_sherd.enabled", "true");
        previous.put("light/brewer_pottery_sherd.maxPurchases", "2");
        previous.put("light/burn_pottery_sherd.cost", "30");
        previous.put("light/burn_pottery_sherd.amount", "1");
        previous.put("light/burn_pottery_sherd.enabled", "true");
        previous.put("light/burn_pottery_sherd.maxPurchases", "2");
        previous.put("light/danger_pottery_sherd.cost", "30");
        previous.put("light/danger_pottery_sherd.amount", "1");
        previous.put("light/danger_pottery_sherd.enabled", "true");
        previous.put("light/danger_pottery_sherd.maxPurchases", "2");
        previous.put("light/explorer_pottery_sherd.cost", "30");
        previous.put("light/explorer_pottery_sherd.amount", "1");
        previous.put("light/explorer_pottery_sherd.enabled", "true");
        previous.put("light/explorer_pottery_sherd.maxPurchases", "2");
        previous.put("light/friend_pottery_sherd.cost", "30");
        previous.put("light/friend_pottery_sherd.amount", "1");
        previous.put("light/friend_pottery_sherd.enabled", "true");
        previous.put("light/friend_pottery_sherd.maxPurchases", "2");
        previous.put("light/heart_pottery_sherd.cost", "30");
        previous.put("light/heart_pottery_sherd.amount", "1");
        previous.put("light/heart_pottery_sherd.enabled", "true");
        previous.put("light/heart_pottery_sherd.maxPurchases", "2");
        previous.put("light/heartbreak_pottery_sherd.cost", "30");
        previous.put("light/heartbreak_pottery_sherd.amount", "1");
        previous.put("light/heartbreak_pottery_sherd.enabled", "true");
        previous.put("light/heartbreak_pottery_sherd.maxPurchases", "2");
        previous.put("light/howl_pottery_sherd.cost", "30");
        previous.put("light/howl_pottery_sherd.amount", "1");
        previous.put("light/howl_pottery_sherd.enabled", "true");
        previous.put("light/howl_pottery_sherd.maxPurchases", "2");
        previous.put("light/miner_pottery_sherd.cost", "30");
        previous.put("light/miner_pottery_sherd.amount", "1");
        previous.put("light/miner_pottery_sherd.enabled", "true");
        previous.put("light/miner_pottery_sherd.maxPurchases", "2");
        previous.put("light/mourn_pottery_sherd.cost", "30");
        previous.put("light/mourn_pottery_sherd.amount", "1");
        previous.put("light/mourn_pottery_sherd.enabled", "true");
        previous.put("light/mourn_pottery_sherd.maxPurchases", "2");
        previous.put("light/plenty_pottery_sherd.cost", "30");
        previous.put("light/plenty_pottery_sherd.amount", "1");
        previous.put("light/plenty_pottery_sherd.enabled", "true");
        previous.put("light/plenty_pottery_sherd.maxPurchases", "2");
        previous.put("light/scrape_pottery_sherd.cost", "30");
        previous.put("light/scrape_pottery_sherd.amount", "1");
        previous.put("light/scrape_pottery_sherd.enabled", "true");
        previous.put("light/scrape_pottery_sherd.maxPurchases", "2");
        previous.put("light/sheaf_pottery_sherd.cost", "30");
        previous.put("light/sheaf_pottery_sherd.amount", "1");
        previous.put("light/sheaf_pottery_sherd.enabled", "true");
        previous.put("light/sheaf_pottery_sherd.maxPurchases", "2");
        previous.put("light/shelter_pottery_sherd.cost", "30");
        previous.put("light/shelter_pottery_sherd.amount", "1");
        previous.put("light/shelter_pottery_sherd.enabled", "true");
        previous.put("light/shelter_pottery_sherd.maxPurchases", "2");
        previous.put("light/skull_pottery_sherd.cost", "30");
        previous.put("light/skull_pottery_sherd.amount", "1");
        previous.put("light/skull_pottery_sherd.enabled", "true");
        previous.put("light/skull_pottery_sherd.maxPurchases", "2");
        previous.put("light/snort_pottery_sherd.cost", "30");
        previous.put("light/snort_pottery_sherd.amount", "1");
        previous.put("light/snort_pottery_sherd.enabled", "true");
        previous.put("light/snort_pottery_sherd.maxPurchases", "2");
        previous.put("light/sand.cost", "10");
        previous.put("light/sand.amount", "64");
        previous.put("light/sand.enabled", "true");
        previous.put("light/sand.maxPurchases", "4");
        previous.put("light/heart_of_the_sea.cost", "150");
        previous.put("light/heart_of_the_sea.amount", "1");
        previous.put("light/heart_of_the_sea.enabled", "true");
        previous.put("light/heart_of_the_sea.maxPurchases", "1");
        previous.put("light/goat_horn.cost", "75");
        previous.put("light/goat_horn.amount", "1");
        previous.put("light/goat_horn.enabled", "true");
        previous.put("light/goat_horn.maxPurchases", "2");
        previous.put("light/sea_lantern.cost", "45");
        previous.put("light/sea_lantern.amount", "16");
        previous.put("light/sea_lantern.enabled", "true");
        previous.put("light/sea_lantern.maxPurchases", "4");
        previous.put("light/wheat_seeds.cost", "6");
        previous.put("light/wheat_seeds.amount", "32");
        previous.put("light/wheat_seeds.enabled", "true");
        previous.put("light/wheat_seeds.maxPurchases", "4");
        previous.put("light/beetroot_seeds.cost", "8");
        previous.put("light/beetroot_seeds.amount", "32");
        previous.put("light/beetroot_seeds.enabled", "true");
        previous.put("light/beetroot_seeds.maxPurchases", "4");
        previous.put("light/torchflower_seeds.cost", "20");
        previous.put("light/torchflower_seeds.amount", "8");
        previous.put("light/torchflower_seeds.enabled", "true");
        previous.put("light/torchflower_seeds.maxPurchases", "2");
        previous.put("light/pitcher_pod.cost", "20");
        previous.put("light/pitcher_pod.amount", "8");
        previous.put("light/pitcher_pod.enabled", "true");
        previous.put("light/pitcher_pod.maxPurchases", "2");
        previous.put("light/cow_spawn_egg.cost", "120");
        previous.put("light/cow_spawn_egg.amount", "1");
        previous.put("light/cow_spawn_egg.enabled", "true");
        previous.put("light/cow_spawn_egg.maxPurchases", "1");
        previous.put("light/pig_spawn_egg.cost", "120");
        previous.put("light/pig_spawn_egg.amount", "1");
        previous.put("light/pig_spawn_egg.enabled", "true");
        previous.put("light/pig_spawn_egg.maxPurchases", "1");
        previous.put("light/sheep_spawn_egg.cost", "120");
        previous.put("light/sheep_spawn_egg.amount", "1");
        previous.put("light/sheep_spawn_egg.enabled", "true");
        previous.put("light/sheep_spawn_egg.maxPurchases", "1");
        previous.put("light/chicken_spawn_egg.cost", "120");
        previous.put("light/chicken_spawn_egg.amount", "1");
        previous.put("light/chicken_spawn_egg.enabled", "true");
        previous.put("light/chicken_spawn_egg.maxPurchases", "1");
        previous.put("light/rabbit_spawn_egg.cost", "100");
        previous.put("light/rabbit_spawn_egg.amount", "1");
        previous.put("light/rabbit_spawn_egg.enabled", "true");
        previous.put("light/rabbit_spawn_egg.maxPurchases", "1");
        previous.put("light/horse_spawn_egg.cost", "150");
        previous.put("light/horse_spawn_egg.amount", "1");
        previous.put("light/horse_spawn_egg.enabled", "true");
        previous.put("light/horse_spawn_egg.maxPurchases", "1");
        previous.put("light/music_disc_13.cost", "90");
        previous.put("light/music_disc_13.amount", "1");
        previous.put("light/music_disc_13.enabled", "true");
        previous.put("light/music_disc_13.maxPurchases", "1");
        previous.put("light/music_disc_cat.cost", "90");
        previous.put("light/music_disc_cat.amount", "1");
        previous.put("light/music_disc_cat.enabled", "true");
        previous.put("light/music_disc_cat.maxPurchases", "1");
        previous.put("light/music_disc_blocks.cost", "100");
        previous.put("light/music_disc_blocks.amount", "1");
        previous.put("light/music_disc_blocks.enabled", "true");
        previous.put("light/music_disc_blocks.maxPurchases", "1");
        previous.put("light/music_disc_chirp.cost", "100");
        previous.put("light/music_disc_chirp.amount", "1");
        previous.put("light/music_disc_chirp.enabled", "true");
        previous.put("light/music_disc_chirp.maxPurchases", "1");
        previous.put("light/random_enchantment.cost", "140");
        previous.put("light/random_enchantment.amount", "1");
        previous.put("light/random_enchantment.enabled", "true");
        previous.put("light/random_enchantment.maxPurchases", "2");
        previous.put("light/random_enchantment.mode", "random_enchantment");
        previous.put("light/random_enchantment.level", "1");
        previous.put("light/suspicious_stew.cost", "35");
        previous.put("light/suspicious_stew.amount", "1");
        previous.put("light/suspicious_stew.enabled", "true");
        previous.put("light/suspicious_stew.maxPurchases", "2");
        previous.put("light/suspicious_stew.mode", "suspicious_stew_cycle");
        previous.put("light/stone.cost", "8");
        previous.put("light/stone.amount", "64");
        previous.put("light/stone.enabled", "true");
        previous.put("light/stone.maxPurchases", "6");
        previous.put("light/stone.category", "BUILDING");
        previous.put("light/stone.weight", "4");
        previous.put("light/cobblestone.cost", "6");
        previous.put("light/cobblestone.amount", "64");
        previous.put("light/cobblestone.enabled", "true");
        previous.put("light/cobblestone.maxPurchases", "6");
        previous.put("light/cobblestone.category", "BUILDING");
        previous.put("light/cobblestone.weight", "4");
        previous.put("light/glass.cost", "18");
        previous.put("light/glass.amount", "32");
        previous.put("light/glass.enabled", "true");
        previous.put("light/glass.maxPurchases", "4");
        previous.put("light/glass.category", "BUILDING");
        previous.put("light/glass.weight", "3");
        previous.put("light/packed_ice.cost", "45");
        previous.put("light/packed_ice.amount", "16");
        previous.put("light/packed_ice.enabled", "true");
        previous.put("light/packed_ice.maxPurchases", "4");
        previous.put("light/packed_ice.category", "BUILDING");
        previous.put("light/packed_ice.weight", "2");
        previous.put("light/blue_ice.cost", "90");
        previous.put("light/blue_ice.amount", "8");
        previous.put("light/blue_ice.enabled", "true");
        previous.put("light/blue_ice.maxPurchases", "2");
        previous.put("light/blue_ice.category", "BUILDING");
        previous.put("light/blue_ice.weight", "1");
        previous.put("light/redstone.cost", "32");
        previous.put("light/redstone.amount", "32");
        previous.put("light/redstone.enabled", "true");
        previous.put("light/redstone.maxPurchases", "4");
        previous.put("light/redstone.category", "RESOURCES");
        previous.put("light/redstone.weight", "3");
        previous.put("light/lapis_lazuli.cost", "16");
        previous.put("light/lapis_lazuli.amount", "32");
        previous.put("light/lapis_lazuli.enabled", "true");
        previous.put("light/lapis_lazuli.maxPurchases", "4");
        previous.put("light/lapis_lazuli.category", "RESOURCES");
        previous.put("light/lapis_lazuli.weight", "3");
        previous.put("light/amethyst_shard.cost", "24");
        previous.put("light/amethyst_shard.amount", "16");
        previous.put("light/amethyst_shard.enabled", "true");
        previous.put("light/amethyst_shard.maxPurchases", "3");
        previous.put("light/amethyst_shard.category", "RESOURCES");
        previous.put("light/amethyst_shard.weight", "2");
        previous.put("light/slime_ball.cost", "35");
        previous.put("light/slime_ball.amount", "8");
        previous.put("light/slime_ball.enabled", "true");
        previous.put("light/slime_ball.maxPurchases", "3");
        previous.put("light/slime_ball.category", "RESOURCES");
        previous.put("light/slime_ball.weight", "2");
        previous.put("light/honeycomb.cost", "24");
        previous.put("light/honeycomb.amount", "16");
        previous.put("light/honeycomb.enabled", "true");
        previous.put("light/honeycomb.maxPurchases", "3");
        previous.put("light/honeycomb.category", "RESOURCES");
        previous.put("light/honeycomb.weight", "2");
        previous.put("light/name_tag.cost", "55");
        previous.put("light/name_tag.amount", "1");
        previous.put("light/name_tag.enabled", "true");
        previous.put("light/name_tag.maxPurchases", "2");
        previous.put("light/name_tag.category", "UTILITY");
        previous.put("light/name_tag.weight", "2");
        previous.put("light/lead.cost", "30");
        previous.put("light/lead.amount", "2");
        previous.put("light/lead.enabled", "true");
        previous.put("light/lead.maxPurchases", "3");
        previous.put("light/lead.category", "UTILITY");
        previous.put("light/lead.weight", "2");
        previous.put("light/nautilus_shell.cost", "70");
        previous.put("light/nautilus_shell.amount", "1");
        previous.put("light/nautilus_shell.enabled", "true");
        previous.put("light/nautilus_shell.maxPurchases", "2");
        previous.put("light/nautilus_shell.category", "UTILITY");
        previous.put("light/nautilus_shell.weight", "1");
        previous.put("light/sponge.cost", "110");
        previous.put("light/sponge.amount", "4");
        previous.put("light/sponge.enabled", "true");
        previous.put("light/sponge.maxPurchases", "2");
        previous.put("light/sponge.category", "UTILITY");
        previous.put("light/sponge.weight", "1");
        previous.put("light/golden_apple.cost", "60");
        previous.put("light/golden_apple.amount", "1");
        previous.put("light/golden_apple.enabled", "true");
        previous.put("light/golden_apple.maxPurchases", "2");
        previous.put("light/golden_apple.category", "COMBAT");
        previous.put("light/golden_apple.weight", "1");
        previous.put("light/phantom_membrane.cost", "65");
        previous.put("light/phantom_membrane.amount", "4");
        previous.put("light/phantom_membrane.enabled", "true");
        previous.put("light/phantom_membrane.maxPurchases", "2");
        previous.put("light/phantom_membrane.category", "COMBAT");
        previous.put("light/phantom_membrane.weight", "1");
        previous.put("light/shulker_shell.cost", "180");
        previous.put("light/shulker_shell.amount", "1");
        previous.put("light/shulker_shell.enabled", "true");
        previous.put("light/shulker_shell.maxPurchases", "1");
        previous.put("light/shulker_shell.category", "RARE");
        previous.put("light/shulker_shell.weight", "1");
        previous.put("light/shulker_shell.unlockStage", "3");
        previous.put("light/enchanted_golden_apple.cost", "250");
        previous.put("light/enchanted_golden_apple.amount", "2");
        previous.put("light/enchanted_golden_apple.enabled", "true");
        previous.put("light/enchanted_golden_apple.maxPurchases", "1");
        previous.put("light/enchanted_golden_apple.category", "RARE");
        previous.put("light/enchanted_golden_apple.weight", "1");
        previous.put("light/enchanted_golden_apple.unlockStage", "5");
        previous.put("dark/blackstone.cost", "12");
        previous.put("dark/blackstone.amount", "64");
        previous.put("dark/blackstone.enabled", "true");
        previous.put("dark/blackstone.maxPurchases", "6");
        previous.put("dark/blackstone.category", "BUILDING");
        previous.put("dark/blackstone.weight", "4");
        previous.put("dark/basalt.cost", "12");
        previous.put("dark/basalt.amount", "64");
        previous.put("dark/basalt.enabled", "true");
        previous.put("dark/basalt.maxPurchases", "6");
        previous.put("dark/basalt.category", "BUILDING");
        previous.put("dark/basalt.weight", "4");
        previous.put("dark/nether_bricks.cost", "18");
        previous.put("dark/nether_bricks.amount", "32");
        previous.put("dark/nether_bricks.enabled", "true");
        previous.put("dark/nether_bricks.maxPurchases", "4");
        previous.put("dark/nether_bricks.category", "BUILDING");
        previous.put("dark/nether_bricks.weight", "3");
        previous.put("dark/rotten_flesh.cost", "8");
        previous.put("dark/rotten_flesh.amount", "32");
        previous.put("dark/rotten_flesh.enabled", "true");
        previous.put("dark/rotten_flesh.maxPurchases", "6");
        previous.put("dark/rotten_flesh.category", "RESOURCES");
        previous.put("dark/rotten_flesh.weight", "4");
        previous.put("dark/gunpowder.cost", "28");
        previous.put("dark/gunpowder.amount", "16");
        previous.put("dark/gunpowder.enabled", "true");
        previous.put("dark/gunpowder.maxPurchases", "4");
        previous.put("dark/gunpowder.category", "RESOURCES");
        previous.put("dark/gunpowder.weight", "3");
        previous.put("dark/bone.cost", "10");
        previous.put("dark/bone.amount", "32");
        previous.put("dark/bone.enabled", "true");
        previous.put("dark/bone.maxPurchases", "6");
        previous.put("dark/bone.category", "RESOURCES");
        previous.put("dark/bone.weight", "4");
        previous.put("dark/string.cost", "12");
        previous.put("dark/string.amount", "32");
        previous.put("dark/string.enabled", "true");
        previous.put("dark/string.maxPurchases", "6");
        previous.put("dark/string.category", "RESOURCES");
        previous.put("dark/string.weight", "4");
        previous.put("dark/blaze_powder.cost", "35");
        previous.put("dark/blaze_powder.amount", "16");
        previous.put("dark/blaze_powder.enabled", "true");
        previous.put("dark/blaze_powder.maxPurchases", "4");
        previous.put("dark/blaze_powder.category", "COMBAT");
        previous.put("dark/blaze_powder.weight", "2");
        previous.put("dark/magma_cream.cost", "45");
        previous.put("dark/magma_cream.amount", "8");
        previous.put("dark/magma_cream.enabled", "true");
        previous.put("dark/magma_cream.maxPurchases", "3");
        previous.put("dark/magma_cream.category", "COMBAT");
        previous.put("dark/magma_cream.weight", "2");
        previous.put("dark/blaze_rod.cost", "70");
        previous.put("dark/blaze_rod.amount", "8");
        previous.put("dark/blaze_rod.enabled", "true");
        previous.put("dark/blaze_rod.maxPurchases", "3");
        previous.put("dark/blaze_rod.category", "COMBAT");
        previous.put("dark/blaze_rod.weight", "1");
        previous.put("dark/ghast_tear.cost", "40");
        previous.put("dark/ghast_tear.amount", "2");
        previous.put("dark/ghast_tear.enabled", "true");
        previous.put("dark/ghast_tear.maxPurchases", "2");
        previous.put("dark/ghast_tear.category", "COMBAT");
        previous.put("dark/ghast_tear.weight", "1");
        previous.put("dark/wither_skeleton_skull.cost", "220");
        previous.put("dark/wither_skeleton_skull.amount", "1");
        previous.put("dark/wither_skeleton_skull.enabled", "true");
        previous.put("dark/wither_skeleton_skull.maxPurchases", "1");
        previous.put("dark/wither_skeleton_skull.category", "RARE");
        previous.put("dark/wither_skeleton_skull.weight", "1");
        previous.put("dark/wither_skeleton_skull.unlockStage", "3");
        previous.put("dark/shulker_shell.cost", "180");
        previous.put("dark/shulker_shell.amount", "1");
        previous.put("dark/shulker_shell.enabled", "true");
        previous.put("dark/shulker_shell.maxPurchases", "1");
        previous.put("dark/shulker_shell.category", "RARE");
        previous.put("dark/shulker_shell.weight", "1");
        previous.put("dark/shulker_shell.unlockStage", "3");
        previous.put("dark/ancient_debris.cost", "260");
        previous.put("dark/ancient_debris.amount", "1");
        previous.put("dark/ancient_debris.enabled", "true");
        previous.put("dark/ancient_debris.maxPurchases", "2");
        previous.put("dark/ancient_debris.category", "RARE");
        previous.put("dark/ancient_debris.weight", "1");
        previous.put("dark/ancient_debris.unlockStage", "4");
        previous.put("dark/netherite_scrap.cost", "220");
        previous.put("dark/netherite_scrap.amount", "1");
        previous.put("dark/netherite_scrap.enabled", "true");
        previous.put("dark/netherite_scrap.maxPurchases", "2");
        previous.put("dark/netherite_scrap.category", "RARE");
        previous.put("dark/netherite_scrap.weight", "1");
        previous.put("dark/netherite_scrap.unlockStage", "4");
        previous.put("dark/ender_eye.cost", "85");
        previous.put("dark/ender_eye.amount", "2");
        previous.put("dark/ender_eye.enabled", "true");
        previous.put("dark/ender_eye.maxPurchases", "3");
        previous.put("dark/ender_eye.category", "UTILITY");
        previous.put("dark/ender_eye.weight", "1");
        previous.put("dark/tnt.cost", "40");
        previous.put("dark/tnt.amount", "4");
        previous.put("dark/tnt.enabled", "true");
        previous.put("dark/tnt.maxPurchases", "0");
        previous.put("dark/obsidian.cost", "40");
        previous.put("dark/obsidian.amount", "16");
        previous.put("dark/obsidian.enabled", "true");
        previous.put("dark/obsidian.maxPurchases", "0");
        previous.put("dark/crying_obsidian.cost", "125");
        previous.put("dark/crying_obsidian.amount", "8");
        previous.put("dark/crying_obsidian.enabled", "true");
        previous.put("dark/crying_obsidian.maxPurchases", "0");
        previous.put("dark/magma_block.cost", "75");
        previous.put("dark/magma_block.amount", "16");
        previous.put("dark/magma_block.enabled", "true");
        previous.put("dark/magma_block.maxPurchases", "0");
        previous.put("dark/fire_charge.cost", "25");
        previous.put("dark/fire_charge.amount", "8");
        previous.put("dark/fire_charge.enabled", "true");
        previous.put("dark/fire_charge.maxPurchases", "0");
        previous.put("dark/ender_pearl.cost", "16");
        previous.put("dark/ender_pearl.amount", "4");
        previous.put("dark/ender_pearl.enabled", "true");
        previous.put("dark/ender_pearl.maxPurchases", "0");
        previous.put("dark/totem_of_undying.cost", "300");
        previous.put("dark/totem_of_undying.amount", "1");
        previous.put("dark/totem_of_undying.enabled", "true");
        previous.put("dark/totem_of_undying.maxPurchases", "1");
        previous.put("dark/froglight.cost", "90");
        previous.put("dark/froglight.amount", "8");
        previous.put("dark/froglight.enabled", "true");
        previous.put("dark/froglight.maxPurchases", "2");
        previous.put("dark/goat_horn.cost", "75");
        previous.put("dark/goat_horn.amount", "1");
        previous.put("dark/goat_horn.enabled", "true");
        previous.put("dark/goat_horn.maxPurchases", "2");
        previous.put("dark/music_disc_pigstep.cost", "180");
        previous.put("dark/music_disc_pigstep.amount", "1");
        previous.put("dark/music_disc_pigstep.enabled", "true");
        previous.put("dark/music_disc_pigstep.maxPurchases", "1");
        previous.put("dark/nether_wart.cost", "15");
        previous.put("dark/nether_wart.amount", "32");
        previous.put("dark/nether_wart.enabled", "true");
        previous.put("dark/nether_wart.maxPurchases", "4");
        previous.put("neutral/torch.cost", "10");
        previous.put("neutral/torch.amount", "64");
        previous.put("neutral/torch.enabled", "true");
        previous.put("neutral/torch.maxPurchases", "0");
        previous.put("neutral/ender_pearl.cost", "25");
        previous.put("neutral/ender_pearl.amount", "8");
        previous.put("neutral/ender_pearl.enabled", "true");
        previous.put("neutral/ender_pearl.maxPurchases", "0");
        previous.put("neutral/ender_pearl.amount", "8");
        previous.put("dark/ender_pearl.amount", "4");
        Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
        String side = "";
        for (String raw : balanceV3Text().split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) { side = line.substring(1, line.length()-1).toLowerCase(Locale.ROOT); defaults.put(side, new LinkedHashMap<>()); }
            else if (line.contains("=")) { String[] parts = line.split("=", 2); defaults.get(side).put(parts[0], parts[1]); }
        }
        Map<String, Map<String, String>> actual = new LinkedHashMap<>();
        List<String> comments = new ArrayList<>();
        side = "";
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[")) { side = line.substring(1, line.length()-1).toLowerCase(Locale.ROOT); actual.putIfAbsent(side, new LinkedHashMap<>()); }
            else if (!line.startsWith("#") && !line.startsWith(";") && line.contains("=") && !side.isEmpty()) {
                String[] parts = line.split("=", 2); actual.get(side).put(parts[0].trim(), parts[1].trim());
            } else if (!line.isEmpty()) comments.add(raw);
        }
        for (var section : defaults.entrySet()) {
            Map<String, String> values = actual.computeIfAbsent(section.getKey(), ignored -> new LinkedHashMap<>());
            values.entrySet().removeIf(entry -> previous.containsKey(section.getKey()+"/"+entry.getKey()) && !section.getValue().containsKey(entry.getKey()));
            section.getValue().forEach((key, value) -> {
                String before = values.get(key);
                if (before == null || before.equals(previous.get(section.getKey()+"/"+key))) values.put(key, value);
            });
        }
        List<String> result = new ArrayList<>(); result.add(marker); result.addAll(comments);
        actual.forEach((section, values) -> { result.add("["+section+"]"); values.forEach((key,value) -> result.add(key+"="+value)); });
        return result;
    }

    static List<String> migrateLegacyPrices(List<String> lines) {
		String marker = "# kic.shopBalanceVersion=1";
		if (lines.stream().anyMatch(line -> line.trim().equals(marker))) return lines;
		Map<String, String> oldValues = Map.of(
			"light/redstone.cost", "18", "light/golden_apple.cost", "100",
			"light/enchanted_golden_apple.cost", "500", "light/enchanted_golden_apple.amount", "1",
			"dark/ender_pearl.cost", "60", "dark/ghast_tear.cost", "90",
			"dark/obsidian.cost", "100", "dark/shulker_shell.cost", "220");
		Map<String, String> newValues = Map.of(
			"light/redstone.cost", "32", "light/golden_apple.cost", "60",
			"light/enchanted_golden_apple.cost", "250", "light/enchanted_golden_apple.amount", "2",
			"dark/ender_pearl.cost", "16", "dark/ghast_tear.cost", "40",
			"dark/obsidian.cost", "40", "dark/shulker_shell.cost", "180");
		List<String> result = new ArrayList<>();
		result.add(marker);
		String section = "";
		for (String raw : lines) {
			String line = raw.trim();
			if (line.startsWith("[") && line.endsWith("]"))
				section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
			int equals = line.indexOf('=');
			if (equals > 0 && !line.startsWith("#") && !line.startsWith(";")) {
				String key = line.substring(0, equals).trim();
				String lookup = section + "/" + key;
				if (line.substring(equals + 1).trim().equals(oldValues.get(lookup)))
					raw = key + "=" + newValues.get(lookup);
			}
			result.add(raw);
		}
		return result;
	}

    private static String balanceV3Text() {
        return """
            [Light]
            recovery_compass.cost=40
            recovery_compass.amount=1
            recovery_compass.enabled=true
            recovery_compass.maxPurchases=1
            gilded_blackstone.cost=25
            gilded_blackstone.amount=32
            gilded_blackstone.enabled=true
            gilded_blackstone.maxPurchases=2
            saddle.cost=20
            saddle.amount=1
            saddle.enabled=true
            saddle.maxPurchases=1
            flower_banner_pattern.cost=35
            flower_banner_pattern.amount=1
            flower_banner_pattern.enabled=true
            flower_banner_pattern.maxPurchases=1
            creeper_banner_pattern.cost=50
            creeper_banner_pattern.amount=1
            creeper_banner_pattern.enabled=true
            creeper_banner_pattern.maxPurchases=1
            snout_banner_pattern.cost=60
            snout_banner_pattern.amount=1
            snout_banner_pattern.enabled=true
            snout_banner_pattern.maxPurchases=1
            globe_banner_pattern.cost=70
            globe_banner_pattern.amount=1
            globe_banner_pattern.enabled=true
            globe_banner_pattern.maxPurchases=1
            flow_banner_pattern.cost=70
            flow_banner_pattern.amount=1
            flow_banner_pattern.enabled=true
            flow_banner_pattern.maxPurchases=1
            guster_banner_pattern.cost=70
            guster_banner_pattern.amount=1
            guster_banner_pattern.enabled=true
            guster_banner_pattern.maxPurchases=1
            coast_armor_trim_smithing_template.cost=80
            coast_armor_trim_smithing_template.amount=1
            coast_armor_trim_smithing_template.enabled=true
            coast_armor_trim_smithing_template.maxPurchases=1
            silence_armor_trim_smithing_template.cost=250
            silence_armor_trim_smithing_template.amount=1
            silence_armor_trim_smithing_template.enabled=true
            silence_armor_trim_smithing_template.maxPurchases=1
            dune_armor_trim_smithing_template.cost=60
            dune_armor_trim_smithing_template.amount=1
            dune_armor_trim_smithing_template.enabled=true
            dune_armor_trim_smithing_template.maxPurchases=1
            wild_armor_trim_smithing_template.cost=65
            wild_armor_trim_smithing_template.amount=1
            wild_armor_trim_smithing_template.enabled=true
            wild_armor_trim_smithing_template.maxPurchases=1
            ward_armor_trim_smithing_template.cost=85
            ward_armor_trim_smithing_template.amount=1
            ward_armor_trim_smithing_template.enabled=true
            ward_armor_trim_smithing_template.maxPurchases=1
            vex_armor_trim_smithing_template.cost=75
            vex_armor_trim_smithing_template.amount=1
            vex_armor_trim_smithing_template.enabled=true
            vex_armor_trim_smithing_template.maxPurchases=1
            tide_armor_trim_smithing_template.cost=100
            tide_armor_trim_smithing_template.amount=1
            tide_armor_trim_smithing_template.enabled=true
            tide_armor_trim_smithing_template.maxPurchases=1
            wayfinder_armor_trim_smithing_template.cost=110
            wayfinder_armor_trim_smithing_template.amount=1
            wayfinder_armor_trim_smithing_template.enabled=true
            wayfinder_armor_trim_smithing_template.maxPurchases=1
            shaper_armor_trim_smithing_template.cost=110
            shaper_armor_trim_smithing_template.amount=1
            shaper_armor_trim_smithing_template.enabled=true
            shaper_armor_trim_smithing_template.maxPurchases=1
            raiser_armor_trim_smithing_template.cost=110
            raiser_armor_trim_smithing_template.amount=1
            raiser_armor_trim_smithing_template.enabled=true
            raiser_armor_trim_smithing_template.maxPurchases=1
            host_armor_trim_smithing_template.cost=125
            host_armor_trim_smithing_template.amount=1
            host_armor_trim_smithing_template.enabled=true
            host_armor_trim_smithing_template.maxPurchases=1
            eye_armor_trim_smithing_template.cost=150
            eye_armor_trim_smithing_template.amount=1
            eye_armor_trim_smithing_template.enabled=true
            eye_armor_trim_smithing_template.maxPurchases=1
            rib_armor_trim_smithing_template.cost=90
            rib_armor_trim_smithing_template.amount=1
            rib_armor_trim_smithing_template.enabled=true
            rib_armor_trim_smithing_template.maxPurchases=1
            spire_armor_trim_smithing_template.cost=180
            spire_armor_trim_smithing_template.amount=1
            spire_armor_trim_smithing_template.enabled=true
            spire_armor_trim_smithing_template.maxPurchases=1
            flow_armor_trim_smithing_template.cost=140
            flow_armor_trim_smithing_template.amount=1
            flow_armor_trim_smithing_template.enabled=true
            flow_armor_trim_smithing_template.maxPurchases=1
            bolt_armor_trim_smithing_template.cost=140
            bolt_armor_trim_smithing_template.amount=1
            bolt_armor_trim_smithing_template.enabled=true
            bolt_armor_trim_smithing_template.maxPurchases=1
            angler_pottery_sherd.cost=30
            angler_pottery_sherd.amount=1
            angler_pottery_sherd.enabled=true
            angler_pottery_sherd.maxPurchases=2
            prize_pottery_sherd.cost=30
            prize_pottery_sherd.amount=1
            prize_pottery_sherd.enabled=true
            prize_pottery_sherd.maxPurchases=2
            arms_up_pottery_sherd.cost=30
            arms_up_pottery_sherd.amount=1
            arms_up_pottery_sherd.enabled=true
            arms_up_pottery_sherd.maxPurchases=2
            blade_pottery_sherd.cost=30
            blade_pottery_sherd.amount=1
            blade_pottery_sherd.enabled=true
            blade_pottery_sherd.maxPurchases=2
            brewer_pottery_sherd.cost=30
            brewer_pottery_sherd.amount=1
            brewer_pottery_sherd.enabled=true
            brewer_pottery_sherd.maxPurchases=2
            burn_pottery_sherd.cost=30
            burn_pottery_sherd.amount=1
            burn_pottery_sherd.enabled=true
            burn_pottery_sherd.maxPurchases=2
            danger_pottery_sherd.cost=30
            danger_pottery_sherd.amount=1
            danger_pottery_sherd.enabled=true
            danger_pottery_sherd.maxPurchases=2
            explorer_pottery_sherd.cost=30
            explorer_pottery_sherd.amount=1
            explorer_pottery_sherd.enabled=true
            explorer_pottery_sherd.maxPurchases=2
            friend_pottery_sherd.cost=30
            friend_pottery_sherd.amount=1
            friend_pottery_sherd.enabled=true
            friend_pottery_sherd.maxPurchases=2
            heart_pottery_sherd.cost=30
            heart_pottery_sherd.amount=1
            heart_pottery_sherd.enabled=true
            heart_pottery_sherd.maxPurchases=2
            heartbreak_pottery_sherd.cost=30
            heartbreak_pottery_sherd.amount=1
            heartbreak_pottery_sherd.enabled=true
            heartbreak_pottery_sherd.maxPurchases=2
            howl_pottery_sherd.cost=30
            howl_pottery_sherd.amount=1
            howl_pottery_sherd.enabled=true
            howl_pottery_sherd.maxPurchases=2
            miner_pottery_sherd.cost=30
            miner_pottery_sherd.amount=1
            miner_pottery_sherd.enabled=true
            miner_pottery_sherd.maxPurchases=2
            mourn_pottery_sherd.cost=30
            mourn_pottery_sherd.amount=1
            mourn_pottery_sherd.enabled=true
            mourn_pottery_sherd.maxPurchases=2
            plenty_pottery_sherd.cost=30
            plenty_pottery_sherd.amount=1
            plenty_pottery_sherd.enabled=true
            plenty_pottery_sherd.maxPurchases=2
            scrape_pottery_sherd.cost=30
            scrape_pottery_sherd.amount=1
            scrape_pottery_sherd.enabled=true
            scrape_pottery_sherd.maxPurchases=2
            sheaf_pottery_sherd.cost=30
            sheaf_pottery_sherd.amount=1
            sheaf_pottery_sherd.enabled=true
            sheaf_pottery_sherd.maxPurchases=2
            shelter_pottery_sherd.cost=30
            shelter_pottery_sherd.amount=1
            shelter_pottery_sherd.enabled=true
            shelter_pottery_sherd.maxPurchases=2
            skull_pottery_sherd.cost=30
            skull_pottery_sherd.amount=1
            skull_pottery_sherd.enabled=true
            skull_pottery_sherd.maxPurchases=2
            snort_pottery_sherd.cost=30
            snort_pottery_sherd.amount=1
            snort_pottery_sherd.enabled=true
            snort_pottery_sherd.maxPurchases=2
            sand.cost=10
            sand.amount=64
            sand.enabled=true
            sand.maxPurchases=4
            heart_of_the_sea.cost=150
            heart_of_the_sea.amount=1
            heart_of_the_sea.enabled=true
            heart_of_the_sea.maxPurchases=1
            goat_horn.cost=75
            goat_horn.amount=1
            goat_horn.enabled=true
            goat_horn.maxPurchases=2
            sea_lantern.cost=45
            sea_lantern.amount=16
            sea_lantern.enabled=true
            sea_lantern.maxPurchases=4
            wheat_seeds.cost=6
            wheat_seeds.amount=32
            wheat_seeds.enabled=true
            wheat_seeds.maxPurchases=4
            beetroot_seeds.cost=8
            beetroot_seeds.amount=32
            beetroot_seeds.enabled=true
            beetroot_seeds.maxPurchases=4
            torchflower_seeds.cost=20
            torchflower_seeds.amount=8
            torchflower_seeds.enabled=true
            torchflower_seeds.maxPurchases=2
            pitcher_pod.cost=20
            pitcher_pod.amount=8
            pitcher_pod.enabled=true
            pitcher_pod.maxPurchases=2
            cow_spawn_egg.cost=40
            cow_spawn_egg.amount=2
            cow_spawn_egg.enabled=true
            cow_spawn_egg.maxPurchases=1
            pig_spawn_egg.cost=40
            pig_spawn_egg.amount=2
            pig_spawn_egg.enabled=true
            pig_spawn_egg.maxPurchases=1
            sheep_spawn_egg.cost=40
            sheep_spawn_egg.amount=2
            sheep_spawn_egg.enabled=true
            sheep_spawn_egg.maxPurchases=1
            chicken_spawn_egg.cost=40
            chicken_spawn_egg.amount=2
            chicken_spawn_egg.enabled=true
            chicken_spawn_egg.maxPurchases=1
            horse_spawn_egg.cost=150
            horse_spawn_egg.amount=1
            horse_spawn_egg.enabled=true
            horse_spawn_egg.maxPurchases=1
            music_disc_13.cost=90
            music_disc_13.amount=1
            music_disc_13.enabled=true
            music_disc_13.maxPurchases=1
            music_disc_cat.cost=90
            music_disc_cat.amount=1
            music_disc_cat.enabled=true
            music_disc_cat.maxPurchases=1
            music_disc_blocks.cost=100
            music_disc_blocks.amount=1
            music_disc_blocks.enabled=true
            music_disc_blocks.maxPurchases=1
            music_disc_chirp.cost=100
            music_disc_chirp.amount=1
            music_disc_chirp.enabled=true
            music_disc_chirp.maxPurchases=1
            suspicious_stew.cost=35
            suspicious_stew.amount=1
            suspicious_stew.enabled=true
            suspicious_stew.maxPurchases=2
            suspicious_stew.mode=suspicious_stew_cycle
            stone.cost=10
            stone.amount=64
            stone.enabled=true
            stone.maxPurchases=6
            stone.category=BUILDING
            stone.weight=4
            cobblestone.cost=8
            cobblestone.amount=64
            cobblestone.enabled=true
            cobblestone.maxPurchases=6
            cobblestone.category=BUILDING
            cobblestone.weight=4
            glass.cost=14
            glass.amount=32
            glass.enabled=true
            glass.maxPurchases=4
            glass.category=BUILDING
            glass.weight=3
            packed_ice.cost=30
            packed_ice.amount=16
            packed_ice.enabled=true
            packed_ice.maxPurchases=4
            packed_ice.category=BUILDING
            packed_ice.weight=2
            blue_ice.cost=60
            blue_ice.amount=8
            blue_ice.enabled=true
            blue_ice.maxPurchases=2
            blue_ice.category=BUILDING
            blue_ice.weight=1
            redstone.cost=24
            redstone.amount=32
            redstone.enabled=true
            redstone.maxPurchases=4
            redstone.category=RESOURCES
            redstone.weight=3
            lapis_lazuli.cost=16
            lapis_lazuli.amount=32
            lapis_lazuli.enabled=true
            lapis_lazuli.maxPurchases=4
            lapis_lazuli.category=RESOURCES
            lapis_lazuli.weight=3
            amethyst_shard.cost=24
            amethyst_shard.amount=16
            amethyst_shard.enabled=true
            amethyst_shard.maxPurchases=3
            amethyst_shard.category=RESOURCES
            amethyst_shard.weight=2
            slime_ball.cost=24
            slime_ball.amount=8
            slime_ball.enabled=true
            slime_ball.maxPurchases=3
            slime_ball.category=RESOURCES
            slime_ball.weight=2
            honeycomb.cost=24
            honeycomb.amount=16
            honeycomb.enabled=true
            honeycomb.maxPurchases=3
            honeycomb.category=RESOURCES
            honeycomb.weight=2
            sponge.cost=110
            sponge.amount=4
            sponge.enabled=true
            sponge.maxPurchases=2
            sponge.category=UTILITY
            sponge.weight=1
            golden_apple.cost=60
            golden_apple.amount=1
            golden_apple.enabled=true
            golden_apple.maxPurchases=2
            golden_apple.category=COMBAT
            golden_apple.weight=1
            phantom_membrane.cost=65
            phantom_membrane.amount=4
            phantom_membrane.enabled=true
            phantom_membrane.maxPurchases=2
            phantom_membrane.category=COMBAT
            phantom_membrane.weight=1
            shulker_shell.cost=180
            shulker_shell.amount=1
            shulker_shell.enabled=true
            shulker_shell.maxPurchases=1
            shulker_shell.category=RARE
            shulker_shell.weight=1
            shulker_shell.unlockStage=3
            enchanted_golden_apple.cost=60
            enchanted_golden_apple.amount=2
            enchanted_golden_apple.enabled=true
            enchanted_golden_apple.maxPurchases=1
            enchanted_golden_apple.category=RARE
            enchanted_golden_apple.weight=1
            enchanted_golden_apple.unlockStage=2
            copper_nautilus_armor.cost=65
            copper_nautilus_armor.amount=1
            copper_nautilus_armor.enabled=true
            copper_nautilus_armor.maxPurchases=1
            copper_nautilus_armor.unlockStage=0
            iron_nautilus_armor.cost=85
            iron_nautilus_armor.amount=1
            iron_nautilus_armor.enabled=true
            iron_nautilus_armor.maxPurchases=1
            iron_nautilus_armor.unlockStage=0
            golden_nautilus_armor.cost=95
            golden_nautilus_armor.amount=1
            golden_nautilus_armor.enabled=true
            golden_nautilus_armor.maxPurchases=1
            golden_nautilus_armor.unlockStage=0
            diamond_nautilus_armor.cost=110
            diamond_nautilus_armor.amount=1
            diamond_nautilus_armor.enabled=true
            diamond_nautilus_armor.maxPurchases=1
            diamond_nautilus_armor.unlockStage=0
            netherite_nautilus_armor.cost=120
            netherite_nautilus_armor.amount=1
            netherite_nautilus_armor.enabled=true
            netherite_nautilus_armor.maxPurchases=1
            netherite_nautilus_armor.unlockStage=0
            rabbit_foot.cost=40
            rabbit_foot.amount=1
            rabbit_foot.enabled=true
            rabbit_foot.maxPurchases=1
            rabbit_foot.unlockStage=0
            book_efficiency_1.cost=40
            book_efficiency_1.amount=1
            book_efficiency_1.enabled=true
            book_efficiency_1.maxPurchases=2
            book_efficiency_1.unlockStage=0
            book_efficiency_1.mode=fixed_enchantment
            book_efficiency_1.variant=minecraft:efficiency
            book_efficiency_1.level=1
            book_efficiency_1.name=Efficiency 1
            book_efficiency_2.cost=50
            book_efficiency_2.amount=1
            book_efficiency_2.enabled=true
            book_efficiency_2.maxPurchases=2
            book_efficiency_2.unlockStage=0
            book_efficiency_2.mode=fixed_enchantment
            book_efficiency_2.variant=minecraft:efficiency
            book_efficiency_2.level=2
            book_efficiency_2.name=Efficiency 2
            book_efficiency_3.cost=60
            book_efficiency_3.amount=1
            book_efficiency_3.enabled=true
            book_efficiency_3.maxPurchases=2
            book_efficiency_3.unlockStage=0
            book_efficiency_3.mode=fixed_enchantment
            book_efficiency_3.variant=minecraft:efficiency
            book_efficiency_3.level=3
            book_efficiency_3.name=Efficiency 3
            book_efficiency_4.cost=70
            book_efficiency_4.amount=1
            book_efficiency_4.enabled=true
            book_efficiency_4.maxPurchases=2
            book_efficiency_4.unlockStage=0
            book_efficiency_4.mode=fixed_enchantment
            book_efficiency_4.variant=minecraft:efficiency
            book_efficiency_4.level=4
            book_efficiency_4.name=Efficiency 4
            book_efficiency_5.cost=80
            book_efficiency_5.amount=1
            book_efficiency_5.enabled=true
            book_efficiency_5.maxPurchases=2
            book_efficiency_5.unlockStage=0
            book_efficiency_5.mode=fixed_enchantment
            book_efficiency_5.variant=minecraft:efficiency
            book_efficiency_5.level=5
            book_efficiency_5.name=Efficiency 5
            book_efficiency_6.cost=90
            book_efficiency_6.amount=1
            book_efficiency_6.enabled=true
            book_efficiency_6.maxPurchases=2
            book_efficiency_6.unlockStage=3
            book_efficiency_6.mode=fixed_enchantment
            book_efficiency_6.variant=minecraft:efficiency
            book_efficiency_6.level=6
            book_efficiency_6.name=Efficiency 6
            book_fortune_1.cost=40
            book_fortune_1.amount=1
            book_fortune_1.enabled=true
            book_fortune_1.maxPurchases=2
            book_fortune_1.unlockStage=0
            book_fortune_1.mode=fixed_enchantment
            book_fortune_1.variant=minecraft:fortune
            book_fortune_1.level=1
            book_fortune_1.name=Fortune 1
            book_fortune_2.cost=50
            book_fortune_2.amount=1
            book_fortune_2.enabled=true
            book_fortune_2.maxPurchases=2
            book_fortune_2.unlockStage=0
            book_fortune_2.mode=fixed_enchantment
            book_fortune_2.variant=minecraft:fortune
            book_fortune_2.level=2
            book_fortune_2.name=Fortune 2
            book_fortune_3.cost=60
            book_fortune_3.amount=1
            book_fortune_3.enabled=true
            book_fortune_3.maxPurchases=2
            book_fortune_3.unlockStage=0
            book_fortune_3.mode=fixed_enchantment
            book_fortune_3.variant=minecraft:fortune
            book_fortune_3.level=3
            book_fortune_3.name=Fortune 3
            book_fortune_4.cost=70
            book_fortune_4.amount=1
            book_fortune_4.enabled=true
            book_fortune_4.maxPurchases=2
            book_fortune_4.unlockStage=3
            book_fortune_4.mode=fixed_enchantment
            book_fortune_4.variant=minecraft:fortune
            book_fortune_4.level=4
            book_fortune_4.name=Fortune 4
            book_protection_1.cost=40
            book_protection_1.amount=1
            book_protection_1.enabled=true
            book_protection_1.maxPurchases=2
            book_protection_1.unlockStage=0
            book_protection_1.mode=fixed_enchantment
            book_protection_1.variant=minecraft:protection
            book_protection_1.level=1
            book_protection_1.name=Protection 1
            book_protection_2.cost=50
            book_protection_2.amount=1
            book_protection_2.enabled=true
            book_protection_2.maxPurchases=2
            book_protection_2.unlockStage=0
            book_protection_2.mode=fixed_enchantment
            book_protection_2.variant=minecraft:protection
            book_protection_2.level=2
            book_protection_2.name=Protection 2
            book_protection_3.cost=60
            book_protection_3.amount=1
            book_protection_3.enabled=true
            book_protection_3.maxPurchases=2
            book_protection_3.unlockStage=0
            book_protection_3.mode=fixed_enchantment
            book_protection_3.variant=minecraft:protection
            book_protection_3.level=3
            book_protection_3.name=Protection 3
            book_protection_4.cost=70
            book_protection_4.amount=1
            book_protection_4.enabled=true
            book_protection_4.maxPurchases=2
            book_protection_4.unlockStage=0
            book_protection_4.mode=fixed_enchantment
            book_protection_4.variant=minecraft:protection
            book_protection_4.level=4
            book_protection_4.name=Protection 4
            book_protection_5.cost=80
            book_protection_5.amount=1
            book_protection_5.enabled=true
            book_protection_5.maxPurchases=2
            book_protection_5.unlockStage=3
            book_protection_5.mode=fixed_enchantment
            book_protection_5.variant=minecraft:protection
            book_protection_5.level=5
            book_protection_5.name=Protection 5
            book_unbreaking_1.cost=40
            book_unbreaking_1.amount=1
            book_unbreaking_1.enabled=true
            book_unbreaking_1.maxPurchases=2
            book_unbreaking_1.unlockStage=0
            book_unbreaking_1.mode=fixed_enchantment
            book_unbreaking_1.variant=minecraft:unbreaking
            book_unbreaking_1.level=1
            book_unbreaking_1.name=Unbreaking 1
            book_unbreaking_2.cost=50
            book_unbreaking_2.amount=1
            book_unbreaking_2.enabled=true
            book_unbreaking_2.maxPurchases=2
            book_unbreaking_2.unlockStage=0
            book_unbreaking_2.mode=fixed_enchantment
            book_unbreaking_2.variant=minecraft:unbreaking
            book_unbreaking_2.level=2
            book_unbreaking_2.name=Unbreaking 2
            book_unbreaking_3.cost=60
            book_unbreaking_3.amount=1
            book_unbreaking_3.enabled=true
            book_unbreaking_3.maxPurchases=2
            book_unbreaking_3.unlockStage=0
            book_unbreaking_3.mode=fixed_enchantment
            book_unbreaking_3.variant=minecraft:unbreaking
            book_unbreaking_3.level=3
            book_unbreaking_3.name=Unbreaking 3
            book_unbreaking_4.cost=70
            book_unbreaking_4.amount=1
            book_unbreaking_4.enabled=true
            book_unbreaking_4.maxPurchases=2
            book_unbreaking_4.unlockStage=3
            book_unbreaking_4.mode=fixed_enchantment
            book_unbreaking_4.variant=minecraft:unbreaking
            book_unbreaking_4.level=4
            book_unbreaking_4.name=Unbreaking 4
            book_mending_1.cost=80
            book_mending_1.amount=1
            book_mending_1.enabled=true
            book_mending_1.maxPurchases=2
            book_mending_1.unlockStage=0
            book_mending_1.mode=fixed_enchantment
            book_mending_1.variant=minecraft:mending
            book_mending_1.level=1
            book_mending_1.name=Mending 1
            book_aqua_affinity_1.cost=80
            book_aqua_affinity_1.amount=1
            book_aqua_affinity_1.enabled=true
            book_aqua_affinity_1.maxPurchases=2
            book_aqua_affinity_1.unlockStage=0
            book_aqua_affinity_1.mode=fixed_enchantment
            book_aqua_affinity_1.variant=minecraft:aqua_affinity
            book_aqua_affinity_1.level=1
            book_aqua_affinity_1.name=Aqua Affinity 1
            book_swift_sneak_1.cost=80
            book_swift_sneak_1.amount=1
            book_swift_sneak_1.enabled=true
            book_swift_sneak_1.maxPurchases=2
            book_swift_sneak_1.unlockStage=0
            book_swift_sneak_1.mode=fixed_enchantment
            book_swift_sneak_1.variant=minecraft:swift_sneak
            book_swift_sneak_1.level=1
            book_swift_sneak_1.name=Swift Sneak 1
            book_swift_sneak_2.cost=95
            book_swift_sneak_2.amount=1
            book_swift_sneak_2.enabled=true
            book_swift_sneak_2.maxPurchases=2
            book_swift_sneak_2.unlockStage=0
            book_swift_sneak_2.mode=fixed_enchantment
            book_swift_sneak_2.variant=minecraft:swift_sneak
            book_swift_sneak_2.level=2
            book_swift_sneak_2.name=Swift Sneak 2
            book_swift_sneak_3.cost=110
            book_swift_sneak_3.amount=1
            book_swift_sneak_3.enabled=true
            book_swift_sneak_3.maxPurchases=2
            book_swift_sneak_3.unlockStage=0
            book_swift_sneak_3.mode=fixed_enchantment
            book_swift_sneak_3.variant=minecraft:swift_sneak
            book_swift_sneak_3.level=3
            book_swift_sneak_3.name=Swift Sneak 3
            book_swift_sneak_4.cost=125
            book_swift_sneak_4.amount=1
            book_swift_sneak_4.enabled=true
            book_swift_sneak_4.maxPurchases=2
            book_swift_sneak_4.unlockStage=3
            book_swift_sneak_4.mode=fixed_enchantment
            book_swift_sneak_4.variant=minecraft:swift_sneak
            book_swift_sneak_4.level=4
            book_swift_sneak_4.name=Swift Sneak 4
            book_silk_touch_1.cost=80
            book_silk_touch_1.amount=1
            book_silk_touch_1.enabled=true
            book_silk_touch_1.maxPurchases=2
            book_silk_touch_1.unlockStage=0
            book_silk_touch_1.mode=fixed_enchantment
            book_silk_touch_1.variant=minecraft:silk_touch
            book_silk_touch_1.level=1
            book_silk_touch_1.name=Silk Touch 1
            [Dark]
            blackstone.cost=12
            blackstone.amount=64
            blackstone.enabled=true
            blackstone.maxPurchases=6
            blackstone.category=BUILDING
            blackstone.weight=4
            basalt.cost=12
            basalt.amount=64
            basalt.enabled=true
            basalt.maxPurchases=6
            basalt.category=BUILDING
            basalt.weight=4
            nether_bricks.cost=18
            nether_bricks.amount=32
            nether_bricks.enabled=true
            nether_bricks.maxPurchases=4
            nether_bricks.category=BUILDING
            nether_bricks.weight=3
            rotten_flesh.cost=8
            rotten_flesh.amount=32
            rotten_flesh.enabled=true
            rotten_flesh.maxPurchases=6
            rotten_flesh.category=RESOURCES
            rotten_flesh.weight=4
            gunpowder.cost=28
            gunpowder.amount=16
            gunpowder.enabled=true
            gunpowder.maxPurchases=4
            gunpowder.category=RESOURCES
            gunpowder.weight=3
            bone.cost=10
            bone.amount=32
            bone.enabled=true
            bone.maxPurchases=6
            bone.category=RESOURCES
            bone.weight=4
            string.cost=12
            string.amount=32
            string.enabled=true
            string.maxPurchases=6
            string.category=RESOURCES
            string.weight=4
            blaze_powder.cost=35
            blaze_powder.amount=16
            blaze_powder.enabled=true
            blaze_powder.maxPurchases=4
            blaze_powder.category=COMBAT
            blaze_powder.weight=2
            magma_cream.cost=45
            magma_cream.amount=8
            magma_cream.enabled=true
            magma_cream.maxPurchases=3
            magma_cream.category=COMBAT
            magma_cream.weight=2
            blaze_rod.cost=70
            blaze_rod.amount=8
            blaze_rod.enabled=true
            blaze_rod.maxPurchases=3
            blaze_rod.category=COMBAT
            blaze_rod.weight=1
            ghast_tear.cost=40
            ghast_tear.amount=2
            ghast_tear.enabled=true
            ghast_tear.maxPurchases=2
            ghast_tear.category=COMBAT
            ghast_tear.weight=1
            wither_skeleton_skull.cost=220
            wither_skeleton_skull.amount=1
            wither_skeleton_skull.enabled=true
            wither_skeleton_skull.maxPurchases=1
            wither_skeleton_skull.category=RARE
            wither_skeleton_skull.weight=1
            wither_skeleton_skull.unlockStage=3
            shulker_shell.cost=180
            shulker_shell.amount=1
            shulker_shell.enabled=true
            shulker_shell.maxPurchases=1
            shulker_shell.category=RARE
            shulker_shell.weight=1
            shulker_shell.unlockStage=4
            ancient_debris.cost=260
            ancient_debris.amount=1
            ancient_debris.enabled=true
            ancient_debris.maxPurchases=2
            ancient_debris.category=RARE
            ancient_debris.weight=1
            ancient_debris.unlockStage=3
            netherite_scrap.cost=220
            netherite_scrap.amount=1
            netherite_scrap.enabled=true
            netherite_scrap.maxPurchases=2
            netherite_scrap.category=RARE
            netherite_scrap.weight=1
            netherite_scrap.unlockStage=3
            ender_eye.cost=40
            ender_eye.amount=1
            ender_eye.enabled=true
            ender_eye.maxPurchases=2
            ender_eye.category=UTILITY
            ender_eye.weight=1
            tnt.cost=40
            tnt.amount=4
            tnt.enabled=true
            tnt.maxPurchases=2
            obsidian.cost=40
            obsidian.amount=16
            obsidian.enabled=true
            obsidian.maxPurchases=1
            crying_obsidian.cost=125
            crying_obsidian.amount=8
            crying_obsidian.enabled=true
            crying_obsidian.maxPurchases=0
            magma_block.cost=75
            magma_block.amount=16
            magma_block.enabled=true
            magma_block.maxPurchases=0
            ender_pearl.cost=16
            ender_pearl.amount=2
            ender_pearl.enabled=true
            ender_pearl.maxPurchases=2
            totem_of_undying.cost=300
            totem_of_undying.amount=1
            totem_of_undying.enabled=true
            totem_of_undying.maxPurchases=1
            goat_horn.cost=75
            goat_horn.amount=1
            goat_horn.enabled=true
            goat_horn.maxPurchases=2
            music_disc_pigstep.cost=180
            music_disc_pigstep.amount=1
            music_disc_pigstep.enabled=true
            music_disc_pigstep.maxPurchases=1
            nether_wart.cost=15
            nether_wart.amount=32
            nether_wart.enabled=true
            nether_wart.maxPurchases=4
            ochre_froglight.cost=90
            ochre_froglight.amount=8
            ochre_froglight.enabled=true
            ochre_froglight.maxPurchases=2
            stone.cost=10
            stone.amount=64
            stone.enabled=true
            stone.maxPurchases=6
            stone.category=BUILDING
            stone.weight=4
            cobblestone.cost=8
            cobblestone.amount=64
            cobblestone.enabled=true
            cobblestone.maxPurchases=6
            cobblestone.category=BUILDING
            cobblestone.weight=4
            glass.cost=14
            glass.amount=32
            glass.enabled=true
            glass.maxPurchases=4
            glass.category=BUILDING
            glass.weight=3
            redstone.cost=24
            redstone.amount=32
            redstone.enabled=true
            redstone.maxPurchases=4
            redstone.category=RESOURCES
            redstone.weight=3
            slime_ball.cost=24
            slime_ball.amount=8
            slime_ball.enabled=true
            slime_ball.maxPurchases=3
            slime_ball.category=RESOURCES
            slime_ball.weight=2
            saddle.cost=20
            saddle.amount=1
            saddle.enabled=true
            saddle.maxPurchases=1
            enchanted_golden_apple.cost=60
            enchanted_golden_apple.amount=2
            enchanted_golden_apple.enabled=true
            enchanted_golden_apple.maxPurchases=1
            enchanted_golden_apple.category=RARE
            enchanted_golden_apple.weight=1
            enchanted_golden_apple.unlockStage=2
            rabbit_foot.cost=40
            rabbit_foot.amount=1
            rabbit_foot.enabled=true
            rabbit_foot.maxPurchases=1
            rabbit_foot.unlockStage=0
            book_sharpness_1.cost=40
            book_sharpness_1.amount=1
            book_sharpness_1.enabled=true
            book_sharpness_1.maxPurchases=2
            book_sharpness_1.unlockStage=0
            book_sharpness_1.mode=fixed_enchantment
            book_sharpness_1.variant=minecraft:sharpness
            book_sharpness_1.level=1
            book_sharpness_1.name=Sharpness 1
            book_sharpness_2.cost=50
            book_sharpness_2.amount=1
            book_sharpness_2.enabled=true
            book_sharpness_2.maxPurchases=2
            book_sharpness_2.unlockStage=0
            book_sharpness_2.mode=fixed_enchantment
            book_sharpness_2.variant=minecraft:sharpness
            book_sharpness_2.level=2
            book_sharpness_2.name=Sharpness 2
            book_sharpness_3.cost=60
            book_sharpness_3.amount=1
            book_sharpness_3.enabled=true
            book_sharpness_3.maxPurchases=2
            book_sharpness_3.unlockStage=0
            book_sharpness_3.mode=fixed_enchantment
            book_sharpness_3.variant=minecraft:sharpness
            book_sharpness_3.level=3
            book_sharpness_3.name=Sharpness 3
            book_sharpness_4.cost=70
            book_sharpness_4.amount=1
            book_sharpness_4.enabled=true
            book_sharpness_4.maxPurchases=2
            book_sharpness_4.unlockStage=0
            book_sharpness_4.mode=fixed_enchantment
            book_sharpness_4.variant=minecraft:sharpness
            book_sharpness_4.level=4
            book_sharpness_4.name=Sharpness 4
            book_sharpness_5.cost=80
            book_sharpness_5.amount=1
            book_sharpness_5.enabled=true
            book_sharpness_5.maxPurchases=2
            book_sharpness_5.unlockStage=0
            book_sharpness_5.mode=fixed_enchantment
            book_sharpness_5.variant=minecraft:sharpness
            book_sharpness_5.level=5
            book_sharpness_5.name=Sharpness 5
            book_sharpness_6.cost=90
            book_sharpness_6.amount=1
            book_sharpness_6.enabled=true
            book_sharpness_6.maxPurchases=2
            book_sharpness_6.unlockStage=3
            book_sharpness_6.mode=fixed_enchantment
            book_sharpness_6.variant=minecraft:sharpness
            book_sharpness_6.level=6
            book_sharpness_6.name=Sharpness 6
            book_soul_speed_1.cost=80
            book_soul_speed_1.amount=1
            book_soul_speed_1.enabled=true
            book_soul_speed_1.maxPurchases=2
            book_soul_speed_1.unlockStage=0
            book_soul_speed_1.mode=fixed_enchantment
            book_soul_speed_1.variant=minecraft:soul_speed
            book_soul_speed_1.level=1
            book_soul_speed_1.name=Soul Speed 1
            book_soul_speed_2.cost=95
            book_soul_speed_2.amount=1
            book_soul_speed_2.enabled=true
            book_soul_speed_2.maxPurchases=2
            book_soul_speed_2.unlockStage=0
            book_soul_speed_2.mode=fixed_enchantment
            book_soul_speed_2.variant=minecraft:soul_speed
            book_soul_speed_2.level=2
            book_soul_speed_2.name=Soul Speed 2
            book_soul_speed_3.cost=110
            book_soul_speed_3.amount=1
            book_soul_speed_3.enabled=true
            book_soul_speed_3.maxPurchases=2
            book_soul_speed_3.unlockStage=0
            book_soul_speed_3.mode=fixed_enchantment
            book_soul_speed_3.variant=minecraft:soul_speed
            book_soul_speed_3.level=3
            book_soul_speed_3.name=Soul Speed 3
            book_soul_speed_4.cost=125
            book_soul_speed_4.amount=1
            book_soul_speed_4.enabled=true
            book_soul_speed_4.maxPurchases=2
            book_soul_speed_4.unlockStage=3
            book_soul_speed_4.mode=fixed_enchantment
            book_soul_speed_4.variant=minecraft:soul_speed
            book_soul_speed_4.level=4
            book_soul_speed_4.name=Soul Speed 4
            book_infinity_1.cost=80
            book_infinity_1.amount=1
            book_infinity_1.enabled=true
            book_infinity_1.maxPurchases=2
            book_infinity_1.unlockStage=0
            book_infinity_1.mode=fixed_enchantment
            book_infinity_1.variant=minecraft:infinity
            book_infinity_1.level=1
            book_infinity_1.name=Infinity 1
            [Neutral]
            torch.cost=10
            torch.amount=64
            torch.enabled=true
            torch.maxPurchases=0
            ender_pearl.cost=25
            ender_pearl.amount=4
            ender_pearl.enabled=true
            ender_pearl.maxPurchases=0
            """;
    }
}
