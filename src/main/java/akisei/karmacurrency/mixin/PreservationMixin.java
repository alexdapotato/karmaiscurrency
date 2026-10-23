package akisei.karmacurrency.mixin;

import akisei.karmacurrency.CurseBlessingService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs after vanilla enchantments have determined the actual durability loss. */
@Mixin(ItemStack.class)
public abstract class PreservationMixin {
    @Inject(method = "processDurabilityChange", at = @At("RETURN"), cancellable = true)
    private void karmaiscurrency$preserve(int amount, ServerLevel level, ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        int loss=cir.getReturnValue();
        if(loss<=0 || player==null || !CurseBlessingService.hasBlessing(player,"preservation"))return;
        int remaining=0;
        for(int i=0;i<loss;i++)if(player.getRandom().nextFloat()>=0.20F)remaining++;
        cir.setReturnValue(remaining);
    }
}
