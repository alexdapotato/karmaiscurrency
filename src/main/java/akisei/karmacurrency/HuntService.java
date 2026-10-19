package akisei.karmacurrency;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Core service for the voluntary three-day Dark Contract: The Hunt. */
public final class HuntService {
    public enum KillHandling {
        NOT_A_HUNT_KILL,
        BLOCKED_BY_GRACE,
        HUNT_COMPLETED,
        CANCELLED_BY_GAMERULE;

        public boolean handled() { return this != NOT_A_HUNT_KILL; }
    }

    private static final String LOGIN_GRACE_UNTIL = "hunt_login_grace_until";
    private static final String COMPASS_DAY = "hunt_compass_day";
    private static final String COMPASS_USED = "hunt_compass_used";
    private static final AtomicLong DEBUG_OFFSET_MILLIS = new AtomicLong(0L);
    private static final Map<UUID, Long> LAST_HUNTED_GAME_TICKS = new HashMap<>();

    private HuntService() {}

    public static void register() {
        HuntConfig.load();
        resetDebugClock();
    }

    public static void reloadConfig() {
        HuntConfig.load();
    }

    public static long now() {
        return System.currentTimeMillis() + DEBUG_OFFSET_MILLIS.get();
    }

    public static void resetDebugClock() {
        DEBUG_OFFSET_MILLIS.set(0L);
        LAST_HUNTED_GAME_TICKS.clear();
    }

    public static long debugAdvanceMinutes(long minutes) {
        if (minutes > 0) DEBUG_OFFSET_MILLIS.addAndGet(minutes * 60_000L);
        return DEBUG_OFFSET_MILLIS.get();
    }

    public static long debugAdvance(MinecraftServer server, long minutes) {
        if (minutes <= 0) return DEBUG_OFFSET_MILLIS.get();
        if (!server.getGameRules().get(KarmaGameRules.HUNT_ENABLED)
                || !server.getGameRules().get(KarmaGameRules.QUESTS_ENABLED)
                || !server.getGameRules().get(KarmaGameRules.TRIALS_ENABLED)) {
            tick(server);
            return DEBUG_OFFSET_MILLIS.get();
        }
        if (!server.getGameRules().get(KarmaGameRules.CONTRACTS_ENABLED)) releaseHuntersForDisabledContracts(server);
        DEBUG_OFFSET_MILLIS.addAndGet(minutes * 60_000L);
        HuntState state = HuntState.get(server);
        long simulatedTicks = minutes * 60L * 20L;
        for (HuntState.HuntRecord hunt : state.activeHunts()) {
            ServerPlayer hunted = getPlayer(server, hunt.hunted());
            if (hunted != null) {
                awardHuntedTimeIntervals(hunted, state.addActiveTicks(hunt.id(), simulatedTicks));
                updateLastKnown(state, hunt, hunted);
            }
        }
        if (!state.activeHunts().isEmpty()) state.setDirty();
        tick(server);
        return DEBUG_OFFSET_MILLIS.get();
    }

    public static int activeHuntCount(MinecraftServer server) {
        return HuntState.get(server).activeHuntCount();
    }

    public static boolean hasActiveHunt(MinecraftServer server) {
        return activeHuntCount(server) > 0;
    }

    public static boolean isHunted(MinecraftServer server, ServerPlayer player) {
        return player != null && HuntState.get(server).findHuntByHunted(player.getUUID()) != null;
    }

    public static boolean acceptHunt(MinecraftServer server, ServerPlayer player) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.HUNT_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(player, KarmaGameRules.TRIALS_ENABLED)) {
            player.sendSystemMessage(Component.literal("The Hunt is disabled by hunt_enabled, quests_enabled or trials_enabled."));
            return false;
        }
        if (!QuestService.canBecomeHunted(player)) {
            player.sendSystemMessage(Component.literal(QuestService.acceptanceBlockReason(player, QuestService.QuestId.HUNT)));
            return false;
        }
        HuntState state = HuntState.get(server);
        int maxHunts = Math.max(0, server.getGameRules().get(KarmaGameRules.MAX_ONGOING_HUNTS));
        if (maxHunts <= 0 || state.activeHuntCount() >= maxHunts) {
            player.sendSystemMessage(Component.literal("The maximum number of ongoing Hunts has been reached."));
            return false;
        }
        if (state.findHuntByHunted(player.getUUID()) != null) {
            player.sendSystemMessage(Component.literal("You are already the Hunted in an active Hunt."));
            return false;
        }
        long now = now();
        if (state.huntedCooldownUntil(player.getUUID()) > now) {
            long remaining = state.huntedCooldownUntil(player.getUUID()) - now;
            player.sendSystemMessage(Component.literal("You cannot accept another Hunt for " + formatDuration(remaining) + "."));
            return false;
        }
        if (state.isHunterAnywhere(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Leave your current Hunter participation before becoming the Hunted."));
            return false;
        }

        UUID huntId = UUID.randomUUID();
        long start = now;
        long end = start + HuntConfig.current().durationHours * 3_600_000L;
        long grace = start + HuntConfig.current().huntGraceMinutes * 60_000L;
        state.createHunt(huntId, player.getUUID(), player.getName().getString(), start, end, grace,
            player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(),
            player.level().dimension().identifier().toString(), QuestService.alignmentCode(player));
        KarmaPlayerState.set(player, LOGIN_GRACE_UNTIL,
            start + HuntConfig.current().loginGraceMinutes * 60_000L);
        player.sendSystemMessage(Component.literal("You accepted Trial of the Hunt. Survive for 3 days to claim an increasing reward."));
        HuntDiscordBridge.announceHuntStarted(player.getName().getString(), end);
        QuestService.refreshPlayerUi(player);
        return true;
    }

    public static boolean joinHunter(MinecraftServer server, ServerPlayer player) {
        HuntState state = HuntState.get(server);
        if (!hasActiveHunt(server)) {
            player.sendSystemMessage(Component.literal("There is no active Hunt to join."));
            return false;
        }
        if (!QuestService.canJoinHunt(player)) {
            player.sendSystemMessage(Component.literal(QuestService.acceptanceBlockReason(player, QuestService.QuestId.HUNT)));
            return false;
        }
        if (state.findHuntByHunted(player.getUUID()) != null) {
            player.sendSystemMessage(Component.literal("You cannot Hunt while you are the Hunted."));
            return false;
        }
        if (state.isHunterAnywhere(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You are already participating as a Hunter."));
            return false;
        }

        List<HuntState.HuntRecord> candidates = state.activeHunts();
        if (candidates.size() > 1) {
            player.sendSystemMessage(Component.literal("Two Hunts are active. Use /karma quests hunt hunter join <hunted-player>."));
            return false;
        }
        return joinHunter(server, player, candidates.get(0).huntedName());
    }

    public static boolean joinHunter(MinecraftServer server, ServerPlayer player, String huntedName) {
        HuntState state = HuntState.get(server);
        if (!QuestService.canJoinHunt(player)) {
            player.sendSystemMessage(Component.literal(QuestService.acceptanceBlockReason(player, QuestService.QuestId.HUNT)));
            return false;
        }
        if (!hasActiveHunt(server)) {
            player.sendSystemMessage(Component.literal("There is no active Hunt to join."));
            return false;
        }
        HuntState.HuntRecord target = null;
        for (HuntState.HuntRecord hunt : state.activeHunts()) {
            ServerPlayer hunted = getPlayer(server, hunt.hunted());
            String name = hunted == null ? hunt.huntedName() : hunted.getName().getString();
            if (name.equalsIgnoreCase(huntedName) || hunt.hunted().toString().equalsIgnoreCase(huntedName)) {
                target = hunt;
                break;
            }
        }
        if (target == null) {
            player.sendSystemMessage(Component.literal("No active Hunt was found for that player."));
            return false;
        }
        int huntedAlignment = target.huntedAlignment();
        if (huntedAlignment == 0) {
            ServerPlayer hunted = getPlayer(server, target.hunted());
            if (hunted != null) huntedAlignment = QuestService.alignmentCode(hunted);
        }
        int hunterAlignment = QuestService.alignmentCode(player);
        if (huntedAlignment != 0 && hunterAlignment != 0 && huntedAlignment == hunterAlignment) {
            player.sendSystemMessage(Component.literal("A Hunter must be on the opposing alignment from the Hunted."));
            return false;
        }
        if (target.isHunter(player.getUUID())) return true;
        if (state.findHuntByHunted(player.getUUID()) != null) {
            player.sendSystemMessage(Component.literal("You cannot Hunt while you are the Hunted."));
            return false;
        }
        if (state.isHunterAnywhere(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You are already participating as a Hunter."));
            return false;
        }
        if (target.hunters().size() >= HuntConfig.current().maxHunters) {
            player.sendSystemMessage(Component.literal("That Hunt already has the maximum number of Hunters."));
            return false;
        }
        if (state.hunterPairCooldownUntil(player.getUUID(), target.hunted()) > now()
            || state.hunterPairCooldownUntil(target.hunted(), player.getUUID()) > now()) {
            player.sendSystemMessage(Component.literal("You are on a cooldown for hunting this player."));
            return false;
        }
        state.addHunter(target.id(), player.getUUID());
        player.sendSystemMessage(Component.literal("You joined The Hunt for " + huntedName + "."));
        HuntDiscordBridge.announceHunterJoined(player.getName().getString(), huntedName);
        QuestService.refreshPlayerUi(player);
        return true;
    }

    public static boolean leaveHunter(MinecraftServer server, ServerPlayer player) {
        HuntState state = HuntState.get(server);
        HuntState.HuntRecord hunt = state.findHuntByHunter(player.getUUID());
        if (hunt == null) {
            player.sendSystemMessage(Component.literal("You are not participating as a Hunter."));
            return false;
        }
        state.removeHunter(hunt.id(), player.getUUID());
        player.sendSystemMessage(Component.literal("You left The Hunt."));
        QuestService.refreshPlayerUi(player);
        return true;
    }

    /** Cancels a Hunted player's participation or lets a Hunter leave without granting rewards. */
    public static boolean cancelParticipation(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return false;
        HuntState state = HuntState.get(server);
        HuntState.HuntRecord huntedRecord = state.findHuntByHunted(player.getUUID());
        if (huntedRecord != null) {
            for (UUID hunterId : huntedRecord.hunters()) {
                ServerPlayer hunter = getPlayer(server, hunterId);
                if (hunter != null) QuestService.refreshPlayerUi(hunter);
            }
            state.removeHunt(huntedRecord.id(), player.getUUID(),
                now() + HuntConfig.current().participationCooldownDays * 86_400_000L);
            clearCompassesForHunt(server, huntedRecord);
            player.sendSystemMessage(Component.literal("You cancelled your participation in The Hunt. No reward was granted."));
            QuestService.refreshPlayerUi(player);
            for (UUID hunterId : huntedRecord.hunters()) {
                ServerPlayer hunter = getPlayer(server, hunterId);
                if (hunter != null) QuestService.refreshPlayerUi(hunter);
            }
            return true;
        }

        HuntState.HuntRecord hunterRecord = state.findHuntByHunter(player.getUUID());
        if (hunterRecord == null) return false;
        state.removeHunter(hunterRecord.id(), player.getUUID());
        player.sendSystemMessage(Component.literal("You left The Hunt. No reward was granted."));
        QuestService.refreshPlayerUi(player);
        return true;
    }

    public static boolean requestCompass(MinecraftServer server, ServerPlayer hunter) {
        if (!KarmaGameRules.enabled(hunter, KarmaGameRules.HUNT_ENABLED)
                || !KarmaGameRules.enabled(hunter, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(hunter, KarmaGameRules.CONTRACTS_ENABLED)) {
            hunter.sendSystemMessage(Component.literal("Hunter participation is disabled by hunt_enabled, quests_enabled or contracts_enabled."));
            return false;
        }
        HuntState state = HuntState.get(server);
        HuntState.HuntRecord hunt = state.findHuntByHunter(hunter.getUUID());
        if (hunt == null) {
            hunter.sendSystemMessage(Component.literal("You are not participating as a Hunter."));
            return false;
        }
        refreshCompassAllowance(hunter);
        int used = KarmaPlayerState.getInt(hunter, COMPASS_USED);
        if (used >= HuntConfig.current().compassPerDay) {
            hunter.sendSystemMessage(Component.literal("You have used all of your Hunt Compasses for today."));
            return false;
        }
        ServerPlayer hunted = getPlayer(server, hunt.hunted());
        if (hunted == null) {
            hunter.sendSystemMessage(Component.literal("The Hunted is currently offline; there is no new location to record."));
            return false;
        }
        long seed = hunter.getUUID().getMostSignificantBits() ^ hunter.getUUID().getLeastSignificantBits() ^ now();
        ItemStack compass = HuntCompass.create(
            new BlockPos(hunt.lastX(), hunt.lastY(), hunt.lastZ()),
            dimensionKey(hunt.lastDimension()), now(), seed);
        if (!hunter.addItem(compass)) hunter.drop(compass, false);
        KarmaPlayerState.setInt(hunter, COMPASS_USED, used + 1);
        hunter.sendSystemMessage(Component.literal("Hunt Compass issued. It will expire in "
            + HuntConfig.current().compassLifetimeMinutes + " minutes."));
        return true;
    }

    public static int remainingCompasses(ServerPlayer hunter) {
        refreshCompassAllowance(hunter);
        return Math.max(0, HuntConfig.current().compassPerDay - KarmaPlayerState.getInt(hunter, COMPASS_USED));
    }

    private static void refreshCompassAllowance(ServerPlayer hunter) {
        long day = LocalDate.now(ZoneId.of("Asia/Manila")).toEpochDay();
        if (KarmaPlayerState.get(hunter, COMPASS_DAY) != day) {
            KarmaPlayerState.set(hunter, COMPASS_DAY, day);
            KarmaPlayerState.set(hunter, COMPASS_USED, 0L);
        }
    }

    public static void tick(MinecraftServer server) {
        if (!server.getGameRules().get(KarmaGameRules.HUNT_ENABLED)
                || !server.getGameRules().get(KarmaGameRules.QUESTS_ENABLED)
                || !server.getGameRules().get(KarmaGameRules.TRIALS_ENABLED)) {
            disableAllHunts(server);
            return;
        }
        if (!server.getGameRules().get(KarmaGameRules.CONTRACTS_ENABLED)) releaseHuntersForDisabledContracts(server);
        HuntState state = HuntState.get(server);
        long now = now();
        long gameTick = server.overworld().getGameTime();

        boolean activeTimeChanged = false;
        for (HuntState.HuntRecord hunt : state.activeHunts()) {
            ServerPlayer hunted = getPlayer(server, hunt.hunted());
            if (now < hunt.endEpochMillis() && hunted != null) {
                Long previousTick = LAST_HUNTED_GAME_TICKS.put(hunt.hunted(), gameTick);
                if (previousTick != null && gameTick > previousTick) {
                    long elapsedTicks = gameTick - previousTick;
                    awardHuntedTimeIntervals(hunted, state.addActiveTicks(hunt.id(), elapsedTicks));
                    activeTimeChanged = true;
                }
            } else {
                LAST_HUNTED_GAME_TICKS.remove(hunt.hunted());
            }
            if (gameTick % 20L == 0L) {
                if (hunted != null) updateLastKnown(state, hunt, hunted);
            }
        }
        if (activeTimeChanged && gameTick % 20L == 0L) state.setDirty();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) HuntCompass.expireFrom(player, now);
        state.cleanupExpiredCooldowns(now);

        for (HuntState.HuntRecord hunt : state.activeHunts()) {
            if (now >= hunt.endEpochMillis()) completeBySurvival(server, hunt.id());
        }
    }

    private static void awardHuntedTimeIntervals(ServerPlayer hunted, int intervals) {
        if (hunted == null || intervals <= 0) return;
        long reward = Math.min(Integer.MAX_VALUE, intervals * 8L);
        SocialCurrencyService.addReputation(hunted, (int) reward, "The Hunt — active Hunted time");
        hunted.sendSystemMessage(Component.literal("Active Hunted time reward: +" + reward + " Reputation."));
    }

    private static void updateLastKnown(HuntState state, HuntState.HuntRecord hunt, ServerPlayer hunted) {
        state.updateLastKnown(hunt.id(), hunted.blockPosition().getX(), hunted.blockPosition().getY(), hunted.blockPosition().getZ(),
            hunted.level().dimension().identifier().toString());
    }

    /**
     * Classifies a player kill for the caller. Every result other than
     * NOT_A_HUNT_KILL suppresses ordinary PvP rewards and kill-quest progress.
     */
    public static KillHandling onHuntedKilled(MinecraftServer server, ServerPlayer killer, ServerPlayer huntedPlayer) {
        HuntState state = HuntState.get(server);
        HuntState.HuntRecord hunt = state.findHuntByHunted(huntedPlayer.getUUID());
        if (hunt == null || killer == null || !hunt.isHunter(killer.getUUID())) return KillHandling.NOT_A_HUNT_KILL;

        if (!KarmaGameRules.enabled(huntedPlayer, KarmaGameRules.HUNT_ENABLED)
                || !KarmaGameRules.enabled(huntedPlayer, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(huntedPlayer, KarmaGameRules.TRIALS_ENABLED)) {
            disableAllHunts(server);
            return KillHandling.CANCELLED_BY_GAMERULE;
        }
        if (!KarmaGameRules.enabled(killer, KarmaGameRules.CONTRACTS_ENABLED)) {
            releaseHuntersForDisabledContracts(server);
            return KillHandling.CANCELLED_BY_GAMERULE;
        }

        long now = now();
        long loginGrace = KarmaPlayerState.get(huntedPlayer, LOGIN_GRACE_UNTIL);
        if (now < hunt.graceUntil() || now < loginGrace) {
            killer.sendSystemMessage(Component.literal("The Hunt target is protected by a grace period. No Hunt reward was paid."));
            return KillHandling.BLOCKED_BY_GRACE;
        }

        long huntedCooldown = now + HuntConfig.current().participationCooldownDays * 86_400_000L;
        for (UUID hunterUuid : hunt.hunters()) {
            ServerPlayer hunter = getPlayer(server, hunterUuid);
            if (hunter != null) {
                QuestService.recognizeCompletion(hunter, QuestService.QuestId.HUNT);
                KarmaService.addInfamyPoints(hunter, HuntConfig.current().hunterRewardInfamy,
                    "The Hunt — successful chase");
                hunter.sendSystemMessage(Component.literal("The Hunt succeeded. You earned "
                    + HuntConfig.current().hunterRewardInfamy + " Infamy."));
            }
            long pairCooldown = now + HuntConfig.current().hunterPairCooldownDays * 86_400_000L;
            state.setPairCooldown(hunterUuid, huntedPlayer.getUUID(), pairCooldown);
            state.setPairCooldown(huntedPlayer.getUUID(), hunterUuid, pairCooldown);
        }
        state.removeHunt(hunt.id(), huntedPlayer.getUUID(), huntedCooldown);
        clearCompassesForHunt(server, hunt);
        HuntDiscordBridge.announceHuntEnded(huntedPlayer.getName().getString(), false);
        huntedPlayer.sendSystemMessage(Component.literal("The Hunt has ended. You were caught."));
        QuestService.refreshPlayerUi(huntedPlayer);
        for (UUID hunterUuid : hunt.hunters()) {
            ServerPlayer hunter = getPlayer(server, hunterUuid);
            if (hunter != null) QuestService.refreshPlayerUi(hunter);
        }
        return KillHandling.HUNT_COMPLETED;
    }

    public static void debugRefreshLoginGrace(ServerPlayer player) {
        KarmaPlayerState.set(player, LOGIN_GRACE_UNTIL, now() + HuntConfig.current().loginGraceMinutes * 60_000L);
    }

    public static boolean debugForceStart(MinecraftServer server, ServerPlayer hunted) {
        if (!KarmaGameRules.enabled(hunted, KarmaGameRules.HUNT_ENABLED)
                || !KarmaGameRules.enabled(hunted, KarmaGameRules.QUESTS_ENABLED)
                || !KarmaGameRules.enabled(hunted, KarmaGameRules.TRIALS_ENABLED)) {
            hunted.sendSystemMessage(Component.literal("The Hunted role is disabled by hunt_enabled, quests_enabled or trials_enabled."));
            return false;
        }
        HuntState state = HuntState.get(server);
        HuntState.HuntRecord existing = state.findHuntByHunted(hunted.getUUID());
        if (existing != null) state.removeHunt(existing.id(), hunted.getUUID(), 0L);
        int maxHunts = Math.max(0, server.getGameRules().get(KarmaGameRules.MAX_ONGOING_HUNTS));
        if (state.activeHuntCount() >= maxHunts) return false;
        long start = now();
        long end = start + HuntConfig.current().durationHours * 3_600_000L;
        long grace = start + HuntConfig.current().huntGraceMinutes * 60_000L;
        state.createHunt(UUID.randomUUID(), hunted.getUUID(), hunted.getName().getString(), start, end, grace,
            hunted.blockPosition().getX(), hunted.blockPosition().getY(), hunted.blockPosition().getZ(),
            hunted.level().dimension().identifier().toString(), QuestService.alignmentCode(hunted));
        KarmaPlayerState.set(hunted, LOGIN_GRACE_UNTIL, start + HuntConfig.current().loginGraceMinutes * 60_000L);
        hunted.sendSystemMessage(Component.literal("[DEBUG] The Hunt started for you."));
        return true;
    }

    public static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        claimPendingReward(server, player);
        HuntState.HuntRecord hunt = HuntState.get(server).findHuntByHunted(player.getUUID());
        if (hunt != null) {
            LAST_HUNTED_GAME_TICKS.remove(player.getUUID());
            KarmaPlayerState.set(player, LOGIN_GRACE_UNTIL, now() + HuntConfig.current().loginGraceMinutes * 60_000L);
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        if (player != null) LAST_HUNTED_GAME_TICKS.remove(player.getUUID());
    }

    public static void resetPlayer(MinecraftServer server, ServerPlayer player) {
        if (player == null) return;
        HuntState state = HuntState.get(server);
        state.clearPlayer(player.getUUID());
        HuntCompass.clearFrom(player);
        KarmaPlayerState.clear(player, LOGIN_GRACE_UNTIL);
        KarmaPlayerState.clear(player, COMPASS_DAY);
        KarmaPlayerState.clear(player, COMPASS_USED);
    }

    public static void resetAll(MinecraftServer server) {
        HuntState state = HuntState.get(server);
        state.clearAll();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) resetPlayerAttachments(player);
        resetDebugClock();
    }

    /** Ends persisted Hunted participation without rewards when its required switch is disabled. */
    private static void disableAllHunts(MinecraftServer server) {
        HuntState state = HuntState.get(server);
        String reason = !server.getGameRules().get(KarmaGameRules.HUNT_ENABLED) ? "hunt_enabled is false"
            : !server.getGameRules().get(KarmaGameRules.QUESTS_ENABLED)
            ? "quests_enabled is false"
            : "trials_enabled is false";
        for (HuntState.HuntRecord hunt : state.activeHunts()) {
            ServerPlayer hunted = getPlayer(server, hunt.hunted());
            if (hunted != null) {
                hunted.sendSystemMessage(Component.literal("The Hunt was cancelled because " + reason + "."));
            }
            for (UUID hunterId : hunt.hunters()) {
                ServerPlayer hunter = getPlayer(server, hunterId);
                if (hunter != null) {
                    hunter.sendSystemMessage(Component.literal("The Hunt was cancelled because " + reason + "."));
                    HuntCompass.clearFrom(hunter);
                }
            }
            clearCompassesForHunt(server, hunt);
            state.removeHunt(hunt.id(), hunt.hunted(), 0L);
            if (hunted != null) QuestService.refreshPlayerUi(hunted);
            for (UUID hunterId : hunt.hunters()) {
                ServerPlayer hunter = getPlayer(server, hunterId);
                if (hunter != null) QuestService.refreshPlayerUi(hunter);
            }
        }
    }

    /** Hunter participation follows contracts_enabled while Hunted participation continues. */
    private static void releaseHuntersForDisabledContracts(MinecraftServer server) {
        HuntState state = HuntState.get(server);
        for (HuntState.HuntRecord hunt : state.activeHunts()) {
            for (UUID hunterId : List.copyOf(hunt.hunters())) {
                state.removeHunter(hunt.id(), hunterId);
                ServerPlayer hunter = getPlayer(server, hunterId);
                if (hunter != null) {
                    HuntCompass.clearFrom(hunter);
                    hunter.sendSystemMessage(Component.literal("Your Hunt participation ended because contracts_enabled is false."));
                    QuestService.refreshPlayerUi(hunter);
                }
            }
        }
    }

    private static void resetPlayerAttachments(ServerPlayer player) {
        HuntCompass.clearFrom(player);
        KarmaPlayerState.clear(player, LOGIN_GRACE_UNTIL);
        KarmaPlayerState.clear(player, COMPASS_DAY);
        KarmaPlayerState.clear(player, COMPASS_USED);
    }

    private static void completeBySurvival(MinecraftServer server, UUID huntId) {
        HuntState state = HuntState.get(server);
        HuntState.HuntRecord hunt = state.get(huntId);
        if (hunt == null) return;
        ServerPlayer hunted = getPlayer(server, hunt.hunted());
        long activeHours = Math.max(0L, hunt.activeTicks() / (20L * 60L * 60L));
        int reward = (int) Math.min(Integer.MAX_VALUE,
            HuntConfig.current().huntedBaseRewardInfamy
                + activeHours * (long) HuntConfig.current().huntedRewardPerActiveHourInfamy);
        long cooldownUntil = now() + HuntConfig.current().participationCooldownDays * 86_400_000L;

        if (hunted != null) {
            QuestService.recognizeCompletion(hunted, QuestService.QuestId.HUNT);
            KarmaService.addInfamyPoints(hunted, reward, "The Hunt — survived");
            hunted.sendSystemMessage(Component.literal("You survived The Hunt and earned " + reward + " Infamy."));
        } else {
            state.setPendingReward(hunt.hunted(), reward);
        }
        for (UUID hunterId : hunt.hunters()) {
            ServerPlayer hunter = getPlayer(server, hunterId);
            if (hunter != null) QuestService.refreshPlayerUi(hunter);
        }
        state.removeHunt(hunt.id(), hunt.hunted(), cooldownUntil);
        clearCompassesForHunt(server, hunt);
        if (hunted != null) HuntDiscordBridge.announceHuntEnded(hunted.getName().getString(), true);
        if (hunted != null) QuestService.refreshPlayerUi(hunted);
        for (UUID hunterId : hunt.hunters()) {
            ServerPlayer hunter = getPlayer(server, hunterId);
            if (hunter != null) QuestService.refreshPlayerUi(hunter);
        }
    }

    private static void claimPendingReward(MinecraftServer server, ServerPlayer player) {
        HuntState state = HuntState.get(server);
        int reward = state.pendingReward(player.getUUID());
        if (reward <= 0) return;
        QuestService.recognizeCompletion(player, QuestService.QuestId.HUNT);
        KarmaService.addInfamyPoints(player, reward, "The Hunt — survived (pending)");
        state.clearPendingReward(player.getUUID());
        player.sendSystemMessage(Component.literal("You survived The Hunt and earned " + reward + " pending Infamy."));
    }

    private static void clearCompassesForHunt(MinecraftServer server, HuntState.HuntRecord hunt) {
        for (UUID hunterUuid : hunt.hunters()) {
            ServerPlayer hunter = getPlayer(server, hunterUuid);
            if (hunter != null) HuntCompass.clearFrom(hunter);
        }
    }

    public static String status(MinecraftServer server) {
        if (!server.getGameRules().get(KarmaGameRules.HUNT_ENABLED)) return "The Hunt is disabled by hunt_enabled.";
        if (!server.getGameRules().get(KarmaGameRules.QUESTS_ENABLED)) {
            return "The Hunt is disabled by the quests_enabled gamerule.";
        }
        if (!server.getGameRules().get(KarmaGameRules.TRIALS_ENABLED)) {
            return "The Hunted role is disabled by the trials_enabled gamerule.";
        }
        List<HuntState.HuntRecord> hunts = HuntState.get(server).activeHunts();
        if (hunts.isEmpty()) return "No Hunt is currently active.";
        StringBuilder out = new StringBuilder();
        out.append("Active Hunts: ").append(hunts.size()).append('/').append(
            Math.max(0, server.getGameRules().get(KarmaGameRules.MAX_ONGOING_HUNTS)));
        for (HuntState.HuntRecord hunt : hunts) {
            long remaining = Math.max(0L, hunt.endEpochMillis() - now());
            long activeMinutes = hunt.activeTicks() / (20L * 60L);
            ServerPlayer hunted = getPlayer(server, hunt.hunted());
            out.append("\n- ").append(hunted == null ? hunt.huntedName() : hunted.getName().getString())
                .append(": ").append(formatDuration(remaining))
                .append(" remaining, ").append(activeMinutes).append("m active, ")
                .append(hunt.hunters().size()).append('/').append(HuntConfig.current().maxHunters).append(" Hunters");
        }
        return out.toString();
    }

    public static long debugOffsetMillis() { return DEBUG_OFFSET_MILLIS.get(); }

    private static ServerPlayer getPlayer(MinecraftServer server, UUID uuid) {
        return uuid == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    private static net.minecraft.resources.ResourceKey<Level> dimensionKey(String value) {
        String id = value == null || value.isBlank() ? "minecraft:overworld" : value;
        return net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.Identifier.parse(id));
    }

    private static String formatDuration(long millis) {
        long totalMinutes = Math.max(0L, millis / 60_000L);
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes / 60L) % 24L;
        long minutes = totalMinutes % 60L;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
