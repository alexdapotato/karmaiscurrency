package akisei.karmacurrency;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;

public final class KarmaEvents {


	private KarmaEvents() {
	}

	public static void register() {
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(KarmaEvents::onKilledEntity);
		ServerLivingEntityEvents.AFTER_DEATH.register(KarmaEvents::onAfterDeath);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer victim) QuestService.recordDamage(victim);
			Entity causing = source.getEntity();
			if (causing instanceof ServerPlayer attacker) return QuestService.allowBonusAttack(attacker, source.getDirectEntity());
			if (causing instanceof net.minecraft.world.entity.projectile.Projectile projectile
					&& projectile.getOwner() instanceof ServerPlayer attacker) {
				return QuestService.allowBonusAttack(attacker, source.getDirectEntity());
			}
			return true;
		});

		// Alignment initialization and health-bonus setup are lifecycle operations.
		// They do not need to run as part of the server's hot tick loop.
		ServerPlayerEvents.JOIN.register(player -> {
			KarmaService.initializeAlignmentChoiceFromExistingKarma(player);
			KarmaService.updateHealthBonus(player);
			CurseBlessingService.syncCurseEffects(player);
			CurseBlessingService.syncBlessingEffects(player);
			HuntService.onPlayerJoin(player);
			QuestService.onPlayerJoin(player);
			if (KarmaService.alignmentChoice(player) == 0) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
					"Choose your starting alignment with /choose light or /choose dark."));
			}
		});
		ServerPlayerEvents.LEAVE.register(HuntService::onPlayerLeave);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			KarmaService.updateHealthBonus(newPlayer);
			CurseBlessingService.expireBlessings(newPlayer);
			CurseBlessingService.syncCurseEffects(newPlayer);
			CurseBlessingService.syncBlessingEffects(newPlayer);
		});


		// Maintenance only needs minute-level precision for decay/DR timers. Run the
		// maintenance pass once per Minecraft minute instead of once per second.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			HuntService.tick(server);
			QuestService.tick(server);
			// Insomnia only needs to be reconciled once per second. Combat state changes
			// call syncInsomniaMovementSpeed() immediately, so this pass only catches
			// timed combat expiry and external state changes.
			if (server.overworld().getGameTime() % 20L == 0L) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					CurseBlessingService.syncInsomniaMovementSpeed(player);
				}
			}
			if (server.overworld().getGameTime() % 1200L != 0L) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				KarmaService.maintain(player);
				CurseBlessingService.expireBlessings(player);
				CurseBlessingService.syncBlessingEffects(player);
				CurseBlessingService.syncCurseEffects(player);
			}
		});
	}

	private static void onKilledEntity(net.minecraft.server.level.ServerLevel level, Entity killer, LivingEntity victim, DamageSource source) {
		if (!(killer instanceof ServerPlayer player)) {
			return;
		}
		ShrineConfig config = ShrineConfig.current();
		if (victim instanceof ServerPlayer otherPlayer) {
			HuntService.KillHandling huntKill = HuntService.onHuntedKilled(level.getServer(), player, otherPlayer);
			// Successful Hunt catches are isolated from ordinary PvP Karma/social rewards,
			// and grace-blocked kills cannot advance ordinary kill quests.
			if (huntKill.handled()) return;
		}
		QuestService.recordKill(player, victim);

		if (victim instanceof ServerPlayer otherPlayer) {
			if (!canReceivePlayerKillReward(level, player, otherPlayer)) return;
			dropPlayerHead(player, otherPlayer);
			int victimWholeKarma = Math.abs(KarmaService.get(otherPlayer)) / KarmaService.POINTS_PER_KARMA;
			int scalingBonus = Math.min(config.karmaPlayerKillMax, victimWholeKarma / config.karmaPlayerKillVictimBonusDivisor);
			SocialCurrencyService.Stance killerStance = SocialCurrencyService.getStance(player);
			SocialCurrencyService.Stance victimStance = SocialCurrencyService.getStance(otherPlayer);
			boolean opposed = killerStance != SocialCurrencyService.Stance.NEUTRAL
				&& victimStance != SocialCurrencyService.Stance.NEUTRAL
				&& killerStance != victimStance;
			int karma = opposed ? config.karmaPlayerKillBase + scalingBonus : -(5 + scalingBonus);
			int social = opposed ? config.socialPlayerKillGain + scalingBonus : -(5 + scalingBonus);
			KarmaService.addWithSocial(player, KarmaService.karma(karma), social,
				"killing " + otherPlayer.getName().getString(), "player_kill");
		} else if (victim instanceof Villager) {
			KarmaService.awardAlignedEvent(player, "villager_kill", 0, "killing a villager");
		} else if (victim instanceof Raider raider && raider.isPatrolLeader()) {
			KarmaService.awardAlignedEvent(player, "raid_captain", 0, "defeating a Raid Captain");
		} else if (victim instanceof EnderDragon) {
			int activePlayers = activePlayersNear(level, victim.blockPosition(), 256.0);
			int social = Math.max(0, config.socialEnderDragonGain - Math.max(0, activePlayers - 1) * 5);
			KarmaService.awardAlignedEvent(player, "ender_dragon", social, "slaying the Ender Dragon");
		}
		if (!(victim instanceof ServerPlayer) && !(victim instanceof Villager)
			&& !(victim instanceof EnderDragon) && victim instanceof net.minecraft.world.entity.monster.Monster) {
			DarkShrineService.recordHostileKill(player);
		}
		// Iron golem kills are intentionally ignored for now.
	}

	private static void dropPlayerHead(ServerPlayer killer, ServerPlayer victim) {
		if (KarmaService.getPlayerHeadCooldownMinutes(killer) > 0) {
			killer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"Player head drop cooldown: " + KarmaService.getPlayerHeadCooldownMinutes(killer) + " minutes."));
			return;
		}
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(victim.getGameProfile()));
		head.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(victim.getName().getString() + "'s Head"));
		victim.spawnAtLocation(victim.level(), head);
		KarmaService.startPlayerHeadCooldown(killer);
	}

	private static void onAfterDeath(LivingEntity entity, DamageSource damageSource) {
		if (entity instanceof ServerPlayer player) QuestService.recordDeath(player);
		if (!(entity instanceof WitherBoss)) return;
		ServerPlayer killer = responsiblePlayer(damageSource);
		if (killer == null) return;
		ShrineConfig config = ShrineConfig.current();
		int activePlayers = activePlayersNear((ServerLevel) entity.level(), entity.blockPosition(), 256.0);
		int social = Math.max(0, config.socialWitherGain - Math.max(0, activePlayers - 1) * 5);
		KarmaService.awardAlignedEvent(killer, "wither", social, "slaying the Wither");
	}

	private static boolean canReceivePlayerKillReward(ServerLevel level, ServerPlayer killer, ServerPlayer victim) {
		String pair = java.util.UUID.nameUUIDFromBytes((killer.getUUID().toString() + victim.getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 16);
		String cooldownKey = "pvp_cd_" + pair;
		String repeatKey = "pvp_repeat_" + pair;
		int now = (int) (level.getGameTime() / 1200L);
		if (KarmaPlayerState.getInt(killer, cooldownKey) > now) return false;
		int repeat = Math.max(0, KarmaPlayerState.getInt(killer, repeatKey));
		int minutes = Math.min(90, 30 + repeat * 15);
		KarmaPlayerState.setInt(killer, cooldownKey, now + minutes);
		KarmaPlayerState.setInt(killer, repeatKey, Math.min(4, repeat + 1));
		return true;
	}

	private static int activePlayersNear(ServerLevel level, net.minecraft.core.BlockPos center, double radius) {
		double radiusSq = radius * radius;
		int count = 0;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (player.level() != level) continue;
			if (player.blockPosition().distSqr(center) <= radiusSq) count++;
		}
		return count;
	}

	private static ServerPlayer responsiblePlayer(DamageSource source) {
		Entity causing = source.getEntity();
		return causing instanceof ServerPlayer player ? player : null;
	}
}
