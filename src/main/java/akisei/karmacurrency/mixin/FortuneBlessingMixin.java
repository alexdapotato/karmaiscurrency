package akisei.karmacurrency.mixin;

import akisei.karmacurrency.CurseBlessingService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds Fortune I to the loot calculation tool while Fortune is active. */
@Mixin(Block.class)
public abstract class FortuneBlessingMixin {
    @ModifyVariable(
        method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static ItemInstance karmaiscurrency$addFortuneLevel(ItemInstance tool, BlockState state,
            ServerLevel level, BlockPos pos, BlockEntity blockEntity, Entity entity) {
        if (!(entity instanceof ServerPlayer player) || !(tool instanceof ItemStack stack)) return tool;
        boolean fortuneActive = CurseBlessingService.hasBlessing(player, "fortune");
        boolean harvestActive = state.getBlock() instanceof CropBlock && CurseBlessingService.hasBlessing(player, "harvest");
        if (!fortuneActive && !harvestActive) return tool;
        Holder<Enchantment> fortune = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .get(Identifier.parse("minecraft:fortune")).orElse(null);
        if (fortune == null) return tool;
        ItemStack modified = stack.copy();
        modified.enchant(fortune, EnchantmentHelper.getItemEnchantmentLevel(fortune, stack) + 1);
        return modified;
    }
}
