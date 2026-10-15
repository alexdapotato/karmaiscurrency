package akisei.karmacurrency;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Base class for Karma-related menus to share common synchronization logic. */
public abstract class KarmaMenu extends ChestMenu {
    protected final SimpleContainer display;
    protected final ServerPlayer player;
    protected final int displaySize;

    public KarmaMenu(net.minecraft.world.inventory.MenuType<?> type, int id, Inventory inventory, SimpleContainer display, int rows, ServerPlayer player) {
        super(type, id, inventory, display, rows);
        this.display = display;
        this.player = player;
        this.displaySize = rows * 9;

        // KIC display icons are actions, never transferable container items.
        // Replace vanilla top slots so every custom menu gets the same server-side
        // pickup/place protections. KarmaStatusMenu replaces only its controlled
        // offering-tray slots after this constructor.
        for (int slotId = 0; slotId < displaySize; slotId++) {
            Slot original = slots.get(slotId);
            slots.set(slotId, new Slot(display, slotId, original.x, original.y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player pickupPlayer) { return false; }
                @Override public ItemStack remove(int amount) { return ItemStack.EMPTY; }
            });
        }
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int slotId) {
        return ItemStack.EMPTY;
    }

    protected final ItemStack quickMoveFromPlayerInventory(Player clicker, int slotId) {
        if (clicker != player || slotId < displaySize) return ItemStack.EMPTY;
        return super.quickMoveStack(clicker, slotId);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container != display;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != display && super.canTakeItemForPickAll(stack, slot);
    }
}
