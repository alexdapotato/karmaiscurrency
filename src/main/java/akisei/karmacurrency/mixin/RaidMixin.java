package akisei.karmacurrency.mixin;

import akisei.karmacurrency.KarmaService;
import akisei.karmacurrency.ShrineConfig;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Raid.class)
public class RaidMixin {
	@Shadow private Set<UUID> heroesOfTheVillage;
	@Unique private boolean karmaiscurrency$victoryRewarded;

	@Inject(method = "tick", at = @At("TAIL"))
	private void karmaiscurrency$rewardRaidVictory(ServerLevel level, CallbackInfo callbackInfo) {
		Raid raid = (Raid) (Object) this;
		if (karmaiscurrency$victoryRewarded || !raid.isVictory()) return;
		karmaiscurrency$victoryRewarded = true;
		for (UUID heroId : heroesOfTheVillage) {
			ServerPlayer hero = level.getServer().getPlayerList().getPlayer(heroId);
			if (hero != null) {
				KarmaService.awardNeutralInfluence(hero, ShrineConfig.current().socialRaidHeroGain,
					"defending a village raid", "raid_hero");
			}
		}
	}
}
