package akisei.karmacurrency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Predicts the exact custom ring positions without loading or generating destination chunks. */
public final class ShrinePredictionService {
	private ShrinePredictionService() {
	}

	public static List<BlockPosTarget> predictedPositions(ServerLevel level) {
		return collect(level, null);
	}

	public static List<BlockPosTarget> predictedPositions(ServerLevel level, boolean monumentsOnly) {
		return collect(level, monumentsOnly);
	}

	/** Returns predicted shrine candidate centers within the given chunk radius without loading chunks. */
	public static List<BlockPosTarget> predictedPositionsNear(ServerLevel level, int chunkX, int chunkZ, int radiusChunks) {
		int radius = Math.max(0, radiusChunks);
		int minX = chunkX - radius;
		int maxX = chunkX + radius;
		int minZ = chunkZ - radius;
		int maxZ = chunkZ + radius;
		return predictedPositions(level).stream()
			.filter(target -> {
				int targetChunkX = target.x() >> 4;
				int targetChunkZ = target.z() >> 4;
				return targetChunkX >= minX && targetChunkX <= maxX
					&& targetChunkZ >= minZ && targetChunkZ <= maxZ;
			})
			.toList();
	}

	private static List<BlockPosTarget> collect(ServerLevel level, Boolean monumentsOnly) {
		try {
			ServerChunkCache chunkSource = level.getChunkSource();
			ChunkMap chunkMap = ((akisei.karmacurrency.mixin.ServerChunkCacheAccessor) chunkSource)
				.karmaiscurrency$getChunkMap();
			ChunkGeneratorStructureState state = ((akisei.karmacurrency.mixin.ChunkMapAccessor) chunkMap)
				.karmaiscurrency$getChunkGeneratorState();

			Set<ChunkPos> result = new LinkedHashSet<>();
			for (var rawSet : state.possibleStructureSets()) {
				StructureSet set = rawSet.value();
				if (!(set.placement() instanceof ShrineRingPlacement placement)) {
					continue;
				}
				if (monumentsOnly != null && placement.monumentPlacement() != monumentsOnly) {
					continue;
				}
				result.addAll(placement.candidatePositions(level.getSeed()));
			}

			return result.stream().map(BlockPosTarget::fromChunk).toList();
		} catch (RuntimeException exception) {
			KarmaIsCurrency.LOGGER.warn(
				"Could not read custom shrine ring positions; falling back to no predicted shrines", exception);
			return List.of();
		}
	}

	public static Optional<BlockPosTarget> nearestPredicted(ServerLevel level, int x, int z) {
		return predictedPositions(level).stream()
			.min(Comparator.comparingDouble(target -> distanceSquared(x, z, target)));
	}

	public static Optional<BlockPosTarget> nearestPredicted(ServerLevel level, int x, int z, ShrineType type) {
		Boolean monumentsOnly = type == ShrineType.MONUMENT ? Boolean.TRUE : Boolean.FALSE;
		return collect(level, monumentsOnly).stream()
			.min(Comparator.comparingDouble(target -> distanceSquared(x, z, target)));
	}

	private static double distanceSquared(int x, int z, BlockPosTarget target) {
		double dx = target.x() - x;
		double dz = target.z() - z;
		return dx * dx + dz * dz;
	}

	public record BlockPosTarget(int x, int z) {
		static BlockPosTarget fromChunk(ChunkPos pos) {
			return new BlockPosTarget(pos.getMiddleBlockX(), pos.getMiddleBlockZ());
		}
	}
}
