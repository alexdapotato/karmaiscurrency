package akisei.karmacurrency;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Rewards registered participants once per completed encounter and cooldown. */
public final class TrialSpawnerRewardService {
    static final long COOLDOWN_TICKS = 30L * 60L * 20L;

    private TrialSpawnerRewardService() {}

    static String cooldownKey(String dimension, long position) {
        return "trial_spawner_reward_until:" + dimension + ":" + position;
    }

    static boolean claimCooldown(ServerPlayer player, String key, long now) {
        if (now < KarmaPlayerState.get(player, key)) return false;
        KarmaPlayerState.set(player, key, now + COOLDOWN_TICKS);
        return true;
    }

    public static void rewardCompletion(ServerLevel level, BlockPos position, Set<UUID> participants) {
        QuestRevamp.encounter(level, position, participants);
        if (!KarmaGameRules.enabled(level, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) return;
        String key = cooldownKey(level.dimension().identifier().toString(), position.asLong());
        for (UUID id : Set.copyOf(participants)) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != level || !player.isAlive()
                    || player.isSpectator() || player.isCreative()
                    || player.distanceToSqr(position.getX()+.5,position.getY()+.5,position.getZ()+.5)>2500) continue;
            if (!claimCooldown(player, key, level.getGameTime())) continue;
            KarmaService.awardAlignedEvent(player, "trial_spawner", 0, "completing a Trial Spawner encounter");
        }
    }
}
