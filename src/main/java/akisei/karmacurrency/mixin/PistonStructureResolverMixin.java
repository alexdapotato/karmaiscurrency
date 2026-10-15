package akisei.karmacurrency.mixin;

import akisei.karmacurrency.ShrineManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
	@Shadow @Final private Level level;
	@Shadow public abstract java.util.List<BlockPos> getToPush();
	@Shadow public abstract java.util.List<BlockPos> getToDestroy();

	@Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
	private void karmaiscurrency$preventShrineMovement(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || level.isClientSide()) {
			return;
		}
		for (BlockPos pos : getToPush()) {
			if (ShrineManager.isShrineProtected(level, pos)) {
				getToPush().clear();
				getToDestroy().clear();
				cir.setReturnValue(false);
				return;
			}
		}
		for (BlockPos pos : getToDestroy()) {
			if (ShrineManager.isShrineProtected(level, pos)) {
				getToPush().clear();
				getToDestroy().clear();
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
