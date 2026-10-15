package akisei.karmacurrency;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Handles saved shrine discovery and shrine protection. */
public final class ShrineManager {
	private static final Identifier[] LIGHT_STRUCTURES = {
		KarmaIsCurrency.id("lightshrine1"),
		KarmaIsCurrency.id("lightshrine2"),
		KarmaIsCurrency.id("lightshrine3")
	};
	private static final Identifier[] DARK_STRUCTURES = {
		KarmaIsCurrency.id("darkshrine1"),
		KarmaIsCurrency.id("darkshrine2")
	};
	private static final Identifier NEUTRAL_STRUCTURE = KarmaIsCurrency.id("neutralshrine1");
	private static final Identifier MONUMENT_STRUCTURE = KarmaIsCurrency.id("monumentv2");
	private static final Identifier WORLDGEN_SHRINE_STRUCTURE = KarmaIsCurrency.id("shrine");
	private static final Identifier WORLDGEN_MONUMENT_STRUCTURE = KarmaIsCurrency.id("monument");
	private static ShrineConfig config = ShrineConfig.current();
	private static final Map<MinecraftServer, Map<ChunkPos, Integer>> PENDING_GENERATED_DISCOVERY = new java.util.WeakHashMap<>();
	private static final int GENERATED_DISCOVERY_RETRIES = 20;
	private static final Map<MinecraftServer, java.util.List<PendingShrine>> PENDING_SHRINE_FINALIZATION = new java.util.WeakHashMap<>();
	private static final AttachmentType<Boolean> SHRINE_DISCOVERY_CHECKED = AttachmentRegistry.create(
		KarmaIsCurrency.id("shrine_discovery_checked")
	);
	private static final Map<java.util.UUID, ChunkPos> LAST_PLAYER_CHUNKS = new HashMap<>();
	private static final Map<java.util.UUID, java.util.Set<Long>> PLAYER_NEARBY_SHRINES = new HashMap<>();
	private static final Map<Long, Long> LAST_SHRINE_REPAIR_TICKS = new HashMap<>();
	private static final int SHRINE_PROXIMITY_CHUNKS = 2;
	private static final long SHRINE_REPAIR_COOLDOWN_TICKS = 1200L;

	private ShrineManager() {
	}

	public static void register() {
		config = ShrineConfig.current();

		ServerLifecycleEvents.SERVER_STARTED.register(ShrineManager::migrateLodestonePositions);

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PENDING_GENERATED_DISCOVERY.remove(server);
			PENDING_SHRINE_FINALIZATION.remove(server);
			LAST_PLAYER_CHUNKS.clear();
			PLAYER_NEARBY_SHRINES.clear();
			LAST_SHRINE_REPAIR_TICKS.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			processPendingGeneratedDiscovery(server);
			processPendingShrineFinalization(server);
			processPlayerShrineProximity(server);
		});

		PlayerBlockBreakEvents.BEFORE.register(ShrineManager::protectShrine);

		// Worldgen-created shrines are registered when their structure start becomes part of a
		// loaded server chunk. This keeps ShrineState as the single source of truth without
		// modifying the deterministic ring-placement system.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyLoaded) -> queueGeneratedShrineDiscovery(level, chunk, newlyLoaded));
	}

	/**
	 * Uses the deterministic ring prediction only as a cheap proximity trigger. We inspect
	 * predicted candidate chunks only when a player changes chunks; never force-load a chunk.
	 */
	private static void processPlayerShrineProximity(MinecraftServer server) {
		if (!config.enabled) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level().dimension() != Level.OVERWORLD) {
				continue;
			}

			ServerLevel level = (ServerLevel) player.level();
			BlockPos currentBlock = player.blockPosition();
			ChunkPos currentChunk = ChunkPos.containing(currentBlock);
			ChunkPos previousChunk = LAST_PLAYER_CHUNKS.put(player.getUUID(), currentChunk);

			if (previousChunk == null || !previousChunk.equals(currentChunk)) {
				processNearbyPredictedShrines(level, player, currentChunk);
			}
		}
	}

	private static void processNearbyPredictedShrines(ServerLevel level, ServerPlayer player, ChunkPos playerChunk) {
		List<ShrinePredictionService.BlockPosTarget> predicted =
			ShrinePredictionService.predictedPositionsNear(level, playerChunk.x(), playerChunk.z(), SHRINE_PROXIMITY_CHUNKS);
	
		for (ShrinePredictionService.BlockPosTarget target : predicted) {
			int chunkX = target.x() >> 4;
			int chunkZ = target.z() >> 4;
			LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
			if (loaded == null) {
				continue;
			}
			discoverGeneratedShrines(level, loaded, true);
		}

		Set<Long> nearby = new java.util.HashSet<>();
		int playerChunkX = playerChunk.x();
		int playerChunkZ = playerChunk.z();
		ShrineState state = ShrineState.get(level.getServer());
		for (ShrineSite site : state.sites()) {
			int shrineChunkX = site.position().getX() >> 4;
			int shrineChunkZ = site.position().getZ() >> 4;
			if (Math.abs(shrineChunkX - playerChunkX) > SHRINE_PROXIMITY_CHUNKS
				|| Math.abs(shrineChunkZ - playerChunkZ) > SHRINE_PROXIMITY_CHUNKS) {
				continue;
			}
			long key = site.position().asLong();
			nearby.add(key);
		}

		Set<Long> previous = PLAYER_NEARBY_SHRINES.computeIfAbsent(player.getUUID(), ignored -> new java.util.HashSet<>());
		for (Long key : nearby) {
			if (previous.contains(key)) {
				continue;
			}
			state.sites().stream()
				.filter(site -> site.position().asLong() == key)
				.findFirst()
				.ifPresent(site -> {
					ensureShrineCore(level.getServer(), site);
					ensureLodestone(level.getServer(), site);
				});
		}
		previous.clear();
		previous.addAll(nearby);
	}

	/** True when the position is inside any saved shrine protection footprint. */
	public static boolean isShrineProtected(Level level, BlockPos pos) {
		if (level.isClientSide() || level.getServer() == null || level.dimension() != Level.OVERWORLD) {
			return false;
		}
		if (!KarmaGameRules.enabled(level, KarmaGameRules.SHRINE_PROTECTION_ENABLED)) {
			return false;
		}
		for (ShrineSite site : ShrineState.get(level.getServer()).sites()) {
			if (site.protectsHorizontal(pos)) {
				return true;
			}
		}
		return false;
	}

	public static void reloadConfig() {
		config = ShrineConfig.current();
	}

	public static Optional<ShrineSite> nearestShrine(ServerPlayerPosition pos, MinecraftServer server) {
		return nearestShrine(pos, server, null);
	}

	public static Optional<ShrineSite> nearestShrine(ServerPlayerPosition pos, MinecraftServer server, ShrineType type) {
		return ShrineState.get(server).sites().stream()
			.filter(site -> type == null || site.type() == type)
			.min(Comparator.comparingDouble(site -> 
				Math.sqrt(Math.pow(site.position().getX() - pos.x(), 2) + Math.pow(site.position().getZ() - pos.z(), 2))));
	}

	public static Optional<ShrineTarget> nearestShrineTarget(ServerPlayerPosition pos, MinecraftServer server) {
		return nearestShrineTarget(pos, server, null);
	}

	public static Optional<ShrineTarget> nearestShrineTarget(ServerPlayerPosition pos, MinecraftServer server, ShrineType type) {
		Optional<ShrineSite> nearest = nearestShrine(pos, server, type);
		if (nearest.isPresent()) {
			return Optional.of(ShrineTarget.generated(nearest.get()));
		}

		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null) return Optional.empty();

		Optional<ShrinePredictionService.BlockPosTarget> predicted = ShrinePredictionService.nearestPredicted(
			level, pos.x(), pos.z());
		
		return predicted.map(p -> {
			ShrineType pType = (type == null) ? null : type; // Simplified, ideally we'd know the predicted type
			return new ShrineTarget(new BlockPos(p.x(), 64, p.z()), Optional.empty(), true, 
				Optional.ofNullable(pType));
		});
	}

	public static ShrineSite createOperatorShrine(MinecraftServer server, ShrineType type, int radius, int centerX, int centerZ) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null) throw new IllegalStateException("Overworld is unavailable");

		// Search only the requested radius. The first candidate is the requested
		// center, so a guaranteed command spawn always has a deterministic fallback.
		for (int r = 0; r <= radius; r += 2) {
			for (int angle = 0; angle < 360; angle += 15) {
				double rad = Math.toRadians(angle);
				int x = centerX + (int) Math.round(Math.cos(rad) * r);
				int z = centerZ + (int) Math.round(Math.sin(rad) * r);
				BlockPos surface = surfaceCore(level, x, z);

				if (!isShrineProtected(level, surface)) {
					Identifier structureId = switch (type) {
						case LIGHT -> LIGHT_STRUCTURES[0];
						case DARK -> DARK_STRUCTURES[0];
						case NEUTRAL -> NEUTRAL_STRUCTURE;
						case MONUMENT -> MONUMENT_STRUCTURE;
					};

					ShrineState state = ShrineState.get(server);
					Vec3i size = structureSize(structureId);
					BlockPos templateOrigin = surface.offset(-(size.getX() / 2), -1, -(size.getZ() / 2));
					boolean placedTemplate = placeStructure(level, templateOrigin, structureId);
					if (!placedTemplate) {
						buildForcePedestal(level, surface, type);
					}

					// The operator shrine is centered on the requested placement point. Keep
					// its lodestone on that same X/Z center rather than inheriting worldgen
					// template offsets that can make a command spawn look misaligned.
					BlockPos core = surface;
					BlockPos lodestonePos = lodestonePosition(core, type);

					ShrineSite site = new ShrineSite(core, type, protectionRadius(type),
						lodestonePos,
						StatueNames.chooseUnique(type, core.asLong(), state.sites().stream()
 						.map(ShrineSite::statueName).toList()), structureId, null, null,
 						placedTemplate ? protectionBlocks(level, structureId, templateOrigin, core) : fallbackProtectionBlocks(core));

					state.add(site);
					ensureShrineCore(server, site);
					ensureLodestone(server, site);
					return site;
				}
			}
		}
		throw new IllegalStateException("Could not find a suitable location for the operator shrine within radius " + radius);
	}

	private static void repairShrineIfNeeded(MinecraftServer server, ShrineSite site) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null || !level.hasChunkAt(site.position())) {
			return;
		}

		long key = site.position().asLong();
		long now = server.getTickCount();
		Long last = LAST_SHRINE_REPAIR_TICKS.get(key);
		boolean coreMissing = !level.getBlockState(site.position()).is(ShrineBlocks.SHRINE_CORE);
		// Proximity processing must never rewrite an intact generated structure. A
		// previous repair pass re-placed every structure after the cooldown elapsed,
		// which overwrote shrines as players revisited their chunks.
		if (!coreMissing) {
			ensureLodestone(server, site);
			return;
		}
		if (last != null && now - last < SHRINE_REPAIR_COOLDOWN_TICKS) {
			return;
		}

		boolean repaired = false;
		Optional<StructureStart> start = findGeneratedStartNear(level, site);
		if (start.isPresent()) {
			StructureStart structureStart = start.get();
			BoundingBox bounds = structureStart.getBoundingBox();
			if (bounds != null) {
				for (int cx = Math.floorDiv(bounds.minX(), 16); cx <= Math.floorDiv(bounds.maxX(), 16); cx++) {
					for (int cz = Math.floorDiv(bounds.minZ(), 16); cz <= Math.floorDiv(bounds.maxZ(), 16); cz++) {
						LevelChunk loaded = level.getChunkSource().getChunkNow(cx, cz);
						if (loaded == null) {
							continue;
						}
						BoundingBox chunkBounds = new BoundingBox(
							loaded.getPos().getMinBlockX(), level.getMinY(), loaded.getPos().getMinBlockZ(),
							loaded.getPos().getMaxBlockX(), level.getMaxY(), loaded.getPos().getMaxBlockZ());
						structureStart.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(), chunkBounds, loaded.getPos());
						repaired = true;
					}
				}
			}
		} else if (site.structureId().isPresent()) {
			// Manual/operator shrines do not have StructureStarts. Reuse the exact template
			// that was recorded when the shrine was created instead of guessing a variant.
			try {
				repaired = placeStructure(level, site.position(), site.structureId().get());
			} catch (RuntimeException exception) {
				KarmaIsCurrency.LOGGER.warn("Could not repair manual shrine '{}' at {}", site.statueName(), site.position(), exception);
			}
		}

		ensureShrineCore(server, site);
		ensureLodestone(server, site);
		if (repaired || coreMissing) {
			LAST_SHRINE_REPAIR_TICKS.put(key, now);
		}
	}

	private static Optional<StructureStart> findGeneratedStartNear(ServerLevel level, ShrineSite site) {
		Registry<Structure> structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		BlockPos core = site.position();
		int centerChunkX = core.getX() >> 4;
		int centerChunkZ = core.getZ() >> 4;
		for (int dx = -SHRINE_PROXIMITY_CHUNKS; dx <= SHRINE_PROXIMITY_CHUNKS; dx++) {
			for (int dz = -SHRINE_PROXIMITY_CHUNKS; dz <= SHRINE_PROXIMITY_CHUNKS; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
				if (chunk == null) {
					continue;
				}
				for (var entry : chunk.getAllStarts().entrySet()) {
					Identifier structureId = structureRegistry.getKey(entry.getKey());
					if (!WORLDGEN_SHRINE_STRUCTURE.equals(structureId) && !WORLDGEN_MONUMENT_STRUCTURE.equals(structureId)) {
						continue;
					}
					StructureStart start = entry.getValue();
					if (start == null || !start.isValid() || start.getBoundingBox() == null) {
						continue;
					}
					BoundingBox bounds = start.getBoundingBox();
					if (core.getX() < bounds.minX() || core.getX() > bounds.maxX()
						|| core.getZ() < bounds.minZ() || core.getZ() > bounds.maxZ()) {
						continue;
					}
					ShrineType discoveredType;
					if (WORLDGEN_MONUMENT_STRUCTURE.equals(structureId)) {
						discoveredType = ShrineType.MONUMENT;
					} else {
						Optional<Identifier> template = detectGeneratedTemplate(start);
						discoveredType = template.flatMap(ShrineManager::typeForTemplate).orElse(null);
					}
					if (discoveredType != site.type()) {
						continue;
					}
					if (site.structureId().isPresent()) {
						Optional<Identifier> template = detectGeneratedTemplate(start);
						if (template.isPresent() && !template.get().equals(site.structureId().get())) {
							continue;
						}
					}
					return Optional.of(start);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * ServerChunkEvents fires after the chunk is already in the server world.
	 * Keep discovery itself lightweight and never force-load chunks from this callback.
	 */
	private static void queueGeneratedShrineDiscovery(ServerLevel level, LevelChunk chunk, boolean newlyLoaded) {
		if (level.dimension() != Level.OVERWORLD || !config.enabled) {
			return;
		}

		ChunkPos chunkPos = chunk.getPos();
		discoverGeneratedShrines(level, chunk, newlyLoaded);

		// A loaded chunk can still be observed before its structure starts have
		// finished attaching. Keep a bounded retry queue so ring shrines
		// cannot be permanently missed by an early discovery pass.
		if (!chunk.getAttachedOrElse(SHRINE_DISCOVERY_CHECKED, false)) {
			PENDING_GENERATED_DISCOVERY
				.computeIfAbsent(level.getServer(), ignored -> new HashMap<>())
				.putIfAbsent(chunkPos, GENERATED_DISCOVERY_RETRIES);
		}
	}

	private static void processPendingGeneratedDiscovery(MinecraftServer server) {
		Map<ChunkPos, Integer> pending = PENDING_GENERATED_DISCOVERY.get(server);
		if (pending == null || pending.isEmpty()) {
			return;
		}

		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null || !config.enabled) {
			pending.clear();
			return;
		}

		var iterator = pending.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			ChunkPos pos = entry.getKey();
			int retries = entry.getValue();

			// Do not force-load an explored chunk solely for discovery. CHUNK_LOAD will
			// queue it again when it becomes active.
			BlockPos probe = new BlockPos(pos.getMiddleBlockX(), level.getMinY(), pos.getMiddleBlockZ());
			if (!level.hasChunkAt(probe)) {
				iterator.remove();
				continue;
			}

			// getChunk(...) is synchronous and can create a server-side chunk-load ticket.
			// Never use it from the server end-tick discovery pass: with C2ME this can
			// stall the main thread while workers are upgrading the same chunk.
			LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
			if (chunk == null) {
				// The chunk is no longer fully loaded. Leave the entry pending and let the
				// normal CHUNK_LOAD callback re-queue/finish discovery when it is loaded.
				continue;
			}

			discoverGeneratedShrines(level, chunk, true);
			if (chunk.getAttachedOrElse(SHRINE_DISCOVERY_CHECKED, false) || retries <= 1) {
				iterator.remove();
			} else {
				entry.setValue(retries - 1);
			}
		}

		if (pending.isEmpty()) {
			PENDING_GENERATED_DISCOVERY.remove(server);
		}
	}

	private static void processPendingShrineFinalization(MinecraftServer server) {
		java.util.List<PendingShrine> pending = PENDING_SHRINE_FINALIZATION.get(server);
		if (pending == null || pending.isEmpty()) {
			return;
		}

		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null || !config.enabled) {
			pending.clear();
			PENDING_SHRINE_FINALIZATION.remove(server);
			return;
		}

		var iterator = pending.iterator();
		while (iterator.hasNext()) {
			PendingShrine pendingShrine = iterator.next();
			iterator.remove();

			BlockPos core = pendingShrine.core();

			// Only finalize if the chunk is still loaded. If not, the shrine will be
			// rediscovered when the chunk loads again.
			if (!level.hasChunkAt(core)) {
				continue;
			}

			ShrineState state = ShrineState.get(server);
			ShrineSite site = createSite(core, pendingShrine.type(), state, pendingShrine.bounds(), pendingShrine.templateId(),
				pendingShrine.protectionBlocks());
			if (state.containsHorizontalPosition(core)) {
				clearObsoleteCore(level, state, core);
				state.replaceAtPosition(site);
				ensureShrineCore(server, site);
				ensureLodestone(server, site);
				logFinalizedShrine(level, site, pendingShrine.chunkPos(), "replaced at exact core");
				continue;
			}
			if (state.replaceNearby(site, 64)) {
				ensureShrineCore(server, site);
				ensureLodestone(server, site);
				logFinalizedShrine(level, site, pendingShrine.chunkPos(), "migrated nearby saved site to exact core");
				continue;
			}
			state.add(site);
			ensureShrineCore(server, site);
			ensureLodestone(server, site);
			logFinalizedShrine(level, site, pendingShrine.chunkPos(), "registered");
		}

		if (pending.isEmpty()) {
			PENDING_SHRINE_FINALIZATION.remove(server);
		}
	}


	private static void clearObsoleteCore(ServerLevel level, ShrineState state, BlockPos core) {
		state.sites().stream()
			.filter(existing -> existing.position().getX() == core.getX()
				&& existing.position().getZ() == core.getZ()
				&& !existing.position().equals(core))
			.map(ShrineSite::position)
			.filter(level::hasChunkAt)
			.filter(position -> level.getBlockState(position).is(ShrineBlocks.SHRINE_CORE))
			.forEach(position -> level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
	}

	/**
	 * Performs an on-demand discovery pass around a player interaction.
	 *
	 * Ring shrines are deterministic worldgen structures, but their StructureStart can
	 * become available independently of the chunk lifecycle callback that normally discovers
	 * them. A player clicking a visible shrine is a stronger signal: inspect the loaded chunk
	 * and its immediate neighbors now, then persist any shrine starts we find.
	 */
	public static void discoverGeneratedShrinesNear(ServerLevel level, BlockPos position) {
		if (level.dimension() != Level.OVERWORLD || !config.enabled) {
			return;
		}

		int chunkX = position.getX() >> 4;
		int chunkZ = position.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos probe = new BlockPos(((chunkX + dx) << 4) + 8, level.getMinY(), ((chunkZ + dz) << 4) + 8);
				if (!level.hasChunkAt(probe)) {
					continue;
				}
				// This method may run from a player interaction, but discovery must still
				// never synchronously load a chunk that has dropped out of memory.
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz);
				if (chunk == null) {
					continue;
				}
				discoverGeneratedShrines(level, chunk, true);
			}
		}
	}

	private static void discoverGeneratedShrines(ServerLevel level, LevelChunk chunk, boolean newlyLoaded) {
		if (level.dimension() != Level.OVERWORLD || !config.enabled) {
			return;
		}

		if (chunk.getAttachedOrElse(SHRINE_DISCOVERY_CHECKED, false)) {
			return;
		}

		if (chunk.getAllStarts().isEmpty()) {
			return;
		}

		Registry<Structure> structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		boolean queuedRelevantStructure = false;
		for (var entry : chunk.getAllStarts().entrySet()) {
			Identifier structureId = structureRegistry.getKey(entry.getKey());
			if (structureId == null) {
				continue;
			}

			StructureStart start = entry.getValue();
			if (start == null || !start.isValid()) {
				continue;
			}

			ShrineType type;
			Optional<Identifier> templateId;
			if (WORLDGEN_MONUMENT_STRUCTURE.equals(structureId)) {
				type = ShrineType.MONUMENT;
				templateId = detectGeneratedTemplate(start);
			} else if (WORLDGEN_SHRINE_STRUCTURE.equals(structureId)) {
				templateId = detectGeneratedTemplate(start);
				type = templateId.flatMap(ShrineManager::typeForTemplate).orElse(null);
			} else {
				continue;
			}
			StructureLog.info("Candidate chunk " + chunk.getPos() + " has structure start id=" + structureId
				+ ", pieces=" + start.getPieces().size() + ", bounds=" + start.getBoundingBox());

			if (type == null) {
				StructureLog.warn("Could not identify generated shrine type for structure start at " + start.getChunkPos());
				continue;
			}

			if (start.getBoundingBox() == null) {
				StructureLog.warn("Could not determine generated " + type.displayName()
					+ " core position for structure start at " + start.getChunkPos());
				continue;
			}

			Optional<BlockPos> core = findTemplateCore(level, start);
			if (core.isEmpty()) {
				StructureLog.warn("Generated " + type.displayName() + " at " + start.getChunkPos()
					+ " has no structure_void core in its placed template; leaving the chunk retryable");
				continue;
			}

			// The template marker is transformed by the same origin and rotation used by
			// jigsaw placement. It is authoritative for the physical shrine and avoids
			// terrain-height queries from the chunk lifecycle path.
			StructureLog.info("Structure id=" + structureId + ", template=" + templateId.orElse(null)
				+ ", bounds=" + start.getBoundingBox() + ", core=" + core.get());
			PENDING_SHRINE_FINALIZATION
				.computeIfAbsent(level.getServer(), ignored -> new java.util.ArrayList<>())
				.add(new PendingShrine(type, templateId.orElse(null),
					start.getBoundingBox(), start.getChunkPos(), core.get(), protectionBlocks(level, start, core.get())));
			queuedRelevantStructure = true;
		}

		// Only mark the chunk checked after a relevant structure was successfully
		// identified and queued. Failed template/type identification remains retryable.
		if (queuedRelevantStructure) {
			chunk.setAttached(SHRINE_DISCOVERY_CHECKED, true);
		}
	}

	private static Optional<Identifier> detectGeneratedTemplate(StructureStart start) {
		for (StructurePiece rawPiece : start.getPieces()) {
			if (!(rawPiece instanceof PoolElementStructurePiece piece)) continue;
			if (piece.getElement() instanceof SinglePoolElement single) {
				return Optional.of(single.getTemplateLocation());
			}
		}
		return Optional.empty();
	}

	/** Finds the hidden core at the exact transformed location used by the jigsaw piece. */
	private static Optional<BlockPos> findTemplateCore(ServerLevel level, StructureStart start) {
		for (StructurePiece rawPiece : start.getPieces()) {
			if (!(rawPiece instanceof PoolElementStructurePiece piece)) {
				continue;
			}
			if (!(piece.getElement() instanceof SinglePoolElement single)) {
				continue;
			}
			StructureTemplate template = level.getStructureManager().get(single.getTemplateLocation()).orElse(null);
			if (template == null) {
				continue;
			}
			StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(piece.getRotation());
			List<StructureTemplate.StructureBlockInfo> cores = template.filterBlocks(
				piece.getPosition(), settings, ShrineBlocks.SHRINE_CORE);
			if (!cores.isEmpty()) {
				return Optional.of(cores.getFirst().pos());
			}
		}
		return Optional.empty();
	}

	private static Optional<ShrineType> typeForTemplate(Identifier template) {
		if (java.util.Arrays.asList(LIGHT_STRUCTURES).contains(template)) return Optional.of(ShrineType.LIGHT);
		if (java.util.Arrays.asList(DARK_STRUCTURES).contains(template)) return Optional.of(ShrineType.DARK);
		if (template.equals(NEUTRAL_STRUCTURE) || template.equals(KarmaIsCurrency.id("neutralshrine2"))) return Optional.of(ShrineType.NEUTRAL);
		if (template.equals(MONUMENT_STRUCTURE) || template.equals(KarmaIsCurrency.id("monument2"))) return Optional.of(ShrineType.MONUMENT);
		return Optional.empty();
	}

	private static BlockPos surfaceCore(ServerLevel level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		// MOTION_BLOCKING_NO_LEAVES still stops on tree trunks. Walk through
		// vegetation so a shrine is anchored to terrain instead of being placed
		// on top of a tree.
		while (y > level.getMinY() + 1 && isVegetation(level.getBlockState(new BlockPos(x, y - 1, z)))) {
			y--;
		}
		return new BlockPos(x, y, z);
	}

	private static BlockPos lodestonePosition(BlockPos core, ShrineType type) {
		return lodestonePosition(core, core, type);
	}

	/**
	 * Computes the functional lodestone from a logical core and an optional physical center.
	 * Operator shrines use the core itself; naturally generated shrines pass the structure
	 * bounding-box center so a template marker in a corner cannot move the lodestone there.
	 */
	private static BlockPos lodestonePosition(BlockPos core, BlockPos center, ShrineType type) {
		int index = type.ordinal();
		return new BlockPos(
			center.getX(),
			core.getY() + Math.max(1, config.lodestoneOffsetY[index]),
			center.getZ());
	}

	private static BlockPos lodestonePosition(ShrineSite site) {
		if (site.protectionBounds().isPresent() && site.structureId().isPresent()) {
			ShrineSite.ProtectionBounds bounds = site.protectionBounds().get();
			BlockPos core = site.position();
			int centerX = Math.floorDiv(
				bounds.minX() + bounds.maxX() + 2 * core.getX(), 2);
			int centerZ = Math.floorDiv(
				bounds.minZ() + bounds.maxZ() + 2 * core.getZ(), 2);
			return lodestonePosition(core, new BlockPos(centerX, core.getY(), centerZ), site.type());
		}
		return lodestonePosition(site.position(), site.type());
	}

	private static void migrateLodestonePositions(MinecraftServer server) {
		ShrineState state = ShrineState.get(server);
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null) return;

		for (ShrineSite site : state.sites()) {
			BlockPos desired = lodestonePosition(site);
			BlockPos current = site.actualLodestonePosition();
			if (desired.equals(current)) {
				continue;
			}

			// Lodestones are KIC-owned functional markers, not template blocks. When
			// migrating an old naturally-generated shrine, always remove the previous
			// KIC lodestone so the shrine cannot retain a second functional core.
			if (level.hasChunkAt(current) && level.getBlockState(current).is(Blocks.LODESTONE)) {
				level.setBlock(current, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}

			ShrineSite migrated = new ShrineSite(site.position(), site.type(), site.protectionRadius(),
				desired, site.statueName(), site.structureId().orElse(null),
				site.protectionBounds().orElse(null), site.protectionMask().orElse(null),
				site.protectionBlocks().orElse(null));
			state.replaceAtPosition(migrated);
			ensureLodestone(server, migrated);
		}
	}

	private static ShrineSite createSite(BlockPos core, ShrineType type, ShrineState state,
		net.minecraft.world.level.levelgen.structure.BoundingBox bounds, Identifier templateId,
		ShrineSite.ProtectionBlocks protectionBlocks) {
		ShrineSite.ProtectionBounds relative = new ShrineSite.ProtectionBounds(
			bounds.minX() - core.getX(), bounds.maxX() - core.getX(),
			bounds.minZ() - core.getZ(), bounds.maxZ() - core.getZ(),
			bounds.minY() - core.getY(), bounds.maxY() - core.getY());
		BlockPos structureCenter = new BlockPos(
			Math.floorDiv(bounds.minX() + bounds.maxX(), 2),
			core.getY(),
			Math.floorDiv(bounds.minZ() + bounds.maxZ(), 2));
		BlockPos lodestone = lodestonePosition(core, structureCenter, type);
		ShrineSite.ProtectionMask mask = protectionMask(templateId, bounds, core);
		return new ShrineSite(core, type, protectionRadius(type), lodestone,
			StatueNames.chooseUnique(type, core.asLong(), state.sites().stream()
			.map(ShrineSite::statueName).toList()), templateId, relative, mask, protectionBlocks);
	}

	/** Captures exact transformed non-air template blocks once, at structure registration time. */
	private static ShrineSite.ProtectionBlocks protectionBlocks(ServerLevel level, StructureStart start, BlockPos core) {
		Set<Long> positions = new java.util.LinkedHashSet<>();
		for (StructurePiece rawPiece : start.getPieces()) {
			if (!(rawPiece instanceof PoolElementStructurePiece piece)
				|| !(piece.getElement() instanceof SinglePoolElement single)) continue;
			StructureTemplate template = level.getStructureManager().get(single.getTemplateLocation()).orElse(null);
			if (template == null) continue;
			StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(piece.getRotation());
			for (Block block : BuiltInRegistries.BLOCK) {
				if (block == Blocks.AIR) continue;
				for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(piece.getPosition(), settings, block)) {
					if (!info.state().isAir() || info.state().is(ShrineBlocks.SHRINE_CORE)) {
						positions.add(info.pos().subtract(core).asLong());
					}
				}
			}
		}
		positions.add(BlockPos.ZERO.asLong());
		return new ShrineSite.ProtectionBlocks(List.copyOf(positions));
	}

	private static ShrineSite.ProtectionBlocks protectionBlocks(ServerLevel level, Identifier structureId,
			BlockPos templateOrigin, BlockPos core) {
		StructureTemplate template = level.getStructureManager().get(structureId).orElse(null);
		if (template == null) return fallbackProtectionBlocks(core);
		Set<Long> positions = new java.util.LinkedHashSet<>();
		StructurePlaceSettings settings = new StructurePlaceSettings();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block == Blocks.AIR) continue;
			for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(templateOrigin, settings, block)) {
				if (!info.state().isAir() || info.state().is(ShrineBlocks.SHRINE_CORE)) {
					positions.add(info.pos().subtract(core).asLong());
				}
			}
		}
		positions.add(BlockPos.ZERO.asLong());
		return new ShrineSite.ProtectionBlocks(List.copyOf(positions));
	}

	private static ShrineSite.ProtectionBlocks fallbackProtectionBlocks(BlockPos core) {
		java.util.List<Long> positions = new java.util.ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) positions.add(new BlockPos(x, -1, z).asLong());
		}
		positions.add(BlockPos.ZERO.asLong());
		positions.add(BlockPos.ZERO.above().asLong());
		return new ShrineSite.ProtectionBlocks(positions);
	}

	private static ShrineSite.ProtectionBounds structureBounds(BlockPos core, Identifier structureId) {
		Vec3i size = structureSize(structureId);
		int minX = -Math.max(0, (size.getX() - 1) / 2);
		int minZ = -Math.max(0, (size.getZ() - 1) / 2);
		return new ShrineSite.ProtectionBounds(
			minX, minX + size.getX() - 1,
			minZ, minZ + size.getZ() - 1,
			0, size.getY() - 1);
	}

	private static ShrineSite.ProtectionMask protectionMask(Identifier structureId, net.minecraft.world.level.levelgen.structure.BoundingBox worldBounds, BlockPos core) {
		if (structureId == null) return new ShrineSite.ProtectionMask(java.util.List.of());
		int[][] localRows = localProtectionRows(structureId);
		if (localRows.length == 0) return new ShrineSite.ProtectionMask(java.util.List.of());

		Vec3i size = structureSize(structureId);
		int worldWidth = worldBounds.maxX() - worldBounds.minX() + 1;
		int worldDepth = worldBounds.maxZ() - worldBounds.minZ() + 1;
		boolean rotated = worldWidth == size.getZ() && worldDepth == size.getX();
		java.util.List<ShrineSite.ProtectionRow> rows = new java.util.ArrayList<>();
		if (!rotated) {
			for (int[] row : localRows) {
				rows.add(new ShrineSite.ProtectionRow(
					worldBounds.minZ() + row[0] - core.getZ(),
					worldBounds.minX() + row[1] - core.getX(),
					worldBounds.minX() + row[2] - core.getX()));
			}
		} else {
			java.util.Map<Integer, int[]> rotatedRows = new java.util.TreeMap<>();
			for (int[] row : localRows) {
				int z = row[0];
				for (int x = row[1]; x <= row[2]; x++) {
					int newX = size.getZ() - 1 - z;
					int newZ = x;
					int[] range = rotatedRows.computeIfAbsent(newZ, ignored -> new int[] {newX, newX});
					range[0] = Math.min(range[0], newX);
					range[1] = Math.max(range[1], newX);
				}
			}
			for (var entry : rotatedRows.entrySet()) {
				rows.add(new ShrineSite.ProtectionRow(
					worldBounds.minZ() + entry.getKey() - core.getZ(),
					worldBounds.minX() + entry.getValue()[0] - core.getX(),
					worldBounds.minX() + entry.getValue()[1] - core.getX()));
			}
		}
		return new ShrineSite.ProtectionMask(rows);
	}

	private static int[][] localProtectionRows(Identifier structureId) {
		if (structureId.equals(KarmaIsCurrency.id("monumentv2"))) {
			return new int[][] {
				{0,2,17},{1,1,18},{2,1,19},{3,1,19},{4,0,20},{5,0,20},
				{6,0,20},{7,0,20},{8,1,19},{9,1,19},{10,1,18},{11,2,17}
			};
		}
		if (structureId.equals(KarmaIsCurrency.id("monument2"))) {
			return new int[][] {
				{0,4,12},{1,2,14},{2,1,15},{3,1,15},{4,0,16},{5,0,16},
				{6,0,16},{7,0,16},{8,1,15},{9,1,15},{10,2,14},{11,4,12}
			};
		}
		return new int[][] {
			{0,3,5},{1,2,6},{2,1,7},{3,0,8},{4,0,8},
			{5,0,8},{6,1,7},{7,2,6},{8,3,5}
		};
	}

	private static Vec3i structureSize(Identifier structureId) {
		if (structureId.equals(MONUMENT_STRUCTURE) || structureId.equals(KarmaIsCurrency.id("monumentv2"))) {
			return new Vec3i(21, 17, 12);
		}
		if (structureId.equals(KarmaIsCurrency.id("monument2"))) {
			return new Vec3i(17, 21, 12);
		}
		return new Vec3i(9, 18, 9);
	}

	private static void buildMarkerShrine(ServerLevel level, ShrineSite site) {
		BlockPos core = site.position();
		ShrineType type = site.type();
		Block floor = switch (type) {
			case LIGHT, MONUMENT -> Blocks.MOSS_BLOCK;
			case DARK -> Blocks.BLACKSTONE;
			case NEUTRAL -> Blocks.CALCITE;
		};
		Block pillar = switch (type) {
			case LIGHT, MONUMENT -> Blocks.EMERALD_BLOCK;
			case DARK -> Blocks.CRYING_OBSIDIAN;
			case NEUTRAL -> Blocks.AMETHYST_BLOCK;
		};

		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				if (x * x + z * z <= 9) {
					level.setBlock(core.offset(x, -1, z), floor.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
		level.setBlock(site.actualLodestonePosition(), Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(core.above(), pillar.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(core.above(2), Blocks.SOUL_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static boolean protectShrine(
		Level level, Player player, BlockPos pos, BlockState state,
		net.minecraft.world.level.block.entity.BlockEntity entity) {
		return !isShrineProtected(level, pos);
	}

	private static double horizontalDistanceSquared(int x, int z, BlockPos target) {
		double dx = target.getX() - x;
		double dz = target.getZ() - z;
		return dx * dx + dz * dz;
	}

	private static boolean isVegetation(BlockState state) {
		return state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
			|| state.is(Blocks.DARK_OAK_LEAVES) || state.is(Blocks.JUNGLE_LEAVES) || state.is(Blocks.OAK_LEAVES)
			|| state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.MANGROVE_LEAVES) || state.is(Blocks.CHERRY_LEAVES)
			|| state.is(Blocks.AZALEA_LEAVES) || state.is(Blocks.FLOWERING_AZALEA_LEAVES)
			|| state.is(Blocks.VINE) || state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.BAMBOO)
			|| state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN)
			|| state.is(Blocks.POWDER_SNOW);
	}

	private static int protectionRadius(ShrineType type) {
		return switch (type) {
			case MONUMENT -> 12;
			default -> 8;
		};
	}

	public static void ensureShrineCore(MinecraftServer server, ShrineSite site) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null || !level.hasChunkAt(site.position())) {
			return;
		}
		if (!level.getBlockState(site.position()).is(ShrineBlocks.SHRINE_CORE)) {
			level.setBlock(site.position(), ShrineBlocks.SHRINE_CORE.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	public static void ensureLodestone(MinecraftServer server, ShrineSite site) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		if (level == null || !level.hasChunkAt(site.actualLodestonePosition())) {
			return;
		}
		if (!level.getBlockState(site.actualLodestonePosition()).is(Blocks.LODESTONE)) {
			level.setBlock(site.actualLodestonePosition(), Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	private static void logFinalizedShrine(ServerLevel level, ShrineSite site, ChunkPos startChunk, String action) {
		boolean corePresent = level.getBlockState(site.position()).is(ShrineBlocks.SHRINE_CORE);
		boolean lodestonePresent = level.getBlockState(site.actualLodestonePosition()).is(Blocks.LODESTONE);
		StructureLog.info("ShrineSite " + action + ": type=" + site.type() + ", structureStart=" + startChunk
			+ ", core=" + site.position() + ", lodestone=" + site.actualLodestonePosition()
			+ ", functional=" + (corePresent && lodestonePresent));
	}

	/** Returns whether a registered shrine has its functional lodestone in place. */
	public static boolean hasFunctionalLodestone(ServerLevel level, ShrineSite site) {
		BlockPos lodestone = site.actualLodestonePosition();
		return level.hasChunkAt(lodestone) && level.getBlockState(lodestone).is(Blocks.LODESTONE);
	}

	private static boolean placeStructure(ServerLevel level, BlockPos pos, Identifier structureId) {
		StructureTemplate template = level.getStructureManager().get(structureId).orElse(null);
		if (template == null) {
			return false;
		}
		StructurePlaceSettings settings = new StructurePlaceSettings();
		settings.setIgnoreEntities(true); // Increased leniency: ignore entities during placement
		template.placeInWorld(level, pos, pos, settings, level.getRandom(), 2);
		return true;
	}


	private static void buildForcePedestal(ServerLevel level, BlockPos surface, ShrineType type) {
		Block base = switch (type) {
			case LIGHT, MONUMENT -> Blocks.MOSS_BLOCK;
			case DARK -> Blocks.BLACKSTONE;
			case NEUTRAL -> Blocks.CALCITE;
		};
		Block accent = switch (type) {
			case LIGHT, MONUMENT -> Blocks.EMERALD_BLOCK;
			case DARK -> Blocks.CRYING_OBSIDIAN;
			case NEUTRAL -> Blocks.AMETHYST_BLOCK;
		};

		// Place a visible 3x3 fallback when a structure template is unavailable.
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				level.setBlock(surface.offset(x, -1, z), base.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		level.setBlock(surface.above(), accent.defaultBlockState(), Block.UPDATE_ALL);
	}


	private record PendingShrine(
		ShrineType type,
		Identifier templateId,
		BoundingBox bounds,
		ChunkPos chunkPos,
		BlockPos core,
		ShrineSite.ProtectionBlocks protectionBlocks) {
	}

	public record ShrineTarget(BlockPos position, Optional<ShrineSite> generated, boolean predicted, Optional<ShrineType> predictedType) {
		static ShrineTarget generated(ShrineSite site) {
			return new ShrineTarget(site.position(), Optional.of(site), false, Optional.empty());
		}

		static ShrineTarget predicted(int x, int z) {
			return new ShrineTarget(new BlockPos(x, 64, z), Optional.empty(), true, Optional.empty());
		}

		static ShrineTarget predicted(int x, int z, ShrineType type) {
			return new ShrineTarget(new BlockPos(x, 64, z), Optional.empty(), true, Optional.of(type));
		}
	}

	public record ServerPlayerPosition(int x, int z) {
	}
}
