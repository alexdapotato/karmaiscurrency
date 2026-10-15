package akisei.karmacurrency.mixin;

import akisei.karmacurrency.TrialSpawnerRewardService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TrialSpawner.class)
public abstract class TrialSpawnerMixin {
    @Unique private boolean karmaiscurrency$wasActive;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void karmaiscurrency$beforeTick(ServerLevel level, BlockPos position, boolean ominous, CallbackInfo ci) {
        karmaiscurrency$wasActive = ((TrialSpawner) (Object) this).getState() == TrialSpawnerState.ACTIVE;
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void karmaiscurrency$afterTick(ServerLevel level, BlockPos position, boolean ominous, CallbackInfo ci) {
        TrialSpawner spawner = (TrialSpawner) (Object) this;
        if (!karmaiscurrency$wasActive || spawner.getState() != TrialSpawnerState.WAITING_FOR_REWARD_EJECTION) return;
        karmaiscurrency$wasActive = false;
        TrialSpawnerRewardService.rewardCompletion(level, position, spawner.getStateData().pack().detectedPlayers());
    }
}
