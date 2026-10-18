package akisei.karmacurrency;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Authority for physical Influence.
 *
 * Every token preserves its origin: Light, Neutral, or Dark. The origin is
 * part of the item data, so transferring a token never changes what side it
 * belongs to. Tokens remain physical and transferable; the former bank has been removed.
 */
public final class SocialTokenService {
	private static final String MARKER = "KarmaSocialToken";
	private static final String VALUE = "KarmaSocialTokenValue";
	private static final String ORIGIN = "KarmaSocialTokenOrigin";

	public static final int STACK_SIZE = 64;

	public enum Origin {
		LIGHT("Light", "Light Influence"),
		NEUTRAL("Neutral", "Neutral Influence"),
		DARK("Dark", "Dark Influence");

		private final String displayName;
		private final String itemName;

		Origin(String displayName, String itemName) {
			this.displayName = displayName;
			this.itemName = itemName;
		}

		public String displayName() { return displayName; }
		public String itemName() { return itemName; }
	}

	private SocialTokenService() {}

	public static ItemStack createToken(int count) {
		return createToken(count, Origin.NEUTRAL);
	}

	public static ItemStack createToken(int count, Origin origin) {
		int safeCount = Math.max(1, count);
		ItemStack stack = new ItemStack(Items.ECHO_SHARD, safeCount);
		stack.set(DataComponents.CUSTOM_NAME, KicUiText.title(origin.itemName()));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Physical Influence · " + origin.displayName() + " origin"),
			Component.literal("Trade or redeem. Does not decay.")
		)));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARKER, true);
		tag.putInt(VALUE, 1);
		tag.putString(ORIGIN, origin.name());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	public static boolean isToken(ItemStack stack) {
		return originOf(stack) != null;
	}

	/** Only tagged Echo Shards are Influence Tokens; ordinary Echo Shards remain ordinary items. */
	public static Origin originOf(ItemStack stack) {
		if (!stack.is(Items.ECHO_SHARD)) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag tag = data.copyTag();
		if (!tag.getBooleanOr(MARKER, false) || tag.getIntOr(VALUE, -1) != 1) return null;
		String raw = tag.getStringOr(ORIGIN, Origin.NEUTRAL.name());
		try {
			return Origin.valueOf(raw);
		} catch (IllegalArgumentException ignored) {
			return Origin.NEUTRAL;
		}
	}

	public static int count(ServerPlayer player) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (isToken(stack)) total += stack.getCount();
		}
		if (isToken(player.getOffhandItem())) total += player.getOffhandItem().getCount();
		return total;
	}

	public static int count(ServerPlayer player, Origin origin) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (originOf(stack) == origin) total += stack.getCount();
		}
		if (originOf(player.getOffhandItem()) == origin) total += player.getOffhandItem().getCount();
		return total;
	}

	public static int insert(ServerPlayer player, int tokens) {
		return insert(player, tokens, Origin.NEUTRAL);
	}

	public static int insert(ServerPlayer player, int tokens, Origin origin) {
		int remaining = Math.max(0, tokens);
		int inserted = 0;
		while (remaining > 0) {
			int amount = Math.min(remaining, STACK_SIZE);
			ItemStack stack = createToken(amount, origin);
			if (!player.addItem(stack)) player.drop(stack, false);
			remaining -= amount;
			inserted += amount;
		}
		
        return inserted;
	}

	public static int remove(ServerPlayer player, int tokens) {
		return remove(player, tokens, null);
	}

	public static int remove(ServerPlayer player, int tokens, Origin origin) {
		int remaining = Math.max(0, tokens);
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (remaining <= 0) break;
			if (!isToken(stack) || (origin != null && originOf(stack) != origin)) continue;
			int amount = Math.min(remaining, stack.getCount());
			stack.shrink(amount);
			remaining -= amount;
		}
		if (remaining > 0) {
			ItemStack stack = player.getOffhandItem();
			if (isToken(stack) && (origin == null || originOf(stack) == origin)) {
				int amount = Math.min(remaining, stack.getCount());
				stack.shrink(amount);
				remaining -= amount;
			}
		}
		int removed = Math.max(0, tokens) - remaining;
        
        return removed;
	}

	public static Origin usableOrigin(ServerPlayer player) {
		return switch (SocialCurrencyService.getStance(player)) {
			case LIGHT -> Origin.LIGHT;
			case NEUTRAL -> Origin.NEUTRAL;
			case DARK -> Origin.DARK;
		};
	}

	public static int usablePhysicalCount(ServerPlayer player) {
		return count(player, usableOrigin(player));
	}

	public static int removeUsable(ServerPlayer player, int amount) {
		return remove(player, Math.max(0, amount), usableOrigin(player));
	}

	public static int usableTotal(ServerPlayer player) {
		return usablePhysicalCount(player);
	}

}
