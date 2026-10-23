package akisei.karmacurrency;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class ShrineCommands {
	private ShrineCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("kd").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)));
            var kd=dispatcher.getRoot().getChild("kd");
            kd.addChild(
			Commands.literal("shrine")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(Commands.literal("locate").executes(context -> locate(context.getSource().getPlayerOrException(), Optional.empty())))
				.then(Commands.literal("compass")
					.executes(context -> giveCompass(
						context.getSource().getPlayerOrException(), Optional.empty(),
						context.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)))
					.then(Commands.literal("light").executes(context -> giveCompass(
						context.getSource().getPlayerOrException(), Optional.of(ShrineType.LIGHT),
						context.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))))
					.then(Commands.literal("dark").executes(context -> giveCompass(
						context.getSource().getPlayerOrException(), Optional.of(ShrineType.DARK),
						context.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))))
					.then(Commands.literal("neutral").executes(context -> giveCompass(
						context.getSource().getPlayerOrException(), Optional.of(ShrineType.NEUTRAL),
						context.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))))
					.then(Commands.literal("monument").executes(context -> giveCompass(
						context.getSource().getPlayerOrException(), Optional.of(ShrineType.MONUMENT),
						context.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))))
				)
				.then(Commands.literal("spawn")
					.then(spawnType("light", ShrineType.LIGHT))
					.then(spawnType("dark", ShrineType.DARK))
					.then(spawnType("neutral", ShrineType.NEUTRAL))
					.then(spawnType("monument", ShrineType.MONUMENT))
				)
				.then(Commands.literal("debug")
					.then(Commands.literal("positions")
						.executes(context -> debugPositions(context.getSource(), Optional.empty()))
						.then(Commands.literal("shrine").executes(context -> debugPositions(context.getSource(), Optional.of(false))))
						.then(Commands.literal("monument").executes(context -> debugPositions(context.getSource(), Optional.of(true)))))
					.then(Commands.literal("registered")
						.executes(context -> debugRegistered(context.getSource())))
					.then(Commands.literal("ring")
						.then(Commands.argument("ring", IntegerArgumentType.integer(1, 100))
							.executes(context -> debugRing(context.getSource(), IntegerArgumentType.getInteger(context, "ring")))))
					.then(Commands.literal("tp")
						.then(Commands.literal("monument")
							.then(Commands.argument("ring", IntegerArgumentType.integer(2, 100))
								.then(Commands.argument("index", IntegerArgumentType.integer(1, 64))
									.executes(context -> debugTeleport(context.getSource(), true,
										IntegerArgumentType.getInteger(context, "ring"),
										IntegerArgumentType.getInteger(context, "index"))))))
						.then(Commands.literal("shrine")
							.then(Commands.argument("ring", IntegerArgumentType.integer(1, 100))
								.then(Commands.argument("index", IntegerArgumentType.integer(1, 64))
									.executes(context -> debugTeleport(context.getSource(), false,
										IntegerArgumentType.getInteger(context, "ring"),
										IntegerArgumentType.getInteger(context, "index")))))))
				)
		.build());
        });
	}

	private static LiteralArgumentBuilder<CommandSourceStack> spawnType(String name, ShrineType type) {
		return Commands.literal(name)
			.executes(context -> spawnDefault(context.getSource(), type))
			.then(Commands.argument("radius", IntegerArgumentType.integer(16))
				.executes(context -> spawnAroundPlayer(
					context.getSource(), type, IntegerArgumentType.getInteger(context, "radius")))
				.then(Commands.literal("onplayer")
					.executes(context -> spawnAroundPlayer(
						context.getSource(), type, IntegerArgumentType.getInteger(context, "radius"))))
				.then(Commands.literal("onspawn")
					.executes(context -> spawnAroundWorldSpawn(
						context.getSource(), type, IntegerArgumentType.getInteger(context, "radius"))))
			);
	}

	private static int locate(ServerPlayer player, Optional<ShrineType> type) {
		if (type.isEmpty()) {
			return ShrineManager.nearestShrineTarget(
				new ShrineManager.ServerPlayerPosition(player.blockPosition().getX(), player.blockPosition().getZ()),
				player.level().getServer())
				.map(target -> {
					if (target.generated().isPresent()) {
						ShrineSite site = target.generated().get();
						player.sendSystemMessage(coordinateMessage(player.level(),
							"Nearest " + site.statueName() + " (" + site.type().displayName() + "): ", site.position()));
					} else {
						player.sendSystemMessage(Component.empty()
							.append(Component.literal("Nearest potential shrine: "))
							.append(coordinateMessage(player.level(), "", target.position()))
							.append(Component.literal(" (not generated yet)")));
					}
					return Command.SINGLE_SUCCESS;
				})
				.orElseGet(() -> {
					player.sendSystemMessage(Component.literal("There are no shrine locations available."));
					return 0;
				});
		}

		return findShrine(player, type)
			.map(site -> {
				player.sendSystemMessage(coordinateMessage(player.level(),
					"Nearest " + site.statueName() + " (" + site.type().displayName() + "): ", site.position()));
				return Command.SINGLE_SUCCESS;
			})
			.orElseGet(() -> {
				player.sendSystemMessage(Component.literal("There is no active " + type.get().displayName() + "."));
				return 0;
			});
	}

	private static int giveCompass(ServerPlayer player, Optional<ShrineType> type, boolean bypassCooldown) {
		if (type.isEmpty()) {
			if ((!bypassCooldown && !KarmaService.canReceiveShrineCompass(player)) || ShrineCompass.hasShrineCompass(player)) {
				int cooldown = KarmaService.getShrineCompassCooldownMinutes(player);
				player.sendSystemMessage(Component.literal(
					ShrineCompass.hasShrineCompass(player)
						? "You already have a shrine compass."
						: "Shrine compass cooldown: " + cooldown + " minutes."));
				return 0;
			}

			MinecraftServer server = player.level().getServer();
			Optional<ShrineSite> saved = ShrineManager.nearestShrine(
				new ShrineManager.ServerPlayerPosition(player.blockPosition().getX(), player.blockPosition().getZ()), server);
			if (saved.isPresent()) {
				ShrineCompass.give(player, saved.get(), bypassCooldown);
				player.sendSystemMessage(Component.literal("Lodestone compass calibrated to " + saved.get().statueName() + "."));
				return Command.SINGLE_SUCCESS;
			}

			ServerLevel level = player.level().getServer().getLevel(Level.OVERWORLD);
			if (level == null) {
				player.sendSystemMessage(Component.literal("The Overworld is unavailable."));
				return 0;
			}
			Optional<ShrinePredictionService.BlockPosTarget> predicted = ShrinePredictionService.nearestPredicted(
				level, player.blockPosition().getX(), player.blockPosition().getZ());
			if (predicted.isEmpty()) {
				player.sendSystemMessage(Component.literal("There are no shrine locations available."));
				return 0;
			}
			ShrineCompass.givePredicted(player, predicted.get().x(), predicted.get().z(), bypassCooldown);
			long dx = (long) predicted.get().x() - player.blockPosition().getX();
			long dz = (long) predicted.get().z() - player.blockPosition().getZ();
			long distance = Math.round(Math.sqrt(dx * dx + dz * dz));
			player.sendSystemMessage(Component.literal("Lodestone compass calibrated to the nearest potential shrine ("
				+ distance + " blocks away). It has not generated yet."));
			return Command.SINGLE_SUCCESS;
		}

		return findShrine(player, type)
			.map(site -> {
				if (!ShrineCompass.give(player, site, bypassCooldown)) {
					int cooldown = KarmaService.getShrineCompassCooldownMinutes(player);
					player.sendSystemMessage(Component.literal(
						ShrineCompass.hasShrineCompass(player)
							? "You already have a shrine compass."
							: "Shrine compass cooldown: " + cooldown + " minutes."));
					return 0;
				}
				player.sendSystemMessage(Component.literal(
					"Lodestone compass calibrated to " + site.statueName() + "."));
				return Command.SINGLE_SUCCESS;
			})
			.orElseGet(() -> {
				player.sendSystemMessage(Component.literal("There is no active " + type.get().displayName() + " to track."));
				return 0;
			});
	}

	private static Optional<ShrineSite> findShrine(ServerPlayer player, Optional<ShrineType> type) {
		ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
			player.blockPosition().getX(),
			player.blockPosition().getZ()
		);
		MinecraftServer server = player.level().getServer();
		return type
			.map(value -> ShrineManager.nearestShrine(position, server, value))
			.orElseGet(() -> ShrineManager.nearestShrine(position, server));
	}

	private static Component coordinateMessage(ServerLevel level, String prefix, net.minecraft.core.BlockPos pos) {
		// Some previously saved generated shrines may have the old world-minimum Y.
		// Do not offer a clickable command that teleports a player into the abyss.
		int y = pos.getY();
		if (level != null && y <= level.getMinY()) {
			y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		}
		// Keep the displayed and clickable command coordinates in vanilla /tp syntax:
		// space-separated X Y Z, never the human-readable comma-separated form.
		String coordinates = pos.getX() + " " + y + " " + pos.getZ();
		return Component.empty()
			.append(Component.literal(prefix))
			.append(Component.literal(coordinates).withStyle(style -> style
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/tp " + coordinates))));
	}

	private static Component ringCoordinateMessage(int x, int z) {
		String coordinates = x + " ~ " + z;
		return Component.empty()
			.append(Component.literal(coordinates).withStyle(style -> style
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/tp " + coordinates))));
	}

	private static int debugRegistered(CommandSourceStack source) {
		if (source.getServer().getLevel(Level.OVERWORLD) == null) {
			source.sendFailure(Component.literal("The Overworld is unavailable."));
			return 0;
		}

		var sites = ShrineState.get(source.getServer()).sites();
		int light = 0;
		int dark = 0;
		int neutral = 0;
		int monuments = 0;
		for (ShrineSite site : sites) {
			switch (site.type()) {
				case LIGHT -> light++;
				case DARK -> dark++;
				case NEUTRAL -> neutral++;
				case MONUMENT -> monuments++;
			}
		}

		String summary = "Registered shrine sites: " + sites.size()
			+ " | Light=" + light
			+ " Dark=" + dark
			+ " Neutral=" + neutral
			+ " Monument=" + monuments;
		source.sendSuccess(() -> Component.literal(summary), false);

		int shown = 0;
		for (ShrineSite site : sites) {
			if (shown++ >= 16) {
				source.sendSuccess(() -> Component.literal("... output truncated at 16 registered sites."), false);
				break;
			}
			source.sendSuccess(() -> coordinateMessage(source.getServer().getLevel(Level.OVERWORLD),
				site.displayName() + " -> ", site.position()), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int debugPositions(CommandSourceStack source, Optional<Boolean> monumentsOnly) {
		ServerLevel level = source.getServer().getLevel(Level.OVERWORLD);
		if (level == null) {
			source.sendFailure(Component.literal("The Overworld is unavailable."));
			return 0;
		}
		List<ShrineRingPlacement.RingCandidate> candidates = new java.util.ArrayList<>();
		try {
			var chunkMap = ((akisei.karmacurrency.mixin.ServerChunkCacheAccessor) level.getChunkSource()).karmaiscurrency$getChunkMap();

			var state = ((akisei.karmacurrency.mixin.ChunkMapAccessor) chunkMap).karmaiscurrency$getChunkGeneratorState();
			for (var rawSet : state.possibleStructureSets()) {
				var set = rawSet.value();
				if (set.placement() instanceof ShrineRingPlacement placement) {
					if (monumentsOnly.isEmpty() || placement.monumentPlacement() == monumentsOnly.get()) {
						candidates.addAll(placement.candidatePositionsByRing(level.getSeed()));
					}
				}
			}
		} catch (RuntimeException exception) {
			source.sendFailure(Component.literal("Could not read shrine ring placement: " + exception.getMessage()));
			return 0;
		}
		candidates.sort(java.util.Comparator.comparingInt(ShrineRingPlacement.RingCandidate::ring)
				.thenComparingInt(c -> c.chunk().x())
				.thenComparingInt(c -> c.chunk().z()));

		source.sendSuccess(() -> Component.literal(
			"Worldgen candidates: " + candidates.size()
				+ (monumentsOnly.isPresent() ? (monumentsOnly.get() ? " monuments" : " normal shrines") : " total")), false);

		int shown = 0;
		for (ShrineRingPlacement.RingCandidate candidate : candidates) {
			if (shown++ >= 32) {
				source.sendSuccess(() -> Component.literal("... output truncated at 32 candidates; use /kd shrine debug ring <n> for a specific ring."), false);
				break;
			}
			ChunkPos chunk = candidate.chunk();
			String type = monumentsOnly.isPresent() && monumentsOnly.get() ? "MONUMENT" : "SHRINE";
			source.sendSuccess(() -> Component.empty()
				.append(Component.literal("Ring " + candidate.ring() + " " + type + " -> "))
				.append(coordinateMessage(level, "", new BlockPos(chunk.getMiddleBlockX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ()), chunk.getMiddleBlockZ()))), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int debugRing(CommandSourceStack source, int ring) {
		ServerLevel level = source.getServer().getLevel(Level.OVERWORLD);
		if (level == null) {
			source.sendFailure(Component.literal("The Overworld is unavailable."));
			return 0;
		}
		List<ShrineRingPlacement.RingCandidate> normal = new java.util.ArrayList<>();
		List<ShrineRingPlacement.RingCandidate> monuments = new java.util.ArrayList<>();
		try {
			var chunkMap = ((akisei.karmacurrency.mixin.ServerChunkCacheAccessor) level.getChunkSource()).karmaiscurrency$getChunkMap();

			var state = ((akisei.karmacurrency.mixin.ChunkMapAccessor) chunkMap).karmaiscurrency$getChunkGeneratorState();
			for (var rawSet : state.possibleStructureSets()) {
				var set = rawSet.value();
				if (set.placement() instanceof ShrineRingPlacement placement) {
					List<ShrineRingPlacement.RingCandidate> target = placement.monumentPlacement() ? monuments : normal;
					target.addAll(placement.candidatePositionsByRing(level.getSeed()).stream()
						.filter(candidate -> candidate.ring() == ring).toList());
				}
			}
		} catch (RuntimeException exception) {
			source.sendFailure(Component.literal("Could not read shrine ring placement: " + exception.getMessage()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
			"Ring " + ring + ": " + normal.size() + " shrine candidates, " + monuments.size() + " Monument candidates."), false);
		for (int i = 0; i < normal.size(); i++) {
			ChunkPos chunk = normal.get(i).chunk();
			final int index = i + 1;
			int x = chunk.getMiddleBlockX();
			int z = chunk.getMiddleBlockZ();
			source.sendSuccess(() -> Component.empty()
				.append(Component.literal("Shrine " + index + " -> "))
				.append(ringCoordinateMessage(x, z)), false);
		}
		for (int i = 0; i < monuments.size(); i++) {
			ChunkPos chunk = monuments.get(i).chunk();
			final int index = i + 1;
			int x = chunk.getMiddleBlockX();
			int z = chunk.getMiddleBlockZ();
			source.sendSuccess(() -> Component.empty()
				.append(Component.literal("Monument " + index + " -> "))
				.append(ringCoordinateMessage(x, z)), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int debugTeleport(CommandSourceStack source, boolean monument, int ring, int index) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
			source.sendFailure(Component.literal("This command must be run by a player."));
			return 0;
		}
		ServerLevel level = source.getServer().getLevel(Level.OVERWORLD);
		if (level == null) {
			source.sendFailure(Component.literal("The Overworld is unavailable."));
			return 0;
		}
		List<ChunkPos> positions = new java.util.ArrayList<>();
		try {
			var chunkMap = ((akisei.karmacurrency.mixin.ServerChunkCacheAccessor) level.getChunkSource()).karmaiscurrency$getChunkMap();

			var state = ((akisei.karmacurrency.mixin.ChunkMapAccessor) chunkMap).karmaiscurrency$getChunkGeneratorState();
			for (var rawSet : state.possibleStructureSets()) {
				var set = rawSet.value();
				if (set.placement() instanceof ShrineRingPlacement placement && placement.monumentPlacement() == monument) {
					positions.addAll(placement.candidatePositionsForRing(level.getSeed(), ring));
				}
			}
		} catch (RuntimeException exception) {
			source.sendFailure(Component.literal("Could not read shrine ring placement: " + exception.getMessage()));
			return 0;
		}
		if (index < 1 || index > positions.size()) {
			source.sendFailure(Component.literal("Ring " + ring + " only has " + positions.size() + " " + (monument ? "Monument" : "shrine") + " candidates."));
			return 0;
		}
		ChunkPos chunk = positions.get(index - 1);
		int x = chunk.getMiddleBlockX();
		int z = chunk.getMiddleBlockZ();
		player.teleportTo(x + 0.5, player.getY(), z + 0.5);
		source.sendSuccess(() -> Component.literal(
			"Teleported to " + (monument ? "Monument" : "shrine") + " ring " + ring + " candidate " + index
				+ " at " + x + ", " + z), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int spawnDefault(CommandSourceStack source, ShrineType type)
		throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return spawn(source, type, ShrineConfig.current().commandSpawnDefaultRadius,
			player.blockPosition().getX(), player.blockPosition().getZ(), "player");
	}

	private static int spawnAroundPlayer(CommandSourceStack source, ShrineType type, int radius)
		throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return spawn(source, type, radius, player.blockPosition().getX(), player.blockPosition().getZ(), "player");
	}

	private static int spawnAroundWorldSpawn(CommandSourceStack source, ShrineType type, int radius) {
		ServerLevel level = source.getServer().getLevel(Level.OVERWORLD);
		if (level == null) {
			source.sendFailure(Component.literal("The Overworld is unavailable."));
			return 0;
		}
		BlockPos spawn = level.getRespawnData().pos();
		return spawn(source, type, radius, spawn.getX(), spawn.getZ(), "world spawn");
	}

	private static int spawn(
		CommandSourceStack source, ShrineType type, int radius, int centerX, int centerZ, String centerName) {
		if (radius > ShrineConfig.current().commandSpawnMaxRadius) {
			source.sendFailure(Component.literal(
				"Radius exceeds the current maximum of " + ShrineConfig.current().commandSpawnMaxRadius + " blocks."));
			return 0;
		}
		try {
			ShrineSite shrine = ShrineManager.createOperatorShrine(
				source.getServer(), type, radius, centerX, centerZ);
			source.sendSuccess(() -> Component.empty()
				.append(Component.literal("Created " + type.displayName() + " at "))
				.append(coordinateMessage(source.getServer().getLevel(Level.OVERWORLD), "", shrine.position()))
				.append(Component.literal("\nCenter: " + centerName + "\nRadius: " + radius)), true);
			return Command.SINGLE_SUCCESS;
		} catch (IllegalStateException exception) {
			source.sendFailure(Component.literal(exception.getMessage()));
			return 0;
		}
	}
}
