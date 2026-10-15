package akisei.karmacurrency;

import net.minecraft.world.item.Item;

public record ShopReward(
        String id,
        ShopCategory category,
        ShrineType shrineType,
        int cost,
        Item item,
        int count,
        String displayName,
        boolean enabled,
        int maxPurchases,
        String mode,
        String variant,
        int level,
        int weight,
        int unlockStage,
        ShopItemCategory itemCategory) {
}
