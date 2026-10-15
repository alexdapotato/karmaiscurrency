package akisei.karmacurrency;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** A server-side chest menu. Display slots are intercepted and cannot be taken. */
public final class KarmaShopMenu extends KarmaMenu {
	private static final int ROWS = 6;
	private static final int DISPLAY_SLOTS = ROWS * 9;
	private static final int COMMON_TAB_SLOT = 2;
	private static final int VILLAIN_TAB_SLOT = 6;
	private static final int BACK_SLOT = 49;
	private static final int[] REWARD_SLOTS = {10, 12, 14, 16, 28, 30, 32, 34, 38, 42};

	private ShopCategory category;
	private final ShrineSite shrine;
	private final Runnable onBack;
	private final boolean allowBothTabs;
	private final long catalogCycle;
	private final String cycleStatus;

	public KarmaShopMenu(int containerId, Inventory inventory, ServerPlayer player) {
		this(containerId, inventory, player, null, null, null, new SimpleContainer(DISPLAY_SLOTS));
	}

	private KarmaShopMenu(int containerId, Inventory inventory, ServerPlayer player, ShopCategory initialCategory, ShrineSite shrine, Runnable onBack, SimpleContainer display) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, ROWS, player);
		this.shrine = shrine;
		this.catalogCycle = KicCycle.currentCycleId();
		this.cycleStatus = KicCycle.statusLine();
		this.allowBothTabs = shrine == null || shrine.type() == ShrineType.NEUTRAL || shrine.type() == ShrineType.MONUMENT;
		this.onBack = onBack != null ? onBack : () -> {
			if (shrine != null) ShrineMenu.open(player, shrine);
			else player.closeContainer();
		};
		showCategory(initialCategory == null ? ShopCategory.COMMON : initialCategory);
	}

	public static void openForShrine(ServerPlayer player, ShrineType type) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) return;
		if (type == null) return;
		if (type == ShrineType.LIGHT && !CurseBlessingService.canUseLightShrine(player)) return;
		if (type == ShrineType.DARK && !CurseBlessingService.canUseDarkShrine(player)) return;
		ShopCategory category = ShopCategory.forShrineType(type);
		player.openMenu(new net.minecraft.world.SimpleMenuProvider(
			(containerId, inventory, ignored) -> new KarmaShopMenu(containerId, inventory, player, category, null, null, new SimpleContainer(DISPLAY_SLOTS)),
			Component.literal("Shrine Shop")));
	}

	public static void openForShrine(ServerPlayer player, ShrineSite shrine) {
		openForShrine(player, shrine, (Runnable) null);
	}

	public static void openForShrine(ServerPlayer player, ShrineSite shrine, ChestMenu parentMenu) {
		openForShrine(player, shrine, parentMenu != null ? () -> ShrineMenu.open(player, shrine) : null);
	}

	public static void openForShrine(ServerPlayer player, ShrineSite shrine, Runnable onBack) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) return;
		if (shrine == null) return;
		if (shrine.type() == ShrineType.LIGHT && !CurseBlessingService.canUseLightShrine(player)) return;
		if (shrine.type() == ShrineType.DARK && !CurseBlessingService.canUseDarkShrine(player)) return;
		ShopCategory category = ShopCategory.forShrineType(shrine == null ? ShrineType.NEUTRAL : shrine.type());
		if (shrine != null && shrine.type() == ShrineType.DARK) category = ShopCategory.VILLAIN;
		if (shrine != null && shrine.type() == ShrineType.MONUMENT) category = ShopCategory.COMMON;
		Runnable finalOnBack = onBack;
		final ShopCategory finalCategory = category;
		player.openMenu(new net.minecraft.world.SimpleMenuProvider(
			(containerId, inventory, ignored) -> new KarmaShopMenu(containerId, inventory, player, finalCategory, shrine, finalOnBack, new SimpleContainer(DISPLAY_SLOTS)),
			Component.literal("Shrine Shop")));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < DISPLAY_SLOTS) {
			if (input == ContainerInput.PICKUP && button == 0) {
				handleDisplayClick(slotId);
			}
			if (player.containerMenu == this) broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	@Override
	public void removed(Player closingPlayer) {
		for (int slot = 0; slot < DISPLAY_SLOTS; slot++) display.setItem(slot, ItemStack.EMPTY);
		super.removed(closingPlayer);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotId) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slots.indexOf(slot) >= DISPLAY_SLOTS;
	}

	private void handleDisplayClick(int slotId) {
		if (slotId == COMMON_TAB_SLOT) {
			if (allowBothTabs || (shrine != null && ShopCategory.canAccess(player, ShopCategory.COMMON, shrine.type()))) {
				showCategory(ShopCategory.COMMON);
			}
			return;
		}
		if (slotId == VILLAIN_TAB_SLOT) {
			if (allowBothTabs || (shrine != null && ShopCategory.canAccess(player, ShopCategory.VILLAIN, shrine.type()))) {
				showCategory(ShopCategory.VILLAIN);
			}
			return;
		}
		if (slotId == BACK_SLOT) {
			onBack.run();
			return;
		}
		List<ShopReward> rewards = KarmaShopCatalog.rewardsFor(category, catalogCycle);
		for (int index = 0; index < rewards.size() && index < REWARD_SLOTS.length; index++) {
			if (slotId == REWARD_SLOTS[index]) {
				ShopReward reward = rewards.get(index);
				String lock = KarmaShopCatalog.purchaseBlocker(player, reward, catalogCycle);
				if (!lock.isEmpty()) {
					player.sendSystemMessage(Component.literal(lock));
					showCategory(category);
					return;
				}
				KarmaShopCatalog.PurchaseResult result = KarmaShopCatalog.purchase(player, reward, catalogCycle);
				if (result.successful()) {
					player.sendOverlayMessage(result.asComponent());
				} else {
					player.sendSystemMessage(result.asComponent());
				}
				showCategory(category);
				return;
			}
		}
	}

	private void showCategory(ShopCategory category) {
		this.category = category;
		fillBackground();
		if (allowBothTabs || (shrine != null && ShopCategory.canAccess(player, ShopCategory.COMMON, shrine.type()))) {
			display.setItem(COMMON_TAB_SLOT, icon(Items.EMERALD,
				"Light Shop" + (category == ShopCategory.COMMON ? "  <" : ""),
				"Reputation.", "Shared by Light and Neutral."));
		}
		if (allowBothTabs || (shrine != null && ShopCategory.canAccess(player, ShopCategory.VILLAIN, shrine.type()))) {
			display.setItem(VILLAIN_TAB_SLOT, icon(Items.WITHER_SKELETON_SKULL,
				"Dark Shop" + (category == ShopCategory.VILLAIN ? "  <" : ""),
				"Infamy.", "Dark-aligned currency."));
		}
		Item categoryIcon = switch (category) {
			case COMMON -> Items.EMERALD;
			case VILLAIN -> Items.WITHER_SKELETON_SKULL;
		};
		display.setItem(4, icon(categoryIcon,
			category.displayName(), balancesLine(), cycleStatus, categoryDescription(category), allowBothTabs ? "Use the tabs above to switch shops." : "Shop category is fixed for this shrine."));
		display.setItem(BACK_SLOT, icon(Items.ARROW, "Back", shrine == null ? "Close the shop." : "Return to the shrine menu."));
		List<ShopReward> rewards = KarmaShopCatalog.rewardsFor(category, catalogCycle);
		for (int index = 0; index < rewards.size() && index < REWARD_SLOTS.length; index++) {
			ShopReward reward = rewards.get(index);
			String lock = KarmaShopCatalog.purchaseBlocker(player, reward, catalogCycle);
			display.setItem(REWARD_SLOTS[index], icon(KarmaShopCatalog.previewStack(player, reward, catalogCycle),
				reward.displayName(),
                KarmaShopCatalog.priceLabel(player, reward), KarmaShopCatalog.balanceLabel(player, reward),
                "Receive: " + reward.count(), lock.isEmpty() ? "Left-click to purchase." : lock,
                reward.maxPurchases() == 0 ? "Unlimited purchases." : "Purchases this cycle: "
                    + ShopPurchaseState.get(player.level().getServer()).count(player.getUUID(), category, reward.id(), catalogCycle)
                    + "/" + reward.maxPurchases()));
		}
	}
	private void fillBackground() {
		for (int slot = 0; slot < DISPLAY_SLOTS; slot++) {
			display.setItem(slot, icon(Items.STAINED_GLASS_PANE.gray(), " "));
		}
	}

	private String balancesLine() {
		return "Karma: " + KarmaService.format(KarmaService.get(player))
			+ " | Reputation: " + SocialCurrencyService.getReputation(player)
			+ " | Infamy: " + KarmaService.getInfamyPoints(player)
			+ " | Tokens: " + SocialTokenService.count(player);
	}

	private static String categoryDescription(ShopCategory category) {
		return switch (category) {
			case COMMON -> "Requires Reputation. Shared by Light and Neutral.";
			case VILLAIN -> "Requires Infamy.";
		};
	}

	private static ItemStack icon(Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		if (lore.length > 0) {
			stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lore).<Component>map(line -> Component.literal(line).withStyle(line.startsWith("Missing:") ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GRAY)).toList()));
		}
		return stack;
	}

	private static ItemStack icon(ItemStack stack, String name, String... lore) {
		if (stack == null || stack.isEmpty()) stack = new ItemStack(Items.BARRIER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		if (lore.length > 0) {
			stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lore).<Component>map(line -> Component.literal(line).withStyle(line.startsWith("Missing:") ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GRAY)).toList()));
		}
		return stack;
	}
}
