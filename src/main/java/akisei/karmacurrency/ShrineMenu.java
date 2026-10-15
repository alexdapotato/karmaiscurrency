package akisei.karmacurrency;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
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

/** The single root menu for a shrine. Every shrine feature opens from this screen. */
public final class ShrineMenu extends KarmaMenu {
	// Main shrine UI follows the vanilla Large Chest layout from the design mockup.
	// Row 0 is a full-width shrine identity strip; the remaining icons are navigation.
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int KARMA = 22;
	private static final int COMPASS = 29;
	private static final int INFO = 31;
	private static final int SHOP = 33;
	private static final int BLESSINGS = 38;
	private static final int QUESTS = 40;
	private static final int CURSES = 42;

	private final ShrineSite site;

	private ShrineMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site, SimpleContainer display) {
		super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
		this.site = site;
		refresh();
	}

	public static void open(ServerPlayer player, ShrineSite site) {
		if (BedrockKicUI.openShrine(player, site)) return;
		if (site.type() == ShrineType.MONUMENT) {
			MonumentMenu.open(player, site);
			return;
		}
		player.openMenu(new SimpleMenuProvider(
			(id, inventory, ignored) -> new ShrineMenu(id, inventory, player, site, new SimpleContainer(SIZE)),
			Component.literal(site.displayName())));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				handleClick(slotId);
			}
			broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	@Override
	public void removed(Player closingPlayer) {
		for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, ItemStack.EMPTY);
		super.removed(closingPlayer);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotId) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slots.indexOf(slot) >= SIZE;
	}

	private void handleClick(int slotId) {
		switch (slotId) {
			case KARMA -> KarmaStatusMenu.open(player, site, this::openRoot);
			case COMPASS -> {
				if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) giveShrineCompass(player, site);
			}
			case INFO -> { } // Reserved for Shrine Info; intentionally inert for now.
			case SHOP -> {
				if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) KarmaShopMenu.openForShrine(player, site, this::openRoot);
			}
			case BLESSINGS, CURSES -> {
				if (KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) KarmaStatusMenu.open(player, site, this::openRoot);
			}
			case QUESTS -> {
				if (KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
					|| KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
					QuestMenu.open(player, site, this::openRoot);
				}
			}
			default -> { }
		}
	}

	static void giveShrineCompass(ServerPlayer player, ShrineSite site) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) return;
		if (ShrineCompass.hasShrineCompass(player)) {
			player.sendSystemMessage(Component.literal("You already have a Shrine Compass."));
			return;
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED)
			&& !KarmaService.canReceiveShrineCompass(player)) {
			player.sendSystemMessage(Component.literal("Shrine Compass cooldown: "
				+ KarmaService.getShrineCompassCooldownMinutes(player) + " minute(s)."));
			return;
		}
		if (ShrineCompass.give(player, site, !KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED))) {
			player.sendSystemMessage(Component.literal("Shrine Compass obtained."));
		}
	}

	private void openRoot() {
		ShrineMenu.open(player, site);
	}

	private void refresh() {
		// Keep the chest empty except for the intentional UI items.
		for (int slot = 0; slot < SIZE; slot++) {
			display.setItem(slot, icon(Items.STAINED_GLASS_PANE.gray(), " "));
		}

		// Full-width visual shrine indicator. These are deliberately decorative, not buttons.
		Item identityBlock = switch (site.type()) {
			case LIGHT -> Items.GOLD_BLOCK;
			case DARK -> Items.REDSTONE_BLOCK;
			case NEUTRAL -> Items.IRON_BLOCK;
			case MONUMENT -> Items.NETHERITE_BLOCK;
		};
		for (int slot = 0; slot < 9; slot++) {
			display.setItem(slot, new ItemStack(identityBlock));
		}

		// Main navigation matches the supplied Large Chest mockup.
		if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
			display.setItem(KARMA, icon(Items.NETHER_STAR, "Karma",
				"Karma: " + KarmaService.format(KarmaService.get(player)), KicUiText.alignmentStatus(player), "Open your Karma interface."));
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
			display.setItem(COMPASS, icon(Items.COMPASS, "Shrine Compass", "Obtain a compass for shrine navigation.",
				KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED) ? "Cooldown: 10 minutes." : "Cooldown disabled."));
		}
		display.setItem(INFO, icon(Items.LODESTONE, "Shrine Info",
			"View shrine information.", "Interaction is reserved for a later pass."));
		if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
			display.setItem(SHOP, icon(Items.BELL, "Shop", "Open the shrine shop."));
		}
		if (site.type() == ShrineType.LIGHT && KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
			&& KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) {
			display.setItem(BLESSINGS, icon(Items.EMERALD_BLOCK, "Blessings",
				"View Light Shrine blessings and their requirements."));
		}
		if (site.type() == ShrineType.DARK && KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)
			&& KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)) {
			display.setItem(CURSES, icon(Items.BLAZE_POWDER, "Curses",
				"View Dark Shrine curses and their requirements."));
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
				|| KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
			display.setItem(QUESTS, icon(Items.WRITABLE_BOOK, "Quests",
				"View the current Trials, Contracts, and The Hunt."));
		}

	}

	static ItemStack icon(Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		if (lore.length > 0) {
			stack.set(DataComponents.LORE, new ItemLore(
					java.util.Arrays.stream(lore).<Component>map(Component::literal).toList()));
		}
		return stack;
	}

	static ItemStack icon(ItemStack stack, String name, String... lore) {
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		if (lore.length > 0) {
			stack.set(DataComponents.LORE, new ItemLore(
					java.util.Arrays.stream(lore)
							.<Component>map(Component::literal)
							.toList()));
		}
		return stack;
	}
}
