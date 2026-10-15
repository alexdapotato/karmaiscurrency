package akisei.karmacurrency.mixin;

import akisei.karmacurrency.KarmaService;
import akisei.karmacurrency.ShrineConfig;
import akisei.karmacurrency.LightShrineService;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ZombieVillager.class)
public class ZombieVillagerMixin {
	@Shadow private UUID conversionStarter;

	@Inject(method = "finishConversion", at = @At("HEAD"))
	private void karmaiscurrency$rewardCure(ServerLevel level, CallbackInfo callbackInfo) {
		if (conversionStarter == null) {
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(conversionStarter);
		if (player != null) {
			KarmaService.awardAlignedEvent(player, "cure", 0, "curing a zombie villager");
			LightShrineService.completeTrial(player, LightShrineService.TRIAL_MERCY);
		}
	}
}
