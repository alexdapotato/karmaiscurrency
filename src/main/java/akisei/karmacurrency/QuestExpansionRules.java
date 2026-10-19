package akisei.karmacurrency;

/** Deterministic balance rules, independent of game APIs. */
public final class QuestExpansionRules {
    private QuestExpansionRules() {}
    public static int mercyKarma(int villagers) { return villagers <= 2 ? 15 : 30; }
    public static int mercyReputation(int villagers) { return 5 + 15 * Math.max(1, Math.min(4, villagers)); }
    public static int pilgrimageCount(int stage) { return Math.max(1, Math.min(5, stage)); }
    public static int pilgrimageReputation(int count) { return 24 + 10 * (Math.max(1, Math.min(5, count)) - 1); }
    public static long bloodPriceMillis(int stage) { return (stage >= 2 ? 16L : 30L) * 60_000L; }
    public static boolean specialCatch(int catches, int rollOutOf100) { return catches >= 75 || rollOutOf100 < 2; }
    /** A success in C blocks C+1, C+2 and C+3; next eligible cycle is C+4. */
    public static long blueNextCycle(long completedCycle) { return completedCycle + 4; }
    public static boolean blueAvailable(long cycle, long nextCycle, long attemptedCyclePlusOne) {
        return cycle >= nextCycle && attemptedCyclePlusOne != cycle + 1;
    }
    public static boolean blueAppears(long cycle) {
        long seed = cycle + 0x6A09E667F3BCC909L;
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        return Math.floorMod(seed ^ (seed >>> 31), 10) == 0;
    }
}
