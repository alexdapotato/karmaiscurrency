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
            .withStyle(style -> style.withItalic(false).withColor(net.minecraft.ChatFormatting.GOLD).withBold(true));
    }
    public static net.minecraft.network.chat.MutableComponent panel(String heading) {
        return net.minecraft.network.chat.Component.literal(heading).withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD);
    }
    public static net.minecraft.network.chat.MutableComponent line(String label, String value, net.minecraft.ChatFormatting color) {
        return net.minecraft.network.chat.Component.literal("\n"+label+": ").withStyle(style->style.withColor(net.minecraft.ChatFormatting.GRAY).withBold(false))
            .append(net.minecraft.network.chat.Component.literal(value).withStyle(style->style.withColor(color).withBold(false)));
    }
    public static java.util.List<net.minecraft.network.chat.Component> lore(String... lines) {
        var result = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        for (String value : lines) for (String line : value.split("\\n", -1)) {
            var color = line.startsWith("Missing:") || line.startsWith("Unavailable:") ? net.minecraft.ChatFormatting.RED
                : line.startsWith("Ready:") || line.startsWith("Reward:") ? net.minecraft.ChatFormatting.GREEN
                : line.startsWith("Progress:") || line.startsWith("Karma:") ? net.minecraft.ChatFormatting.AQUA
                : line.startsWith("Reputation:") ? net.minecraft.ChatFormatting.GOLD
                : line.startsWith("Infamy:") ? net.minecraft.ChatFormatting.LIGHT_PURPLE : net.minecraft.ChatFormatting.GRAY;
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
    /** Bedrock forms understand legacy section colors; every line resets its style. */
    public static String formText(String text) {
        return java.util.Arrays.stream(text.split("\\n",-1)).map(line->{
            if(line.startsWith("§"))return line;
            String color=line.startsWith("Missing:")||line.startsWith("Unavailable:")?"§c"
                :line.startsWith("Ready:")||line.startsWith("Reward:")?"§a"
                :line.startsWith("Karma:")||line.startsWith("Progress:")?"§b"
                :line.startsWith("Reputation:")?"§6":line.startsWith("Infamy:")?"§d":"§7";
            return color+line+"§r";
        }).collect(java.util.stream.Collectors.joining("\n"));
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
            String name = cost.karma() ? "Karma (consumed toward zero)" : cost.influence() ? (cost.reputation() ? "Reputation" : "Infamy")
                : (cost.item() == net.minecraft.world.item.Items.ENCHANTED_BOOK ? "Sharpness V book" : net.minecraft.network.chat.Component.translatable(cost.item().getDescriptionId()).getString());
            return (owned >= cost.count() ? "Ready: " : "Missing: ") + name + " " + owned + "/" + cost.count();
        }).toList();
    }
    public static String projectedEffect(ServerPlayer player, boolean blessing, String id, String description) {
        int next = (blessing ? CurseBlessingService.storedStack(player, true, id) : CurseBlessingService.storedStack(player, false, id)) + 1;
        if (blessing && id.equals("protection")) return "Next stack: +" + (2 * Math.min(2,next)) + " total armor. Adds 5 minutes.";
        if (blessing && id.equals("hunter")) return "Next stack: +" + (5 * Math.min(4,next)) + "% damage against hostile mobs.";
        if (blessing && id.equals("haste")) return "Next stack: " + (next >= 3 ? "Haste II" : "Haste I") + ". Adds 3 minutes.";
        if (!blessing && id.equals("fettered")) return "Next stack: +" + (int)Math.floor(2 + 1.5 * (Math.min(4,next)-1)) + " total armor; " + (next >= 3 ? "-10%" : "-5%") + " total movement speed.\nKnockback resistance: +30%. Jump strength: -0.20.";
        if (!blessing && ("justice".equals(id) || "strength".equals(id))) {
            double damage = next * ("justice".equals(id) ? 1.0 : 0.5);
            return "After activation: +" + damage + " damage dealt and +" + damage + " damage taken.";
        }
        if(blessing && id.equals("preservation"))return "20% less durability loss after Unbreaking. Adds 15 minutes; remains one stack.";
        if(blessing && id.equals("aegis"))return "10% chance to halve damage for 1.5s. Cooldown: "+(next>=2?10:15)+"s. Shield sound on mitigation.";
        if(blessing && id.equals("unseen_hand"))return "Block reach +"+(next>=2?"0.75":"0.5")+"; entity reach +"+(next>=2?"0.5":"0.25")+". Adds 10 minutes; highest bonus wins with Hollow.";
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
