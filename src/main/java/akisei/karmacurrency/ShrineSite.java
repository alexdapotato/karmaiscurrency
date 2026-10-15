package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

public record ShrineSite(
		BlockPos position,
		ShrineType type,
		int protectionRadius,
		Optional<BlockPos> lodestonePosition,
		String statueName,
		Optional<Identifier> structureId,
		Optional<ProtectionBounds> protectionBounds,
		Optional<ProtectionMask> protectionMask,
		Optional<ProtectionBlocks> protectionBlocks) {
	public static final Codec<ProtectionBounds> PROTECTION_BOUNDS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.intRange(-128, 128).fieldOf("min_x").forGetter(ProtectionBounds::minX),
		Codec.intRange(-128, 128).fieldOf("max_x").forGetter(ProtectionBounds::maxX),
		Codec.intRange(-128, 128).fieldOf("min_z").forGetter(ProtectionBounds::minZ),
		Codec.intRange(-128, 128).fieldOf("max_z").forGetter(ProtectionBounds::maxZ),
		Codec.INT.optionalFieldOf("min_y", -128).forGetter(ProtectionBounds::minY),
		Codec.INT.optionalFieldOf("max_y", 128).forGetter(ProtectionBounds::maxY)
	).apply(instance, ProtectionBounds::new));

	public static final Codec<ProtectionMask> PROTECTION_MASK_CODEC = Codec.list(RecordCodecBuilder.<ProtectionRow>create(instance -> instance.group(
		Codec.intRange(-128, 128).fieldOf("z").forGetter(ProtectionRow::z),
		Codec.intRange(-128, 128).fieldOf("min_x").forGetter(ProtectionRow::minX),
		Codec.intRange(-128, 128).fieldOf("max_x").forGetter(ProtectionRow::maxX)
	).apply(instance, ProtectionRow::new))).xmap(ProtectionMask::new, ProtectionMask::rows);
	public static final Codec<ProtectionBlocks> PROTECTION_BLOCKS_CODEC = Codec.LONG.listOf()
		.xmap(ProtectionBlocks::new, ProtectionBlocks::relativePositions);

	public static final Codec<ShrineSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("position").forGetter(ShrineSite::position),
		ShrineType.CODEC.fieldOf("type").forGetter(ShrineSite::type),
		Codec.intRange(1, 64).optionalFieldOf("protection_radius", 8).forGetter(ShrineSite::protectionRadius),
		BlockPos.CODEC.optionalFieldOf("lodestone_position").forGetter(ShrineSite::lodestonePosition),
		Codec.STRING.optionalFieldOf("statue_name", "").forGetter(ShrineSite::statueName),
		Identifier.CODEC.optionalFieldOf("structure_id").forGetter(ShrineSite::structureId),
		PROTECTION_BOUNDS_CODEC.optionalFieldOf("protection_bounds").forGetter(ShrineSite::protectionBounds),
		PROTECTION_MASK_CODEC.optionalFieldOf("protection_mask").forGetter(ShrineSite::protectionMask),
		PROTECTION_BLOCKS_CODEC.optionalFieldOf("protection_blocks").forGetter(ShrineSite::protectionBlocks)
	).apply(instance, ShrineSite::new));

	/** Compatibility constructor for saved-site callers predating exact block ownership. */
	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, Optional<BlockPos> lodestonePosition,
			String statueName, Optional<Identifier> structureId, Optional<ProtectionBounds> protectionBounds,
			Optional<ProtectionMask> protectionMask) {
		this(position, type, protectionRadius, lodestonePosition, statueName, structureId, protectionBounds,
			protectionMask, Optional.empty());
	}

	public ShrineSite(BlockPos position, ShrineType type) {
		this(position, type, 8, Optional.empty(), "", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius) {
		this(position, type, protectionRadius, Optional.empty(), "", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, BlockPos lodestonePosition, String statueName) {
		this(position, type, protectionRadius, Optional.ofNullable(lodestonePosition), statueName, Optional.empty(), Optional.empty(), Optional.empty());
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, BlockPos lodestonePosition, String statueName, ProtectionBounds bounds) {
		this(position, type, protectionRadius, Optional.ofNullable(lodestonePosition), statueName, Optional.empty(), Optional.ofNullable(bounds), Optional.empty());
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, Optional<BlockPos> lodestonePosition, String statueName, ProtectionBounds bounds) {
		this(position, type, protectionRadius, lodestonePosition, statueName, Optional.empty(), Optional.ofNullable(bounds), Optional.empty());
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, BlockPos lodestonePosition, String statueName, ProtectionBounds bounds, ProtectionMask mask) {
		this(position, type, protectionRadius, Optional.ofNullable(lodestonePosition), statueName, Optional.empty(), Optional.ofNullable(bounds), Optional.ofNullable(mask));
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, Optional<BlockPos> lodestonePosition, String statueName, ProtectionBounds bounds, ProtectionMask mask) {
		this(position, type, protectionRadius, lodestonePosition, statueName, Optional.empty(), Optional.ofNullable(bounds), Optional.ofNullable(mask));
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, BlockPos lodestonePosition, String statueName, Identifier structureId, ProtectionBounds bounds, ProtectionMask mask) {
		this(position, type, protectionRadius, Optional.ofNullable(lodestonePosition), statueName, Optional.ofNullable(structureId), Optional.ofNullable(bounds), Optional.ofNullable(mask));
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, BlockPos lodestonePosition, String statueName,
			Identifier structureId, ProtectionBounds bounds, ProtectionMask mask, ProtectionBlocks blocks) {
		this(position, type, protectionRadius, Optional.ofNullable(lodestonePosition), statueName, Optional.ofNullable(structureId),
			Optional.ofNullable(bounds), Optional.ofNullable(mask), Optional.ofNullable(blocks));
	}

	public ShrineSite(BlockPos position, ShrineType type, int protectionRadius, Optional<BlockPos> lodestonePosition, String statueName, Identifier structureId, ProtectionBounds bounds, ProtectionMask mask) {
		this(position, type, protectionRadius, lodestonePosition, statueName, Optional.ofNullable(structureId), Optional.ofNullable(bounds), Optional.ofNullable(mask));
	}

	public BlockPos actualLodestonePosition() {
		return lodestonePosition.orElse(position);
	}

	public boolean protectsHorizontal(BlockPos pos) {
		// The lodestone is created by ShrineManager rather than stored inside the
		// structure template, so it is not necessarily present in protectionBlocks.
		// Always protect the authoritative functional block explicitly, including
		// older persisted ShrineSite records.
		if (pos.equals(actualLodestonePosition())) {
			return true;
		}
		if (protectionBlocks.isPresent()) {
			return protectionBlocks.get().contains(pos.subtract(position));
		}
		int dx = pos.getX() - position.getX();
		int dz = pos.getZ() - position.getZ();
		if (protectionBounds.isPresent()) {
			ProtectionBounds bounds = protectionBounds.get();
			if (pos.getY() - position.getY() < bounds.minY() || pos.getY() - position.getY() > bounds.maxY()) {
				return false;
			}
			if (protectionMask.isPresent()) {
				return protectionMask.get().contains(dx, dz);
			}
			return bounds.contains(dx, dz);
		}
		if (protectionMask.isPresent()) {
			return protectionMask.get().contains(dx, dz);
		}
		return dx * dx + dz * dz <= protectionRadius * protectionRadius;
	}

	public boolean withinVerticalInteractionRange(BlockPos pos, double margin) {
		if (protectionBounds.isEmpty()) return true;
		int dy = pos.getY() - position.getY();
		ProtectionBounds bounds = protectionBounds.get();
		return dy >= bounds.minY() - margin && dy <= bounds.maxY() + margin;
	}

	public double horizontalDistanceSquaredToFootprint(BlockPos pos) {
		int dx = pos.getX() - position.getX();
		int dz = pos.getZ() - position.getZ();
		if (protectionMask.isPresent()) {
			return protectionMask.get().distanceSquared(dx, dz);
		}
		if (protectionBounds.isEmpty()) {
			double x = dx;
			double z = dz;
			return x * x + z * z;
		}
		ProtectionBounds bounds = protectionBounds.get();
		int clampedX = Math.clamp(dx, bounds.minX(), bounds.maxX());
		int clampedZ = Math.clamp(dz, bounds.minZ(), bounds.maxZ());
		double deltaX = dx - clampedX;
		double deltaZ = dz - clampedZ;
		return deltaX * deltaX + deltaZ * deltaZ;
	}

	/** Distance to the saved rectangular structure bounds, ignoring the protection mask. */
	public double horizontalDistanceSquaredToBounds(BlockPos pos) {
		int dx = pos.getX() - position.getX();
		int dz = pos.getZ() - position.getZ();
		if (protectionBounds.isEmpty()) {
			return (double) dx * dx + (double) dz * dz;
		}
		ProtectionBounds bounds = protectionBounds.get();
		int clampedX = Math.clamp(dx, bounds.minX(), bounds.maxX());
		int clampedZ = Math.clamp(dz, bounds.minZ(), bounds.maxZ());
		double deltaX = dx - clampedX;
		double deltaZ = dz - clampedZ;
		return deltaX * deltaX + deltaZ * deltaZ;
	}

	/** Functionality is intentionally independent from the exact protected template footprint. */
	public double horizontalDistanceSquaredToCenter(BlockPos pos) {
		double dx = pos.getX() - position.getX();
		double dz = pos.getZ() - position.getZ();
		return dx * dx + dz * dz;
	}

	/** Exact interaction distance is measured from the functional lodestone, not the protection footprint. */
	public double horizontalDistanceSquaredToLodestone(BlockPos pos) {
		BlockPos lodestone = actualLodestonePosition();
		double dx = pos.getX() - lodestone.getX();
		double dz = pos.getZ() - lodestone.getZ();
		return dx * dx + dz * dz;
	}

	public String displayName() {
		if (statueName == null || statueName.isBlank()) {
			return type.displayName();
		}
		String name = statueName.trim();
		String typeName = type.displayName();
		String simpleType = type.configName();
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		String lowerType = typeName.toLowerCase(java.util.Locale.ROOT);
		String lowerSimple = simpleType.toLowerCase(java.util.Locale.ROOT);
		if (lower.equals(lowerType) || lower.endsWith(", " + lowerType)
			|| lower.endsWith(" " + lowerType) || lower.equals(lowerSimple)
			|| lower.endsWith(" " + lowerSimple)) {
			return name;
		}
		return name + ", " + typeName;
	}

	public record ProtectionBounds(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
		public ProtectionBounds(int minX, int maxX, int minZ, int maxZ) {
			this(minX, maxX, minZ, maxZ, -128, 128);
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}
	}

	public record ProtectionMask(List<ProtectionRow> rows) {
		public ProtectionMask {
			rows = List.copyOf(rows);
		}

		public boolean contains(int x, int z) {
			for (ProtectionRow row : rows) {
				if (row.z() == z && x >= row.minX() && x <= row.maxX()) return true;
			}
			return false;
		}

		public double distanceSquared(int x, int z) {
			double best = Double.POSITIVE_INFINITY;
			for (ProtectionRow row : rows) {
				double dx = x < row.minX() ? row.minX() - x : (x > row.maxX() ? x - row.maxX() : 0.0);
				double dz = z - row.z();
				double distance = dx * dx + dz * dz;
				if (distance < best) best = distance;
			}
			return best;
		}
	}

	public record ProtectionRow(int z, int minX, int maxX) {
	}

	/** Exact non-air template blocks, encoded relative to the authoritative shrine core. */
	public record ProtectionBlocks(List<Long> relativePositions) {
		public ProtectionBlocks {
			relativePositions = List.copyOf(relativePositions);
		}

		public boolean contains(BlockPos relative) {
			return relativePositions.contains(relative.asLong());
		}
	}
}
