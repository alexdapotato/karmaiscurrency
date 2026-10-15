package akisei.karmacurrency;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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

/** Simple readable history screens for players and World Progression contributors. */
public final class KicHistoryMenu extends KarmaMenu {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int BACK = 49;
    private static final int[] ENTRY_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private final Runnable onBack;
    private final boolean contributions;

    private KicHistoryMenu(int id, Inventory inventory, ServerPlayer player, Runnable onBack, boolean contributions, SimpleContainer display) {
        super(MenuType.GENERIC_9x6, id, inventory, display, ROWS, player);
        this.onBack = onBack;
        this.contributions = contributions;
        refresh();
    }

    public static void openPersonal(ServerPlayer player, Runnable onBack) {
        open(player, onBack, false, "KIC History");
    }

    public static void openContributions(ServerPlayer player, Runnable onBack) {
        open(player, onBack, true, "Contribution Log");
    }

    private static void open(ServerPlayer player, Runnable onBack, boolean contributions, String title) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> new KicHistoryMenu(id, inventory, player, onBack, contributions, new SimpleContainer(SIZE)),
            Component.literal(title)));
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player clicker) {
        if (clicker != player) return;
        if (slotId >= 0 && slotId < SIZE) {
            if (input == ContainerInput.PICKUP && button == 0 && slotId == BACK) onBack.run();
            broadcastFullState();
            return;
        }
        super.clicked(slotId, button, input, clicker);
    }

    @Override public ItemStack quickMoveStack(Player player, int slotId) { return ItemStack.EMPTY; }
    @Override public boolean canDragTo(Slot slot) { return slots.indexOf(slot) >= SIZE; }

    private void refresh() {
        for (int slot = 0; slot < SIZE; slot++) display.setItem(slot, icon(Items.STAINED_GLASS_PANE.gray(), " "));
        List<String> lines = contributions
            ? KicHistoryService.contributions(player.level().getServer())
            : KicHistoryService.personal(player);
        int start = Math.max(0, lines.size() - ENTRY_SLOTS.length);
        int shown = 0;
        for (int i = lines.size() - 1; i >= start && shown < ENTRY_SLOTS.length; i--, shown++) {
            String line = lines.get(i);
            display.setItem(ENTRY_SLOTS[shown], icon(contributions ? Items.BOOK : Items.PAPER,
                compact(line), line));
        }
        display.setItem(4, icon(contributions ? Items.BEACON : Items.WRITABLE_BOOK,
            contributions ? "Contribution Log" : "KIC History",
            contributions ? "Recent community Influence contributions." : "Recent changes to your Karma and social currencies.",
            lines.isEmpty() ? "No entries yet." : lines.size() + " recent entries stored."));
        display.setItem(BACK, icon(Items.ARROW, "Back"));
    }

    private static String compact(String line) {
        if (line.length() <= 52) return line;
        return line.substring(0, 49) + "...";
    }

    private static ItemStack icon(net.minecraft.world.item.Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore.length > 0) stack.set(DataComponents.LORE,
            new ItemLore(java.util.Arrays.stream(lore).<Component>map(Component::literal).toList()));
        return stack;
    }
}
