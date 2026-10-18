package akisei.karmacurrency;

import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.component.ItemLore;

/** Server-wide community Influence progression screen. */
public final class WorldProgressionMenu extends KarmaMenu {
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int BAR_START = 20;
	private static final int[] CONTRIBUTION_AMOUNTS = {-100, -10, -1, 1, 10, 100};
	private static final int[] CONTRIBUTION_SLOTS = {28, 29, 30, 32, 33, 34};
	private static final int FILL_STAGE = 37;
	private static final int MAXIMUM = 43;
	private static final int CONFIRM_CONTRIBUTION = 39;
	private static final int CANCEL_CONTRIBUTION = 41;
	private static final int CONTRIBUTION_LOG = 49;
	private static final int BACK = 53;

	private final Runnable onBack;
	private int pendingContribution;
	private int previewPool;
    private String previewReview;

	private WorldProgressionMenu(int id, Inventory inventory, ServerPlayer player, Runnable onBack, SimpleContainer display) {
		super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
		this.onBack = onBack;
		refresh();
	}

	public static void open(ServerPlayer player, Runnable onBack) {
		player.openMenu(new SimpleMenuProvider(
			(id, inventory, ignored) -> new WorldProgressionMenu(id, inventory, player, onBack, new SimpleContainer(SIZE)),
			Component.literal("World Progression")));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				if (slotId == BACK) {
					onBack.run();
					return;
				}
				else if (slotId == CONFIRM_CONTRIBUTION && pendingContribution > 0) {
					if (!CommunityProgressionService.contributionReview(player).equals(previewReview)) {
                        player.sendSystemMessage(Component.literal("World progress changed. Please select and review your contribution again."));
                        pendingContribution = 0;
                    } else {
                        contribute(pendingContribution);
                        pendingContribution = 0;
                    }
				} else if (slotId == MAXIMUM) {
                    pendingContribution = CommunityProgressionService.availableInfluence(player);
                } else if (slotId == FILL_STAGE) {
                    CommunityProgressionService data = CommunityProgressionService.get(player.level().getServer());
                    pendingContribution = WorldContributionAmounts.fill(CommunityProgressionService.availableInfluence(player), data.pool(),
                        CommunityProgressionService.get(player.level().getServer()).nextStageThreshold(data.stage()), data.stage() >= CommunityProgressionService.MAX_STAGE);
                } else if (slotId == CANCEL_CONTRIBUTION) {
					pendingContribution = 0;
				} else {
					for (int i = 0; i < CONTRIBUTION_SLOTS.length; i++) {
						if (slotId == CONTRIBUTION_SLOTS[i]) {
							pendingContribution = WorldContributionAmounts.adjust(pendingContribution, CONTRIBUTION_AMOUNTS[i], CommunityProgressionService.availableInfluence(player));
							break;
						}
					}
				}
				refresh();
			}
			broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	@Override public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }
	@Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }

	private void contribute(int amount) {
        if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
            player.sendSystemMessage(Component.literal("Social Currency is disabled for this world."));
            return;
        }
		boolean contributed = CommunityProgressionService.contributeInfluence(player, amount);
		if (!contributed) player.sendSystemMessage(Component.literal("You need " + amount + " stored Influence in your inventory."));
	}

	private void refresh() {
		for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, icon(Items.STAINED_GLASS_PANE.gray(), " "));
		CommunityProgressionService community = CommunityProgressionService.get(player.level().getServer());
		int stage = community.stage();
		int pool = community.pool();
        previewPool = pool;
        previewReview = CommunityProgressionService.contributionReview(player);
		int previous = CommunityProgressionService.get(player.level().getServer()).thresholdForStage(stage);
		int next = CommunityProgressionService.get(player.level().getServer()).nextStageThreshold(stage);
		int progress = next > previous ? Math.clamp(pool - previous, 0, next - previous) : next - previous;
		int filled = next > previous ? progress * 5 / (next - previous) : 5;

		display.setItem(13, icon(Items.BEACON, "World Progression",
			"Stage " + stage + "/" + CommunityProgressionService.MAX_STAGE,
			"Cumulative community Influence: " + CommunityProgressionService.formatInfluence(pool),
			stage < CommunityProgressionService.MAX_STAGE
				? "Progress to Stage " + (stage + 1) + ": "
					+ CommunityProgressionService.formatInfluence(progress) + " / "
					+ CommunityProgressionService.formatInfluence(next - previous)
				: "Maximum Stage reached. Additional Influence stays in the community pool.",
			stage < CommunityProgressionService.MAX_STAGE
				? "Next-stage threshold: " + CommunityProgressionService.formatInfluence(next)
				: "Stage 5 is the cap."));
		for (int i = 0; i < 5; i++) {
			display.setItem(BAR_START + i, icon(i < stage ? Items.EMERALD_BLOCK : Items.STAINED_GLASS_PANE.gray(),
				"Stage " + (i + 1), i < stage ? "Unlocked" : "Requires " + CommunityProgressionService.get(player.level().getServer()).thresholdForStage(i + 1) + " total Influence"));
		}
		int availableInfluence = CommunityProgressionService.availableInfluence(player);
		for (int i = 0; i < CONTRIBUTION_AMOUNTS.length; i++) {
			int amount = CONTRIBUTION_AMOUNTS[i];
			display.setItem(CONTRIBUTION_SLOTS[i], icon(Math.abs(amount) == 100 ? Items.GOLD_BLOCK : Math.abs(amount) == 10 ? Items.GOLD_INGOT : Items.GOLD_NUGGET,
				(amount > 0 ? "+" : "") + amount + " Influence",
				"Spends " + CommunityProgressionService.contributionCurrency(player) + ".",
				"Available now: " + CommunityProgressionService.formatInfluence(availableInfluence)));
		}
        display.setItem(31, icon(Items.ECHO_SHARD, "Selected: " + pendingContribution,
            "Available " + CommunityProgressionService.contributionCurrency(player) + ": " + availableInfluence,
            "Community pool: " + pool + " -> " + ((long) pool + pendingContribution)));
        display.setItem(FILL_STAGE, icon(Items.BEACON, "Fill next stage", "Select what you can afford toward the next stage."));
        display.setItem(MAXIMUM, icon(Items.EMERALD_BLOCK, "Select maximum", "Select all stored Influence; confirm below."));
		display.setItem(CONFIRM_CONTRIBUTION,
			pendingContribution > 0
				? icon(Items.CONCRETE.lime(), "Confirm " + pendingContribution + " Influence",
					"Influence: " + availableInfluence + "/" + pendingContribution,
                        !KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED) ? "Social Currency is disabled."
                            : availableInfluence < pendingContribution ? "Missing Influence: " + (pendingContribution - availableInfluence) : "Ready to contribute.")
				: icon(Items.CONCRETE.gray(), "Select an amount first"));
		display.setItem(CANCEL_CONTRIBUTION, icon(Items.CONCRETE.red(), "Cancel selection"));
		display.setItem(45, icon(Items.BOOK, "Next stage", CommunityProgressionService.nextStagePreview(player).toArray(String[]::new)));
        display.setItem(49, icon(Items.NAME_TAG, "Your contribution milestones", CommunityProgressionService.personalContributionSummary(player)));
		display.setItem(BACK, icon(Items.ARROW, "Back"));
	}

	private static ItemStack icon(net.minecraft.world.item.Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, KicUiText.title(name));
		if (lore.length > 0) stack.set(DataComponents.LORE,
			new ItemLore(KicUiText.lore(lore)));
		return stack;
	}
}
