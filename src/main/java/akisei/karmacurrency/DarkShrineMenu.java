package akisei.karmacurrency;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Dedicated Dark Shrine contract screen. */
public final class DarkShrineMenu extends KarmaMenu {
	private static final int SIZE = 27;
	private static final int CURSES = 12;
	private static final int SHOP = 14;
	private static final int BACK = 26;

	private final ShrineSite site;
	private final Runnable onBack;

	private DarkShrineMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
			Runnable onBack, SimpleContainer display) {
		super(MenuType.GENERIC_9x3, id, inventory, display, 3, player);
		this.site = site;
		this.onBack = onBack;
		refresh();
	}

	public static void open(ServerPlayer player, ShrineSite site, Runnable onBack) {
		player.openMenu(new SimpleMenuProvider(
			(id, inventory, ignored) -> new DarkShrineMenu(id, inventory, player, site, onBack, new SimpleContainer(SIZE)),
			Component.literal(site.displayName() + " - Dark Hub")));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				if (slotId == CURSES) DarkCursesMenu.open(player, site, () -> openDarkHub(player, site, onBack));
				if (slotId == SHOP) KarmaShopMenu.openForShrine(player, site, this);
				if (slotId == BACK) onBack.run();
				refresh();
			}
			broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	private static void openDarkHub(ServerPlayer player, ShrineSite site, Runnable onBack) {
		DarkShrineMenu.open(player, site, onBack);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }

	@Override
	public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }

	private void refresh() {
		for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
		display.setItem(4, ShrineMenu.icon(Items.WITHER_SKELETON_SKULL, "Dark Hub",
			"Infamy: " + KarmaService.getInfamyPoints(player)));
		display.setItem(CURSES, ShrineMenu.icon(Items.BLAZE_POWDER, "Dark Curses",
			"Spend Infamy for dark power."));
		display.setItem(SHOP, ShrineMenu.icon(Items.CHEST, "Dark Shop",
			"Buy items with Infamy."));
		display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to the shrine menu."));
	}
}
