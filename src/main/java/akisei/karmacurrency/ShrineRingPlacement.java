package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

/**
 * Deterministic ring placement used by Karma Is Currency.
 *
 * Unlike vanilla's ConcentricRingsStructurePlacement, this keeps the configured
 * number of points per ring constant. That lets the mod have exactly 8 shrine
 * candidates per ring for 20 rings, while a separate Monument placement can add
 * 1/2/3/... monuments on even-numbered rings without changing the normal shrine ring.
 */
public final class ShrineRingPlacement extends StructurePlacement {
	private enum Mode {
		SHRINE,
		MONUMENT
	}

	private final int ringDistanceBlocks;
	private final int ringSpreadBlocks;
	private final int pointsPerRing;
	private final int ringCount;
	private final Mode mode;

	private transient Cache cachedPositions;

	private static final double TAU = Math.PI * 2.0;
	private static final double MONUMENT_PHASE_OFFSET = Math.PI / 24.0; // 7.5 degrees

	public static final MapCodec<ShrineRingPlacement> SHRINE_CODEC = codec(Mode.SHRINE);
	public static final MapCodec<ShrineRingPlacement> MONUMENT_CODEC = codec(Mode.MONUMENT);

	public static final StructurePlacementType<ShrineRingPlacement> SHRINE_TYPE = () -> SHRINE_CODEC;
	public static final StructurePlacementType<ShrineRingPlacement> MONUMENT_TYPE = () -> MONUMENT_CODEC;

	private ShrineRingPlacement(
		Vec3i locateOffset,
		FrequencyReductionMethod frequencyReductionMethod,
		float frequency,
		int salt,
		Optional<ExclusionZone> exclusionZone,
		int ringDistanceBlocks,
		int ringSpreadBlocks,
		int pointsPerRing,
		int ringCount,
		Mode mode) {
		super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone);
		this.ringDistanceBlocks = ringDistanceBlocks;
		this.ringSpreadBlocks = ringSpreadBlocks;
		this.pointsPerRing = pointsPerRing;
		this.ringCount = ringCount;
		this.mode = mode;
	}

	private static MapCodec<ShrineRingPlacement> codec(Mode mode) {
		return RecordCodecBuilder.mapCodec(instance -> instance.group(
			Vec3i.offsetCodec(16)
				.optionalFieldOf("locate_offset", Vec3i.ZERO)
				.forGetter(ShrineRingPlacement::locateOffset),
			FrequencyReductionMethod.CODEC
				.optionalFieldOf("frequency_reduction_method", FrequencyReductionMethod.DEFAULT)
				.forGetter(ShrineRingPlacement::frequencyReductionMethod),
			Codec.floatRange(0.0F, 1.0F)
				.optionalFieldOf("frequency", 1.0F)
				.forGetter(ShrineRingPlacement::frequency),
			ExtraCodecs.NON_NEGATIVE_INT
				.fieldOf("salt")
				.forGetter(ShrineRingPlacement::salt),
			ExclusionZone.CODEC
				.optionalFieldOf("exclusion_zone")
				.forGetter(ShrineRingPlacement::exclusionZone),
			Codec.intRange(1, 30000)
				.fieldOf("ring_distance_blocks")
				.forGetter(ShrineRingPlacement::ringDistanceBlocks),
			Codec.intRange(0, 10000)
				.fieldOf("ring_spread_blocks")
				.forGetter(ShrineRingPlacement::ringSpreadBlocks),
			Codec.intRange(1, 64)
				.fieldOf("points_per_ring")
				.forGetter(ShrineRingPlacement::pointsPerRing),
			Codec.intRange(1, 100)
				.fieldOf("ring_count")
				.forGetter(ShrineRingPlacement::ringCount)
		).apply(instance, (locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone,
			ringDistanceBlocks, ringSpreadBlocks, pointsPerRing, ringCount) ->
			new ShrineRingPlacement(
				locateOffset,
				frequencyReductionMethod,
				frequency,
				salt,
				exclusionZone,
				ringDistanceBlocks,
				ringSpreadBlocks,
				pointsPerRing,
				ringCount,
				mode)));
	}

	public static void register() {
		RegistryRegister.register(KarmaIsCurrency.id("shrine_rings"), SHRINE_TYPE);
		RegistryRegister.register(KarmaIsCurrency.id("monument_rings"), MONUMENT_TYPE);
	}

	public int ringDistanceBlocks() {
		return ringDistanceBlocks;
	}

	public int ringSpreadBlocks() {
		return ringSpreadBlocks;
	}

	public int pointsPerRing() {
		return pointsPerRing;
	}

	public int ringCount() {
		return ringCount;
	}

	public boolean monumentPlacement() {
		return mode == Mode.MONUMENT;
	}

	@Override
	protected boolean isPlacementChunk(net.minecraft.world.level.chunk.ChunkGeneratorStructureState generatorState, int chunkX, int chunkZ) {
		if (generatorState == null) {
			return false;
		}
		return candidateSet(generatorState.getLevelSeed()).contains(new ChunkPos(chunkX, chunkZ));
	}

	@Override
	public StructurePlacementType<?> type() {
		return mode == Mode.MONUMENT ? MONUMENT_TYPE : SHRINE_TYPE;
	}

	public List<ChunkPos> candidatePositions(long worldSeed) {
		return cache(worldSeed).positions;
	}

	/** Returns candidate chunks annotated with their ring number for debug/inspection tools. */
	public List<RingCandidate> candidatePositionsByRing(long worldSeed) {
		return calculateRingCandidates(worldSeed);
	}

	/** Returns only the candidates on one ring (1-indexed). */
	public List<ChunkPos> candidatePositionsForRing(long worldSeed, int ring) {
		return calculateRingCandidates(worldSeed).stream()
			.filter(candidate -> candidate.ring() == ring)
			.map(RingCandidate::chunk)
			.toList();
	}

	private Set<ChunkPos> candidateSet(long worldSeed) {
		return cache(worldSeed).set;
	}

	private Cache cache(long worldSeed) {
		Cache cache = this.cachedPositions;
		if (cache != null && cache.worldSeed == worldSeed) {
			return cache;
		}
		List<ChunkPos> positions = calculatePositions(worldSeed);
		Cache fresh = new Cache(worldSeed, positions, Set.copyOf(positions));
		this.cachedPositions = fresh;
		return fresh;
	}

	private List<ChunkPos> calculatePositions(long worldSeed) {
		return calculateRingCandidates(worldSeed).stream()
			.map(RingCandidate::chunk)
			.toList();
	}

	private List<RingCandidate> calculateRingCandidates(long worldSeed) {
		long seed = worldSeed
			^ ((long) salt() * 0x9E3779B97F4A7C15L)
			^ (mode == Mode.MONUMENT ? 0xD1B54A32D192ED03L : 0L);
		RandomSource random = RandomSource.create(seed);
		double initialAngle = random.nextDouble() * TAU;
		List<RingCandidate> positions = new ArrayList<>();

		for (int ring = 1; ring <= ringCount; ring++) {
			if (mode == Mode.MONUMENT && (ring & 1) == 1) {
				// Monuments only occupy even-numbered rings.
				consumeRingRandomness(random);
				continue;
			}

			double jitter = (random.nextDouble() * 2.0 - 1.0) * ringSpreadBlocks;
			double radius = ringDistanceBlocks * (double) ring + jitter;
			double ringAngle = initialAngle + random.nextDouble() * TAU;
			int samples = pointsPerRing;
			int pointCount = pointsForRing(ring);

			for (int i = 0; i < pointCount; i++) {
				int point = selectedPointIndex(i, pointCount, samples);
				double phase = mode == Mode.MONUMENT ? MONUMENT_PHASE_OFFSET : 0.0;
				double angle = ringAngle + phase + TAU * point / samples;
				int blockX = (int) Math.round(Math.cos(angle) * radius);
				int blockZ = (int) Math.round(Math.sin(angle) * radius);
				positions.add(new RingCandidate(ring, new ChunkPos(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))));
			}
		}

		return List.copyOf(positions);
	}

	private int pointsForRing(int ring) {
		if (mode == Mode.SHRINE) {
			return pointsPerRing;
		}
		return Math.min(pointsPerRing, ring / 2);
	}

	private static int selectedPointIndex(int index, int selectedCount, int totalPoints) {
		if (selectedCount <= 1) {
			return 0;
		}
		return (int) Math.floor((double) index * totalPoints / selectedCount);
	}

	private static void consumeRingRandomness(RandomSource random) {
		random.nextDouble();
		random.nextDouble();
	}

	public record RingCandidate(int ring, ChunkPos chunk) {
	}

	private record Cache(long worldSeed, List<ChunkPos> positions, Set<ChunkPos> set) {
	}

	/** Small indirection to keep the generic Registry.register call readable. */
	private static final class RegistryRegister {
		private RegistryRegister() {
		}

		private static void register(Identifier id, StructurePlacementType<ShrineRingPlacement> type) {
			net.minecraft.core.Registry.register(BuiltInRegistries.STRUCTURE_PLACEMENT, id, type);
		}
	}
}
