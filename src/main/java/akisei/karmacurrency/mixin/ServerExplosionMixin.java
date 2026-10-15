package akisei.karmacurrency.mixin;

import akisei.karmacurrency.ShrineManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void karmaiscurrency$removeShrineBlocks(CallbackInfoReturnable<List<BlockPos>> cir) {
		List<BlockPos> positions = cir.getReturnValue();
		ServerExplosion explosion = (ServerExplosion) (Object) this;
		positions.removeIf(pos -> ShrineManager.isShrineProtected(explosion.level(), pos));
	}
}
