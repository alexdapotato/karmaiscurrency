package akisei.karmacurrency.mixin;

import akisei.karmacurrency.KarmaService;
import akisei.karmacurrency.ShrineConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractVillager.class)
public class AbstractVillagerMixin {
	@Inject(method = "notifyTrade", at = @At("HEAD"))
	private void karmaiscurrency$rewardTrade(MerchantOffer offer, CallbackInfo callbackInfo) {
		Player trader = ((AbstractVillager) (Object) this).getTradingPlayer();
		if (trader instanceof ServerPlayer player) {
			KarmaService.recordVillagerTrade(player);
		}
	}
}
