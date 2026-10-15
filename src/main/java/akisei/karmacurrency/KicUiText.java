package akisei.karmacurrency;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/** Shared player-facing wording; no balances or transaction state live here. */
public final class KicUiText {
    private KicUiText() {}
    public static String durationSeconds(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3600L, minutes = safe / 60L % 60L, remainder = safe % 60L;
        if (hours > 0) return hours + "h " + minutes + "m " + remainder + "s";
        return minutes > 0 ? minutes + "m " + remainder + "s" : remainder + "s";
    }
    public static String alignmentStatus(ServerPlayer player) {
        SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
        int karma = KarmaService.get(player);
        boolean grace = stance != SocialCurrencyService.Stance.NEUTRAL
            && Math.abs((long) karma) < KarmaService.karma(100);
        return stance.name() + (grace ? " — grace range (retained until Karma reaches zero)" : "")
            + (stance == SocialCurrencyService.Stance.NEUTRAL ? "; reward preference: " + KarmaService.rewardAlignment(player).name() : "");
    }
    public static String target(ServerPlayer player) {
        return switch (KarmaService.rewardAlignment(player)) {
            case LIGHT -> "Reputation";
            case DARK -> "Infamy";
            case NEUTRAL -> "Neutral Influence";
        };
    }
    public static String conversion(ServerPlayer player, double gross) {
        int net = SocialCurrencyService.offeringNet(gross);
        return gross + " gross → " + net + " " + target(player) + " | " + (gross - net) + " lost to tax";
    }
    public static List<CurseBlessingRegistry.ItemCost> costs(boolean blessing, String id) {
        return blessing ? CurseBlessingRegistry.blessing(id).cost() : CurseBlessingRegistry.curse(id).cost();
    }
    public static List<String> costLines(ServerPlayer player, boolean blessing, String id) {
        return costs(blessing, id).stream().map(cost -> {
            int owned = CurseBlessingService.availableCost(player, cost);
            String name = cost.influence() ? "Influence (any origin)"
                : new net.minecraft.world.item.ItemStack(cost.item()).getHoverName().getString();
            return (owned >= cost.count() ? "Ready: " : "Missing: ") + name + " " + owned + "/" + cost.count();
        }).toList();
    }
    public static String projectedEffect(ServerPlayer player, boolean blessing, String id, String description) {
        int next = (blessing ? CurseBlessingService.blessingStack(player, id) : CurseBlessingService.curseStack(player, id)) + 1;
        if (!blessing && ("justice".equals(id) || "strength".equals(id))) {
            double damage = next * ("justice".equals(id) ? 1.0 : 0.5);
            return "After activation: +" + damage + " damage dealt and +" + damage + " damage taken.";
        }
        return "Effect after activation: " + description;
    }
    public static String commitmentWarning(boolean blessing, String id) {
        if (blessing && "devotion".equals(id)) return "Permanent commitment: requires releasing all Curses first and prevents Dark Shrine access. Closing this menu will not undo it.";
        if (!blessing && "devil".equals(id)) return "Permanent commitment: requires releasing all Blessings first and prevents Light Shrine access. Closing this menu will not undo it.";
        return "";
    }
    public static void success(ServerPlayer player) {
        player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.35F, 1.2F);
    }
}
