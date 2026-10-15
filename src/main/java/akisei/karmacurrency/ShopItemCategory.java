package akisei.karmacurrency;

import java.util.Locale;

/** Player-facing content categories used to guarantee shop variety per rotation. */
public enum ShopItemCategory {
    BUILDING("Building"),
    UTILITY("Utility"),
    RESOURCES("Resources"),
    COMBAT("Combat"),
    DECORATION("Decoration"),
    RARE("Rare");

    private final String displayName;

    ShopItemCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    public static ShopItemCategory parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try { return valueOf(normalized); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
