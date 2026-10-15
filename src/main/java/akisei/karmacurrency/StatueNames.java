package akisei.karmacurrency;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;

/** Loads editable statue names and chooses a deterministic unique name per shrine. */
public final class StatueNames {
	private static final Path FILE = KicConfigPaths.file("karmaiscurrency-statue-names.properties");
	private static final Map<ShrineType, List<String>> names = new EnumMap<>(ShrineType.class);

	private StatueNames() {
	}

	public static void load() {
		Properties properties = new Properties();
		try {
			Files.createDirectories(FILE.getParent());
			if (Files.notExists(FILE)) {
				Files.writeString(FILE, """
					# Comma-separated names. Add more names to increase variety.
					Light=Beacon, Dawnkeeper, Sunward
					Neutral=Equilibrium, Wayfinder, Stillpoint
					Dark=Nightfall, Ashbringer, Dreadwatch
					Monument=Worldheart, Concord, The Keystone
					""");
			}
			try (Reader reader = Files.newBufferedReader(FILE)) {
				properties.load(reader);
			}
		} catch (IOException exception) {
			KarmaIsCurrency.LOGGER.error("Could not read statue names {}", FILE, exception);
		}

		for (ShrineType type : ShrineType.values()) {
			String value = properties.getProperty(type.configName(), type.configName());
			List<String> loaded = new ArrayList<>();
			for (String name : value.split(",")) {
				if (!name.isBlank()) {
					loaded.add(name.trim());
				}
			}
			names.put(type, loaded.isEmpty() ? List.of(type.configName()) : List.copyOf(loaded));
		}
		KarmaIsCurrency.LOGGER.info("Loaded statue names from {}", FILE);
	}

	public static String choose(ShrineType type, long seed) {
		return chooseUnique(type, seed, List.of());
	}

	/** Chooses a configured name that is unique among the supplied shrine names. */
	public static String chooseUnique(ShrineType type, long seed, Collection<String> usedNames) {
		List<String> values = names.getOrDefault(type, List.of(type.configName()));
		Set<String> used = new HashSet<>();
		for (String usedName : usedNames) {
			if (usedName != null && !usedName.isBlank()) {
				used.add(normalize(usedName, type));
			}
		}

		int start = Math.floorMod((int) (seed ^ (seed >>> 32)), values.size());
		for (int offset = 0; offset < values.size(); offset++) {
			String candidate = format(values.get((start + offset) % values.size()), type);
			if (!used.contains(normalize(candidate, type))) {
				return candidate;
			}
		}

		String base = format(values.get(start), type);
		int suffix = 2;
		String candidate = base + " " + suffix;
		while (used.contains(normalize(candidate, type))) {
			suffix++;
			candidate = base + " " + suffix;
		}
		return candidate;
	}
	private static String format(String raw, ShrineType type) {
		String name = raw == null ? "" : raw.trim();
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		String config = type.configName().toLowerCase(java.util.Locale.ROOT);
		String display = type.displayName().toLowerCase(java.util.Locale.ROOT);
		if (lower.equals(config) || lower.endsWith(" " + config)
			|| lower.equals(display) || lower.endsWith(" " + display)
			|| lower.endsWith(", " + display)) {
			return name;
		}
		return name + " " + type.configName();
	}

	private static String normalize(String value, ShrineType type) {
		return value.trim().toLowerCase(java.util.Locale.ROOT);
	}


}
