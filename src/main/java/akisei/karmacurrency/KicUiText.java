package akisei.karmacurrency;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/** Shared player-facing wording; no balances or transaction state live here. */
public final class KicUiText {
    private KicUiText() {}
    public static String displayCase(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + text.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
    public static String number(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    public static net.minecraft.network.chat.Component title(String text) {
        return net.minecraft.network.chat.Component.literal(text.replace('\n', ' '))
            .withStyle(style -> style.withItalic(false).withColor(net.minecraft.ChatFormatting.WHITE));
    }
    public static java.util.List<net.minecraft.network.chat.Component> lore(String... lines) {
        var result = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        for (String value : lines) for (String line : value.split("\\n", -1)) {
            var color = line.startsWith("Missing:") || line.startsWith("Unavailable:") ? net.minecraft.ChatFormatting.RED
                : line.startsWith("Ready:") ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY;
            StringBuilder current = new StringBuilder();
            for (String word : line.split(" ")) {
                if (current.length() > 0 && current.length() + word.length() + 1 > 44) {
                    result.add(net.minecraft.network.chat.Component.literal(current.toString()).withStyle(st -> st.withItalic(false).withColor(color)));
                    current.setLength(0);
                }
                if (current.length() > 0) current.append(' ');
                current.append(word);
            }
            result.add(net.minecraft.network.chat.Component.literal(current.toString()).withStyle(st -> st.withItalic(false).withColor(color)));
        }
        return result;
    }
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
        return displayCase(stance.name()) + (grace ? " — grace range (retained until Karma reaches zero)" : "")
            + (stance == SocialCurrencyService.Stance.NEUTRAL ? "; reward preference: " + displayCase(KarmaService.rewardAlignment(player).name()) : "");
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
        return "Value: " + number(gross) + "\nReceive: " + net + " " + target(player) + "\nTax / rounding: " + number(gross - net);
    }
    public static List<CurseBlessingRegistry.ItemCost> costs(boolean blessing, String id) {
        return blessing ? CurseBlessingRegistry.blessing(id).cost() : CurseBlessingRegistry.curse(id).cost();
    }
    public static List<String> costLines(ServerPlayer player, boolean blessing, String id) {
        return CurseBlessingService.activationCosts(player, blessing, id).stream().map(cost -> {
            int owned = CurseBlessingService.availableCost(player, cost);
            String name = cost.influence() ? (cost.reputation() ? "Reputation" : "Infamy")
                : (cost.item() == net.minecraft.world.item.Items.ENCHANTED_BOOK ? "Sharpness V book" : new net.minecraft.world.item.ItemStack(cost.item()).getHoverName().getString());
            return (owned >= cost.count() ? "Ready: " : "Missing: ") + name + " " + owned + "/" + cost.count();
        }).toList();
    }
    public static String projectedEffect(ServerPlayer player, boolean blessing, String id, String description) {
        int next = (blessing ? CurseBlessingService.storedStack(player, true, id) : CurseBlessingService.storedStack(player, false, id)) + 1;
        if (blessing && id.equals("protection")) return "Next stack: +" + (2 * Math.min(2,next)) + " total armor. Adds 5 minutes.";
        if (blessing && id.equals("hunter")) return "Next stack: +" + (5 * Math.min(2,next)) + "% damage against hostile mobs.";
        if (blessing && id.equals("haste")) return "Next stack: " + (next >= 3 ? "Haste II" : "Haste I") + ". Adds 3 minutes.";
        if (!blessing && id.equals("fettered")) return "Next stack: +" + (int)Math.floor(2 + 1.5 * (Math.min(4,next)-1)) + " total armor; " + (next >= 3 ? "-10%" : "-5%") + " total movement speed.\nKnockback resistance: +30%. Jump strength: -0.20.";
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
