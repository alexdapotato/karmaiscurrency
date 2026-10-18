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

/** Player-facing inspection and confirmation step for a Blessing or Curse Sigil. */
public final class SigilDetailsMenu extends KarmaMenu {
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int DURATION = 10;
	private static final int REQUIREMENTS = 12;
	private static final int STACKING = 14;
	private static final int EFFECTS = 16;
	private static final int ACTIVATE = 31;
	private static final int BACK = 53;
	private static final int CONFIRM_COMMITMENT = 33;

	private final String id;
	private final String name;
	private final int maxStacks;
	private final boolean blessing;
	private final CurseBlessingRegistry.SigilClassification classification;
	private final net.minecraft.world.item.Item displayItem;
	private final String effectDescription;
	private final CurseBlessingRegistry.SigilDuration duration;
	private final java.util.List<String> requirements;
	private final Runnable onBack;
	private boolean confirmingCommitment;
    private boolean confirmingRemoval;

	private SigilDetailsMenu(int containerId, Inventory inventory, ServerPlayer player, String id, String name,
			int maxStacks, boolean blessing, CurseBlessingRegistry.SigilClassification classification,
			net.minecraft.world.item.Item displayItem, String effectDescription,
			CurseBlessingRegistry.SigilDuration duration, java.util.List<String> requirements,
			Runnable onBack, SimpleContainer display) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, ROWS, player);
		this.id = id;
		this.name = name;
		this.maxStacks = maxStacks;
		this.blessing = blessing;
		this.classification = classification;
		this.displayItem = displayItem;
		this.effectDescription = effectDescription;
		this.duration = duration;
		this.requirements = requirements;
		this.onBack = onBack;
		refresh();
	}

	public static void openBlessing(ServerPlayer player, ShrineSite site,
			CurseBlessingRegistry.BlessingDefinition definition, Runnable onBack) {
		open(player, site, definition.id(), definition.name(), definition.maxStacks(), true, definition.classification(),
			definition.displayItem(), definition.effectDescription(), definition.duration(), definition.requirements(), onBack);
	}

	public static void openCurse(ServerPlayer player, ShrineSite site,
			CurseBlessingRegistry.CurseDefinition definition, Runnable onBack) {
		open(player, site, definition.id(), definition.name(), definition.maxStacks(), false, definition.classification(),
			definition.displayItem(), definition.effectDescription(), definition.duration(), definition.requirements(), onBack);
	}

	private static void open(ServerPlayer player, ShrineSite site, String id, String name, int maxStacks,
			boolean blessing, CurseBlessingRegistry.SigilClassification classification,
			net.minecraft.world.item.Item displayItem, String effectDescription,
			CurseBlessingRegistry.SigilDuration duration, java.util.List<String> requirements, Runnable onBack) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SIGILS_ENABLED)) {
			player.sendSystemMessage(Component.literal("Sigils are disabled for this world."));
			return;
		}
		player.openMenu(new SimpleMenuProvider(
			(containerId, inventory, ignored) -> new SigilDetailsMenu(containerId, inventory, player, id, name,
				maxStacks, blessing, classification, displayItem, effectDescription, duration, requirements, onBack, new SimpleContainer(SIZE)),
			Component.literal((blessing ? "Blessing" : "Curse") + " Sigil: " + name)));
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				if (slotId == BACK) {
					if (confirmingCommitment || confirmingRemoval) { confirmingCommitment = false; confirmingRemoval = false; refresh(); }
					else onBack.run();
				}
				else if (slotId == 40 && !confirmingCommitment && CurseBlessingService.canReleaseSigil(player, blessing, id)) {
                    if (confirmingRemoval) {
                        if (CurseBlessingService.releaseSigil(player, blessing, id)) {
                            player.sendSystemMessage(Component.literal(name + " removed. All stacks released; no refund."));
                            onBack.run();
                        }
                    } else { confirmingRemoval = true; refresh(); }
                }
                else if (slotId == ACTIVATE && !confirmingCommitment && !confirmingRemoval) activate();
                else if (slotId == CONFIRM_COMMITMENT && confirmingCommitment) activate();
			}
			if (player.containerMenu == this) broadcastFullState();
			return;
		}
		super.clicked(slotId, button, input, clicker);
	}

	@Override
	public ItemStack quickMoveStack(Player clicker, int slotId) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slots.indexOf(slot) >= SIZE;
	}

	private void activate() {
		String blocker = activationBlocker();
		if (blocker != null) {
			player.sendSystemMessage(Component.literal(blocker));
			refresh();
			return;
		}
        if (!KicUiText.commitmentWarning(blessing, id).isEmpty() && !confirmingCommitment) {
            confirmingCommitment = true;
            refresh();
            return;
        }
		boolean activated = blessing
			? CurseBlessingService.addBlessing(player, id, 5)
			: CurseBlessingService.addCurse(player, id);
		if (activated) {
			KicUiText.success(player);
			player.sendSystemMessage(Component.literal(name + " Sigil activated."));
			onBack.run();
			return;
		}
		refresh();
	}

    private String activationBlocker() {
        return CurseBlessingService.activationBlocker(player, blessing, id, maxStacks);
    }

	private int stackCount() {
		return blessing ? CurseBlessingService.blessingStack(player, id) : CurseBlessingService.curseStack(player, id);
	}

	private void refresh() {
		for (int slot = 0; slot < SIZE; slot++) {
			display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
		}
		int stacks = stackCount();
		String blocker = activationBlocker();
		display.setItem(4, ShrineMenu.icon(displayItem, name,
			classification.displayName() + (blessing ? " Blessing Sigil" : " Curse Sigil"), "Inspect before activating."));
		display.setItem(DURATION, ShrineMenu.icon(duration == CurseBlessingRegistry.SigilDuration.TEMPORARY ? Items.CLOCK : Items.RECOVERY_COMPASS, "Duration",
		duration == CurseBlessingRegistry.SigilDuration.TEMPORARY ? "Temporary: " + KicUiText.durationSeconds(CurseBlessingRegistry.blessing(id).durationMinutes() * 60L) + " per activation." : "Permanent."));
		java.util.List<String> requirementLore = new java.util.ArrayList<>(requirements.stream().filter(line -> !line.startsWith("Cost:")).toList());
        requirementLore.addAll(KicUiText.costLines(player, blessing, id));
        requirementLore.add(blocker == null ? "Available now." : blocker);
		display.setItem(REQUIREMENTS, ShrineMenu.icon(Items.PAPER, "Requirements",
			requirementLore.toArray(String[]::new)));
        ItemStack requirementsIcon = display.getItem(REQUIREMENTS);
        requirementsIcon.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(KicUiText.lore(requirementLore.toArray(String[]::new))));
        display.setItem(STACKING, ShrineMenu.icon(Items.EXPERIENCE_BOTTLE, "Stacking",
            maxStacks == 1 ? "Not stackable." : "Current: " + stacks + " / " + maxStacks,
            maxStacks == 1 ? "Current: " + stacks + " / 1" : "Each activation adds one stack.",
            CurseBlessingService.slotSummary(player), CurseBlessingService.sigilDetails(player), CurseBlessingService.activationPreview(player, blessing, id)));
		display.setItem(EFFECTS, ShrineMenu.icon(Items.BOOK, "Effects",
			KicUiText.projectedEffect(player, blessing, id, effectDescription),
			KicUiText.commitmentWarning(blessing, id).isEmpty() ? "Review the effect before spending items." : KicUiText.commitmentWarning(blessing, id)));
		display.setItem(confirmingCommitment ? CONFIRM_COMMITMENT : ACTIVATE, ShrineMenu.icon(blocker == null ? Items.EMERALD_BLOCK : Items.BARRIER,
			blocker == null ? (confirmingCommitment ? "Confirm commitment" : "Activate sigil") : blocker,
			blocker == null ? (confirmingCommitment ? KicUiText.commitmentWarning(blessing, id) : "Activate one stack.") : "Resolve the requirement shown above."));
        if (CurseBlessingService.canReleaseSigil(player, blessing, id)) display.setItem(40,
            ShrineMenu.icon(Items.BARRIER, confirmingRemoval ? "Confirm removal" : "Remove this Sigil",
                "Releases all " + stacks + " stacks and the type slot. No refund.", "Commitments use their own removal rules."));
        display.setItem(BACK, ShrineMenu.icon(Items.ARROW, confirmingCommitment || confirmingRemoval ? "Cancel" : "Back", confirmingCommitment ? "Return to Sigil details without payment." : "Return to available Sigils."));
	}
}
