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

/** Dedicated Light Shrine blessing screen for the persistent Blessing framework. */
public final class LightShrineMenu extends KarmaMenu {
    private static final int SIZE = 27;
    private static final int HEADER = 4;
    private static final int FIRST_BLESSING = 9;
    private static final int BACK = 26;

    private final ShrineSite site;
    private final Runnable onBack;

    private LightShrineMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
            Runnable onBack, SimpleContainer display) {
        super(MenuType.GENERIC_9x3, id, inventory, display, 3, player);
        this.site = site;
        this.onBack = onBack;
        refresh();
    }

    public static void open(ServerPlayer player, ShrineSite site, Runnable onBack) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> new LightShrineMenu(id, inventory, player, site, onBack, new SimpleContainer(SIZE)),
            Component.literal(site.displayName() + " - Blessings")));
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
        if (clicker != player) return;
        if (slotId >= 0 && slotId < SIZE) {
            if (input == ContainerInput.PICKUP && button == 0) {
                if (slotId == BACK) {
                    onBack.run();
                } else if (slotId >= FIRST_BLESSING && slotId < FIRST_BLESSING + currentBlessings().size()) {
                    CurseBlessingRegistry.BlessingDefinition blessing = currentBlessings().get(slotId - FIRST_BLESSING);
                    CurseBlessingRequirementsMenu.openBlessing(player, site, blessing,
                        () -> LightShrineMenu.open(player, site, onBack));
                }
                refresh();
            }
            broadcastFullState();
            return;
        }
        super.clicked(slotId, button, input, clicker);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }

    @Override
    public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }

    private void refresh() {
        for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));

        if (!CurseBlessingService.canUseLightShrine(player)) {
            display.setItem(HEADER, ShrineMenu.icon(Items.BARRIER, "Blessings Unavailable",
                "The Devil prevents access to Light Shrines.",
                "Purification is the future neutral escape route."));
            display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to the shrine menu."));
            return;
        }

        display.setItem(HEADER, ShrineMenu.icon(Items.EMERALD_BLOCK, "Blessings",
            "Reputation: " + SocialCurrencyService.getReputation(player),
            CurseBlessingService.slotSummary(player), CurseBlessingService.sigilDetails(player),
            CurseBlessingService.isDevoted(player) ? "Devotion grants one Blessing slot." : "One type slot per distinct Sigil; one stack slot per stack."));

        int slot = FIRST_BLESSING;
        for (CurseBlessingRegistry.BlessingDefinition blessing : currentBlessings()) {
            if (slot >= BACK) break;
            int stacks = CurseBlessingService.blessingStack(player, blessing.id());
            boolean active = stacks > 0;
            int minutes = active ? CurseBlessingService.blessingMinutesRemaining(player, blessing.id()) : 0;
            String status = active ? "ACTIVE x" + stacks + " ("
                + (blessing.duration() == CurseBlessingRegistry.SigilDuration.PERMANENT ? "Permanent" : minutes + "m") + ")"
                : costLine(blessing);
            display.setItem(slot++, ShrineMenu.icon(iconFor(blessing.id()), blessing.name(),
                status,
                blessing.effectDescription(),
                "Max stacks: " + blessing.maxStacks()));
        }

        display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to the shrine menu."));
    }

    private java.util.List<CurseBlessingRegistry.BlessingDefinition> currentBlessings() {
        return CurseBlessingRegistry.liveBlessings(CommunityProgressionService.stage(player));
    }

    private static String costLine(CurseBlessingRegistry.BlessingDefinition blessing) {
        return "Cost: " + CurseBlessingRegistry.costDescription(blessing.cost());
    }

    private static net.minecraft.world.item.Item iconFor(String id) {
        return switch (id) {
            case "watcher" -> Items.ENDER_EYE;
            case "smite" -> Items.NETHER_STAR;
            case "devotion" -> Items.NETHERITE_INGOT;
            case "swiftness" -> Items.SUGAR;
            case "giants" -> Items.POPPED_CHORUS_FRUIT;
            case "fortune" -> Items.EMERALD;
            case "miner" -> Items.IRON_PICKAXE;
            case "deep" -> Items.HEART_OF_THE_SEA;
            case "mountain" -> Items.SHIELD;
            case "night" -> Items.CLOCK;
            case "smith" -> Items.ANVIL;
            case "vanguard" -> Items.IRON_SWORD;
            case "protection" -> Items.DIAMOND_CHESTPLATE;
            case "abundance" -> Items.CHEST;
            case "hunter" -> Items.BOW;
            case "haste" -> Items.GOLDEN_PICKAXE;
            case "harvest" -> Items.WHEAT;
            case "aegis" -> Items.SHIELD;
            default -> Items.PAPER;
        };
    }
}
