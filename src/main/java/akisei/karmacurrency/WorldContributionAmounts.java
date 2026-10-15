package akisei.karmacurrency;

/** Shared, overflow-safe selection rules for Java menus and Bedrock forms. */
final class WorldContributionAmounts {
    private WorldContributionAmounts() {}
    static int adjust(int selected, int delta, int available) {
        return (int) Math.max(0L, Math.min((long) Math.max(0, available), (long) selected + delta));
    }
    static int fill(int available, int pool, int next, boolean capped) {
        return capped ? 0 : (int) Math.max(0L, Math.min((long) Math.max(0, available), (long) next - pool));
    }
    static int parse(String raw) {
        if (raw == null || !raw.trim().matches("[0-9]+")) return 0;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
