package akisei.karmacurrency;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Allows special shop books one extra anvil level; vanilla handles compatibility, XP and consumption. */
public final class OverlevelBookService {
    private static final String MARKER = "KicOverlevelEnchantment";
    private OverlevelBookService() {}

    public static void mark(ItemStack book, String enchantment) {
        CompoundTag tag = new CompoundTag();
        tag.putString(MARKER, enchantment);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        book.set(DataComponents.LORE, new ItemLore(java.util.List.of(
            Component.literal("Requires World Progression Stage 2 to apply."),
            Component.literal("Apply using an anvil. Normal XP costs and compatibility rules apply."))));
    }

    public static boolean isSpecialBook(ItemStack book) {
        if (!book.is(Items.ENCHANTED_BOOK)) return false;
        CustomData data = book.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.copyTag().getStringOr(MARKER, "").isEmpty();
    }

    public static int anvilMaximum(Enchantment enchantment, ItemStack equipment, ItemStack book, int stage) {
        int normalMax = enchantment.getMaxLevel();
        int existing = level(equipment, DataComponents.ENCHANTMENTS, enchantment);
        int stored = level(book, DataComponents.STORED_ENCHANTMENTS, enchantment);
        boolean special = isSpecialBook(book);
        return allowedMaximum(normalMax, existing, stored, special, stage);
    }

    /** Preserve existing special levels on repair, but never manufacture extra levels with ordinary books. */
    static int allowedMaximum(int normalMax, int existing, int stored, boolean special, int stage) {
        int maximum = Math.max(normalMax, existing);
        if (special && stage >= 2 && stored == normalMax + 1)
            maximum = Math.max(maximum, stored);
        return maximum;
    }

    private static int level(ItemStack stack,
            net.minecraft.core.component.DataComponentType<ItemEnchantments> component, Enchantment enchantment) {
        for (var entry : stack.getOrDefault(component, ItemEnchantments.EMPTY).entrySet()) {
            if (entry.getKey().value() == enchantment) return entry.getIntValue();
        }
        return 0;
    }
}
