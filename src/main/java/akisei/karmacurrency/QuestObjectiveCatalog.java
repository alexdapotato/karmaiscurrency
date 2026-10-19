package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Server-owned curated quest objectives. UI strings are derived from these definitions. */
public final class QuestObjectiveCatalog {
    public enum Kind { COLLECT, OFFER, KILL, TIMER, EXISTING, DUEL }

    public enum Tier {
        UNSPECIFIED(""),
        EARLY("Early Game"),
        MID("Mid Game"),
        LATE("Late Game"),
        ENDGAME("End Game");

        private final String label;

        Tier(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Requirement(Item item, int count) {}
    public record Objective(String key, String description, int difficulty, Kind kind,
                            List<Requirement> requirements, String targetId, int targetCount, Tier tier) {
        public Objective(String key, String description, int difficulty, Kind kind,
                List<Requirement> requirements, String targetId, int targetCount) {
            this(key, description, difficulty, kind, requirements, targetId, targetCount, Tier.UNSPECIFIED);
        }

        public Objective {
            difficulty = Math.max(1, difficulty);
            requirements = List.copyOf(requirements);
            targetCount = Math.max(1, targetCount);
            tier = tier == null ? Tier.UNSPECIFIED : tier;
        }

        public String displayDescription() {
            return tier == Tier.UNSPECIFIED ? description : tier.label() + ": " + description;
        }
    }

    private QuestObjectiveCatalog() {}

    public static List<Objective> objectives(QuestService.QuestId quest) {
        if (quest == null) return List.of();
        return switch (quest) {
            case ABUNDANCE -> abundance();
            case MERCY -> mercy();
            // "Ancient City center" is intentionally excluded until its center is precisely defined.
            // "Dangerous encounter" is intentionally excluded until it has a deterministic trigger.
            case COURAGE -> List.of(
                existing("end_return", "Reach the End, then return alive to a shrine in the Overworld.", 6),
                existing("ancient_city", "Enter an Ancient City, acquire an Echo Shard there, and return alive to a shrine carrying it (kept).", 5),
                existing("trial_vault", "Enter a Trial Chamber, acquire a Trial Key there, and return alive to a shrine carrying it (kept).", 6),
                existing("fortress_blaze_rod", "Enter a Nether Fortress, acquire a Blaze Rod there, and return alive to a shrine carrying it (kept).", 5));
            case OFFERING -> List.of(
                offer(Tier.EARLY, "homestead", "Offer 8 wheat, 8 carrots, 8 potatoes and 4 mixed logs", 1, item(Items.WHEAT,8), item(Items.CARROT,8), item(Items.POTATO,8),item(Items.OAK_LOG,4)),
                offer(Tier.EARLY, "workshop", "Offer 8 coal, 8 copper, 4 iron and 8 cobblestone", 2,item(Items.COAL,8),item(Items.COPPER_INGOT,8),item(Items.IRON_INGOT,4),item(Items.COBBLESTONE,8)),
                offer(Tier.MID, "colours", "Offer 8 lapis, 8 redstone, 8 amethyst and 4 gold", 3,item(Items.LAPIS_LAZULI,8),item(Items.REDSTONE,8),item(Items.AMETHYST_SHARD,8),item(Items.GOLD_INGOT,4)),
                offer(Tier.MID, "shore", "Offer 8 cod, 8 salmon, 4 prismarine crystals and 1 nautilus shell", 3,item(Items.COD,8),item(Items.SALMON,8),item(Items.PRISMARINE_CRYSTALS,4),item(Items.NAUTILUS_SHELL,1)),
                offer(Tier.LATE, "nether", "Offer 8 quartz, 4 magma cream, 2 blaze rods and 1 ghast tear", 5,item(Items.QUARTZ,8),item(Items.MAGMA_CREAM,4),item(Items.BLAZE_ROD,2),item(Items.GHAST_TEAR,1)),
                offer(Tier.ENDGAME, "relics", "Offer 2 echo shards, 2 shulker shells, 1 diamond and 4 ender pearls", 7,item(Items.ECHO_SHARD,2),item(Items.SHULKER_SHELL,2),item(Items.DIAMOND,1),item(Items.ENDER_PEARL,4)));
            case SURVIVOR -> List.of(existing("endurance", "Explore and survive during a 12-hour window. Movement unlocks rewarded online time.", 5));
            case BLOOD_PRICE -> List.of(existing("kill_period", "Make valid kills: 30 minutes at stages 0-I, 16 minutes from stage II.", 4));
            case EXECUTIONER -> List.of(
                kill("ravager", "Kill a Ravager", 5, "minecraft:ravager"),
                kill("evoker", "Kill an Evoker", 5, "minecraft:evoker"),
                kill("piglin_brute", "Kill a Piglin Brute", 4, "minecraft:piglin_brute"),
                kill("elder_guardian", "Kill an Elder Guardian", 6, "minecraft:elder_guardian"),
                kill("wither_skeleton", "Kill a Wither Skeleton", 3, "minecraft:wither_skeleton"));
            case COLLECTOR -> List.of(
                offer(Tier.EARLY,"mining", "Submit 24 coal, 16 iron and 24 copper", 2,item(Items.COAL,24),item(Items.IRON_INGOT,16),item(Items.COPPER_INGOT,24)),
                offer(Tier.EARLY,"ranch", "Submit 16 leather and 16 feathers", 2,item(Items.LEATHER,16),item(Items.FEATHER,16)),
                offer(Tier.MID,"minerals", "Submit 16 gold, 24 lapis and 24 redstone", 3,item(Items.GOLD_INGOT,16),item(Items.LAPIS_LAZULI,24),item(Items.REDSTONE,24)),
                offer(Tier.MID,"ocean", "Submit 16 prismarine crystals and 4 nautilus shells", 4,item(Items.PRISMARINE_CRYSTALS,16),item(Items.NAUTILUS_SHELL,4)),
                offer(Tier.LATE,"nether_cache", "Submit 8 blaze rods, 4 ghast tears and 16 quartz", 5,item(Items.BLAZE_ROD,8),item(Items.GHAST_TEAR,4),item(Items.QUARTZ,16)),
                offer(Tier.LATE,"deep_cache", "Submit 4 diamonds and 16 obsidian", 6,item(Items.DIAMOND,4),item(Items.OBSIDIAN,16)),
                offer(Tier.ENDGAME,"end_cache", "Submit 4 shulker shells and 16 ender pearls", 7,item(Items.SHULKER_SHELL,4),item(Items.ENDER_PEARL,16)),
                offer(Tier.ENDGAME,"ancient_cache", "Submit 4 echo shards and 1 totem", 8,item(Items.ECHO_SHARD,4),item(Items.TOTEM_OF_UNDYING,1)));
            case PILGRIMAGE -> pilgrimage();
            case HIDDEN_BLUE -> List.of(existing("ocean_treasure", "Fish in ocean open water for a special book. An enchanted golden apple ends the quest as a failure; you keep the catch.", 4));
            case CHALLENGER -> challenger();
            case WARDEN -> List.of(kill("warden", "Kill a Warden", 10, "minecraft:warden"));
            case DUEL -> List.of(new Objective("designated_duel", "Challenge a player; both players must explicitly agree. Only the designated opponent's kill counts.",
                7, Kind.DUEL, List.of(), null, 1));
            case HUNT -> List.of(existing("hunt_role", "Participate in The Hunt as the Hunted or an eligible Hunter.", 5));
        };
    }

    /** Stage 0-1 use early objectives, Stage 2 mid, Stage 3 late, and Stages 4-5 endgame. */
    public static List<Integer> availableObjectiveIndexes(QuestService.QuestId quest, int communityStage) {
        List<Objective> pool = objectives(quest);
        if (quest == QuestService.QuestId.MERCY && communityStage != 1) return List.of();
        if (quest == QuestService.QuestId.HIDDEN_BLUE && communityStage < 2) return List.of();
        if (quest == QuestService.QuestId.PILGRIMAGE) return List.of(QuestExpansionRules.pilgrimageCount(communityStage)-1);
        Tier maximumTier = communityStage <= 1 ? Tier.EARLY
            : communityStage == 2 ? Tier.MID
            : communityStage == 3 ? Tier.LATE : Tier.ENDGAME;
        List<Integer> indexes = new ArrayList<>();
        boolean tieredQuest = true;
        for (int i = 0; i < pool.size(); i++) {
            Objective objective = pool.get(i);
            if (!tieredQuest || (!objective.key().startsWith("legacy_")
                    && objective.tier().ordinal() <= maximumTier.ordinal())) {
                if (objective.kind() != Kind.OFFER || offeringMeetsMinimum(objective)) indexes.add(i);
            }
        }
        return List.copyOf(indexes);
    }

    /** Fixed net payouts belong to quest completion, never ordinary offerings. */
    public static int fixedOfferingNet(Objective objective) {
        if (objective.kind() != Kind.OFFER) return -1;
        double gross = objective.requirements().stream().mapToDouble(r -> questValue(r.item()) * r.count()).sum();
        return (int) Math.floor(gross * .95);
    }

    private static boolean offeringMeetsMinimum(Objective objective) {
        return objective.requirements().stream().allMatch(r -> questValue(r.item()) > 0);
    }


    public static double questValue(Item item) {
        double configured = KarmaOfferingConfig.socialPerItem(item);
        if (configured > 0) return configured;
        String id = BuiltInRegistries.ITEM.getKey(item).getPath();
        return switch (id) {
            case "diamond" -> 8; case "echo_shard", "shulker_shell" -> 12;
            case "totem_of_undying" -> 40; case "nautilus_shell", "ghast_tear" -> 4;
            case "blaze_rod", "obsidian" -> 2; default -> 0.5;
        };
    }
    private static List<Objective> abundance() {
        List<Objective> out = new ArrayList<>();
        String[] crops = {"wheat","carrot","potato","beetroot","melon_slice","pumpkin","sugar_cane","bamboo","cactus","kelp","sweet_berries","glow_berries","cocoa_beans","nether_wart"};
        for (String crop : crops) for (int n : new int[]{32,64,128}) out.add(offer(Tier.EARLY, crop+"_"+n,"Submit "+n+" "+crop.replace('_',' '),n/32,item(item("minecraft:"+crop),n)));
        for (String resource : new String[]{"iron_ingot","gold_ingot","copper_ingot","coal","redstone","lapis_lazuli","amethyst_shard","quartz"})
            for (int n : new int[]{16,32,48}) out.add(offer(Tier.MID,resource+"_"+n,"Submit "+n+" "+resource.replace('_',' '),2+n/16,item(item("minecraft:"+resource),n)));
        for(int n : new int[]{4,8,16}) out.add(offer(Tier.LATE,"diamond_"+n,"Submit "+n+" diamonds",6+n/8,item(Items.DIAMOND,n)));
        for(String fish : new String[]{"cod","salmon","tropical_fish","pufferfish"}) for(int n : new int[]{8,16,32}) out.add(offer(Tier.EARLY,fish+"_"+n,"Submit "+n+" "+fish.replace('_',' '),1+n/8,item(item("minecraft:"+fish),n)));
        out.add(offer(Tier.EARLY,"mixed_logs","Submit 32 mixed logs, wood, stems or hyphae",2,item(Items.OAK_LOG,32)));
        return List.copyOf(out);
    }
    private static List<Objective> mercy() {
        List<Objective> out = new ArrayList<>();
        for (int n=1;n<=4;n++) out.add(new Objective("cure_"+n,"Cure "+n+" Zombie Villager(s). No timer.",n<=2?1:2,Kind.EXISTING,List.of(),null,n));
        return List.copyOf(out);
    }
    private static List<Objective> pilgrimage() {
        List<Objective> out = new ArrayList<>();
        for (int n=1;n<=5;n++) out.add(new Objective("compasses_"+n,"Submit "+n+" compasses bound to different valid shrines (consumed).",n,Kind.EXISTING,List.of(),null,n));
        return List.copyOf(out);
    }
    private static List<Objective> challenger() {
        List<Objective> out = new ArrayList<>();
        for(int n=4;n<=18;n+=2) out.add(new Objective("spawners_"+n,"Complete "+n+" distinct Trial Spawners as a registered participant within 50 blocks", n/2, Kind.EXISTING,List.of(),null,n));
        return List.copyOf(out);
    }

    private static Requirement item(Item item, int count) { return new Requirement(item, count); }
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.PAPER);
    }
    private static Objective collect(String key, String text, int difficulty, Requirement... requirements) {
        return new Objective(key, text, difficulty, Kind.COLLECT, List.of(requirements), null, 1);
    }
    private static Objective collect(Tier tier, String key, String text, int difficulty, Requirement... requirements) {
        return new Objective(key, text, difficulty, Kind.COLLECT, List.of(requirements), null, 1, tier);
    }
    private static Objective offer(Tier tier, String key, String text, int difficulty, Requirement... requirements) {
        return new Objective(key, text, difficulty, Kind.OFFER, List.of(requirements), null, 1, tier);
    }
    private static Objective kill(String key, String text, int difficulty, String targetId) {
        return new Objective(key, text, difficulty, Kind.KILL, List.of(), targetId, 1);
    }
    private static Objective existing(String key, String text, int difficulty) {
        return new Objective(key, text, difficulty, Kind.EXISTING, List.of(), null, 1);
    }
}
