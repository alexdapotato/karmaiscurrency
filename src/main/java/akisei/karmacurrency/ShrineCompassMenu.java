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

/** Dedicated target selector opened by right-clicking a shrine compass. */
public final class ShrineCompassMenu extends KarmaMenu {
	private static final int ROWS = 3;
	private static final int DISPLAY_SLOTS = ROWS * 9;
	private static final int LIGHT_SLOT = 10;
	private static final int NEAREST_SLOT = 11;
	private static final int DARK_SLOT = 12;
	private static final int NEUTRAL_SLOT = 14;
	private static final int MONUMENT_SLOT = 16;
	private static final int BACK_SLOT = 26;

	private final ShrineSite shrine;
	private final Runnable onBack;

	private ShrineCompassMenu(int containerId, Inventory inventory, ServerPlayer player, ShrineSite shrine, Runnable onBack) {
		this(containerId, inventory, player, shrine, onBack, new SimpleContainer(DISPLAY_SLOTS));
	}

	private ShrineCompassMenu(
			int containerId, Inventory inventory, ServerPlayer player, ShrineSite shrine, Runnable onBack,
			SimpleContainer display) {
		super(MenuType.GENERIC_9x3, containerId, inventory, display, ROWS, player);
		this.shrine = shrine;
		this.onBack = onBack;
		refresh();
	}

	public static void open(ServerPlayer player) {
		if (BedrockKicUI.openShrineCompass(player, null, () -> BedrockKicUI.openMain(player))) return;
		open(player, null, player::closeContainer);
	}

	public static void open(ServerPlayer player, ShrineSite shrine, Runnable onBack) {
		if (BedrockKicUI.openShrineCompass(player, shrine, onBack)) return;
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)) {
			player.sendSystemMessage(Component.literal("Shrine compasses are disabled for this world."));
			return;
		}
		player.openMenu(new SimpleMenuProvider(
			(containerId, inventory, ignored) -> new ShrineCompassMenu(containerId, inventory, player, shrine, onBack),
			Component.literal("Compass")
		));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < DISPLAY_SLOTS) {
			if (input == ContainerInput.PICKUP && button == 0) {
				handleClick(slotId);
			}
			broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotId) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slots.indexOf(slot) >= DISPLAY_SLOTS;
	}

	private void handleClick(int slotId) {
		ShrineType type = switch (slotId) {
			case LIGHT_SLOT -> ShrineType.LIGHT;
			case DARK_SLOT -> ShrineType.DARK;
			case NEUTRAL_SLOT -> ShrineType.NEUTRAL;
			case MONUMENT_SLOT -> ShrineType.MONUMENT;
			default -> null;
		};
		if (slotId == BACK_SLOT) {
			onBack.run();
			return;
		}

		ItemStack compass = player.getInventory().getNonEquipmentItems().stream()
			.filter(ShrineCompass::isShrineCompass)
			.findFirst()
			.orElse(null);
		if (compass == null) {
			player.sendSystemMessage(Component.literal("Your shrine compass is no longer in your inventory."));
			return;
		}

		if (slotId == NEAREST_SLOT) {
			ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
				player.blockPosition().getX(), player.blockPosition().getZ());
			ShrineManager.nearestShrineTarget(position, player.level().getServer()).ifPresentOrElse(target -> {
				if (target.generated().isPresent()) {
					ShrineCompass.retarget(compass, target.generated().get(), player.level().getServer());
					player.sendSystemMessage(Component.literal("Compass now points to the nearest " + target.generated().get().displayName() + "."));
				} else {
					ShrineCompass.retargetPredicted(compass, target.position().getX(), target.position().getZ());
					player.sendSystemMessage(Component.literal("Compass now points to the nearest predicted shrine location (type will be known when generated)."));
				}
				refresh();
			}, () -> player.sendSystemMessage(Component.literal("No shrine location is available.")));
			return;
		}
		if (type == null) {
			return;
		}

		ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
			player.blockPosition().getX(), player.blockPosition().getZ()
		);
		ShrineManager.nearestShrine(position, player.level().getServer(), type).ifPresentOrElse(
			site -> {
				ShrineCompass.retarget(compass, site, player.level().getServer());
				player.sendSystemMessage(Component.literal(
					"Compass now points to the nearest " + type.displayName() + "."
				));
				refresh();
			},
			() -> player.sendSystemMessage(Component.literal(
				"No active " + type.displayName() + " statue is available."
			))
		);
	}

	private void refresh() {
		for (int slot = 0; slot < DISPLAY_SLOTS; slot++) {
			display.setItem(slot, icon(Items.STAINED_GLASS_PANE.gray(), " "));
		}
		display.setItem(4, icon(Items.COMPASS, "Choose a Statue",
			"Select where your compass should point."));
		display.setItem(NEAREST_SLOT, icon(Items.COMPASS, "Nearest Shrine",
			"Keep the compass on the nearest generated or predicted shrine."));
		display.setItem(LIGHT_SLOT, icon(Items.EMERALD, targetLabel(ShrineType.LIGHT),
			"Point to the nearest active Light shrine."));
		display.setItem(DARK_SLOT, icon(Items.WITHER_SKELETON_SKULL, targetLabel(ShrineType.DARK),
			"Point to the nearest active Dark shrine."));
		display.setItem(NEUTRAL_SLOT, icon(Items.AMETHYST_SHARD, targetLabel(ShrineType.NEUTRAL),
			"Point to the nearest active Neutral shrine."));
		display.setItem(MONUMENT_SLOT, icon(Items.GOLD_BLOCK, targetLabel(ShrineType.MONUMENT),
			"Point to the nearest active Monument."));
		display.setItem(BACK_SLOT, icon(Items.ARROW, "Back", shrine == null ? "Return to the game." : "Return to the shrine menu."));
	}

	private String targetLabel(ShrineType type) {
		ShrineManager.ServerPlayerPosition position = new ShrineManager.ServerPlayerPosition(
			player.blockPosition().getX(), player.blockPosition().getZ());
		return ShrineManager.nearestShrineTarget(position, player.level().getServer(), type)
			.map(target -> target.generated().isPresent()
				? target.generated().get().statueName() + " (" + type.displayName() + ")"
				: "Predicted shrine")
			.orElse("No " + type.displayName() + " location available");
	}

	private static ItemStack icon(Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, KicUiText.title(name));
		if (lore.length > 0) {
			stack.set(DataComponents.LORE, new ItemLore(
				KicUiText.lore(lore)
			));
		}
		return stack;
	}
}
