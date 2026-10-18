package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent state and rules for the new curse/blessing system.
 *
 * Curse and blessing definitions live in CurseBlessingRegistry. This class is
 * owns persistent state/slot rules and the currently finalized Curse effects.
 * Effects are applied as transient server-side attribute modifiers so they can
 * be reconstructed safely after player join/respawn without changing persistence.
 */
public final class CurseBlessingService {
    public enum Commitment {
        NONE(0),
        DEVOTION(1),
        DEVIL(-1);

        private final int value;
        Commitment(int value) { this.value = value; }
        public int value() { return value; }

        public static Commitment fromValue(int value) {
            return switch (value) {
                case 1 -> DEVOTION;
                case -1 -> DEVIL;
                default -> NONE;
            };
        }
    }

    /** Shared stack budget; unique Curses and Blessings have independent limits. */
    public static final int BASE_SIGIL_SLOTS = 3;
    public static final int BASE_CURSE_SLOTS = 2;
    public static final int BASE_BLESSING_SLOTS = 2;

    private static final String COMMITMENT_KEY = "curse_blessing_commitment";
    private static final String MIGRATED_KEY = "sigil_pool_migrated_v1";
    private static final String SIGIL_SLOT_PREFIX = "sigil_slot_";
    private static final String SIGIL_STACK_PREFIX = "sigil_stack_";
    private static final String SIGIL_UNTIL_PREFIX = "sigil_until_";
    private static final int MAX_SIGIL_STORAGE_SLOTS = 16;
    // Legacy storage keys are read once and migrated without discarding overflow.
    private static final String CURSE_SLOT_PREFIX = "curse_slot_";
    private static final String CURSE_STACK_PREFIX = "curse_stack_";
    private static final String BLESSING_SLOT_PREFIX = "blessing_slot_";
    private static final String BLESSING_STACK_PREFIX = "blessing_stack_";
    private static final String BLESSING_UNTIL_PREFIX = "blessing_until_";
    private static final String BLESSING_CD_PREFIX = "blessing_cd_";
    private static final Identifier SWIFTNESS_MOVEMENT_SPEED = KarmaIsCurrency.id("blessing_swiftness_movement_speed");
    private static final Identifier DEVOTION_MOVEMENT_SPEED = KarmaIsCurrency.id("commitment_devotion_movement_speed");
    private static final Identifier DEVOTION_ATTACK_DAMAGE = KarmaIsCurrency.id("commitment_devotion_attack_damage");
    private static final Identifier DEVOTION_MAX_HEALTH = KarmaIsCurrency.id("commitment_devotion_max_health");
    private static final Identifier DEVOTION_ARMOR = KarmaIsCurrency.id("commitment_devotion_armor");
    private static final Identifier DEVIL_MAX_HEALTH = KarmaIsCurrency.id("commitment_devil_max_health");

    // Balance baseline for the currently finalized numeric Curses.
    private static final Identifier WARRIOR_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_warrior_attack_damage");
    private static final Identifier ARCHER_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_archer_movement_speed");
    private static final Identifier ARCHER_MAX_HEALTH = KarmaIsCurrency.id("curse_archer_max_health");
    private static final Identifier SHIELD_ARMOR = KarmaIsCurrency.id("curse_shield_armor");
    private static final Identifier SHIELD_MAX_HEALTH = KarmaIsCurrency.id("curse_shield_max_health");
    private static final Identifier SHIELD_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_shield_movement_speed");
    private static final Identifier SCAPEGOAT_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_scapegoat_movement_speed");
    private static final Identifier SCAPEGOAT_ARMOR = KarmaIsCurrency.id("curse_scapegoat_armor");
    private static final Identifier SCAPEGOAT_MAX_HEALTH = KarmaIsCurrency.id("curse_scapegoat_max_health");
    private static final Identifier SCAPEGOAT_JUMP_STRENGTH = KarmaIsCurrency.id("curse_scapegoat_jump_strength");
    private static final Identifier FOOL_LUCK = KarmaIsCurrency.id("curse_fool_luck");
    private static final Identifier FOOL_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_fool_movement_speed");
    private static final Identifier FOOL_BLOCK_INTERACTION_RANGE = KarmaIsCurrency.id("curse_fool_block_interaction_range");
    private static final Identifier HIGH_PRIESTESS_TEMPT_RANGE = KarmaIsCurrency.id("curse_high_priestess_tempt_range");
    private static final Identifier HIGH_PRIESTESS_SNEAKING_SPEED = KarmaIsCurrency.id("curse_high_priestess_sneaking_speed");
    private static final Identifier HIGH_PRIESTESS_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_high_priestess_movement_speed");
    private static final Identifier HIEROPHANT_ENTITY_INTERACTION_RANGE = KarmaIsCurrency.id("curse_hierophant_entity_interaction_range");
    private static final Identifier STRENGTH_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_strength_attack_damage");
    private static final Identifier STRENGTH_MAX_HEALTH = KarmaIsCurrency.id("curse_strength_max_health");
    private static final Identifier HANGED_MAN_KNOCKBACK_RESISTANCE = KarmaIsCurrency.id("curse_hanged_man_knockback_resistance");
    private static final Identifier HANGED_MAN_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_hanged_man_movement_speed");
    private static final Identifier HANGED_MAN_JUMP_STRENGTH = KarmaIsCurrency.id("curse_hanged_man_jump_strength");
    private static final Identifier HANGED_MAN_FALL_DAMAGE = KarmaIsCurrency.id("curse_hanged_man_fall_damage");
    private static final Identifier TEMPERANCE_ARMOR = KarmaIsCurrency.id("curse_temperance_armor");
    private static final Identifier TEMPERANCE_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_temperance_attack_damage");
    private static final Identifier STAR_LUCK = KarmaIsCurrency.id("curse_star_luck");
    private static final Identifier STAR_BLOCK_INTERACTION_RANGE = KarmaIsCurrency.id("curse_star_block_interaction_range");
    private static final Identifier STAR_ENTITY_INTERACTION_RANGE = KarmaIsCurrency.id("curse_star_entity_interaction_range");
    private static final Identifier STAR_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_star_movement_speed");
    private static final Identifier MOON_LUCK = KarmaIsCurrency.id("curse_moon_luck");
    private static final Identifier MOON_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_moon_movement_speed");
    private static final Identifier MOON_ARMOR = KarmaIsCurrency.id("curse_moon_armor");
    private static final Identifier MOON_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_moon_attack_damage");
    private static final Identifier MOON_MAX_HEALTH = KarmaIsCurrency.id("curse_moon_max_health");
    private static final Identifier SUN_LUCK = KarmaIsCurrency.id("curse_sun_luck");
    private static final Identifier SUN_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_sun_movement_speed");
    private static final Identifier SUN_ARMOR = KarmaIsCurrency.id("curse_sun_armor");
    private static final Identifier SUN_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_sun_attack_damage");
    private static final Identifier SUN_MAX_HEALTH = KarmaIsCurrency.id("curse_sun_max_health");
    private static final Identifier BERSERKER_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_berserker_attack_damage");
    private static final Identifier BERSERKER_ATTACK_SPEED = KarmaIsCurrency.id("curse_berserker_attack_speed");
    private static final Identifier BERSERKER_ARMOR = KarmaIsCurrency.id("curse_berserker_armor");
    private static final Identifier BERSERKER_MAX_HEALTH = KarmaIsCurrency.id("curse_berserker_max_health");
    private static final Identifier PARASITE_MAX_HEALTH = KarmaIsCurrency.id("curse_parasite_max_health");
    private static final Identifier HOLLOW_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_hollow_movement_speed");
    private static final Identifier HOLLOW_BLOCK_INTERACTION_RANGE = KarmaIsCurrency.id("curse_hollow_block_interaction_range");
    private static final Identifier HOLLOW_ENTITY_INTERACTION_RANGE = KarmaIsCurrency.id("curse_hollow_entity_interaction_range");
    private static final Identifier HOLLOW_MAX_HEALTH = KarmaIsCurrency.id("curse_hollow_max_health");
    private static final Identifier GAMBLER_LUCK = KarmaIsCurrency.id("curse_gambler_luck");
    private static final Identifier MISER_BLOCK_BREAK_SPEED = KarmaIsCurrency.id("curse_miser_block_break_speed");
    private static final Identifier INSOMNIA_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_insomnia_movement_speed");
    private static final Identifier INSOMNIA_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_insomnia_attack_damage");
    private static final Identifier EXECUTIONER_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_executioner_attack_damage");
    private static final Identifier DUELIST_ARMOR = KarmaIsCurrency.id("curse_duelist_armor");
    private static final Identifier DUELIST_ATTACK_DAMAGE = KarmaIsCurrency.id("curse_duelist_attack_damage");
    private static final Identifier FETTERED_ARMOR = KarmaIsCurrency.id("curse_fettered_armor");
    private static final Identifier FETTERED_KNOCKBACK = KarmaIsCurrency.id("curse_fettered_knockback");
    private static final Identifier FETTERED_MOVEMENT_SPEED = KarmaIsCurrency.id("curse_fettered_movement_speed");
    private static final Identifier FETTERED_JUMP_STRENGTH = KarmaIsCurrency.id("curse_fettered_jump_strength");

    private CurseBlessingService() {}

    public static Commitment commitment(ServerPlayer player) {
        migrateLegacySigils(player);
        return Commitment.fromValue(KarmaPlayerState.getInt(player, COMMITMENT_KEY));
    }

    public static boolean isDevoted(ServerPlayer player) {
        return commitment(player) == Commitment.DEVOTION;
    }

    public static boolean isDevilBound(ServerPlayer player) {
        return commitment(player) == Commitment.DEVIL;
    }

    public static boolean canUseLightShrine(ServerPlayer player) {
        return !isDevilBound(player);
    }

    public static boolean canUseDarkShrine(ServerPlayer player) {
        return !isDevoted(player);
    }

    public static int sigilSlotLimit(ServerPlayer player) {
        return stackSlotLimit(CommunityProgressionService.stage(player)) + (isDevilBound(player) ? 2 : 0);
    }

    static int stackSlotLimit(int worldStage) {
        return BASE_SIGIL_SLOTS + Math.max(0, Math.min(CommunityProgressionService.MAX_STAGE, worldStage));
    }

    static int typeSlotLimit(boolean blessing, Commitment commitment) {
        return commitment==Commitment.NONE?2:(blessing==(commitment==Commitment.DEVOTION)?3:0);
    }

    public static int curseSlotLimit(ServerPlayer player) { return alignedLimit(player,false,commitment(player)); }
    public static int blessingSlotLimit(ServerPlayer player) { return alignedLimit(player,true,commitment(player)); }


    private static int alignedLimit(ServerPlayer p,boolean blessing,Commitment target) {
        boolean neutral=SocialCurrencyService.getStance(p)==SocialCurrencyService.Stance.NEUTRAL;
        if(target!=Commitment.NONE)return blessing==(target==Commitment.DEVOTION)?(neutral?1:3):0;
        if(neutral)return 1;
        return blessing==(SocialCurrencyService.getStance(p)==SocialCurrencyService.Stance.LIGHT)?2:1;
    }
    private static boolean slotActive(ServerPlayer p,int slot) {
        int encoded=KarmaPlayerState.getInt(p,SIGIL_SLOT_PREFIX+slot);
        if(encoded==0)return false;
        int limit=alignedLimit(p,encoded>0,commitment(p));
        long time=KarmaPlayerState.get(p,"sigil_equipped_"+slot);
        Commitment current=commitment(p);
        boolean committedSide=current==(encoded>0?Commitment.DEVOTION:Commitment.DEVIL);
        int permanent=encoded>0?encodedBlessing(CurseBlessingRegistry.blessingIndex("devotion")):encodedCurse(CurseBlessingRegistry.curseIndex("devil"));
        if(committedSide && encoded==permanent)return limit>0;
        int preceding=committedSide?1:0;
        for(int i=1;i<=MAX_SIGIL_STORAGE_SLOTS;i++) {
            int other=KarmaPlayerState.getInt(p,SIGIL_SLOT_PREFIX+i);
            if(other==0||(other>0)!=(encoded>0)||i==slot||(committedSide&&other==permanent))continue;
            long t=KarmaPlayerState.get(p,"sigil_equipped_"+i);
            if(t<time||(t==time&&i<slot))preceding++;
        }
        return preceding<limit;
    }
    public static String sigilDetails(ServerPlayer p) {
        migrateLegacySigils(p);expireBlessings(p);
        List<String> lines=new ArrayList<>();
        for(int i=1;i<=MAX_SIGIL_STORAGE_SLOTS;i++) {
            int encoded=KarmaPlayerState.getInt(p,SIGIL_SLOT_PREFIX+i);if(encoded==0)continue;
            String id=encoded>0?CurseBlessingRegistry.blessingId(encoded):CurseBlessingRegistry.curseId(-encoded);
            lines.add((encoded>0?"Blessing ":"Curse ")+id+" x"+KarmaPlayerState.getInt(p,SIGIL_STACK_PREFIX+i)
                +(slotActive(p,i)?" [active]":" [inactive: alignment/commitment slot limit]")
                +(encoded>0 ? " — " + (CurseBlessingRegistry.blessing(id).duration()==CurseBlessingRegistry.SigilDuration.PERMANENT ? "Permanent" : KicUiText.durationSeconds(blessingMinutesRemaining(p,id)*60L)+" remaining") : " — Permanent"));
        }
        if(virtualCommitment(p,true))lines.add("Blessing devotion x1 [permanent, active]");
        if(virtualCommitment(p,false))lines.add("Curse devil x1 [permanent, active]");
        return lines.isEmpty()?"No equipped sigils.":String.join("\n",lines);
    }

    /** Count every stack, including commitments represented without a stored slot. */
    private static int stackCount(ServerPlayer player, Boolean curse) {
        migrateLegacySigils(player);
        expireBlessings(player);
        int count = 0;
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            int id = KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot);
            if (id != 0 && (curse == null || (curse ? id < 0 : id > 0)))
                count += Math.max(1, KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + slot));
        }
        if ((curse == null || curse) && virtualCommitment(player, false)) count++;
        if ((curse == null || !curse) && virtualCommitment(player, true)) count++;
        return count;
    }

    private static boolean virtualCommitment(ServerPlayer player, boolean blessing) {
        Commitment current = Commitment.fromValue(KarmaPlayerState.getInt(player, COMMITMENT_KEY));
        int encoded = blessing ? encodedBlessing(CurseBlessingRegistry.blessingIndex("devotion"))
            : encodedCurse(CurseBlessingRegistry.curseIndex("devil"));
        return current == (blessing ? Commitment.DEVOTION : Commitment.DEVIL)
            && findSigilSlot(player, encoded) == 0;
    }

    static String capacityBlocker(int usedTypes, int typeLimit, int usedStacks, int stackLimit, boolean newType) {
        if (newType && usedTypes >= typeLimit) return "No free slot for another distinct Sigil of this type.";
        if (usedStacks >= stackLimit) return "No free shared Sigil stack slots.";
        return null;
    }

    private static String capacityBlocker(ServerPlayer player, boolean blessing, String id) {
        boolean becomingCommitted = (blessing && "devotion".equals(id)) || (!blessing && "devil".equals(id));
        if (becomingCommitted && commitment(player) != Commitment.NONE) return "You already have a permanent commitment.";
        Commitment target = becomingCommitted ? (blessing ? Commitment.DEVOTION : Commitment.DEVIL) : commitment(player);
        int currentStacks = storedStack(player, blessing, id);
        if(becomingCommitted && slotCount(player, blessing)>0)return "Release all opposing sigils before committing.";
        // Commitment removes the opposite type before adding its own one-stack Sigil.
        int usedStacks = becomingCommitted ? stackCount(player, !blessing) : stackCount(player, null);
        return capacityBlocker(slotCount(player, !blessing), alignedLimit(player, blessing, target),
            usedStacks, stackSlotLimit(CommunityProgressionService.stage(player)) + (target == Commitment.DEVIL ? 2 : 0), currentStacks == 0);
    }

    public static boolean canReleaseSigil(ServerPlayer player, boolean blessing, String id) {
        return id != null && !"devotion".equals(id) && !"devil".equals(id)
            && storedStack(player, blessing, id) > 0;
    }
    public static boolean releaseSigil(ServerPlayer player, boolean blessing, String id) {
        if (!canReleaseSigil(player, blessing, id)) return false;
        return removeSigil(player, id); // All stacks, no refund; commitments cannot pass this boundary.
    }
    public static String activationPreview(ServerPlayer player, boolean blessing, String id) {
        int max = blessing ? CurseBlessingRegistry.blessing(id).maxStacks() : CurseBlessingRegistry.curse(id).maxStacks();
        String blocker = activationBlocker(player, blessing, id, max);
        if (blocker != null) return "Activation unavailable: " + blocker;
        boolean committed = "devotion".equals(id) || "devil".equals(id);
        Commitment target = committed ? (blessing ? Commitment.DEVOTION : Commitment.DEVIL) : commitment(player);
        int current = storedStack(player, blessing, id);
        int curses = committed && blessing ? 0 : activeCurseSlotCount(player);
        int blessings = committed && !blessing ? 0 : activeBlessingSlotCount(player);
        if (current == 0) { if (blessing) blessings++; else curses++; }
        int stacks = (committed ? stackCount(player, !blessing) : activeSigilSlotCount(player)) + 1;
        return "After activation: " + (current + 1) + " stack(s); Curses " + curses + "/" + alignedLimit(player, false, target)
            + "; Blessings " + blessings + "/" + alignedLimit(player, true, target) + "; Stack slots " + stacks + "/" + (stackSlotLimit(CommunityProgressionService.stage(player)) + (target == Commitment.DEVIL ? 2 : 0));
    }

    public static String slotSummary(ServerPlayer player) {
        return "Curse types: " + activeCurseSlotCount(player) + "/" + curseSlotLimit(player)
            + "\nBlessing types: " + activeBlessingSlotCount(player) + "/" + blessingSlotLimit(player)
            + "\nShared stacks: " + activeSigilSlotCount(player) + "/" + sigilSlotLimit(player);
    }

    private static int encodedCurse(int storageId) { return -storageId; }
    private static int encodedBlessing(int storageId) { return storageId; }

    private static void migrateLegacySigils(ServerPlayer player) {
        if (KarmaPlayerState.getInt(player, MIGRATED_KEY) != 0) return;
        for (int oldSlot = 1; oldSlot <= 3; oldSlot++) {
            int curseId = KarmaPlayerState.getInt(player, CURSE_SLOT_PREFIX + oldSlot);
            if (curseId > 0 && CurseBlessingRegistry.curse(curseId) != null) {
                int curseStacks = Math.max(1, KarmaPlayerState.getInt(player, CURSE_STACK_PREFIX + oldSlot));
                storeMigrated(player, encodedCurse(curseId), curseStacks, 0);
            }
            int blessingId = KarmaPlayerState.getInt(player, BLESSING_SLOT_PREFIX + oldSlot);
            if (blessingId > 0 && CurseBlessingRegistry.blessing(blessingId) != null) {
                int stacks = Math.max(1, KarmaPlayerState.getInt(player, BLESSING_STACK_PREFIX + oldSlot));
                int until = KarmaPlayerState.getInt(player, BLESSING_UNTIL_PREFIX + oldSlot);
                storeMigrated(player, encodedBlessing(blessingId), stacks, until);
            }
        }
        KarmaPlayerState.setInt(player, MIGRATED_KEY, 1);
        int occupied = 0;
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            if (KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot) != 0) occupied++;
        }
        int priorCapacity = BASE_SIGIL_SLOTS
            + (Commitment.fromValue(KarmaPlayerState.getInt(player, COMMITMENT_KEY)) == Commitment.NONE ? 0 : 1);
        if (occupied > priorCapacity) {
            KarmaIsCurrency.LOGGER.warn("Migrated {} active sigils for {}; the shared capacity is {}. Existing entries were preserved.",
                occupied, player.getGameProfile().name(), priorCapacity);
            player.sendSystemMessage(Component.literal("Your previous Sigil loadout exceeded the shared capacity. Existing Sigils were preserved; remove a Sigil before adding another."));
        }
        for (int oldSlot = 1; oldSlot <= 3; oldSlot++) {
            KarmaPlayerState.clear(player, CURSE_SLOT_PREFIX + oldSlot);
            KarmaPlayerState.clear(player, CURSE_STACK_PREFIX + oldSlot);
            KarmaPlayerState.clear(player, BLESSING_SLOT_PREFIX + oldSlot);
            KarmaPlayerState.clear(player, BLESSING_STACK_PREFIX + oldSlot);
            KarmaPlayerState.clear(player, BLESSING_UNTIL_PREFIX + oldSlot);
        }
    }

    private static void storeMigrated(ServerPlayer player, int encodedId, int stacks, int until) {
        int existing = findSigilSlot(player, encodedId);
        if (existing > 0) {
            int oldStacks = KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + existing);
            int cap = maxStacks(player, encodedId);
            KarmaPlayerState.setInt(player, SIGIL_STACK_PREFIX + existing, Math.min(cap, oldStacks + stacks));
            if (until > KarmaPlayerState.getInt(player, SIGIL_UNTIL_PREFIX + existing)) {
                KarmaPlayerState.setInt(player, SIGIL_UNTIL_PREFIX + existing, until);
            }
            return;
        }
        int slot = firstEmptySigilSlot(player);
        if (slot == 0) {
            KarmaIsCurrency.LOGGER.error("Could not migrate Sigil {} for {}; shared storage is full. Legacy data is retained.",
                encodedId, player.getGameProfile().name());
            return;
        }
        KarmaPlayerState.setInt(player, SIGIL_SLOT_PREFIX + slot, encodedId);
        KarmaPlayerState.setInt(player, SIGIL_STACK_PREFIX + slot, stacks);
        if (until > 0) KarmaPlayerState.setInt(player, SIGIL_UNTIL_PREFIX + slot, until);
    }

    private static int maxStacks(ServerPlayer player, int encodedId) {
        if (encodedId < 0) {
            CurseBlessingRegistry.CurseDefinition definition = CurseBlessingRegistry.curse(-encodedId);
            if (definition == null) return 1;
            return definition.maxStacks();
        }
        CurseBlessingRegistry.BlessingDefinition definition = CurseBlessingRegistry.blessing(encodedId);
        return definition == null ? 1 : definition.maxStacks();
    }

    private static int findSigilSlot(ServerPlayer player, int encodedId) {
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            if (KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot) == encodedId) return slot;
        }
        return 0;
    }

    private static int firstEmptySigilSlot(ServerPlayer player) {
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            if (KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot) == 0) return slot;
        }
        return 0;
    }

    private static int slotCount(ServerPlayer player, Boolean curse) {
        migrateLegacySigils(player);
        expireBlessings(player);
        int count = 0;
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            int id = KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot);
            if (id == 0) continue;
            if (curse == null || (curse && id < 0) || (!curse && id > 0)) count++;
        }
        if ((curse == null || curse) && virtualCommitment(player, false)) count++;
        if ((curse == null || !curse) && virtualCommitment(player, true)) count++;
        return count;
    }

    /**
     * Makes a permanent Light/Dark commitment. The first commitment is the only
     * normal transition; Purification can later return the player to Neutral.
     */
    public static boolean commit(ServerPlayer player, Commitment target) {
        if (target == Commitment.NONE) return false;
        if (commitment(player) != Commitment.NONE) {
            player.sendSystemMessage(Component.literal("You are already bound by a permanent commitment."));
            return false;
        }

        String blocker = capacityBlocker(player, target == Commitment.DEVOTION,
            target == Commitment.DEVOTION ? "devotion" : "devil");
        if (blocker != null) {
            player.sendSystemMessage(Component.literal(blocker));
            return false;
        }
        KarmaPlayerState.setInt(player, COMMITMENT_KEY, target.value());
        if (target == Commitment.DEVOTION) {
            removeAllCurses(player);
            player.sendSystemMessage(Component.literal("Devotion accepted. Dark Shrines are now inaccessible to you."));
        } else {
            removeAllBlessings(player);
            player.sendSystemMessage(Component.literal("The Devil accepted. Light Shrines are now inaccessible to you."));
        }
        return true;
    }

    /** Clears the permanent Light/Dark commitment. */
    public static void clearCommitment(ServerPlayer player) {
        migrateLegacySigils(player);
        int devilSlot = findSigilSlot(player, encodedCurse(CurseBlessingRegistry.curseIndex("devil")));
        if (devilSlot > 0) clearSigilSlot(player, devilSlot);
        KarmaPlayerState.clear(player, COMMITMENT_KEY);
        syncCurseEffects(player);
        syncBlessingEffects(player);
    }

    /** Shared backend reset used by any future purification altar or command. */
    public static void purify(ServerPlayer player) {
        KarmaService.set(player, 0);
        removeAllCurses(player);
        removeAllBlessings(player);
        clearCommitment(player);
        for (CurseBlessingRegistry.BlessingDefinition definition : CurseBlessingRegistry.blessings()) {
            KarmaPlayerState.clear(player, BLESSING_CD_PREFIX + definition.storageId());
        }
        KarmaPlayerState.clear(player, "aegis_next_proc");
        KarmaPlayerState.clear(player, "aegis_pending_proc_until");
        KarmaPlayerState.clear(player, "curse_combat_until");
        player.sendSystemMessage(Component.literal("Purification cleared your Karma, Sigils, and permanent commitment."));
    }

    public static List<String> activeCurses(ServerPlayer player) {
        migrateLegacySigils(player);
        List<String> result = new ArrayList<>();
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            int encoded = KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot);
            String id = encoded < 0 ? CurseBlessingRegistry.curseId(-encoded) : null;
            if (id != null) result.add(id);
        }
        if (isDevilBound(player) && !result.contains("devil")) result.add("devil");
        return List.copyOf(result);
    }

    public static List<String> activeBlessings(ServerPlayer player) {
        migrateLegacySigils(player);
        List<String> result = new ArrayList<>();
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            expireBlessingIfNeeded(player, slot);
            int encoded = KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot);
            String id = encoded > 0 ? CurseBlessingRegistry.blessingId(encoded) : null;
            if (id != null) result.add(id);
        }
        if (isDevoted(player) && !result.contains("devotion")) result.add("devotion");
        return List.copyOf(result);
    }

    public static int curseStack(ServerPlayer player, String id) {
        migrateLegacySigils(player);
        int index = CurseBlessingRegistry.curseIndex(id);
        if (index <= 0) return 0;
        if ("devil".equals(id) && isDevilBound(player) && findSigilSlot(player, encodedCurse(index)) == 0) return 1;
        int slot = findSigilSlot(player, encodedCurse(index));
        return slot == 0 || !slotActive(player,slot) ? 0 : KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + slot);
    }

    public static int storedStack(ServerPlayer p,boolean blessing,String id) {
        int encoded=blessing?encodedBlessing(CurseBlessingRegistry.blessingIndex(id)):encodedCurse(CurseBlessingRegistry.curseIndex(id));
        int slot=findSigilSlot(p,encoded);
        return slot==0?0:KarmaPlayerState.getInt(p,SIGIL_STACK_PREFIX+slot);
    }
    public static boolean hasCurse(ServerPlayer player, String id) {
        return curseStack(player, id) > 0;
    }

    /** Removes a specific Sigil for debug/admin tooling without charging or refunding its cost. */
    public static boolean removeSigil(ServerPlayer player, String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        if ("devotion".equals(normalized) && isDevoted(player)) {
            clearCommitment(player);
            return true;
        }
        if ("devil".equals(normalized) && isDevilBound(player)) {
            clearCommitment(player);
            return true;
        }

        int curseIndex = CurseBlessingRegistry.curseIndex(normalized);
        if (curseIndex > 0) {
            int slot = findSigilSlot(player, encodedCurse(curseIndex));
            if (slot == 0) return false;
            clearSigilSlot(player, slot);
            syncCurseEffects(player);
            return true;
        }

        int blessingIndex = CurseBlessingRegistry.blessingIndex(normalized);
        if (blessingIndex > 0) {
            int slot = findSigilSlot(player, encodedBlessing(blessingIndex));
            if (slot == 0) return false;
            clearSigilSlot(player, slot);
            syncBlessingEffects(player);
            return true;
        }
        return false;
    }

    public static int blessingStack(ServerPlayer player, String id) {
        migrateLegacySigils(player);
        int index = CurseBlessingRegistry.blessingIndex(id);
        if (index <= 0) return 0;
        if ("devotion".equals(id) && isDevoted(player)) return 1;
        int slot = findSigilSlot(player, encodedBlessing(index));
        if (slot > 0) expireBlessingIfNeeded(player, slot);
        slot = findSigilSlot(player, encodedBlessing(index));
        return slot == 0 || !slotActive(player,slot) ? 0 : KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + slot);
    }

    public static boolean hasBlessing(ServerPlayer player, String id) {
        return blessingStack(player, id) > 0;
    }

    /** Adds one Curse stack; all stacks of the same unique Sigil share one slot. */
    public static boolean addCurse(ServerPlayer player, String id) {
        migrateLegacySigils(player);
        CurseBlessingRegistry.CurseDefinition definition = CurseBlessingRegistry.curse(id);
        if (definition == null) return false;
        String eligibility = commitmentRequirement(player, id);
        if (eligibility != null) { player.sendSystemMessage(Component.literal(eligibility)); return false; }
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
            || !KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)) {
            player.sendSystemMessage(Component.literal("Curses are disabled for this world."));
            return false;
        }
        if (!CurseBlessingRegistry.isCurseAvailable(id, CommunityProgressionService.stage(player))) {
            player.sendSystemMessage(Component.literal("That Curse is not available in the current cycle."));
            return false;
        }
        if (!canUseDarkShrine(player)) {
            player.sendSystemMessage(Component.literal("You cannot use Curses while Devoted to the Light."));
            return false;
        }
        int encoded = encodedCurse(CurseBlessingRegistry.curseIndex(id));
        int existing = findSigilSlot(player, encoded);
        boolean devilCommitment = "devil".equals(id) && !isDevilBound(player);
        if (devilCommitment && commitment(player) != Commitment.NONE) {
            player.sendSystemMessage(Component.literal("You are already bound by a permanent commitment."));
            return false;
        }
        if (existing > 0 && !devilCommitment) {
            int current = Math.max(1, KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + existing));
            if (current >= maxStacks(player, encoded)) {
                player.sendSystemMessage(Component.literal("That Curse is already at its maximum stack count."));
                return false;
            }
        }
        String capacityIssue = capacityBlocker(player, false, id);
        if (capacityIssue != null) {
            player.sendSystemMessage(Component.literal(capacityIssue));
            return false;
        }
        if (existing == 0 && firstEmptySigilSlot(player) == 0) {
            player.sendSystemMessage(Component.literal("Sigil storage is full."));
            return false;
        }
        if (!hasCosts(player, activationCosts(player, false, id))) {
            player.sendSystemMessage(Component.literal("You do not have the required items: "
                + CurseBlessingRegistry.costDescription(activationCosts(player, false, id))));
            return false;
        }
        consumeCosts(player, activationCosts(player, false, id));
        if (devilCommitment && !commit(player, Commitment.DEVIL)) return false;

        if (existing > 0 && !devilCommitment) {
            KarmaPlayerState.setInt(player, SIGIL_STACK_PREFIX + existing,
                KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + existing) + 1);
        } else {
            int slot = firstEmptySigilSlot(player);
            if (slot == 0) {
                player.sendSystemMessage(Component.literal("You have no free Sigil slots."));
                return false;
            }
            KarmaPlayerState.set(player,"sigil_equipped_"+slot,System.currentTimeMillis());
            KarmaPlayerState.setInt(player, SIGIL_SLOT_PREFIX + slot, encoded);
            KarmaPlayerState.setInt(player, SIGIL_STACK_PREFIX + slot, 1);
        }
        syncCurseEffects(player);
        return true;
    }

    public static void removeAllCurses(ServerPlayer player) {
        migrateLegacySigils(player);
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            if (KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot) < 0) clearSigilSlot(player, slot);
        }
        syncCurseEffects(player);
    }

    private static void clearSigilSlot(ServerPlayer player, int slot) {
        KarmaPlayerState.clear(player,"sigil_equipped_"+slot);
        KarmaPlayerState.clear(player, SIGIL_SLOT_PREFIX + slot);
        KarmaPlayerState.clear(player, SIGIL_STACK_PREFIX + slot);
        KarmaPlayerState.clear(player, SIGIL_UNTIL_PREFIX + slot);
    }

    public static String activationBlocker(ServerPlayer player, boolean blessing, String id, int maxStacks) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) return "Sigils are disabled for this world.";
        if ((blessing && "devotion".equals(id) || !blessing && "devil".equals(id))
                && commitment(player) != Commitment.NONE) return "You already have a permanent commitment.";
        String requirement = commitmentRequirement(player, id);
        if (requirement != null) return requirement;
        int stacks = storedStack(player, blessing, id);
        if (blessing) {
            if (!KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) return "Blessings are disabled for this world.";
            if (!CurseBlessingRegistry.isBlessingAvailable(id, CommunityProgressionService.stage(player))) return "This Blessing is not available in the current cycle.";
            if (!CurseBlessingService.canUseLightShrine(player)) return "The Devil commitment prevents Light Shrine Blessings.";
            if (CurseBlessingService.blessingOnCooldown(player, id)) {
                return "Ready in " + KicUiText.durationSeconds(CurseBlessingService.blessingCooldownRemaining(player, id) * 60L);
            }
            if (stacks >= maxStacks) return "This Blessing is already at its maximum stack count.";
        } else {
            if (!KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)) return "Curses are disabled for this world.";
            if (!CurseBlessingRegistry.isCurseAvailable(id, CommunityProgressionService.stage(player))) return "This Curse is not available in the current cycle.";
            if (!CurseBlessingService.canUseDarkShrine(player)) return "The Devotion commitment prevents Dark Shrine Curses.";
            if (stacks >= maxStacks) return "This Curse is already at its maximum stack count.";
        }
        String capacityIssue = capacityBlocker(player, blessing, id);
        if (capacityIssue != null) return capacityIssue;
        List<CurseBlessingRegistry.ItemCost> costs = activationCosts(player, blessing, id);
        if (!CurseBlessingService.hasRequiredItems(player, costs)) {
            return String.join("; ", KicUiText.costLines(player, blessing, id).stream().filter(line -> line.startsWith("Missing:")).toList());
        }
        return null;
    }

    public static String commitmentRequirement(ServerPlayer player, String id) {
        if ("devotion".equals(id) && KarmaService.get(player) < KarmaService.karma(300)) return "Requires 300 Karma (not consumed).";
        if ("devil".equals(id) && KarmaService.get(player) > KarmaService.karma(-300)) return "Requires -300 Karma or lower (not consumed).";
        return null;
    }

    public static List<CurseBlessingRegistry.ItemCost> activationCosts(ServerPlayer player, boolean blessing, String id) {
        var base = blessing ? CurseBlessingRegistry.blessing(id).cost() : CurseBlessingRegistry.curse(id).cost();
        if (!blessing) return base;
        int stacks = storedStack(player, true, id);
        if (id.equals("protection")) return List.of(CurseBlessingRegistry.ItemCost.reputation(KicBalanceRules.protectionCost(stacks)));
        if (id.equals("haste")) return List.of(CurseBlessingRegistry.ItemCost.reputation(KicBalanceRules.hasteCost(stacks)));
        if (id.equals("hunter") && stacks > 0) {
            var costs = new java.util.ArrayList<>(base);
            costs.add(new CurseBlessingRegistry.ItemCost(net.minecraft.world.item.Items.NETHERITE_INGOT, 1));
            return List.copyOf(costs);
        }
        return base;
    }

    public static int availableCost(ServerPlayer player, CurseBlessingRegistry.ItemCost cost) {
        if (cost.influence()) return cost.reputation() ? SocialCurrencyService.getReputation(player) : KarmaService.getInfamyPoints(player);
        int available = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && cost.matches(stack)) available += stack.getCount();
        }
        return available;
    }

    private static boolean hasCosts(ServerPlayer player, List<CurseBlessingRegistry.ItemCost> costs) {
        return costs.stream().allMatch(cost -> availableCost(player, cost) >= cost.count());
    }

    public static boolean hasRequiredItems(ServerPlayer player, List<CurseBlessingRegistry.ItemCost> costs) {
        return hasCosts(player, costs);
    }

    private static void consumeCosts(ServerPlayer player, List<CurseBlessingRegistry.ItemCost> costs) {
        for (CurseBlessingRegistry.ItemCost cost : costs) {
            if (cost.influence()) {
                if (cost.reputation()) SocialCurrencyService.addReputation(player, -cost.count(), "Sigil purchase");
                else KarmaService.addInfamyPoints(player, -cost.count(), "Sigil purchase");
                continue;
            }
            int remaining = cost.count();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() || !cost.matches(stack)) continue;
                int amount = Math.min(remaining, stack.getCount());
                stack.shrink(amount);
                remaining -= amount;
                if (stack.isEmpty()) player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    /** Re-applies the persistent Curse modifiers to the current player entity. */
    public static void syncCurseEffects(ServerPlayer player) {
        boolean warrior = hasCurse(player, "warrior");
        boolean archer = hasCurse(player, "archer");
        boolean shield = hasCurse(player, "shield");
        boolean scapegoat = hasCurse(player, "scapegoat");
        boolean fool = hasCurse(player, "fool");
        boolean highPriestess = hasCurse(player, "high_priestess");
        boolean hierophant = hasCurse(player, "hierophant");
        boolean strength = hasCurse(player, "strength");
        boolean justice = hasCurse(player, "justice");
        boolean hangedMan = hasCurse(player, "hanged_man");
        boolean temperance = hasCurse(player, "temperance");
        boolean star = hasCurse(player, "star");
        boolean moon = hasCurse(player, "moon");
        boolean berserker = hasCurse(player, "berserker");
        boolean parasite = hasCurse(player, "parasite");
        boolean hollow = hasCurse(player, "hollow");
        boolean gambler = hasCurse(player, "gambler");
        boolean miser = hasCurse(player, "miser");
        boolean insomnia = hasCurse(player, "insomnia");
        boolean predator = hasCurse(player, "predator");
        boolean duelist = hasCurse(player, "duelist");
        boolean fettered = hasCurse(player, "fettered");

        setModifier(player, Attributes.ATTACK_DAMAGE, WARRIOR_ATTACK_DAMAGE, 0.5, warrior);

        setModifier(player, Attributes.MOVEMENT_SPEED, ARCHER_MOVEMENT_SPEED, -0.10, archer);
        setModifier(player, Attributes.MAX_HEALTH, ARCHER_MAX_HEALTH, -4.0, archer);

        setModifier(player, Attributes.ARMOR, SHIELD_ARMOR, 3.0, shield);
        setModifier(player, Attributes.MAX_HEALTH, SHIELD_MAX_HEALTH, 2.0, shield);
        setModifier(player, Attributes.MOVEMENT_SPEED, SHIELD_MOVEMENT_SPEED, -0.20, shield);

        setModifier(player, Attributes.MOVEMENT_SPEED, SCAPEGOAT_MOVEMENT_SPEED, 0.20, scapegoat);
        setModifier(player, Attributes.ARMOR, SCAPEGOAT_ARMOR, -3.0, scapegoat);
        setModifier(player, Attributes.MAX_HEALTH, SCAPEGOAT_MAX_HEALTH, -3.0, scapegoat);
        setModifier(player, Attributes.JUMP_STRENGTH, SCAPEGOAT_JUMP_STRENGTH, 0.5, scapegoat);

        setModifier(player, Attributes.LUCK, FOOL_LUCK, -0.5, fool);
        setModifier(player, Attributes.MOVEMENT_SPEED, FOOL_MOVEMENT_SPEED, 0.20, fool);
        setModifier(player, Attributes.BLOCK_INTERACTION_RANGE, FOOL_BLOCK_INTERACTION_RANGE, 1.5, fool);

        setModifier(player, Attributes.TEMPT_RANGE, HIGH_PRIESTESS_TEMPT_RANGE, -1.0, highPriestess);
        setModifier(player, Attributes.SNEAKING_SPEED, HIGH_PRIESTESS_SNEAKING_SPEED, -0.20, highPriestess);
        setModifier(player, Attributes.MOVEMENT_SPEED, HIGH_PRIESTESS_MOVEMENT_SPEED, -0.10, highPriestess);

        setModifier(player, Attributes.ENTITY_INTERACTION_RANGE, HIEROPHANT_ENTITY_INTERACTION_RANGE, 0.5, hierophant);

        setModifier(player, Attributes.KNOCKBACK_RESISTANCE, HANGED_MAN_KNOCKBACK_RESISTANCE, 0.40, hangedMan);
        setModifier(player, Attributes.MOVEMENT_SPEED, HANGED_MAN_MOVEMENT_SPEED, -0.10, hangedMan);
        setModifier(player, Attributes.JUMP_STRENGTH, HANGED_MAN_JUMP_STRENGTH, -0.15, hangedMan);
        setModifier(player, Attributes.FALL_DAMAGE_MULTIPLIER, HANGED_MAN_FALL_DAMAGE, 0.15, hangedMan);

        setModifier(player, Attributes.ARMOR, TEMPERANCE_ARMOR, 3.0, temperance);
        setModifier(player, Attributes.ATTACK_DAMAGE, TEMPERANCE_ATTACK_DAMAGE, -2.0, temperance);

        setModifier(player, Attributes.LUCK, STAR_LUCK, 1.0, star);
        setModifier(player, Attributes.BLOCK_INTERACTION_RANGE, STAR_BLOCK_INTERACTION_RANGE, 0.5, star);
        setModifier(player, Attributes.ENTITY_INTERACTION_RANGE, STAR_ENTITY_INTERACTION_RANGE, 0.5, star);
        setModifier(player, Attributes.MOVEMENT_SPEED, STAR_MOVEMENT_SPEED, -0.10, star);

        long timeOfDay = Math.floorMod(player.level().getDefaultClockTime(), 24_000L);
        boolean moonNight = timeOfDay >= 13_000L && timeOfDay < 23_000L;
        setModifier(player, Attributes.LUCK, MOON_LUCK, 1.0, moon);
        setModifier(player, Attributes.MOVEMENT_SPEED, MOON_MOVEMENT_SPEED, moonNight ? 0.20 : -0.10, moon);
        setModifier(player, Attributes.ATTACK_DAMAGE, MOON_ATTACK_DAMAGE, moonNight ? 2.0 : -2.0, moon);
        setModifier(player, Attributes.ARMOR, MOON_ARMOR, moonNight ? -4.0 : 2.0, moon);
        setModifier(player, Attributes.MAX_HEALTH, MOON_MAX_HEALTH, moonNight ? 0.0 : -4.0, moon);

        boolean sun = hasCurse(player, "sun");
        setModifier(player, Attributes.LUCK, SUN_LUCK, 1.0, sun);
        setModifier(player, Attributes.MOVEMENT_SPEED, SUN_MOVEMENT_SPEED, sun && !moonNight ? 0.20 : (sun ? -0.10 : 0.0), sun);
        setModifier(player, Attributes.ATTACK_DAMAGE, SUN_ATTACK_DAMAGE, sun && !moonNight ? 2.0 : (sun ? -2.0 : 0.0), sun);
        setModifier(player, Attributes.ARMOR, SUN_ARMOR, sun && !moonNight ? -4.0 : (sun ? 2.0 : 0.0), sun);
        setModifier(player, Attributes.MAX_HEALTH, SUN_MAX_HEALTH, sun && !moonNight ? 0.0 : (sun ? -4.0 : 0.0), sun);

        setModifier(player, Attributes.ATTACK_DAMAGE, BERSERKER_ATTACK_DAMAGE, 1.0, berserker);
        setModifier(player, Attributes.ATTACK_SPEED, BERSERKER_ATTACK_SPEED, 0.10, berserker);
        setModifier(player, Attributes.ARMOR, BERSERKER_ARMOR, -3.0, berserker);
        setModifier(player, Attributes.MAX_HEALTH, BERSERKER_MAX_HEALTH, -2.0, berserker);

        setModifier(player, Attributes.MAX_HEALTH, PARASITE_MAX_HEALTH, -2.0, parasite);

        setModifier(player, Attributes.MOVEMENT_SPEED, HOLLOW_MOVEMENT_SPEED, 0.075, hollow, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        setModifier(player, Attributes.BLOCK_INTERACTION_RANGE, HOLLOW_BLOCK_INTERACTION_RANGE, 1.0, hollow);
        setModifier(player, Attributes.ENTITY_INTERACTION_RANGE, HOLLOW_ENTITY_INTERACTION_RANGE, 1.0, hollow);
        setModifier(player, Attributes.MAX_HEALTH, HOLLOW_MAX_HEALTH, -4.0, hollow);

        setModifier(player, Attributes.LUCK, GAMBLER_LUCK, 2.0, gambler);
        setModifier(player, Attributes.BLOCK_BREAK_SPEED, MISER_BLOCK_BREAK_SPEED, 0.50, miser);
        setModifier(player, Attributes.ATTACK_DAMAGE, INSOMNIA_ATTACK_DAMAGE, -2.0, insomnia);
        setModifier(player, Attributes.ARMOR, DUELIST_ARMOR, -3.0, duelist);
        setModifier(player, Attributes.ARMOR, FETTERED_ARMOR, KicBalanceRules.fetteredArmor(curseStack(player, "fettered")), fettered);
        setModifier(player, Attributes.KNOCKBACK_RESISTANCE, FETTERED_KNOCKBACK, 0.30, fettered);
        setModifier(player, Attributes.MOVEMENT_SPEED, FETTERED_MOVEMENT_SPEED, KicBalanceRules.fetteredMovement(curseStack(player, "fettered")), fettered, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        setModifier(player, Attributes.JUMP_STRENGTH, FETTERED_JUMP_STRENGTH, -0.20, fettered);

        boolean devotion = isDevoted(player);
        boolean devil = isDevilBound(player);
        setModifier(player, Attributes.MOVEMENT_SPEED, DEVOTION_MOVEMENT_SPEED, 0.10, devotion, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        setModifier(player, Attributes.ATTACK_DAMAGE, DEVOTION_ATTACK_DAMAGE, 0.10, devotion, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        setModifier(player, Attributes.MAX_HEALTH, DEVOTION_MAX_HEALTH, 2.0, devotion);
        setModifier(player, Attributes.ARMOR, DEVOTION_ARMOR, 0.0, false);
        setModifier(player, Attributes.MAX_HEALTH, DEVIL_MAX_HEALTH, 0.0, false);

        // Lowering max health must never leave the player above the new maximum.
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        syncInsomniaMovementSpeed(player);
    }

    public static void syncBlessingEffects(ServerPlayer player) {
        boolean watcher = hasBlessing(player, "watcher");
        boolean swiftness = hasBlessing(player, "swiftness");
        boolean protection = hasBlessing(player, "protection");
        setModifier(player, Attributes.ARMOR, KarmaIsCurrency.id("blessing_protection_armor"), 2.0 * blessingStack(player, "protection"), protection);
        boolean depth = hasBlessing(player, "depth");
        boolean haste = hasBlessing(player, "haste");

        setModifier(player, Attributes.MOVEMENT_SPEED, SWIFTNESS_MOVEMENT_SPEED, 0.10, swiftness);

        int watcherMinutes = blessingMinutesRemaining(player, "watcher");
        if (watcher && watcherMinutes > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, watcherMinutes * 1200, 0, false, false, true));
        }

        int protectionMinutes = blessingMinutesRemaining(player, "protection");
        if (protection && protectionMinutes > 0) {
            // Protection now grants armor, not Resistance.
        }

        int depthMinutes = blessingMinutesRemaining(player, "depth");
        if (depth && depthMinutes > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, depthMinutes * 1200, 0, false, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, depthMinutes * 1200, 0, false, false, true));
        }
        int hasteMinutes = blessingMinutesRemaining(player, "haste");
        if (haste && hasteMinutes > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.HASTE, hasteMinutes * 1200, blessingStack(player, "haste") >= 3 ? 1 : 0, false, false, true));
        }
    }

    public static double socialRewardMultiplier(ServerPlayer player, String activityId) {
        double multiplier = 1.0;
        if (hasCurse(player, "miser")) multiplier *= 0.80;
        if (hasCurse(player, "hierophant")) {
            boolean institutional = "mercy_trial".equals(activityId)
                || "protection_trial".equals(activityId)
                || "raid_captain".equals(activityId);
            multiplier *= institutional ? 1.15 : 0.90;
        }
        return multiplier;
    }

    private static void setModifier(ServerPlayer player,
                                     net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                     Identifier id, double amount, boolean enabled) {
        setModifier(player, attribute, id, amount, enabled, AttributeModifier.Operation.ADD_VALUE);
    }

    private static void setModifier(ServerPlayer player,
                                     net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                     Identifier id, double amount, boolean enabled,
                                     AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        if (enabled) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, operation));
        } else {
            instance.removeModifier(id);
        }
    }

    public static boolean isInCombat(ServerPlayer player) {
        return KarmaPlayerState.get(player, "curse_combat_until") > player.level().getGameTime();
    }

    /** Combat lasts two minutes after either side deals or receives a valid hit. */
    public static void markInCombat(ServerPlayer player) {
        KarmaPlayerState.set(player, "curse_combat_until", player.level().getGameTime() + 2400L);
        syncInsomniaMovementSpeed(player);
    }

    public static void syncInsomniaMovementSpeed(ServerPlayer player) {
        boolean insomnia = hasCurse(player, "insomnia");
        int mode = !insomnia ? 0 : (isInCombat(player) ? 2 : 1);
        if (KarmaPlayerState.getInt(player, "insomnia_speed_mode") == mode) return;
        setModifier(player, Attributes.MOVEMENT_SPEED, INSOMNIA_MOVEMENT_SPEED,
            mode == 2 ? 0.05 : 0.15, mode != 0);
        KarmaPlayerState.setInt(player, "insomnia_speed_mode", mode);
    }

    /** Adds one temporary blessing stack to a slot, extending its expiry. */
    public static boolean addBlessing(ServerPlayer player, String id, int durationMinutes) {
        migrateLegacySigils(player);
        CurseBlessingRegistry.BlessingDefinition definition = CurseBlessingRegistry.blessing(id);
        if (definition == null) return false;
        String eligibility = commitmentRequirement(player, id);
        if (eligibility != null) { player.sendSystemMessage(Component.literal(eligibility)); return false; }
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
            || !KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) {
            player.sendSystemMessage(Component.literal("Blessings are disabled for this world."));
            return false;
        }
        if (!CurseBlessingRegistry.isBlessingAvailable(id, CommunityProgressionService.stage(player))) {
            player.sendSystemMessage(Component.literal("That Blessing is not available in the current cycle."));
            return false;
        }
        if (!canUseLightShrine(player)) {
            player.sendSystemMessage(Component.literal("You cannot use Blessings while bound to The Devil."));
            return false;
        }
        boolean devotion = "devotion".equals(id);
        if (devotion && isDevoted(player)) {
            player.sendSystemMessage(Component.literal("You are already devoted to the Light."));
            return false;
        }
        int index = CurseBlessingRegistry.blessingIndex(id);
        int encoded = encodedBlessing(index);
        int existing = findSigilSlot(player, encoded);
        if (!devotion && existing > 0) {
            int current = Math.max(1, KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + existing));
            if (current >= definition.maxStacks()) {
                player.sendSystemMessage(Component.literal("That Blessing is already at its maximum stack count."));
                return false;
            }
        }
        String capacityIssue = capacityBlocker(player, true, id);
        if (capacityIssue != null) {
            player.sendSystemMessage(Component.literal(capacityIssue));
            return false;
        }
        if (existing == 0 && firstEmptySigilSlot(player) == 0) {
            player.sendSystemMessage(Component.literal("Sigil storage is full."));
            return false;
        }
        if (!hasCosts(player, activationCosts(player, true, id))) {
            player.sendSystemMessage(Component.literal("You do not have the required items: "
                + CurseBlessingRegistry.costDescription(activationCosts(player, true, id))));
            return false;
        }
        consumeCosts(player, activationCosts(player, true, id));
        if (devotion) {
            if (!commit(player, Commitment.DEVOTION)) return false;
            syncBlessingEffects(player);
            return true;
        }

        int now = currentMinute(player);
        int duration = definition.durationMinutes();
        if (existing > 0) {
            KarmaPlayerState.setInt(player, SIGIL_STACK_PREFIX + existing,
                KarmaPlayerState.getInt(player, SIGIL_STACK_PREFIX + existing) + 1);
            if (duration > 0) {
                int until = Math.max(now, KarmaPlayerState.getInt(player, SIGIL_UNTIL_PREFIX + existing));
                KarmaPlayerState.setInt(player, SIGIL_UNTIL_PREFIX + existing, until + duration);
            }
        } else {
            int slot = firstEmptySigilSlot(player);
            if (slot == 0) {
                player.sendSystemMessage(Component.literal("You have no free Sigil slots."));
                return false;
            }
            KarmaPlayerState.set(player,"sigil_equipped_"+slot,System.currentTimeMillis());
            KarmaPlayerState.setInt(player, SIGIL_SLOT_PREFIX + slot, encoded);
            KarmaPlayerState.setInt(player, SIGIL_STACK_PREFIX + slot, 1);
            if (duration > 0) KarmaPlayerState.setInt(player, SIGIL_UNTIL_PREFIX + slot, now + duration);
        }
        syncBlessingEffects(player);
        return true;
    }

    public static boolean blessingOnCooldown(ServerPlayer player, String id) {
        int index = CurseBlessingRegistry.blessingIndex(id);
        if (index <= 0) return false;
        int now = currentMinute(player);
        return KarmaPlayerState.getInt(player, BLESSING_CD_PREFIX + index) > now;
    }

    public static int blessingCooldownRemaining(ServerPlayer player, String id) {
        int index = CurseBlessingRegistry.blessingIndex(id);
        if (index <= 0) return 0;
        return Math.max(0, KarmaPlayerState.getInt(player, BLESSING_CD_PREFIX + index) - currentMinute(player));
    }

    public static void setBlessingCooldown(ServerPlayer player, String id, int minutes) {
        int index = CurseBlessingRegistry.blessingIndex(id);
        if (index <= 0) return;
        KarmaPlayerState.setInt(player, BLESSING_CD_PREFIX + index, currentMinute(player) + Math.max(0, minutes));
    }

    public static int blessingMinutesRemaining(ServerPlayer player, String id) {
        int index = CurseBlessingRegistry.blessingIndex(id);
        if (index <= 0) return 0;
        int slot = findSigilSlot(player, encodedBlessing(index));
        if (slot == 0) return 0;
        expireBlessingIfNeeded(player, slot);
        int until = KarmaPlayerState.getInt(player, SIGIL_UNTIL_PREFIX + slot);
        return until <= 0 ? 0 : Math.max(0, until - currentMinute(player));
    }

    public static int activeCurseSlotCount(ServerPlayer player) {
        return slotCount(player, true);
    }

    public static int activeBlessingSlotCount(ServerPlayer player) {
        return slotCount(player, false);
    }

    public static int activeSigilSlotCount(ServerPlayer player) {
        return stackCount(player, null);
    }

    public static void removeAllBlessings(ServerPlayer player) {
        migrateLegacySigils(player);
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) {
            if (KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot) > 0) clearSigilSlot(player, slot);
        }
        syncBlessingEffects(player);
    }

    /** Clears expired temporary blessings and returns whether anything changed. */
    public static boolean expireBlessings(ServerPlayer player) {
        migrateLegacySigils(player);
        boolean changed = false;
        for (int slot = 1; slot <= MAX_SIGIL_STORAGE_SLOTS; slot++) changed |= expireBlessingIfNeeded(player, slot);
        if (changed) syncBlessingEffects(player);
        return changed;
    }

    private static boolean expireBlessingIfNeeded(ServerPlayer player, int slot) {
        int id = KarmaPlayerState.getInt(player, SIGIL_SLOT_PREFIX + slot);
        if (id <= 0) return false;
        int until = KarmaPlayerState.getInt(player, SIGIL_UNTIL_PREFIX + slot);
        if (until <= 0 || currentMinute(player) < until) return false;
        clearSigilSlot(player, slot);
        return true;
    }


    private static int currentMinute(ServerPlayer player) {
        return (int) (player.level().getGameTime() / 1200L);
    }
}
