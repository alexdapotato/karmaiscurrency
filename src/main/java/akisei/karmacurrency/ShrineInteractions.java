package akisei.karmacurrency;

import java.util.Comparator;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** Right-click near a shrine core opens a chest UI usable by Java and Bedrock (via Geyser). */
public final class ShrineInteractions {
	/** Shrine menus open from a nearby block while the player is within that shrine's access radius. */

	private ShrineInteractions() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register(ShrineInteractions::onUseBlock);
		UseItemCallback.EVENT.register(ShrineInteractions::onUseItem);
	}

	private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
			|| !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		var stack = serverPlayer.getItemInHand(hand);
		if (!ShrineCompass.isShrineCompass(stack)) {
			return InteractionResult.PASS;
		}
		if (!KarmaGameRules.enabled(serverPlayer, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
			serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Shrine compasses are disabled for this world."));
			return InteractionResult.SUCCESS_SERVER;
		}
		ShrineCompassMenu.open(serverPlayer);
		return InteractionResult.SUCCESS_SERVER;
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
		if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = level.getServer();
		if (server == null || level.dimension() != Level.OVERWORLD) {
			return InteractionResult.PASS;
		}

		// Ring-generated shrines can exist in the world before the chunk lifecycle
		// callback has persisted their StructureStart. A player clicking the visible
		// shrine is a stronger signal, so inspect the current 3x3 loaded-chunk area
		// before relying on ShrineState. This never force-loads distant chunks.
		ShrineManager.discoverGeneratedShrinesNear((ServerLevel) level, hitResult.getBlockPos());

		Optional<ShrineSite> shrine = findShrineNear(serverPlayer, hitResult.getBlockPos());
		if (shrine.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (openShrine(serverPlayer, shrine.get())) {
			return InteractionResult.SUCCESS_SERVER;
		}
		return InteractionResult.PASS;
	}

	/** Shared shrine activation path used by both right-click and core-contact activation. */
	static boolean openShrine(ServerPlayer serverPlayer, ShrineSite shrine) {
		if (!KarmaGameRules.enabled(serverPlayer, KarmaGameRules.SHRINES_ENABLED)) {
			serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Shrines are disabled for this world."));
			return true;
		}
		MinecraftServer server = serverPlayer.level().getServer();
		if (server == null || !(serverPlayer.level() instanceof ServerLevel level)
			|| !ShrineManager.hasFunctionalLodestone(level, shrine)) {
			serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"This shrine's lodestone is missing, so it cannot be used."));
			return true;
		}
		ShrineManager.ensureShrineCore(server, shrine);
	
		if (shrine.type() == ShrineType.LIGHT && !CurseBlessingService.canUseLightShrine(serverPlayer)) {
			serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"The Devil bars you from Light Shrines."));
			return true;
		}
		if (shrine.type() == ShrineType.DARK && !CurseBlessingService.canUseDarkShrine(serverPlayer)) {
			serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"Devotion bars you from Dark Shrines."));
			return true;
		}

		ShrineMenu.open(serverPlayer, shrine);
		return true;
	}

	static Optional<ShrineSite> findShrineNear(ServerPlayer player, BlockPos clicked) {
		return ShrineState.get(player.level().getServer()).sites().stream()
			.filter(site -> {
				int radius = menuAccessRadius(site);
				double radiusSquared = (double) radius * radius;
				return site.horizontalDistanceSquaredToLodestone(clicked) <= radiusSquared
					&& horizontalDistanceSquaredToLodestone(player.getX(), player.getZ(), site) <= radiusSquared;
			})
			.min(Comparator.comparingDouble(site -> site.horizontalDistanceSquaredToLodestone(clicked)));
	}

	private static int menuAccessRadius(ShrineSite site) {
		return site.type() == ShrineType.MONUMENT ? 7 : 4;
	}

	private static double horizontalDistanceSquaredToLodestone(double x, double z, ShrineSite site) {
		BlockPos lodestone = site.actualLodestonePosition();
		double dx = x - (lodestone.getX() + 0.5D);
		double dz = z - (lodestone.getZ() + 0.5D);
		return dx * dx + dz * dz;
	}

}
