package akisei.karmacurrency;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent purchase counts for configured limited rewards. */
public final class ShopPurchaseState extends SavedData {
	private static final Codec<ShopPurchaseState> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT)
		.xmap(ShopPurchaseState::new, state -> state.counts);
	public static final SavedDataType<ShopPurchaseState> TYPE = new SavedDataType<>(
		KarmaIsCurrency.id("shop_purchases"), ShopPurchaseState::new, CODEC, null
	);

	private final Map<String, Integer> counts;

	public ShopPurchaseState() {
		this(new HashMap<>());
	}

	private ShopPurchaseState(Map<String, Integer> counts) {
		this.counts = new HashMap<>(counts);
	}

	public static ShopPurchaseState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public long currentCycle() {
		return KicCycle.currentCycleId();
	}

	public int count(UUID player, ShopCategory category, String rewardId, long cycle) {
        String family = rewardId.equals("ender_pearl_small") ? "ender_pearl" : rewardId;
        long total = 0;
        for (ShopCategory side : ShopCategory.values()) {
            total += counts.getOrDefault(player + ":" + side.name() + ":" + family + ":" + cycle, 0);
            if (family.equals("ender_pearl")) total += counts.getOrDefault(player + ":" + side.name() + ":ender_pearl_small:" + cycle, 0);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
	}

	/** Legacy/common-shop lookup retained for old call sites. */
	public int count(UUID player, String rewardId, long cycle) {
		return count(player, ShopCategory.COMMON, rewardId, cycle);
	}

	public void increment(UUID player, ShopCategory category, String rewardId, long cycle) {
		String key = player + ":" + category.name() + ":" + rewardId + ":" + cycle;
		counts.put(key, (int) Math.min(Integer.MAX_VALUE, (long) counts.getOrDefault(key, 0) + 1));
		setDirty();
	}

	public void increment(UUID player, String rewardId, long cycle) {
		increment(player, ShopCategory.COMMON, rewardId, cycle);
	}
}
