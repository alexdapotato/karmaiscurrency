package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Unified Karma interface used by Light, Dark, Neutral, and Monument shrines. */
public final class KarmaStatusMenu extends KarmaMenu {
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;

	// Row 1 tab strip, shifted left by one column.
	private static final int TAB_ALIGNMENT = 1;
	private static final int TAB_TITLES = 3;
	private static final int TAB_SOCIAL = 5;
	/** Row 1, column 8: community Karma / World Progression tab. */
	private static final int TAB_KARMA = 7;
	// Monument controls remain in row 2: indicator = column 4, toggle = column 6.
	private static final int MONUMENT_INDICATOR = 12;
	private static final int MONUMENT_TOGGLE = 14;
	private static final int BACK = 53;
	private static final int[] ACTIVE_CURSE_SLOTS = {19, 20, 21, 22};
	private static final int[] ACTIVE_BLESSING_SLOTS = {23, 24, 25};
	private static final int[] AVAILABLE_BLESSING_SLOTS = {38, 39, 40, 41, 42};
	private static final int[] AVAILABLE_CURSE_SLOTS = {38, 39, 40, 41, 42};
	private static final int SIGIL_PREVIOUS_PAGE = 28;
	private static final int SIGIL_NEXT_PAGE = 34;

	// Social Currency layout. In Materialize mode row 4 is a dispenser: the centre
	// slot is a free-to-take payout and the surrounding gold controls choose/extend
	// the replenishment batch. In Dematerialize mode row 4 becomes a seven-slot
	// offering tray.
	private static final int SOCIAL_DECREASE_100 = 28;
	private static final int SOCIAL_DECREASE_10 = 29;
	private static final int SOCIAL_DECREASE_1 = 30;
	private static final int SOCIAL_COUNTER = 31;
	private static final int SOCIAL_INCREASE_1 = 32;
	private static final int SOCIAL_INCREASE_10 = 33;
	private static final int SOCIAL_INCREASE_100 = 34;
	private static final int SOCIAL_SOURCE = 38;
	private static final int SOCIAL_MODE = 39;
	private static final int SOCIAL_ACTION = 40;
	private static final int SOCIAL_ALL = 41;
	private static final int WORLD_PROGRESSION = 22;

	private enum Tab { ALIGNMENT, TITLES, SOCIAL, KARMA }
	private static final int MAX_MATERIALIZATION_AMOUNT = 9 * SocialTokenService.STACK_SIZE;

	private final ShrineSite site;
	private final Runnable onBack;
	private Tab tab = Tab.ALIGNMENT;
	private boolean monumentBlessingMode;
	private boolean redeemMode;
	private int selected;
    private String pendingSocialConfirmation;
    private java.util.List<ItemStack> pendingOfferingStacks;

    private void resetSocialConfirmation() {
        pendingSocialConfirmation = null;
        pendingOfferingStacks = null;
    }

    private boolean confirmSocial(String action) {
        String key = action + ":" + selected + ":" + SocialCurrencyService.getStance(player)
            + ":" + KarmaService.rewardAlignment(player) + ":" + socialBalance() + ":" + dematerializationExactValue();
        boolean same = key.equals(pendingSocialConfirmation);
        if (redeemMode && same) {
            for (int i = 0; i < 7; i++) {
                ItemStack before = pendingOfferingStacks.get(i), now = display.getItem(SOCIAL_DECREASE_100 + i);
                if (before.getCount() != now.getCount() || !ItemStack.isSameItemSameComponents(before, now)) same = false;
            }
        }
        if (same) { resetSocialConfirmation(); return true; }
        pendingSocialConfirmation = key;
        pendingOfferingStacks = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) pendingOfferingStacks.add(display.getItem(SOCIAL_DECREASE_100 + i).copy());
        player.sendSystemMessage(Component.literal("Review " + action + ". Click the same action again to confirm; changing the selection cancels confirmation."));
        return false;
    }
	private int sigilPage;

	private KarmaStatusMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
			Runnable onBack, boolean monumentBlessingMode, int sigilPage, SimpleContainer display) {
		super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
		this.site = site;
		this.onBack = onBack;
		this.monumentBlessingMode = monumentBlessingMode;
		this.sigilPage = Math.max(0, sigilPage);
		for (int i = 0; i < 7; i++) {
			int slotId = 28 + i;
			int x = 8 + i * 18;
			slots.set(slotId, new Slot(display, slotId, x, 53) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return isOfferingTrayActive() && dematerializationItemValue(stack) > 0;
				}

				@Override
				public boolean mayPickup(Player pickupPlayer) {
					return pickupPlayer == player && isOfferingTrayActive();
				}

				@Override
				public ItemStack remove(int amount) {
					return isOfferingTrayActive() ? super.remove(amount) : ItemStack.EMPTY;
				}
			});
		}
		refresh();
	}

	private boolean isOfferingTrayActive() {
		return tab == Tab.SOCIAL && redeemMode;
	}

	public static void open(ServerPlayer player, ShrineSite site, Runnable onBack) {
		if (BedrockKicUI.openMain(player, site, onBack)) return;
		if (!KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
			player.sendSystemMessage(Component.literal("The Karma menu is disabled for this world."));
			return;
		}
		openSigils(player, site, onBack,
			site != null && SocialCurrencyService.getStance(player) != SocialCurrencyService.Stance.DARK, 0);
	}

	static void openSigils(ServerPlayer player, ShrineSite site, Runnable onBack,
			boolean monumentBlessingMode, int sigilPage) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
			player.sendSystemMessage(Component.literal("The Karma menu is disabled for this world."));
			return;
		}
		player.openMenu(new SimpleMenuProvider(
			(id, inventory, ignored) -> new KarmaStatusMenu(id, inventory, player, site, onBack,
				monumentBlessingMode, sigilPage, new SimpleContainer(SIZE)),
			Component.literal("Karma")));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (tab == Tab.SOCIAL && isSocialTraySlot(slotId)) {
			if (!redeemMode && slotId == SOCIAL_COUNTER && input == ContainerInput.PICKUP && button == 0) {
				materializeSelected();
				refresh();
				if (player.containerMenu == this) broadcastFullState();
				return;
			}
			if (redeemMode) {
				// The offering tray supports ordinary left/right pickup and placement only.
				// Reject hotbar/offhand swaps, cloning, throwing, drag, and double-click input.
				if (input == ContainerInput.PICKUP && (button == 0 || button == 1)) {
		                resetSocialConfirmation();
					super.clicked(slotId, button, input, clicker);
					refresh();
				}
				if (player.containerMenu == this) broadcastFullState();
				return;
			}
		}
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				handleClick(slotId);
			}
			if (player.containerMenu == this) broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	private boolean isSocialTraySlot(int slotId) {
		return slotId >= SOCIAL_DECREASE_100 && slotId <= SOCIAL_INCREASE_100;
	}

	@Override
	public ItemStack quickMoveStack(Player clicker, int slotId) {
		// Only shift-clicking an eligible item from the player's inventory into the
		// server-validated offering tray is supported. Never quick-move GUI contents out.
		if (clicker == player && slotId >= SIZE && tab == Tab.SOCIAL && redeemMode) {
			resetSocialConfirmation();
            ItemStack moved = quickMoveFromPlayerInventory(clicker, slotId);
			refresh();
			if (player.containerMenu == this) broadcastFullState();
			return moved;
		}
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		if (tab == Tab.SOCIAL && redeemMode && slot.container == display) {
			return isDematerializationSlot(slot.index);
		}
		return slots.indexOf(slot) >= SIZE;
	}

	@Override
	public void removed(Player closingPlayer) {
		if (tab == Tab.SOCIAL && redeemMode) {
			for (int i = SOCIAL_DECREASE_100; i <= SOCIAL_INCREASE_100; i++) {
				returnDisplayItem(i, closingPlayer);
			}
		}
		super.removed(closingPlayer);
	}

	private void returnDisplayItem(int slotId, Player closingPlayer) {
		ItemStack stack = display.removeItemNoUpdate(slotId);
		if (stack.isEmpty()) return;
		// Never silently destroy an item that made it into the player-facing tray.
		// Slot validation normally prevents invalid/token stacks, but this is a final
		// safety net for stale menu state, drag/shift-click edge cases, or future changes.
		if (!closingPlayer.addItem(stack)) closingPlayer.drop(stack, false);
	}

	@Override
	public boolean stillValid(Player player) {
		return player.isAlive();
	}

	private void handleClick(int slotId) {
		if (slotId == TAB_ALIGNMENT) {
			if (!canLeaveSocial()) return;
			if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) return;
			tab = Tab.ALIGNMENT;
			refresh();
			return;
		}
		if (slotId == TAB_TITLES) {
			if (!canLeaveSocial()) return;
			if (!KarmaGameRules.enabled(player, KarmaGameRules.TITLES_ENABLED)) return;
			tab = Tab.TITLES;
			refresh();
			return;
		}
		if (slotId == TAB_SOCIAL) {
			if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) return;
            if (tab != Tab.SOCIAL) {
                redeemMode = false;
                selected = 0;
                resetSocialConfirmation();
            }
			tab = Tab.SOCIAL;
			refresh();
			return;
		}
		if (slotId == TAB_KARMA) {
            if (!canLeaveSocial()) return;
			tab = Tab.KARMA;
			refresh();
			return;
		}
		if (slotId == MONUMENT_TOGGLE && site != null && site.type() == ShrineType.MONUMENT) {
			monumentBlessingMode = !monumentBlessingMode;
			sigilPage = 0;
			refresh();
			return;
		}
		if (slotId == BACK) {
			if (!canLeaveSocial()) return;
			onBack.run();
			return;
		}

		switch (tab) {
			case ALIGNMENT -> handleAlignmentClick(slotId);
			case SOCIAL -> handleSocialClick(slotId);
			case TITLES -> { }
			case KARMA -> {
				if (slotId == WORLD_PROGRESSION) {
					WorldProgressionMenu.open(player, this::openKarmaTab);
					return;
				}
			}
		}
		refresh();
	}

	private boolean showsBlessings() {
		if (site == null) return false;
		return site.type() == ShrineType.LIGHT || (site.type() == ShrineType.MONUMENT && monumentBlessingMode);
	}

	private boolean showsCurses() {
		if (site == null) return false;
		return site.type() == ShrineType.DARK || (site.type() == ShrineType.MONUMENT && !monumentBlessingMode);
	}

	private int availableSigilIndex(int slotId) {
		int[] slotsForTab = showsCurses() ? AVAILABLE_CURSE_SLOTS : AVAILABLE_BLESSING_SLOTS;
		for (int i = 0; i < slotsForTab.length; i++) {
			if (slotsForTab[i] == slotId) return i;
		}
		return -1;
	}

	private void handleAlignmentClick(int slotId) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) return;
		if (slotId == SIGIL_PREVIOUS_PAGE && sigilPage > 0) {
			sigilPage--;
			return;
		}
		if (slotId == SIGIL_NEXT_PAGE && sigilPage < sigilPageCount() - 1) {
			sigilPage++;
			return;
		}
        for (int i = 0; i < ACTIVE_CURSE_SLOTS.length; i++) {
            java.util.List<String> active = CurseBlessingService.activeCurses(player);
            if (slotId == ACTIVE_CURSE_SLOTS[i] && i < active.size()) {
                SigilDetailsMenu.openCurse(player, site, CurseBlessingRegistry.curse(active.get(i)), this::openStatus);
                return;
            }
        }
        for (int i = 0; i < ACTIVE_BLESSING_SLOTS.length; i++) {
            java.util.List<String> active = CurseBlessingService.activeBlessings(player);
            if (slotId == ACTIVE_BLESSING_SLOTS[i] && i < active.size()) {
                SigilDetailsMenu.openBlessing(player, site, CurseBlessingRegistry.blessing(active.get(i)), this::openStatus);
                return;
            }
        }
		int index = availableSigilIndex(slotId);
		if (index < 0) return;
		int registryIndex = sigilPage * (showsCurses() ? AVAILABLE_CURSE_SLOTS.length : AVAILABLE_BLESSING_SLOTS.length) + index;

		if (showsBlessings() && KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)
			&& registryIndex < currentBlessings().size()) {
			CurseBlessingRegistry.BlessingDefinition blessing = currentBlessings().get(registryIndex);
			SigilDetailsMenu.openBlessing(player, site, blessing,
				() -> openSigils(player, site, onBack, monumentBlessingMode, sigilPage));
			return;
		}

		if (showsCurses() && KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)
			&& registryIndex < currentCurses().size()) {
			CurseBlessingRegistry.CurseDefinition curse = currentCurses().get(registryIndex);
			SigilDetailsMenu.openCurse(player, site, curse,
				() -> openSigils(player, site, onBack, monumentBlessingMode, sigilPage));
		}
	}

	private void handleSocialClick(int slotId) {
        if (slotId != SOCIAL_ACTION) resetSocialConfirmation();
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) return;
		switch (slotId) {
			case SOCIAL_MODE -> {
				if (!redeemMode && !KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED)) {
					player.sendSystemMessage(Component.literal("Offerings are disabled for this world."));
					return;
				}
				if (redeemMode && hasDematerializationItems()) {
					player.sendSystemMessage(Component.literal("Remove the offered items before switching to Materialize."));
					return;
				}
				if (!redeemMode && hasMaterializerPayout()) {
					player.sendSystemMessage(Component.literal("Take the current payout before switching to Dematerialize."));
					return;
				}
				redeemMode = !redeemMode;
				selected = 0;
				if (redeemMode) {
					// Materialize controls are UI state, never offerings. Clear them before
					// the dematerialization refresh inspects the shared row-4 slots.
					for (int slot = SOCIAL_DECREASE_100; slot <= SOCIAL_INCREASE_100; slot++) {
						display.setItem(slot, ItemStack.EMPTY);
					}
				}
			}
			case SOCIAL_ACTION -> {
				if (redeemMode) performDematerialization();
			}
			case SOCIAL_DECREASE_100 -> selected = Math.max(0, selected - 100);
			case SOCIAL_DECREASE_10 -> selected = Math.max(0, selected - 10);
			case SOCIAL_DECREASE_1 -> selected = Math.max(0, selected - 1);
			case SOCIAL_INCREASE_1 -> selectBatch(1);
			case SOCIAL_INCREASE_10 -> selectBatch(10);
			case SOCIAL_INCREASE_100 -> selectBatch(100);
			case SOCIAL_ALL -> {
				if (redeemMode) {
					clearDematerializationTray();
					return;
				}
				if (selected > 0) selected = 0;
				else selectBatch(socialBalance());
			}
			default -> { }
		}
	}

	private int socialBalance() {
		if (redeemMode) return SocialTokenService.count(player);
		SocialCurrencyService.RedemptionTarget source = alignmentSource();
		return source == SocialCurrencyService.RedemptionTarget.REPUTATION
			? SocialCurrencyService.getReputation(player)
			: KarmaService.getInfamyPoints(player);
	}

	private int targetSocialBalance() {
		return socialBalance();
	}

	private SocialCurrencyService.RedemptionTarget alignmentSource() {
		return SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.DARK
			? SocialCurrencyService.RedemptionTarget.INFAMY
			: SocialCurrencyService.RedemptionTarget.REPUTATION;
	}


	private void selectBatch(int delta) {
		if (redeemMode || delta <= 0) return;
		if (SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.NEUTRAL) {
			player.sendSystemMessage(Component.literal("Neutral players cannot materialize Reputation or Infamy."));
			return;
		}
		SocialCurrencyService.RedemptionTarget source = alignmentSource();
		int available = socialBalance();
		int newSelected = WorldContributionAmounts.adjust(selected, delta, Math.min(available, MAX_MATERIALIZATION_AMOUNT));
		if (newSelected == selected) {
			player.sendSystemMessage(Component.literal("You have no " + source.displayName() + " left to materialize."));
			return;
		}
		selected = newSelected;
	}

	private void materializeSelected() {
		if (redeemMode || selected <= 0) return;
		if (SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.NEUTRAL) {
			player.sendSystemMessage(Component.literal("Neutral players cannot materialize Reputation or Infamy."));
			return;
		}
		SocialCurrencyService.RedemptionTarget source = alignmentSource();
		int available = socialBalance();
		int amount = Math.min(selected, available);
		if (amount <= 0) {
			player.sendSystemMessage(Component.literal("You have no " + source.displayName() + " left to materialize."));
			selected = 0;
			return;
		}
        if (!confirmSocial("materialize " + amount + " " + source.displayName())) return;
		ItemStack created = source == SocialCurrencyService.RedemptionTarget.REPUTATION
			? SocialCurrencyService.materializeReputationToStack(player, amount)
			: SocialCurrencyService.materializeInfamyToStack(player, amount);
		if (created.isEmpty()) return;
		SocialTokenService.insert(player, created.getCount(),
			source == SocialCurrencyService.RedemptionTarget.REPUTATION
				? SocialTokenService.Origin.LIGHT : SocialTokenService.Origin.DARK);
		if (socialBalance() <= 0) selected = 0;
	}

	private void performDematerialization() {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED)) {
			player.sendSystemMessage(Component.literal("Offerings are disabled for this world."));
			return;
		}
		if (!SocialCurrencyService.canDematerialize(player)) {
			player.sendSystemMessage(Component.literal("Dematerialization cooldown: "
				+ KicUiText.durationSeconds(SocialCurrencyService.dematerializationCooldownRemainingSeconds(player))));
			return;
		}
		int gross = dematerializationValue();
		if (gross < SocialCurrencyService.OFFERING_MINIMUM_GROSS) {
			player.sendSystemMessage(Component.literal(
				"Offerings need at least " + SocialCurrencyService.OFFERING_MINIMUM_GROSS
					+ " social value (current: " + gross + ")."));
			return;
		}
		int net = SocialCurrencyService.offeringNet(dematerializationExactValue());
		if (net <= 0) return;
        if (!confirmSocial("dematerialize for " + net + " " + KicUiText.target(player))) return;

		for (int i = SOCIAL_DECREASE_100; i <= SOCIAL_INCREASE_100; i++) {
			ItemStack stack = display.removeItemNoUpdate(i);
			if (stack.isEmpty()) continue;
			if (dematerializationItemValue(stack) <= 0) {
				if (!player.addItem(stack)) player.drop(stack, false);
			} else if (SocialTokenService.isToken(stack)) {
				KicHistoryService.recordInfluence(player, -stack.getCount(), "Influence dematerialization");
                KicMessages.change(player, "Physical Influence", -stack.getCount(), "Shrine offering");
			}
		}

		SocialCurrencyService.Stance stance = KarmaService.rewardAlignment(player);
		String target = switch (stance) {
			case LIGHT -> {
				SocialCurrencyService.addReputation(player, net, "Shrine offering");
				yield "Reputation";
			}
			case DARK -> {
				KarmaService.addInfamyPoints(player, net, "Shrine offering");
				yield "Infamy";
			}
			case NEUTRAL -> {
				SocialTokenService.insert(player, net, SocialTokenService.Origin.NEUTRAL);
				yield "Neutral Influence";
			}
		};
		if (stance == SocialCurrencyService.Stance.NEUTRAL) KicMessages.change(player, "Physical Influence", net, "Shrine offering");
		SocialCurrencyService.startDematerializationCooldown(player);
        KicUiText.success(player);
		selected = 0;
	}

	private double dematerializationItemValue(ItemStack stack) {
		return SocialTokenService.isToken(stack) ? 1 : KarmaOfferingConfig.socialPerStackItem(stack, player);
	}

	private int dematerializationValue() { return (int) dematerializationExactValue(); }

    private double dematerializationExactValue() {
		double total = 0.0;
		for (int i = SOCIAL_DECREASE_100; i <= SOCIAL_INCREASE_100; i++) {
			ItemStack stack = display.getItem(i);
			if (stack.isEmpty() || dematerializationItemValue(stack) <= 0) continue;
			total += dematerializationItemValue(stack) * stack.getCount();
		}
		return Math.min(Integer.MAX_VALUE, total);
	}

	private boolean hasDematerializationItems() {
        if (!redeemMode) return false;
		for (int i = SOCIAL_DECREASE_100; i <= SOCIAL_INCREASE_100; i++) {
			ItemStack stack = display.getItem(i);
			if (stack.isEmpty()) continue;
			if (dematerializationItemValue(stack) > 0) return true;
		}
		return false;
	}

	private void clearDematerializationTray() {
		for (int i = SOCIAL_DECREASE_100; i <= SOCIAL_INCREASE_100; i++) {
			returnDisplayItem(i, player);
		}
	}

	private void refresh() {
		for (int slot = 0; slot < SIZE; slot++) {
			if (tab == Tab.SOCIAL && redeemMode && isDematerializationSlot(slot)) continue;
			display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
		}

		String alignmentName = site != null && site.type() == ShrineType.LIGHT ? "Blessings"
			: site != null && site.type() == ShrineType.MONUMENT && monumentBlessingMode ? "Blessings" : "Curses";
		if (KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) {
			setTabIcon(TAB_ALIGNMENT, Items.END_CRYSTAL, alignmentName, tab == Tab.ALIGNMENT);
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.TITLES_ENABLED)) {
			setTabIcon(TAB_TITLES, Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, "Titles", tab == Tab.TITLES);
		}
		if (KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
			setTabIcon(TAB_SOCIAL, Items.GHAST_TEAR, "Social Currency", tab == Tab.SOCIAL);
		}
		setTabIcon(TAB_KARMA, Items.NETHER_STAR, "World Progression", tab == Tab.KARMA);
		display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to the shrine menu."));

		if (site != null && site.type() == ShrineType.MONUMENT && tab == Tab.ALIGNMENT) {
			refreshMonumentModeControls();
		}

		switch (tab) {
			case ALIGNMENT -> refreshAlignment();
			case TITLES -> refreshTitles();
			case SOCIAL -> refreshSocial();
			case KARMA -> refreshKarma();
		}
	}

	private void refreshMonumentModeControls() {
		if (monumentBlessingMode) {
			display.setItem(MONUMENT_INDICATOR, ShrineMenu.icon(Items.WIND_CHARGE, "Blessings",
				"Current Monument mode.", "Light-aligned blessings are displayed below."));
			display.setItem(MONUMENT_TOGGLE, ShrineMenu.icon(Items.GOLD_INGOT, "Light",
				"Switch to Dark / Curses."));
		} else {
			display.setItem(MONUMENT_INDICATOR, ShrineMenu.icon(Items.BLAZE_POWDER, "Curses",
				"Current Monument mode.", "Dark-aligned curses are displayed below."));
			display.setItem(MONUMENT_TOGGLE, ShrineMenu.icon(Items.REDSTONE, "Dark",
				"Switch to Light / Blessings."));
		}
	}

	private void setTabIcon(int slot, net.minecraft.world.item.Item item, String name, boolean selectedTab) {
		display.setItem(slot, ShrineMenu.icon(item, selectedTab ? name + "  [Selected]" : name,
			selectedTab ? "Current Karma tab." : "Switch to " + name + "."));
	}

	private void refreshAlignment() {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Sigils Disabled", "Sigils are disabled for this world."));
			return;
		}
		refreshActiveSigils();
		if (site == null) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Shrine Unavailable", "A shrine context is required."));
			return;
		}

		if (showsBlessings()) {
			refreshBlessings();
			return;
		}
		if (showsCurses()) {
			refreshCurses();
			return;
		}

		display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Neutral Shrine",
			"Sigils are unavailable from Neutral Shrines."));
		for (int slot = 37; slot <= 43; slot++) {
			display.setItem(slot, ShrineMenu.icon(Items.BARRIER, "Sigils Unavailable",
				"Blessings and Curses require a Light, Dark, or Monument context."));
		}
	}

	private void refreshActiveSigils() {
		java.util.List<String> activeCurses = CurseBlessingService.activeCurses(player);
		java.util.List<String> activeBlessings = CurseBlessingService.activeBlessings(player);
		for (int i = 0; i < ACTIVE_CURSE_SLOTS.length; i++) {
			if (i < activeCurses.size()) {
				String id = activeCurses.get(i);
				CurseBlessingRegistry.CurseDefinition curse = CurseBlessingRegistry.curse(id);
				int stacks = CurseBlessingService.curseStack(player, id);
				display.setItem(ACTIVE_CURSE_SLOTS[i], ShrineMenu.icon(curse.displayItem(),
					curse.name(), curse.classification().displayName() + " Curse Sigil",
					(stacks>0?"Active Sigil":"Inactive: alignment/commitment slot limit"), "Equipped stacks: " + CurseBlessingService.storedStack(player, false, id) + "/" + curse.maxStacks()));
			} else {
				display.setItem(ACTIVE_CURSE_SLOTS[i], ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), "Empty Curse Sigil", "No active Curse Sigil."));
			}
		}
        for (int i = 0; i < ACTIVE_BLESSING_SLOTS.length; i++) {
			if (i < activeBlessings.size()) {
				String id = activeBlessings.get(i);
				CurseBlessingRegistry.BlessingDefinition blessing = CurseBlessingRegistry.blessing(id);
				int stacks = CurseBlessingService.blessingStack(player, id);
				int minutes = CurseBlessingService.blessingMinutesRemaining(player, id);
				display.setItem(ACTIVE_BLESSING_SLOTS[i], ShrineMenu.icon(blessing.displayItem(),
					blessing.name(), blessing.classification().displayName() + " Blessing Sigil",
					(stacks>0?"Active Sigil":"Inactive: alignment/commitment slot limit"), "Equipped stacks: " + CurseBlessingService.storedStack(player, true, id) + "/" + blessing.maxStacks(), KicUiText.durationSeconds(minutes * 60L) + " remaining"));
			} else {
				display.setItem(ACTIVE_BLESSING_SLOTS[i], ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), "Empty Blessing Sigil", "No active Blessing Sigil."));
			}
		}
	}

	private java.util.List<CurseBlessingRegistry.BlessingDefinition> currentBlessings() {
		return CurseBlessingRegistry.liveBlessings(CommunityProgressionService.stage(player));
	}

	private java.util.List<CurseBlessingRegistry.CurseDefinition> currentCurses() {
		return CurseBlessingRegistry.liveCurses(CommunityProgressionService.stage(player));
	}

	private void refreshBlessings() {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.BLESSINGS_ENABLED)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Blessings Disabled", "Blessings are disabled for this world."));
			return;
		}
		if (!CurseBlessingService.canUseLightShrine(player)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Blessings Unavailable",
				"The Devil prevents access to Light Shrine blessings.",
				"This commitment prevents access."));
			return;
		}

		display.setItem(13, ShrineMenu.icon(Items.EMERALD_BLOCK, "Blessings",
			"Reputation: " + SocialCurrencyService.getReputation(player),
			CurseBlessingService.slotSummary(player), CurseBlessingService.sigilDetails(player),
			CurseBlessingService.isDevoted(player) ? "Devotion allows 3 Blessing types; no Curses." : "Each stack also uses a shared stack slot."));

		int start = sigilPage * AVAILABLE_BLESSING_SLOTS.length;
		int count = Math.min(AVAILABLE_BLESSING_SLOTS.length, currentBlessings().size() - start);
		for (int i = 0; i < count; i++) {
			CurseBlessingRegistry.BlessingDefinition blessing = currentBlessings().get(start + i);
			int slot = AVAILABLE_BLESSING_SLOTS[i];
			int stacks = CurseBlessingService.blessingStack(player, blessing.id());
			boolean active = stacks > 0;
			int minutes = active ? CurseBlessingService.blessingMinutesRemaining(player, blessing.id()) : 0;
			String status = active ? "ACTIVE x" + stacks + " ("
				+ (blessing.duration() == CurseBlessingRegistry.SigilDuration.PERMANENT ? "Permanent" : minutes + "m remaining") + ")"
				: "Click to inspect this Sigil.";
			display.setItem(slot, ShrineMenu.icon(blessing.displayItem(), blessing.name(), blessing.classification().displayName() + " Blessing Sigil",
				status,
				blessing.effectDescription(),
				"Max stacks: " + blessing.maxStacks(),
				active ? "This Blessing Sigil is currently active." : "Click to inspect and activate."));
		}
		refreshSigilPageControls();
	}

	private void refreshCurses() {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.CURSES_ENABLED)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Curses Disabled", "Curses are disabled for this world."));
			return;
		}
		if (!CurseBlessingService.canUseDarkShrine(player)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Curses Unavailable",
				"Devotion prevents access to Dark Shrine curses."));
			return;
		}

		display.setItem(13, ShrineMenu.icon(Items.BLAZE_POWDER, "Sigil Capacity",
			CurseBlessingService.slotSummary(player), CurseBlessingService.sigilDetails(player),
			"Each distinct Sigil uses a type slot; every stack uses a stack slot."));

		int start = sigilPage * AVAILABLE_CURSE_SLOTS.length;
		int count = Math.min(AVAILABLE_CURSE_SLOTS.length, currentCurses().size() - start);
		for (int i = 0; i < count; i++) {
			CurseBlessingRegistry.CurseDefinition curse = currentCurses().get(start + i);
			int slot = AVAILABLE_CURSE_SLOTS[i];
			int stacks = CurseBlessingService.curseStack(player, curse.id());
			String status = stacks > 0 ? "ACTIVE" + (curse.maxStacks() > 1 ? " x" + stacks : "") : "Available in pool";
			display.setItem(slot, ShrineMenu.icon(curse.displayItem(), curse.name(), curse.classification().displayName() + " Curse Sigil",
				status,
				curse.effectDescription(),
				"Max stacks: " + curse.maxStacks(),
				stacks > 0 ? "This Curse Sigil is currently active." : "Click to inspect and activate."));
		}
		refreshSigilPageControls();
	}

	private int sigilPageCount() {
		int count = showsBlessings() ? currentBlessings().size() : currentCurses().size();
		return Math.max(1, (count + (showsBlessings() ? AVAILABLE_BLESSING_SLOTS.length : AVAILABLE_CURSE_SLOTS.length) - 1) / (showsBlessings() ? AVAILABLE_BLESSING_SLOTS.length : AVAILABLE_CURSE_SLOTS.length));
	}

	private void refreshSigilPageControls() {
		if (sigilPageCount() <= 1) return;
		display.setItem(SIGIL_PREVIOUS_PAGE, ShrineMenu.icon(Items.ARROW, "Previous Sigils",
			sigilPage > 0 ? "Show the previous Sigils." : "Already showing the first Sigils."));
		display.setItem(SIGIL_NEXT_PAGE, ShrineMenu.icon(Items.ARROW, "Next Sigils",
			sigilPage < sigilPageCount() - 1 ? "Show more Sigils." : "Already showing the last Sigils."));
	}


	private void refreshKarma() {
        display.setItem(19, ShrineMenu.icon(Items.COMPASS, "Alignment", KicUiText.alignmentStatus(player),
            "Once established, alignment persists below 100 magnitude until zero.", "Neutral reward gains use your saved alignment preference."));
        display.setItem(25, ShrineMenu.icon(Items.BOOK, "Reward breakdowns", KarmaService.rewardBreakdown(player).toArray(String[]::new)));
		if (!KarmaGameRules.enabled(player, KarmaGameRules.KARMA_MENU_ENABLED)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Karma Disabled", "The Karma menu is disabled for this world."));
			return;
		}
		CommunityProgressionService community = CommunityProgressionService.get(player.level().getServer());
		int stage = community.stage();
		display.setItem(13, ShrineMenu.icon(Items.BEACON, "World Progression",
			"Stage " + stage + "/" + CommunityProgressionService.MAX_STAGE,
			"Community Influence: " + CommunityProgressionService.formatInfluence(community.pool()),
			stage < CommunityProgressionService.MAX_STAGE
				? "Next Stage: " + CommunityProgressionService.formatInfluence(
					CommunityProgressionService.get(player.level().getServer()).nextStageThreshold(stage))
				: "Stage 5 is the cap.",
			"Open the community World Progression screen below."));
		display.setItem(WORLD_PROGRESSION, ShrineMenu.icon(Items.EMERALD_BLOCK, "World Progression",
			"View community progress and submit Influence.",
			"Reputation and Infamy share one community pool."));
	}

	private void refreshTitles() {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.TITLES_ENABLED)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Titles Disabled", "Titles are disabled for this world."));
			return;
		}
		var recognition = QuestService.recognition(player);
        display.setItem(13, ShrineMenu.icon(Items.NAME_TAG, "Earned recognition",
            recognition.stream().filter(line -> line.startsWith("First completion:")).toArray(String[]::new)));
        display.setItem(22, ShrineMenu.icon(Items.BEACON, "Contribution milestones", CommunityProgressionService.personalContributionSummary(player)));
	}

	private void refreshSocial() {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SOCIAL_CURRENCY_ENABLED)) {
			display.setItem(13, ShrineMenu.icon(Items.BARRIER, "Social Currency Disabled", "Social Currency is disabled for this world."));
			return;
		}
		if (redeemMode) {
			refreshDematerialize();
		} else {
			refreshMaterialize();
		}
	}

	private void refreshMaterialize() {
		SocialCurrencyService.RedemptionTarget source = alignmentSource();
		SocialCurrencyService.Stance stance = SocialCurrencyService.getStance(player);
		int available = socialBalance();
		if (stance == SocialCurrencyService.Stance.NEUTRAL) selected = 0;
		selected = Math.min(selected, Math.min(available, MAX_MATERIALIZATION_AMOUNT));
		if (selected > 0) {
			int amount = selected;
			String origin = source == SocialCurrencyService.RedemptionTarget.REPUTATION ? "Light" : "Dark";
			display.setItem(SOCIAL_COUNTER, ShrineMenu.icon(Items.GHAST_TEAR,
				(pendingSocialConfirmation == null ? "Materialize " : "Confirm materialization: ") + amount,
				"Spend: " + amount + " " + source.displayName(),
                "Receive: " + ((long)amount * SocialCurrencyService.materializationPercent() / 100) + " Physical Influence",
                "Remaining: " + (available - amount) + " " + source.displayName(),
                pendingSocialConfirmation == null ? "Click to review." : "Click again to confirm.",
				"Balance: " + available + " " + source.displayName()));
			display.getItem(SOCIAL_COUNTER).set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		} else {
			display.setItem(SOCIAL_COUNTER, ItemStack.EMPTY);
		}

		display.setItem(SOCIAL_DECREASE_100, ShrineMenu.icon(Items.GOLD_BLOCK, "-100", "Decrease the selected amount."));
		display.setItem(SOCIAL_DECREASE_10, ShrineMenu.icon(Items.GOLD_INGOT, "-10", "Decrease the selected amount."));
		display.setItem(SOCIAL_DECREASE_1, ShrineMenu.icon(Items.GOLD_NUGGET, "-1", "Decrease the selected amount."));
		display.setItem(SOCIAL_INCREASE_1, ShrineMenu.icon(Items.GOLD_NUGGET, "+1", "Increase selection by 1."));
		display.setItem(SOCIAL_INCREASE_10, ShrineMenu.icon(Items.GOLD_INGOT, "+10", "Increase selection by 10."));
		display.setItem(SOCIAL_INCREASE_100, ShrineMenu.icon(Items.GOLD_BLOCK, "+100", "Increase selection by 100."));

		display.setItem(SOCIAL_SOURCE, ShrineMenu.icon(
			source == SocialCurrencyService.RedemptionTarget.REPUTATION ? Items.EMERALD : Items.WITHER_SKELETON_SKULL,
			source.displayName(),
			"Selected automatically from your Karma alignment.",
			"Balance: " + targetSocialBalance(), "Maximum batch: " + MAX_MATERIALIZATION_AMOUNT));

		display.setItem(SOCIAL_MODE, ShrineMenu.icon(Items.SHROOMLIGHT, "Materialize",
			"Reputation / Infamy → Physical Influence.",
			"Click the centre preview, then click again to confirm."));
		display.setItem(SOCIAL_ALL, ShrineMenu.icon(Items.BARRIER,
			selected > 0 ? "Clear All" : "Convert All",
			selected > 0 ? "Clear the selected amount" : "Stage all available " + source.displayName() + ".",
			"Offering payout: " + KicUiText.target(player) + " (uses saved preference while Neutral)."));
		display.setItem(SOCIAL_ACTION, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
	}

	private void refreshDematerialize() {
		SocialCurrencyService.RedemptionTarget source = alignmentSource();
		for (int i = SOCIAL_DECREASE_100; i <= SOCIAL_INCREASE_100; i++) {
			ItemStack stack = display.getItem(i);
			if (stack.isEmpty()) continue;
			if (dematerializationItemValue(stack) <= 0) {
				returnDisplayItem(i, player);
			}
		}

		int gross = dematerializationValue();
		int net = SocialCurrencyService.offeringNet(dematerializationExactValue());

		display.setItem(22, ShrineMenu.icon(Items.BOOK, "Offering Value",
			KicUiText.conversion(player, dematerializationExactValue()),
			gross >= SocialCurrencyService.OFFERING_MINIMUM_GROSS ? "Ready to offer."
				: "Minimum: " + SocialCurrencyService.OFFERING_MINIMUM_GROSS + " social value."));

		display.setItem(SOCIAL_SOURCE, ShrineMenu.icon(
			source == SocialCurrencyService.RedemptionTarget.REPUTATION ? Items.EMERALD : Items.WITHER_SKELETON_SKULL,
			"Alignment Target",
			KicUiText.alignmentStatus(player),
            "Payout: " + KicUiText.target(player),
            "Established alignment remains active in its grace range."));
		display.setItem(SOCIAL_MODE, ShrineMenu.icon(Items.SEA_LANTERN,
			KarmaGameRules.enabled(player, KarmaGameRules.OFFERINGS_ENABLED) ? "Dematerialize" : "Offerings Disabled",
			"Place offerings or Influence of any origin in row 4.",
			"Each Influence is worth 1 before tax.",
			"Minimum value: " + SocialCurrencyService.OFFERING_MINIMUM_GROSS + ".",
			ShrineConfig.current().socialDematerializationCooldownSeconds == 0 ? "No cooldown configured."
				: "Cooldown: " + KicUiText.durationSeconds(ShrineConfig.current().socialDematerializationCooldownSeconds)));
		display.setItem(SOCIAL_ACTION, ShrineMenu.icon(Items.NETHER_STAR,
			!SocialCurrencyService.canDematerialize(player) ? "Dematerialization on cooldown" : gross >= SocialCurrencyService.OFFERING_MINIMUM_GROSS ? (pendingSocialConfirmation == null ? "Offer Items" : "Confirm offering") : "Need More Value",
			"Gross value: " + gross,
			"Tax: " + SocialCurrencyService.OFFERING_TAX_PERCENT + "% (rounded down payout)",
			KicUiText.conversion(player, dematerializationExactValue()),
            SocialCurrencyService.canDematerialize(player) ? "Cooldown ready." : "Ready in " + KicUiText.durationSeconds(SocialCurrencyService.dematerializationCooldownRemainingSeconds(player))));
		display.setItem(SOCIAL_ALL, ShrineMenu.icon(Items.BARRIER, "Clear Offerings",
			"Return all inserted items to your inventory."));
	}

	private boolean isDematerializationSlot(int slotId) {
		return slotId >= SOCIAL_DECREASE_100 && slotId <= SOCIAL_INCREASE_100;
	}




	private boolean hasMaterializerPayout() {
		return false; // Materialization slot is a preview, never an unclaimed item.
	}

	private boolean canLeaveSocial() {
        resetSocialConfirmation();
		if (tab != Tab.SOCIAL) return true;
		if (hasDematerializationItems()) {
			player.sendSystemMessage(Component.literal("Clear the offered items before leaving Social Currency."));
			return false;
		}
		if (hasMaterializerPayout()) {
			player.sendSystemMessage(Component.literal("Take the current payout before leaving Social Currency."));
			return false;
		}
		return true;
	}

	private void openKarmaTab() {
		player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
			KarmaStatusMenu menu = new KarmaStatusMenu(id, inventory, player, site, onBack,
				monumentBlessingMode, sigilPage, new SimpleContainer(SIZE));
			menu.tab = Tab.KARMA;
			menu.refresh();
			return menu;
		}, Component.literal("Karma")));
	}

	private void openStatus() {
		openSigils(player, site, onBack, monumentBlessingMode, sigilPage);
	}


	static net.minecraft.world.item.Item iconFor(String id) {
		return switch (id) {
			case "watcher" -> Items.ENDER_EYE;
			case "smite" -> Items.NETHER_STAR;
			case "devotion" -> Items.NETHERITE_INGOT;
			case "swiftness" -> Items.SUGAR;
			case "giants" -> Items.POTION;
			case "fortune" -> Items.GOLDEN_PICKAXE;
			case "miner" -> Items.DIAMOND_PICKAXE;
			case "deep" -> Items.HEART_OF_THE_SEA;
			case "mountain" -> Items.STONE;
			case "night" -> Items.PHANTOM_MEMBRANE;
			case "smith" -> Items.ANVIL;
			case "vanguard" -> Items.SHIELD;
			case "protection" -> Items.IRON_CHESTPLATE;
			case "abundance" -> Items.CHEST;
			default -> Items.EMERALD;
		};
	}
}
