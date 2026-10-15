package akisei.karmacurrency;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Detail view that keeps shrine selections in the chest-menu navigation flow. */
public final class CurseBlessingRequirementsMenu extends KarmaMenu {
    private static final int SIZE = 27;
    private static final int BACK = 22;
    private final Runnable onBack;

    private CurseBlessingRequirementsMenu(int id, Inventory inventory, ServerPlayer player, String title,
            String name, int maxStacks, String[] requirements, Runnable onBack, SimpleContainer display) {
        super(MenuType.GENERIC_9x3, id, inventory, display, 3, player);
        this.onBack = onBack;
        for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, ShrineMenu.icon(Items.STAINED_GLASS_PANE.gray(), " "));
        display.setItem(4, ShrineMenu.icon(Items.BOOK, name, requirements));
        display.setItem(13, ShrineMenu.icon(Items.EXPERIENCE_BOTTLE, "Stack limit", "Maximum stacks: " + maxStacks));
        display.setItem(BACK, ShrineMenu.icon(Items.ARROW, "Back", "Return to " + title + "."));
    }

    public static void openCurse(ServerPlayer player, ShrineSite site, CurseBlessingRegistry.CurseDefinition curse, Runnable onBack) {
        open(player, site.displayName() + " - Curse Requirements", curse.name(), curse.maxStacks(),
            details(curse.requirements(), curse.effectDescription()), onBack);
    }

    public static void openBlessing(ServerPlayer player, ShrineSite site, CurseBlessingRegistry.BlessingDefinition blessing, Runnable onBack) {
        open(player, site.displayName() + " - Blessing Requirements", blessing.name(), blessing.maxStacks(),
            details(blessing.requirements(), blessing.effectDescription()), onBack);
    }

    private static void open(ServerPlayer player, String title, String name, int maxStacks, String[] requirements, Runnable onBack) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, ignored) -> new CurseBlessingRequirementsMenu(id, inventory, player, title, name, maxStacks, requirements, onBack, new SimpleContainer(SIZE)),
            Component.literal(title)));
    }

    private static String[] details(java.util.List<String> requirements, String effect) {
        java.util.List<String> result = new java.util.ArrayList<>(requirements);
        result.add("Effect: " + effect);
        return result.toArray(String[]::new);
    }

    @Override public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
        if (clicker != player) return;
        if (slotId >= 0 && slotId < SIZE) {
            if (slotId == BACK && input == ContainerInput.PICKUP && button == 0) onBack.run();
            broadcastFullState();
            return;
        }
        super.clicked(slotId, button, input, clicker);
    }

    @Override public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }
    @Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }
}
