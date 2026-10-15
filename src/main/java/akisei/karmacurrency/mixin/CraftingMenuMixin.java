package akisei.karmacurrency.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Recovery compasses are shrine rewards and cannot be crafted. */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"), cancellable = true)
	private static void karmaiscurrency$disableRecoveryCompassRecipe(
		AbstractContainerMenu menu,
		ServerLevel level,
		Player player,
		CraftingContainer craftingContainer,
		ResultContainer resultContainer,
		RecipeHolder<CraftingRecipe> recipe,
		CallbackInfo callbackInfo
	) {
		// Minecraft calls this hook with a null recipe when the current crafting
		// grid has no matching recipe. Ordinary crafting must continue normally.
		if (recipe == null) {
			return;
		}

		if (recipe.value().assemble(craftingContainer.asCraftInput()).is(Items.RECOVERY_COMPASS)) {
			resultContainer.setItem(0, ItemStack.EMPTY);
			callbackInfo.cancel();
		}
	}
}
