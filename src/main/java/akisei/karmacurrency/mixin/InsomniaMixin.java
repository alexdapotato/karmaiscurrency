package akisei.karmacurrency.mixin;

import akisei.karmacurrency.CurseBlessingService;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class InsomniaMixin {
    @Inject(method = "startSleepInBed", at = @At("HEAD"), cancellable = true)
    private void karmaiscurrency$blockSleep(BlockPos pos, CallbackInfoReturnable<Either<Player.BedSleepingProblem, Unit>> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        if (!CurseBlessingService.hasCurse(player, "insomnia")) return;

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Insomnia prevents you from sleeping."));
        cir.setReturnValue(Either.left(Player.BedSleepingProblem.OTHER_PROBLEM));
    }
}
