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

/** Dedicated Dark Shrine curse screen for the persistent Curse framework. */
public final class DarkCursesMenu extends KarmaMenu {
    private static final int SIZE = 27;
    private static final int HEADER = 4;
    private static final int FIRST_CURSE = 9;
    private static final int BACK = 26;

    private final ShrineSite site;
    private final Runnable onBack;

    private DarkCursesMenu(int id, Inventory inventory, ServerPlayer player, ShrineSite site,
            Runnable onBack, SimpleContainer display) {
        super(MenuType.GENERIC_9x3, id, inventory, display, 3, player);
        this.site = site;
        this.onBack = onBack;
        refresh();
    }

    public static void open(ServerPlayer player, ShrineSite site, Runnable onBack) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> new DarkCursesMenu(id, inventory, player, site, onBack, new SimpleContainer(SIZE)),
            Component.literal(site.displayName() + " - Curses")));
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
        if (clicker != player) return;
        if (slotId >= 0 && slotId < SIZE) {
            if (input == ContainerInput.PICKUP && button == 0) {
                if (slotId == BACK) {
                    onBack.run();
                } else if (slotId >= FIRST_CURSE && slotId < FIRST_CURSE + currentCurses().size()) {
                    CurseBlessingRegistry.CurseDefinition curse = currentCurses().get(slotId - FIRST_CURSE);
                    CurseBlessingRequirementsMenu.openCurse(player, site, curse,
                        () -> DarkCursesMenu.open(player, site, onBack));
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

        if (!CurseBlessingService.canUseDarkShrine(player)) {
            display.setItem(HEADER, ShrineMenu.icon(Items.BARRIER, "Curses Unavailable",
                "Devotion prevents access to Dark Shrines.",
                "This commitment prevents access."));
            display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to the shrine menu."));
            return;
        }

        display.setItem(HEADER, ShrineMenu.icon(Items.BLAZE_POWDER, "Dark Curses",
            "Infamy: " + KarmaService.getInfamyPoints(player),
            CurseBlessingService.slotSummary(player), CurseBlessingService.sigilDetails(player),
            "One Curse slot per distinct Curse; every stack uses a stack slot."));

        int slot = FIRST_CURSE;
        for (CurseBlessingRegistry.CurseDefinition curse : currentCurses()) {
            if (slot >= BACK) break;
            int stacks = CurseBlessingService.curseStack(player, curse.id());
            boolean active = stacks > 0;
            String status = active ? "ACTIVE" + (curse.maxStacks() > 1 ? " x" + stacks : "") : "Available in rotation";
            display.setItem(slot++, ShrineMenu.icon(iconFor(curse.id()), curse.name(),
                curse.classification().displayName() + " Curse Sigil", status,
                "Max stacks: " + curse.maxStacks()));
        }

        display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to the shrine menu."));
    }

    private java.util.List<CurseBlessingRegistry.CurseDefinition> currentCurses() {
        return CurseBlessingRegistry.liveCurses(CommunityProgressionService.stage(player));
    }

    private static net.minecraft.world.item.Item iconFor(String id) {
        return switch (id) {
            case "warrior" -> Items.IRON_SWORD;
            case "archer" -> Items.BOW;
            case "shield" -> Items.SHIELD;
            case "scapegoat" -> Items.RABBIT_FOOT;
            case "fool" -> Items.FEATHER;
            case "high_priestess" -> Items.ENDER_EYE;
            case "hierophant" -> Items.ENCHANTED_BOOK;
            case "justice" -> Items.SCAFFOLDING;
            case "hanged_man" -> Items.LEAD;
            case "temperance" -> Items.CLOCK;
            case "star" -> Items.NETHER_STAR;
            case "moon" -> Items.CLOCK;
            case "lovers" -> Items.POPPY;
            case "devil" -> Items.NETHER_BRICK;
            case "predator", "executioner" -> Items.IRON_SWORD;
            case "duelist" -> Items.DIAMOND_SWORD;
            case "fettered" -> Items.CHAINMAIL_CHESTPLATE;
            case "hollow" -> Items.ECHO_SHARD;
            case "insomnia" -> Items.PHANTOM_MEMBRANE;
            default -> Items.PAPER;
        };
    }
}
