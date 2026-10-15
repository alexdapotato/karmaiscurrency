package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.util.Locale;

/** Shrine identities used throughout the mod. */
public enum ShrineType {
	LIGHT,
	DARK,
	NEUTRAL,
	MONUMENT;

	public static final Codec<ShrineType> CODEC = Codec.STRING.xmap(
		value -> {
			String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
			// Read older save/config names safely while writing only the new names.
			return switch (normalized) {
				case "CIVIC", "CIVIL" -> ShrineType.LIGHT;
				case "RUINED" -> ShrineType.DARK;
				case "ALL_ROUNDER" -> ShrineType.MONUMENT;
				default -> ShrineType.valueOf(normalized);
			};
		},
		type -> type.name().toLowerCase(Locale.ROOT)
	);

	public String displayName() {
		return switch (this) {
			case LIGHT -> "Light Shrine";
			case DARK -> "Dark Shrine";
			case NEUTRAL -> "Neutral Shrine";
			case MONUMENT -> "Monument";
		};
	}

	public String configName() {
		return switch (this) {
			case LIGHT -> "Light";
			case DARK -> "Dark";
			case NEUTRAL -> "Neutral";
			case MONUMENT -> "Monument";
		};
	}
}
