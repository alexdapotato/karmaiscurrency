package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import static akisei.karmacurrency.QuestService.QuestId;

/** Quest-specific integration. All mutations run on the server thread. */
public final class QuestExpansion {
    private QuestExpansion() {}
    private static long get(ServerPlayer p, String key) { return QuestRevamp.get(p, key); }
    private static void put(ServerPlayer p, String key, long value) { QuestRevamp.put(p, key, value); }
    public static boolean handles(QuestId q) { return q == QuestId.MERCY || q == QuestId.PILGRIMAGE || q == QuestId.HIDDEN_BLUE; }
    public static boolean stageAllows(QuestId q, int stage) {
        return q == QuestId.MERCY ? stage == 1 : q != QuestId.HIDDEN_BLUE || stage >= 2;
    }
    public static boolean blueAvailable(ServerPlayer p) {
        return QuestExpansionRules.blueAvailable(KicCycle.currentCycleId(), get(p,"blue_next_cycle"), get(p,"blue_attempt_cycle"));
    }
    public static void migrate(ServerPlayer p) {
        if (get(p,"expansion_version") != 0) return;
        put(p,"expansion_version",1);
        if (QuestService.isStoredActive(p,QuestId.MERCY) || QuestService.isStoredActive(p,QuestId.PILGRIMAGE)) {
            QuestService.cancelActive(p);
            p.sendSystemMessage(Component.literal("Mercy and Pilgrimage objectives changed. Your old quest was cancelled without spending a completion."));
        }
    }
    public static void start(ServerPlayer p, QuestId q) {
        if (q == QuestId.MERCY) put(p,"mercy_cures",0);
        if (q == QuestId.BLOOD_PRICE) put(p,"blood_duration",QuestExpansionRules.bloodPriceMillis(CommunityProgressionService.stage(p)));
        if (q == QuestId.HIDDEN_BLUE) { resetCatchCycle(p); put(p,"blue_success",0); }
    }
    private static void resetCatchCycle(ServerPlayer p) {
        long cycle = KicCycle.currentCycleId() + 1;
        if (get(p,"blue_catch_cycle") != cycle) { put(p,"blue_catch_cycle",cycle); put(p,"blue_catches",0); }
    }
    public static boolean ready(ServerPlayer p, QuestId q) {
        return q == QuestId.MERCY ? get(p,"mercy_cures") >= QuestService.activeObjective(p,q).targetCount()
            : q == QuestId.PILGRIMAGE ? get(p,"submitted") == 1 : q == QuestId.HIDDEN_BLUE && get(p,"blue_success") == 1;
    }
    public static void cured(ServerPlayer p) {
        if (!QuestService.isStoredActive(p,QuestId.MERCY) || !QuestRevamp.eligible(p,QuestId.MERCY)
                || !KarmaGameRules.enabled(p,KarmaGameRules.QUESTS_ENABLED) || !KarmaGameRules.enabled(p,KarmaGameRules.TRIALS_ENABLED)) return;
        int target = QuestService.activeObjective(p,QuestId.MERCY).targetCount();
        put(p,"mercy_cures",Math.min(target,get(p,"mercy_cures")+1));
        p.sendSystemMessage(Component.literal("Mercy: " + get(p,"mercy_cures") + "/" + target + " villagers cured."));
        if (ready(p,QuestId.MERCY)) QuestService.complete(p,QuestId.MERCY);
    }
    public static String preview(QuestId q, QuestObjectiveCatalog.Objective o) {
        if (q == QuestId.MERCY) return "+" + QuestExpansionRules.mercyKarma(o.targetCount()) + " Karma, +" + QuestExpansionRules.mercyReputation(o.targetCount()) + " Reputation.";
        if (q == QuestId.PILGRIMAGE) return "+15 Karma, +" + QuestExpansionRules.pilgrimageReputation(o.targetCount()) + " Reputation. Consumes " + o.targetCount() + " compasses bound to distinct valid shrines.";
        return "Book: success, keep the book and gain 15 alignment-signed Karma. Enchanted golden apple: failure, keep the apple, no quest currency or Karma. One outcome per cycle; success blocks the next 3 cycles.";
    }
    public static void reward(ServerPlayer p, QuestId q, QuestObjectiveCatalog.Objective o) {
        int karma = q == QuestId.MERCY ? QuestExpansionRules.mercyKarma(o.targetCount()) : 15;
        int rep = q == QuestId.MERCY ? QuestExpansionRules.mercyReputation(o.targetCount())
            : q == QuestId.PILGRIMAGE ? QuestExpansionRules.pilgrimageReputation(o.targetCount()) : 0;
        if (q == QuestId.HIDDEN_BLUE) {
            karma *= (int)get(p,"side");
            put(p,"blue_attempt_cycle",KicCycle.currentCycleId()+1);
            put(p,"blue_next_cycle",QuestExpansionRules.blueNextCycle(KicCycle.currentCycleId()));
        }
        if (rep > 0) SocialCurrencyService.addReputation(p,rep,q.displayName());
        KarmaService.addQuietly(p,KarmaService.karma(karma),q.displayName());
    }
    /** Accept only KIC compasses with a resolved, registered Overworld shrine target. */
    public static GlobalPos validShrineTarget(ServerPlayer p, ItemStack stack) {
        if (stack.isEmpty() || !ShrineCompass.isShrineCompass(stack)) return null;
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.copyTag().getBooleanOr("Predicted",false)) return null;
        var tracker = stack.get(DataComponents.LODESTONE_TRACKER);
        if (tracker == null || tracker.target().isEmpty()) return null;
        GlobalPos target = tracker.target().get();
        if (!target.dimension().equals(Level.OVERWORLD)) return null;
        var server = p.level().getServer();
        boolean registered = ShrineState.get(server).sites().stream().anyMatch(site -> site.lodestonePosition().isPresent()
            && site.actualLodestonePosition().equals(target.pos()));
        if (!registered) return null;
        var level = server.overworld();
        if (level.hasChunkAt(target.pos()) && !level.getBlockState(target.pos()).is(Blocks.LODESTONE)) return null;
        return target;
    }
    private static List<ItemStack> uniqueCompasses(ServerPlayer p) {
        LinkedHashMap<GlobalPos,ItemStack> result = new LinkedHashMap<>();
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            GlobalPos target = validShrineTarget(p,stack);
            if (target != null) result.putIfAbsent(target,stack);
        }
        return new ArrayList<>(result.values());
    }
    public static boolean submitCompasses(ServerPlayer p, boolean confirm) {
        QuestId q = QuestId.PILGRIMAGE;
        if (!QuestService.isStoredActive(p,q) || !QuestRevamp.eligible(p,q) || get(p,"debug_hold") == 1
                || !KarmaGameRules.enabled(p,KarmaGameRules.QUESTS_ENABLED) || !KarmaGameRules.enabled(p,KarmaGameRules.TRIALS_ENABLED)) return false;
        var objective = QuestService.activeObjective(p,q);
        if (!confirm) {
            put(p,"confirm_until",System.currentTimeMillis()+30_000);
            p.sendSystemMessage(Component.literal(preview(q,objective) + " Confirm within 30 seconds using /quest submit confirm."));
            return true;
        }
        if (System.currentTimeMillis() > get(p,"confirm_until")) return false;
        put(p,"confirm_until",0);
        if (!QuestRevamp.available(p,q,objective)) return false;
        List<ItemStack> valid = uniqueCompasses(p);
        if (valid.size() < objective.targetCount()) {
            p.sendSystemMessage(Component.literal("Missing distinct valid shrine compasses. Nothing was consumed.")); return false;
        }
        for (int i=0;i<objective.targetCount();i++) valid.get(i).shrink(1);
        p.getInventory().setChanged(); put(p,"submitted",1);
        return QuestService.complete(p,q);
    }
    public static String status(ServerPlayer p, QuestId q) {
        if (q == QuestId.MERCY) return "Villagers cured: " + get(p,"mercy_cures") + "/" + QuestService.activeObjective(p,q).targetCount() + ". No timer.";
        if (q == QuestId.PILGRIMAGE) return "Distinct valid shrine compasses carried: " + uniqueCompasses(p).size() + "/" + QuestService.activeObjective(p,q).targetCount() + ". Review submission to consume them.";
        if (q == QuestId.HIDDEN_BLUE) return "Ocean open-water catches: " + (get(p,"blue_catch_cycle") == KicCycle.currentCycleId()+1 ? get(p,"blue_catches") : 0) + "/75. Each qualifying catch: 2% special outcome; 75th guaranteed. Apple = failure (10% of special outcomes).";
        return "";
    }
    private static final String[] BOOKS = {"efficiency","unbreaking","looting","respiration","lure","luck_of_the_sea","loyalty","infinity","soul_speed"};
    private static final int[] LEVELS = {6,4,4,4,4,4,4,1,3};
    /** Called only for a generated fishing catch, never for empty casts or hooked entities. */
    public static ItemStack fishingCatch(FishingHook hook, ItemStack ordinary) {
        if (!(hook.getPlayerOwner() instanceof ServerPlayer p) || !QuestService.isStoredActive(p,QuestId.HIDDEN_BLUE)
                || !QuestRevamp.eligible(p,QuestId.HIDDEN_BLUE) || !blueAvailable(p) || get(p,"debug_hold") == 1
                || !stageAllows(QuestId.HIDDEN_BLUE,CommunityProgressionService.stage(p))
                || !KarmaGameRules.enabled(p,KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(p,get(p,"side") > 0 ? KarmaGameRules.TRIALS_ENABLED : KarmaGameRules.CONTRACTS_ENABLED)
                || !hook.isOpenWaterFishing() || !hook.level().getBiome(hook.blockPosition()).is(BiomeTags.IS_OCEAN)) return ordinary;
        resetCatchCycle(p);
        int catches = (int)Math.min(75,get(p,"blue_catches")+1);
        put(p,"blue_catches",catches);
        if (!QuestExpansionRules.specialCatch(catches,p.getRandom().nextInt(100))) return ordinary;
        int outcome = p.getRandom().nextInt(10);
        if (outcome == 9) {
            put(p,"blue_attempt_cycle",KicCycle.currentCycleId()+1);
            QuestService.fail(p,QuestId.HIDDEN_BLUE);
            KicHistoryService.recordEvent(p,"Hidden in the Blue failed: enchanted golden apple caught; cycle attempt spent.");
            return new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
        }
        var enchantment = p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .get(Identifier.parse("minecraft:"+BOOKS[outcome])).orElse(null);
        if (enchantment == null) return ordinary;
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.enchant(enchantment,LEVELS[outcome]);
        if (LEVELS[outcome] > enchantment.value().getMaxLevel()) OverlevelBookService.mark(book,"minecraft:"+BOOKS[outcome]);
        put(p,"blue_success",1);
        if (!QuestService.complete(p,QuestId.HIDDEN_BLUE)) { put(p,"blue_success",0); return ordinary; }
        KicHistoryService.recordEvent(p,"Hidden in the Blue succeeded: " + BOOKS[outcome] + " " + LEVELS[outcome]);
        return book;
    }
}
