package akisei.karmacurrency;

/** Successful action flavor with the actual currency delta. */
public final class KicFlavorText {
    private KicFlavorText() {}

    public static String offering(SocialCurrencyService.Stance stance, int amount, String currency) {
        return offeringFlavor(stance) + " [+" + amount + " " + currency + "]";
    }
    public static String offeringFlavor(SocialCurrencyService.Stance stance) {
        String[] lines = switch (stance) {
            case LIGHT -> new String[] {"The deity rewards your virtue.", "It is delighted by your gifts.", "Your offering is met with warmth."};
            case DARK -> new String[] {"The devil smirks.", "He is pleased at your actions.", "Your offering earns a knowing smile."};
            case NEUTRAL -> new String[] {"Your offering finds its place in the balance.", "Something stirs beyond the veil."};
        };
        return pick(lines);
    }

    public static String contribution(int amount) {
        return pick(new String[] {"The world line moves closer to 1.", "... and so the world steps forward.",
            "Your offering leaves its mark upon the world."})
            + " [-" + amount + " Influence | +" + amount + " World Progression]";
    }

    private static String pick(String[] lines) {
        return lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)];
    }
}
