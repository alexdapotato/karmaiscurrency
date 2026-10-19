package akisei.karmacurrency.mixin;

import akisei.karmacurrency.QuestExpansion;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace at most one caught item per retrieval; vanilla handles the item trajectory and rod costs. */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique private boolean karmaiscurrency$catchChecked;
    @Inject(method="retrieve", at=@At("HEAD"))
    private void karmaiscurrency$beginCatch(ItemStack rod, CallbackInfoReturnable<Integer> ci) {
        karmaiscurrency$catchChecked = false;
    }
    @ModifyArg(method="retrieve", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/item/ItemEntity;<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"), index=4)
    private ItemStack karmaiscurrency$specialCatch(ItemStack caught) {
        if (karmaiscurrency$catchChecked) return caught;
        karmaiscurrency$catchChecked = true;
        return QuestExpansion.fishingCatch((FishingHook)(Object)this,caught);
    }
}
