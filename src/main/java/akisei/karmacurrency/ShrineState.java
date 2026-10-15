package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent record of generated roaming shrines. */
public final class ShrineState extends SavedData {
	public static final Codec<ShrineState> CODEC = ShrineSite.CODEC.listOf().fieldOf("shrines")
		.xmap(ShrineState::new, ShrineState::sites).codec();
	public static final SavedDataType<ShrineState> TYPE = new SavedDataType<>(
		KarmaIsCurrency.id("shrines"), ShrineState::new, CODEC, null
	);

	private final List<ShrineSite> shrines;

	public ShrineState() {
		this(List.of());
	}

	private ShrineState(List<ShrineSite> shrines) {
		this.shrines = new ArrayList<>(shrines);
	}

	public static ShrineState get(MinecraftServer server) {
		ShrineState state = server.getDataStorage().computeIfAbsent(TYPE);
		state.ensureUniqueNames();
		return state;
	}

	private void ensureUniqueNames() {
		java.util.Set<String> used = new java.util.HashSet<>();
		boolean changed = false;
		for (int i = 0; i < shrines.size(); i++) {
			ShrineSite site = shrines.get(i);
			String name = site.statueName();
			boolean missing = name == null || name.isBlank();
			String key = missing ? "" : name.toLowerCase(java.util.Locale.ROOT);
			if (!missing && used.add(key)) {
				continue;
			}
			String unique = StatueNames.chooseUnique(site.type(), site.position().asLong(), used);
			shrines.set(i, new ShrineSite(site.position(), site.type(), site.protectionRadius(),
				site.actualLodestonePosition(), unique, site.structureId().orElse(null),
				site.protectionBounds().orElse(null), site.protectionMask().orElse(null), site.protectionBlocks().orElse(null)));
			used.add(unique.toLowerCase(java.util.Locale.ROOT));
			changed = true;
		}
		if (changed) setDirty();
	}

	public List<ShrineSite> sites() {
		return List.copyOf(shrines);
	}

	public void add(ShrineSite shrine) {
		shrines.add(shrine);
		setDirty();
	}

	public void replaceAtPosition(ShrineSite shrine) {
		for (int i = 0; i < shrines.size(); i++) {
			ShrineSite existing = shrines.get(i);
			if (existing.position().equals(shrine.position()) && existing.type() == shrine.type()) {
				String statueName = existing.statueName() == null || existing.statueName().isBlank()
					? shrine.statueName() : existing.statueName();
				shrines.set(i, new ShrineSite(shrine.position(), shrine.type(), shrine.protectionRadius(),
					shrine.actualLodestonePosition(), statueName, shrine.structureId().orElse(null),
					shrine.protectionBounds().orElse(null), shrine.protectionMask().orElse(null), shrine.protectionBlocks().orElse(null)));
				setDirty();
				return;
			}
		}
		add(shrine);
	}

	/** Repairs entries created by older discovery logic whose saved core was slightly off-center. */
	public boolean replaceNearby(ShrineSite shrine, int maxHorizontalDistance) {
		int maxDistanceSquared = maxHorizontalDistance * maxHorizontalDistance;
		int match = -1;
		boolean changed = false;
		for (int i = shrines.size() - 1; i >= 0; i--) {
			ShrineSite existing = shrines.get(i);
			if (existing.type() != shrine.type()) continue;
			long dx = (long) existing.position().getX() - shrine.position().getX();
			long dz = (long) existing.position().getZ() - shrine.position().getZ();
			if (dx * dx + dz * dz > maxDistanceSquared) continue;
			if (match < 0) {
				match = i;
			} else {
				shrines.remove(i);
				changed = true;
			}
		}
		if (match >= 0) {
			ShrineSite existing = shrines.get(match);
			String statueName = existing.statueName() == null || existing.statueName().isBlank()
				? shrine.statueName() : existing.statueName();
			shrines.set(match, new ShrineSite(shrine.position(), shrine.type(), shrine.protectionRadius(),
				shrine.actualLodestonePosition(), statueName, shrine.structureId().orElse(null),
				shrine.protectionBounds().orElse(null), shrine.protectionMask().orElse(null), shrine.protectionBlocks().orElse(null)));
			changed = true;
		}
		if (changed) setDirty();
		return match >= 0;
	}

	public boolean containsPosition(BlockPos position) {
		return shrines.stream().anyMatch(site -> site.position().equals(position));
	}

	public boolean containsHorizontalPosition(BlockPos position) {
		return shrines.stream().anyMatch(site ->
			site.position().getX() == position.getX() && site.position().getZ() == position.getZ());
	}

	public List<ShrineSite> activeSites(MinecraftServer server, ShrineType type) {
		List<ShrineSite> candidates = shrines.stream()
			.filter(site -> site.type() == type)
			.sorted(Comparator.comparingInt(site -> site.position().hashCode()))
			.toList();
		if (candidates.size() <= 2) {
			return candidates;
		}

		// Minecraft dayTime is offset from sunrise; +6000 makes the boundaries noon and midnight.
		long rotation = (server.overworld().getGameTime() + 6000L) / 12000L;
		int start = Math.floorMod((int) (rotation * 2L), candidates.size());
		List<ShrineSite> active = new ArrayList<>(2);
		active.add(candidates.get(start));
		active.add(candidates.get((start + 1) % candidates.size()));
		return List.copyOf(active);
	}
}
