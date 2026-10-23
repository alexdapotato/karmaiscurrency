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

/** Conversion UI for Reputation/Infamy <-> Influence Tokens. Karma itself is not convertible. */
public final class KarmaConversionMenu extends KarmaMenu {
	private static final int SIZE = 27;
	private static final int TITLE = 4;
	private static final int BACK = 10;
	private static final int SOURCE = 12;
	private static final int ACTION = 14;
	private static final int DECREASE = 20, INCREASE = 24, ALL = 22;
	private static final int REDEEM = 16;
	private boolean redeemMode;
    private String pendingConfirmation;
	private SocialCurrencyService.RedemptionTarget source = SocialCurrencyService.RedemptionTarget.REPUTATION;
	private int selected = 1;
	private final ShrineSite shrine;
	private final Runnable onBack;

	private KarmaConversionMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite shrine, Runnable onBack, SimpleContainer display) {
		super(MenuType.GENERIC_9x3, id, inventory, display, 3, player);
		this.shrine = shrine; this.onBack = onBack; refresh();
	}

	public static void open(ServerPlayer player) { open(player, null, player::closeContainer); }
	public static void open(ServerPlayer player, ShrineSite shrine) { open(player, shrine, () -> ShrineMenu.open(player, shrine)); }
	public static void open(ServerPlayer player, ShrineSite shrine, Runnable onBack) {
		player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new KarmaConversionMenu(id, inventory, player, shrine, onBack, new SimpleContainer(SIZE)), Component.literal("Social Currency")));
	}

	@Override public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
		if (clicker != player) return;
		if (slotId >= 0 && slotId < SIZE) {
			if (input == ContainerInput.PICKUP && button == 0) {
				if (slotId != ACTION) pendingConfirmation = null;
                switch (slotId) {
					case BACK -> onBack.run();
					case REDEEM -> redeemMode = !redeemMode;
					case ACTION -> perform();
					case DECREASE -> selected = Math.max(1, selected - 1);
					case INCREASE -> selected = clamp(selected + 1);
					case ALL -> selected = maxConvertible();
                    case 18 -> selected = WorldContributionAmounts.adjust(selected, -100, balance());
                    case 19 -> selected = WorldContributionAmounts.adjust(selected, -10, balance());
                    case 25 -> selected = WorldContributionAmounts.adjust(selected, 10, balance());
                    case 26 -> selected = WorldContributionAmounts.adjust(selected, 100, balance());
					default -> {}
				}
				refresh();
			}
			broadcastFullState(); return;
		}
		super.clicked(slotId, button, input, clicker);
	}
	@Override public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }
	@Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }

	private int balance() {
		source = alignmentSource();
		return redeemMode ? SocialTokenService.count(player) : source == SocialCurrencyService.RedemptionTarget.REPUTATION ? SocialCurrencyService.getReputation(player) : KarmaService.getInfamyPoints(player);
	}
	private SocialCurrencyService.RedemptionTarget alignmentSource() {
		return SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.DARK
			? SocialCurrencyService.RedemptionTarget.INFAMY : SocialCurrencyService.RedemptionTarget.REPUTATION;
	}
	private int maxConvertible() { return balance(); }
	private int clamp(int value) { int max = maxConvertible(); return max <= 0 ? 0 : Math.clamp(value, 1, max); }

	private void perform() {
		int amount = clamp(selected);
		if (amount <= 0) return;
        String key = redeemMode + ":" + amount + ":" + source + ":" + SocialCurrencyService.getStance(player);
        if (!key.equals(pendingConfirmation)) {
            pendingConfirmation = key;
            player.sendSystemMessage(Component.literal("Review the amount and click the action again to confirm."));
            return;
        }
        pendingConfirmation = null;
		if (!redeemMode) {
			int created = source == SocialCurrencyService.RedemptionTarget.REPUTATION
				? SocialCurrencyService.materializeReputation(player, amount)
				: SocialCurrencyService.materializeInfamy(player, amount);
			if (created <= 0) player.sendSystemMessage(Component.literal("Not enough influence to materialize."));
		} else {
			int gained = SocialCurrencyService.redeem(player, amount, source);
			if (gained <= 0) player.sendSystemMessage(Component.literal("Those tokens cannot be redeemed into the selected currency with your current stance."));
		}
		selected = clamp(amount);
	}

	private void refresh() {
		source = alignmentSource();
		for (int i = 0; i < SIZE; i++) display.setItem(i, icon(Items.STAINED_GLASS_PANE.gray(), " "));
		display.setItem(TITLE, icon(Items.NETHER_STAR, "Social Currency",
			"Karma: " + KarmaService.format(KarmaService.get(player)),
			"Reputation: " + SocialCurrencyService.getReputation(player),
			"Infamy: " + KarmaService.getInfamyPoints(player),
			"Physical Influence: " + SocialTokenService.count(player),
			"Origin: " + SocialTokenService.usableOrigin(player).displayName()));
		display.setItem(BACK, icon(Items.ARROW, "Back"));
		display.setItem(SOURCE, icon(source == SocialCurrencyService.RedemptionTarget.REPUTATION ? Items.EMERALD : Items.WITHER_SKELETON_SKULL,
			source.displayName(), "Selected automatically from your Karma alignment."));
		display.setItem(REDEEM, icon(Items.CLOCK, redeemMode ? "Mode: Tokens -> Currency" : "Mode: Currency -> Tokens", "Click to switch mode."));
		selected = clamp(selected);
		display.setItem(ACTION, icon(redeemMode ? Items.ECHO_SHARD : source == SocialCurrencyService.RedemptionTarget.REPUTATION ? Items.EMERALD : Items.WITHER_SKELETON_SKULL,
			pendingConfirmation != null ? "Confirm conversion" : redeemMode ? "Redeem" : "Materialize", "Amount: " + selected,
			redeemMode ? "Output: " + (selected * (SocialCurrencyService.getStance(player) == SocialCurrencyService.Stance.NEUTRAL ? SocialCurrencyService.neutralRedemptionPercent() : SocialCurrencyService.alignedRedemptionPercent()) / 100) : "Creates about " + (selected * SocialCurrencyService.materializationPercent() / 100) + " " + (source == SocialCurrencyService.RedemptionTarget.REPUTATION ? "Light" : "Dark") + " Influence."));
		display.setItem(18, icon(Items.GOLD_BLOCK, "-100"));
        display.setItem(19, icon(Items.GOLD_INGOT, "-10"));
        display.setItem(25, icon(Items.GOLD_INGOT, "+10"));
        display.setItem(26, icon(Items.GOLD_BLOCK, "+100"));
		display.setItem(DECREASE, icon(Items.GOLD_NUGGET, "-1"));
		display.setItem(INCREASE, icon(Items.GOLD_INGOT, "+1"));
		display.setItem(ALL, icon(Items.DIAMOND, "All", "Select all available."));
	}

	private static ItemStack icon(net.minecraft.world.item.Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME, KicUiText.title(name));
		if (lore.length > 0) stack.set(DataComponents.LORE, new ItemLore(KicUiText.lore(lore)));
		return stack;
	}
}
