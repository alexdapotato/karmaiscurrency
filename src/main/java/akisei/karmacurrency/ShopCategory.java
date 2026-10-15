package akisei.karmacurrency;

import net.minecraft.server.level.ServerPlayer;

public enum ShopCategory {
	COMMON("Light Shop"),
	VILLAIN("Dark Shop");

	private final String displayName;

	ShopCategory(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}


	public static boolean canAccess(ServerPlayer player, ShopCategory category, ShrineType shrineType) {
		if (category == null || shrineType == null) return false;
		return switch (category) {
			case COMMON -> shrineType != ShrineType.DARK;
			case VILLAIN -> shrineType == ShrineType.DARK
				? CurseBlessingService.canUseDarkShrine(player)
				: shrineType == ShrineType.NEUTRAL || shrineType == ShrineType.MONUMENT;
		};
	}

	public static ShopCategory forShrineType(ShrineType type) {
		return switch (type) {
			case LIGHT, NEUTRAL -> COMMON;
			case DARK -> VILLAIN;
			case MONUMENT -> COMMON;
		};
	}
}
