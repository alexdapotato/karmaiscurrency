package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Stable persistence IDs and authoritative content definitions for KIC sigils. */
public final class CurseBlessingRegistry {
    public enum SigilClassification {
        MAJOR("Major"), MINOR("Minor");
        private final String displayName;
        SigilClassification(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    public enum SigilDuration { TEMPORARY, PERMANENT }

    public record ItemCost(Item item, int count, boolean influence, boolean reputation) {
        public ItemCost(Item item, int count) { this(item, count, false, false); }
        public static ItemCost influence(int count) { return new ItemCost(Items.ECHO_SHARD, count, true, false); }
        public static ItemCost reputation(int count) { return new ItemCost(Items.EMERALD, count, true, true); }
        public boolean matches(ItemStack stack) {
            if (item == Items.ENCHANTED_BOOK) return stack.is(Items.ENCHANTED_BOOK) && stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).entrySet().stream().anyMatch(e -> e.getKey().is(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS) && e.getIntValue() >= 5);
            return influence ? SocialTokenService.isToken(stack)
                : stack.is(item) && !SocialTokenService.isToken(stack);
        }
        public ItemCost { count = Math.max(1, count); }
        public String displayName() { return count + " " + (influence ? (reputation ? "Reputation" : "Infamy") : (item == Items.ENCHANTED_BOOK ? "Sharpness V book" : new ItemStack(item).getHoverName().getString())); }
    }

    public record CurseDefinition(int storageId, String id, String name, int maxStacks,
                                  SigilClassification classification, Item displayItem, List<String> lore,
                                  String effectDescription, SigilDuration duration, int durationMinutes,
                                  List<String> requirements, List<ItemCost> cost, int unlockStage,
                                  boolean rotationEligible, int rotationWeight) {}
    public record BlessingDefinition(int storageId, String id, String name, int maxStacks,
                                     SigilClassification classification, Item displayItem, List<String> lore,
                                     String effectDescription, SigilDuration duration, int durationMinutes,
                                     List<String> requirements, List<ItemCost> cost, int unlockStage,
                                     boolean rotationEligible, int rotationWeight) {}

    private static final Map<String, CurseDefinition> CURSES = new LinkedHashMap<>();
    private static final Map<String, BlessingDefinition> BLESSINGS = new LinkedHashMap<>();
    private static final Map<Integer, CurseDefinition> CURSES_BY_STORAGE_ID = new LinkedHashMap<>();
    private static final Map<Integer, BlessingDefinition> BLESSINGS_BY_STORAGE_ID = new LinkedHashMap<>();
    private static final List<String> LIVE_BLESSINGS = List.of(
        "protection", "depth", "devotion", "hunter", "fortune", "haste", "aegis");
    private static final List<String> LIVE_CURSES = List.of(
        "predator", "justice", "devil", "hanged_man", "temperance", "hollow", "insomnia", "executioner", "duelist", "fettered");

    static {
        // Legacy IDs remain registered so existing player saves continue to load.
        registerCurse(1, "warrior", "Warrior", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(2, "archer", "Archer", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(3, "shield", "Shield", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(4, "scapegoat", "Scapegoat", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(5, "fool", "The Fool", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(6, "high_priestess", "The High Priestess", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(7, "hierophant", "The Hierophant", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(8, "strength", "Strength", 3, SigilClassification.MINOR, List.of(), false);
        registerCurse(9, "justice", "Justice", 2, SigilClassification.MAJOR,
            List.of(ItemCost.influence(75), new ItemCost(Items.IRON_INGOT, 64)), true);
        registerCurse(10, "hanged_man", "The Hanged Man", 1, SigilClassification.MAJOR,
            List.of(ItemCost.influence(40), new ItemCost(Items.LEAD, 10)), true);
        registerCurse(11, "temperance", "Temperance", 1, SigilClassification.MAJOR,
            List.of(ItemCost.influence(40), new ItemCost(Items.IRON_INGOT, 20)), true);
        registerCurse(12, "star", "The Star", 1, SigilClassification.MAJOR, List.of(), false);
        registerCurse(13, "moon", "The Moon", 1, SigilClassification.MAJOR, List.of(), false);
        registerCurse(14, "lovers", "The Lovers", 1, SigilClassification.MAJOR, List.of(), false);
        registerCurse(15, "devil", "The Devil", 1, SigilClassification.MAJOR,
            List.of(new ItemCost(Items.NETHERITE_INGOT, 3)), true);
        registerCurse(16, "berserker", "Berserker", 1, SigilClassification.MAJOR, List.of(), false);
        registerCurse(17, "parasite", "Parasite", 1, SigilClassification.MAJOR, List.of(), false);
        registerCurse(18, "hollow", "Hollow", 1, SigilClassification.MAJOR,
            List.of(ItemCost.influence(60), new ItemCost(Items.ECHO_SHARD, 5)), true);
        registerCurse(19, "gambler", "Gambler", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(20, "miser", "Miser", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(21, "insomnia", "Insomnia", 1, SigilClassification.MINOR,
            List.of(ItemCost.influence(60), new ItemCost(Items.FEATHER, 80), new ItemCost(Items.DIAMOND, 20)), true);
        registerCurse(22, "predator", "Predator", 1, SigilClassification.MAJOR,
            List.of(ItemCost.influence(100), new ItemCost(Items.DIAMOND, 16)), true);
        registerCurse(23, "bard", "Curse of the Bard", 1, SigilClassification.MINOR, List.of(), false);
        registerCurse(24, "sun", "The Sun", 1, SigilClassification.MAJOR, List.of(), false);
        registerCurse(25, "executioner", "Executioner", 1, SigilClassification.MAJOR,
            List.of(ItemCost.influence(100), new ItemCost(Items.ENCHANTED_BOOK, 1)), true);
        registerCurse(26, "duelist", "Duelist", 1, SigilClassification.MAJOR,
            List.of(ItemCost.influence(80), new ItemCost(Items.DIAMOND_SWORD, 2), new ItemCost(Items.DIAMOND, 20)), true);
        registerCurse(27, "fettered", "Fettered", 4, SigilClassification.MAJOR,
            List.of(ItemCost.influence(60), new ItemCost(Items.OBSIDIAN, 32)), true);

        registerBlessing(1, "watcher", "Watcher", 8, SigilClassification.MINOR,
            List.of(new ItemCost(Items.EMERALD, 32)), false);
        registerBlessing(2, "smite", "Smite", 8, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(3, "devotion", "Devotion", 1, SigilClassification.MAJOR,
            List.of(new ItemCost(Items.NETHERITE_INGOT, 3)), true);
        registerBlessing(4, "swiftness", "Swiftness", 1, SigilClassification.MINOR,
            List.of(new ItemCost(Items.EMERALD, 24)), false);
        registerBlessing(5, "giants", "Giants", 1, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(6, "fortune", "Fortune", 1, SigilClassification.MINOR,
            List.of(new ItemCost(Items.EMERALD, 32)), true);
        registerBlessing(7, "miner", "Miner", 1, SigilClassification.MINOR, List.of(), false);
        registerBlessing(8, "deep", "Blessing of the Deep", 1, SigilClassification.MINOR, List.of(), false);
        registerBlessing(9, "mountain", "Mountain", 1, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(10, "night", "Blessing of the Night", 1, SigilClassification.MINOR, List.of(), false);
        registerBlessing(11, "smith", "Blessing of the Smith", 1, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(12, "vanguard", "Blessing of the Vanguard", 1, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(13, "protection", "Protection", 2, SigilClassification.MAJOR,
            List.of(ItemCost.reputation(60)), true);
        registerBlessing(14, "abundance", "Abundance", 1, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(15, "mender", "Mender", 1, SigilClassification.MINOR, List.of(), false);
        registerBlessing(16, "depth", "Depth", 1, SigilClassification.MINOR,
            List.of(new ItemCost(Items.EMERALD, 24), new ItemCost(Items.PRISMARINE_CRYSTALS, 8)), true);
        registerBlessing(17, "prosperity", "Prosperity", 1, SigilClassification.MAJOR, List.of(), false);
        registerBlessing(18, "hunter", "Hunter", 2, SigilClassification.MAJOR,
            List.of(ItemCost.reputation(150), new ItemCost(Items.DIAMOND, 8)), true);
        registerBlessing(19, "haste", "Haste", 3, SigilClassification.MINOR,
            List.of(ItemCost.reputation(60)), true);
        registerBlessing(20, "harvest", "Harvest", 1, SigilClassification.MINOR,
            List.of(new ItemCost(Items.WHEAT, 16), new ItemCost(Items.CARROT, 16), new ItemCost(Items.POTATO, 16)), false);
        registerBlessing(21, "aegis", "Aegis", 1, SigilClassification.MAJOR,
            List.of(new ItemCost(Items.EMERALD, 64), new ItemCost(Items.DIAMOND, 8)), true);
    }

    private CurseBlessingRegistry() {}

    public static void registerCurse(int storageId, String id, String name, int maxStacks) {
        registerCurse(storageId, id, name, maxStacks, SigilClassification.MINOR, List.of(), false);
    }

    public static void registerCurse(int storageId, String id, String name, int maxStacks,
                                     SigilClassification classification) {
        registerCurse(storageId, id, name, maxStacks, classification, List.of(), false);
    }

    private static void registerCurse(int storageId, String id, String name, int maxStacks,
                                      SigilClassification classification, List<ItemCost> cost, boolean rotationEligible) {
        registerCurse(storageId, id, name, maxStacks, classification, cost, 0, rotationEligible, 1);
    }

    public static void registerCurse(int storageId, String id, String name, int maxStacks,
                                     SigilClassification classification, List<ItemCost> cost,
                                     int unlockStage, boolean rotationEligible, int rotationWeight) {
        requireId(storageId, CURSES_BY_STORAGE_ID, "Curse");
        int minutes = 0;
        List<String> requirements = requirements(id.equals("devil") ? "Requires -300 Karma or lower (not consumed)." : "Dark Shrine access required.", cost);
        CurseDefinition definition = new CurseDefinition(storageId, id, name, Math.max(1, maxStacks), classification,
            curseIcon(id), List.of(classification.displayName(), "Curse Sigil"), curseEffectDescription(id),
            SigilDuration.PERMANENT, minutes, requirements, List.copyOf(cost), Math.max(0, unlockStage),
            rotationEligible, Math.max(1, rotationWeight));
        CURSES.put(id, definition);
        CURSES_BY_STORAGE_ID.put(storageId, definition);
    }

    public static void registerBlessing(int storageId, String id, String name, int maxStacks) {
        registerBlessing(storageId, id, name, maxStacks, SigilClassification.MINOR);
    }

    public static void registerBlessing(int storageId, String id, String name, int maxStacks,
                                        SigilClassification classification) {
        registerBlessing(storageId, id, name, maxStacks, classification, List.of(), false);
    }

    private static void registerBlessing(int storageId, String id, String name, int maxStacks,
                                         SigilClassification classification, List<ItemCost> cost, boolean rotationEligible) {
        registerBlessing(storageId, id, name, maxStacks, classification, cost, 0, rotationEligible, 1);
    }

    public static void registerBlessing(int storageId, String id, String name, int maxStacks,
                                        SigilClassification classification, List<ItemCost> cost,
                                        int unlockStage, boolean rotationEligible, int rotationWeight) {
        requireId(storageId, BLESSINGS_BY_STORAGE_ID, "Blessing");
        boolean devotion = id.equals("devotion");
        boolean timed = List.of("watcher", "swiftness", "protection", "depth", "fortune", "haste").contains(id);
        int minutes = timed ? (id.equals("haste") ? 3 : 5) : 0;
        List<String> requirements = requirements(devotion ? "Requires 300 Karma (not consumed). Permanent Light commitment."
            : "Light Shrine access required.", cost);
        BlessingDefinition definition = new BlessingDefinition(storageId, id, name, Math.max(1, maxStacks), classification,
            blessingIcon(id), List.of(classification.displayName(), "Blessing Sigil"), blessingEffectDescription(id),
            timed ? SigilDuration.TEMPORARY : SigilDuration.PERMANENT, minutes, requirements, List.copyOf(cost),
            Math.max(0, unlockStage), rotationEligible, Math.max(1, rotationWeight));
        BLESSINGS.put(id, definition);
        BLESSINGS_BY_STORAGE_ID.put(storageId, definition);
    }

    private static void requireId(int storageId, Map<Integer, ?> map, String type) {
        if (storageId <= 0) throw new IllegalArgumentException(type + " storage ID must be positive: " + storageId);
        if (map.containsKey(storageId)) throw new IllegalArgumentException("Duplicate " + type.toLowerCase() + " storage ID: " + storageId);
    }

    private static List<String> requirements(String access, List<ItemCost> cost) {
        List<String> values = new ArrayList<>();
        values.add(access);
        if (!cost.isEmpty()) values.add("Cost: " + cost.stream().map(ItemCost::displayName).reduce((a, b) -> a + " + " + b).orElse(""));
        return List.copyOf(values);
    }

    public static String costDescription(List<ItemCost> cost) {
        return cost.isEmpty() ? "No material cost configured." : cost.stream().map(ItemCost::displayName)
            .reduce((a, b) -> a + " + " + b).orElse("No material cost configured.");
    }

    private static String curseEffectDescription(String id) {
        return switch (id) {
            case "justice" -> "+1 damage dealt and +1 damage taken per stack. Maximum 2 stacks.";
            case "predator" -> "+20% damage against hostile mobs; take 15% more damage from players.";
            case "hanged_man" -> "+40% knockback resistance, -10% movement speed, -15% jump strength, +15% fall damage.";
            case "temperance" -> "+3 Defense and -2 Attack Damage.";
            case "devil" -> "Dark commitment. Cannot use Light Shrines, removes Blessings, and grants +2 shared stack slots (individual caps unchanged). The commitment uses one Curse slot and one stack slot.";
            case "strength" -> "+0.5 damage dealt and +0.5 damage taken per stack. Maximum 3 stacks (legacy).";
            case "hollow" -> "+7.5% movement speed, +1 block and entity interaction range, -4 HP.";
            case "insomnia" -> "+15% movement speed normally, +5% while in combat for 2 minutes after taking damage, and -2 Attack Damage.";
            case "executioner" -> "+25% damage against targets below 40% HP; -20% against targets above 50% HP.";
            case "duelist" -> "+25% player damage, -30% PvE damage, -3 Defense.";
            case "fettered" -> "Armor by stack: +2 / +3 / +5 / +6. Movement: -5% / -5% / -10% / -10%. +30% knockback resistance; jump strength -0.20.";
            case "bard" -> "Goat Horns buff nearby players and inflict a drawback on the Bard.";
            case "sun" -> "Day and night effects follow the existing Sun behavior.";
            case "fool" -> "-0.5 Luck, +20% speed, +1.5 block interaction range, and increased fall damage.";
            default -> "Gameplay effect is implemented for this Curse.";
        };
    }

    private static String blessingEffectDescription(String id) {
        return switch (id) {
            case "watcher" -> "5 minutes of Night Vision.";
            case "swiftness" -> "+10% movement speed for 5 minutes.";
            case "protection" -> "+2 armor per stack; 5 minutes added per activation. Maximum 2 stacks.";
            case "depth" -> "Water Breathing and Dolphin's Grace for 5 minutes.";
            case "devotion" -> "Permanent Light commitment; prevents Dark Shrine access and allows 3 Blessing types and no Curses. The commitment uses one Blessing slot and one stack slot.";
            case "hunter" -> "+5% damage against hostile mobs per stack; maximum +10%.";
            case "fortune" -> "+1 Fortune level for applicable block drops.";
            case "haste" -> "3 minutes added per stack. Haste I at stacks 1–2; Haste II at stack 3.";
            case "harvest" -> "Increases crop output.";
            case "aegis" -> "10% chance to reduce an incoming hit by 50%; 15-second proc cooldown.";
            default -> "Gameplay effect is not part of the current live pool.";
        };
    }

    private static Item curseIcon(String id) {
        return switch (id) {
            case "justice" -> Items.IRON_SWORD;
            case "devil", "executioner", "fettered" -> Items.WITHER_SKELETON_SKULL;
            case "hanged_man" -> Items.LEAD;
            case "temperance" -> Items.IRON_INGOT;
            case "hollow" -> Items.ECHO_SHARD;
            case "insomnia" -> Items.PHANTOM_MEMBRANE;
            case "predator" -> Items.IRON_SWORD;
            case "duelist" -> Items.DIAMOND_SWORD;
            default -> Items.WITHER_ROSE;
        };
    }

    private static Item blessingIcon(String id) {
        return switch (id) {
            case "watcher" -> Items.ENDER_EYE;
            case "smite" -> Items.NETHER_STAR;
            case "devotion" -> Items.NETHERITE_INGOT;
            case "swiftness" -> Items.SUGAR;
            case "giants" -> Items.POTION;
            case "fortune" -> Items.GOLDEN_PICKAXE;
            case "miner" -> Items.DIAMOND_PICKAXE;
            case "deep", "depth" -> Items.HEART_OF_THE_SEA;
            case "mountain" -> Items.STONE;
            case "night" -> Items.PHANTOM_MEMBRANE;
            case "smith", "mender" -> Items.ANVIL;
            case "vanguard" -> Items.SHIELD;
            case "protection" -> Items.IRON_CHESTPLATE;
            case "abundance", "harvest" -> Items.CHEST;
            case "hunter" -> Items.BOW;
            case "haste" -> Items.GOLDEN_PICKAXE;
            case "aegis" -> Items.SHIELD;
            default -> Items.EMERALD;
        };
    }

    public static List<CurseDefinition> allLiveCurses() {
        return LIVE_CURSES.stream().map(CURSES::get).filter(Objects::nonNull).toList();
    }

    public static List<BlessingDefinition> allLiveBlessings() {
        return LIVE_BLESSINGS.stream().map(BLESSINGS::get).filter(Objects::nonNull).toList();
    }

    public static List<CurseDefinition> liveCurses() { return liveCurses(0); }
    public static List<CurseDefinition> liveCurses(int stage) {
        List<CurseDefinition> eligible = allLiveCurses().stream()
            .filter(CurseDefinition::rotationEligible).filter(value -> value.unlockStage() <= stage).toList();
        return KicCycle.cursePool(eligible, KicCycle.currentCycleId(), CurseDefinition::id, CurseDefinition::rotationWeight);
    }

    public static List<BlessingDefinition> liveBlessings() { return liveBlessings(0); }
    public static List<BlessingDefinition> liveBlessings(int stage) {
        List<BlessingDefinition> eligible = allLiveBlessings().stream()
            .filter(BlessingDefinition::rotationEligible).filter(value -> value.unlockStage() <= stage).toList();
        return KicCycle.blessingPool(eligible, KicCycle.currentCycleId(),
            BlessingDefinition::id, BlessingDefinition::rotationWeight);
    }

    public static boolean isCurseAvailable(String id) { return isCurseAvailable(id, 0); }
    public static boolean isCurseAvailable(String id, int stage) {
        return liveCurses(stage).stream().anyMatch(value -> value.id().equals(id));
    }

    public static boolean isBlessingAvailable(String id) { return isBlessingAvailable(id, 0); }
    public static boolean isBlessingAvailable(String id, int stage) {
        return liveBlessings(stage).stream().anyMatch(value -> value.id().equals(id));
    }

    public static boolean isLive(String id) { return isCurseAvailable(id) || isBlessingAvailable(id); }
    public static CurseDefinition curse(String id) { return CURSES.get(id); }
    public static CurseDefinition curse(int storageId) { return CURSES_BY_STORAGE_ID.get(storageId); }
    public static BlessingDefinition blessing(String id) { return BLESSINGS.get(id); }
    public static BlessingDefinition blessing(int storageId) { return BLESSINGS_BY_STORAGE_ID.get(storageId); }
    public static List<CurseDefinition> curses() { return List.copyOf(CURSES.values()); }
    public static List<BlessingDefinition> blessings() { return List.copyOf(BLESSINGS.values()); }
    public static int curseIndex(String id) { CurseDefinition d = CURSES.get(id); return d == null ? 0 : d.storageId(); }
    public static int blessingIndex(String id) { BlessingDefinition d = BLESSINGS.get(id); return d == null ? 0 : d.storageId(); }
    public static String curseId(int storageId) { CurseDefinition d = CURSES_BY_STORAGE_ID.get(storageId); return d == null ? null : d.id(); }
    public static String blessingId(int storageId) { BlessingDefinition d = BLESSINGS_BY_STORAGE_ID.get(storageId); return d == null ? null : d.id(); }
}
