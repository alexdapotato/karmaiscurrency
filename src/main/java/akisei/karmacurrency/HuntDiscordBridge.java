package akisei.karmacurrency;

/** Optional Discord integration seam for The Hunt. The actual SDLink adapter is intentionally separate. */
public final class HuntDiscordBridge {
    private HuntDiscordBridge() {}

    public static void announceHuntStarted(String hunted, long endEpochMillis) {
        KarmaIsCurrency.LOGGER.info("[HUNT] started: hunted={}, ends={}", hunted, endEpochMillis);
    }

    public static void announceHunterJoined(String hunter, String hunted) {
        KarmaIsCurrency.LOGGER.info("[HUNT] hunter joined: hunter={}, hunted={}", hunter, hunted);
    }

    public static void announceHuntEnded(String hunted, boolean survived) {
        KarmaIsCurrency.LOGGER.info("[HUNT] ended: hunted={}, survived={}", hunted, survived);
    }
}
