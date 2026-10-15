package akisei.karmacurrency.mixin;

import akisei.karmacurrency.CommunityProgressionService;
import akisei.karmacurrency.OverlevelBookService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only raises the anvil's cap for authorized special books; vanilla still performs the transaction. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Redirect(method = "createResult", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/enchantment/Enchantment;getMaxLevel()I"))
    private int karmaiscurrency$specialMaximum(Enchantment enchantment) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        Player player = ((ItemCombinerMenuAccessor) this).karmaiscurrency$getPlayer();
        int stage = player instanceof ServerPlayer serverPlayer
            ? CommunityProgressionService.stage(serverPlayer) : 0;
        return OverlevelBookService.anvilMaximum(enchantment,
            menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), stage);
    }

    @Inject(method = "createResult", at = @At("RETURN"))
    private void karmaiscurrency$gateSpecialPreview(CallbackInfo callbackInfo) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        Player player = ((ItemCombinerMenuAccessor) this).karmaiscurrency$getPlayer();
        if (OverlevelBookService.isSpecialBook(menu.getSlot(1).getItem())
                && player instanceof ServerPlayer serverPlayer
                && CommunityProgressionService.stage(serverPlayer) < 3) {
            menu.getSlot(2).set(ItemStack.EMPTY);
            menu.broadcastChanges();
        }
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void karmaiscurrency$gateSpecialPickup(Player player, boolean hasStack,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (OverlevelBookService.isSpecialBook(menu.getSlot(1).getItem())
                && player instanceof ServerPlayer serverPlayer
                && CommunityProgressionService.stage(serverPlayer) < 3)
            callbackInfo.setReturnValue(false);
    }
}
