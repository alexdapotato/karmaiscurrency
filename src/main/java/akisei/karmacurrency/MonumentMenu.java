package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Monument/Worldheart root menu. It combines Light and Dark functionality in one screen. */
public final class MonumentMenu extends KarmaMenu {
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int KARMA = 22;
	private static final int COMPASS = 29;
	private static final int INFO = 31;
	private static final int SHOP = 33;
	private static final int ACTIVITIES = 40;
	private static final int BACK = 53;

	private final ShrineSite site;

	private MonumentMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site, SimpleContainer display) {
		super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
		this.site = site;
		refresh();
	}

	public static void open(ServerPlayer player, ShrineSite site) {
        if (!ShrineInteractions.withinMenuRange(player, site)) return;
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINES_ENABLED)) return;
		player.openMenu(new SimpleMenuProvider(
			(id, inventory, ignored) -> new MonumentMenu(id, inventory, player, site, new SimpleContainer(SIZE)),
			Component.literal(site.displayName())));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player || !stillValid(clicker)) return;
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				switch (slotId) {
					case KARMA -> KarmaStatusMenu.open(player, site, this::openRoot);
					case COMPASS -> ShrineMenu.giveShrineCompass(player, site);
					case INFO -> { } // Reserved for Shrine Info; intentionally inert for now.
					case SHOP -> KarmaShopMenu.openForShrine(player, site, this::openRoot);
					case ACTIVITIES -> {
						if (KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
								|| KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
							QuestMenu.open(player, site, this::openRoot);
						}
					}
					case BACK -> player.closeContainer();
					default -> { }
				}
			}
			broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

    @Override
    public boolean stillValid(Player other) {
        return other == player && ShrineInteractions.withinMenuRange(player, site);
    }

	@Override
	public void removed(Player closingPlayer) {
		for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, ItemStack.EMPTY);
		super.removed(closingPlayer);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }

	@Override
	public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }

	private void openRoot() {
		open(player, site);
	}

	private void refresh() {
		for (int slot = 0; slot < SIZE; slot++) {
			display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
		}

		// Monument identity strip: decorative and non-clickable.
		for (int slot = 0; slot < 9; slot++) {
			display.setItem(slot, new ItemStack(Items.NETHERITE_BLOCK));
		}

		if (KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
			display.setItem(KARMA, ShrineMenu.icon(Items.NETHER_STAR, "Karma",
				"Karma: " + KarmaService.format(KarmaService.get(player)),
				"Open Karma to manage Blessings, Curses, Titles, and Social Currency."));
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
			display.setItem(COMPASS, ShrineMenu.icon(Items.COMPASS, "Shrine Compass", "Obtain a compass for shrine navigation."));
		}
		display.setItem(INFO, ShrineMenu.icon(Items.LODESTONE, "Shrine Info",
			"Worldheart / Monument information.",
			"Shared progression for the whole world."));
		if (KarmaGameRules.enabled(player, KarmaGameRules.SHOP_ENABLED)) {
			display.setItem(SHOP, ShrineMenu.icon(Items.BELL, "Shop", "Access the combined Monument shop."));
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.QUESTS_ENABLED)
				|| KarmaGameRules.enabled(player, KarmaGameRules.DUELS_ENABLED)) {
			display.setItem(ACTIVITIES, ShrineMenu.icon(Items.WRITABLE_BOOK, "Quests",
				"Open permanent The Hunt and current rotating Trials / Contracts.",
				"The Hunt does not use a rotating slot."));
		}
		display.setItem(BACK, ShrineMenu.icon(Items.BARRIER, "Close"));
	}
}
