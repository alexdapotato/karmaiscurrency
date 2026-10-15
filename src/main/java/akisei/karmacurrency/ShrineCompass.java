package akisei.karmacurrency;

import java.util.Optional;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;

/**
 * Uses a vanilla lodestone compass so Java and Bedrock (via Geyser) both get a
 * working needle without a custom item.
 */
public final class ShrineCompass {
	private static final String SHRINE_COMPASS_TAG = "KarmaShrineCompass";
	private static final String TARGET_TAG = "Target";
	private static final String TARGET_NAME_TAG = "TargetName";
	private static final String PREDICTED_TAG = "Predicted";

	private ShrineCompass() {
	}

	public static ItemStack create(MinecraftServer server, ShrineSite site) {
		ShrineManager.ensureLodestone(server, site);
		GlobalPos target = GlobalPos.of(Level.OVERWORLD, site.actualLodestonePosition());
		ItemStack compass = new ItemStack(Items.COMPASS);
		compass.set(
			DataComponents.LODESTONE_TRACKER,
			new LodestoneTracker(Optional.of(target), true)
		);
		compass.set(DataComponents.CUSTOM_NAME, Component.literal(site.statueName() + " Compass"));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(SHRINE_COMPASS_TAG, true);
		tag.putString(TARGET_TAG, site.type().name());
		tag.putString(TARGET_NAME_TAG, site.statueName());
		compass.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return compass;
	}

	public static boolean give(ServerPlayer player, ShrineSite site) {
		return give(player, site, false);
	}

	public static boolean give(ServerPlayer player, ShrineSite site, boolean bypassCooldown) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)
			|| (!bypassCooldown && KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED)
				&& !KarmaService.canReceiveShrineCompass(player)) || hasShrineCompass(player)) {
			return false;
		}
		ItemStack compass = create(player.level().getServer(), site);
		if (!player.addItem(compass)) {
			player.drop(compass, false);
		}
		if (!bypassCooldown && KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED)) {
			KarmaService.startShrineCompassCooldown(player);
		}
		return true;
	}

	public static boolean givePredicted(ServerPlayer player, int x, int z, boolean bypassCooldown) {
		if (!KarmaGameRules.enabled(player, KarmaGameRules.SHRINE_COMPASS_ENABLED)
			|| (!bypassCooldown && KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED)
				&& !KarmaService.canReceiveShrineCompass(player))
			|| hasShrineCompass(player)) {
			return false;
		}
		ItemStack compass = createPredicted(x, z);
		if (!player.addItem(compass)) {
			player.drop(compass, false);
		}
		if (!bypassCooldown && KarmaGameRules.enabled(player, KarmaGameRules.COMPASS_COOLDOWN_ENABLED)) {
			KarmaService.startShrineCompassCooldown(player);
		}
		return true;
	}

	private static ItemStack createPredicted(int x, int z) {
		GlobalPos target = GlobalPos.of(Level.OVERWORLD, new net.minecraft.core.BlockPos(x, 64, z));
		ItemStack compass = new ItemStack(Items.COMPASS);
		// tracked=false prevents vanilla's server-side tracker from invalidating the
		// target simply because the destination chunk has not generated yet.
		compass.set(
			DataComponents.LODESTONE_TRACKER,
			new LodestoneTracker(Optional.of(target), false)
		);
		compass.set(DataComponents.CUSTOM_NAME, Component.literal("Predicted Shrine Compass"));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(SHRINE_COMPASS_TAG, true);
		tag.putBoolean(PREDICTED_TAG, true);
		tag.putInt("TargetX", x);
		tag.putInt("TargetZ", z);
		compass.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return compass;
	}

	public static boolean isShrineCompass(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return stack.is(Items.COMPASS) && data != null
			&& data.copyTag().getBooleanOr(SHRINE_COMPASS_TAG, false);
	}

	public static boolean hasShrineCompass(ServerPlayer player) {
		return player.getInventory().getNonEquipmentItems().stream().anyMatch(ShrineCompass::isShrineCompass)
			|| isShrineCompass(player.getOffhandItem());
	}

	public static Optional<ShrineType> targetType(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(ShrineType.valueOf(data.copyTag().getStringOr(TARGET_TAG, "")));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	public static void retarget(ItemStack stack, ShrineSite site, MinecraftServer server) {
		ShrineManager.ensureLodestone(server, site);
		stack.set(
			DataComponents.LODESTONE_TRACKER,
			new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, site.actualLodestonePosition())), true)
		);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(site.statueName() + " Compass"));
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putString(TARGET_TAG, site.type().name());
			tag.putString(TARGET_NAME_TAG, site.statueName());
			tag.remove(PREDICTED_TAG);
		});
	}

	public static void retargetPredicted(ItemStack stack, int x, int z) {
		GlobalPos target = GlobalPos.of(Level.OVERWORLD, new net.minecraft.core.BlockPos(x, 64, z));
		stack.set(
			DataComponents.LODESTONE_TRACKER,
			new LodestoneTracker(Optional.of(target), false)
		);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Predicted Shrine Compass"));
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putBoolean(PREDICTED_TAG, true);
			tag.putInt("TargetX", x);
			tag.putInt("TargetZ", z);
			tag.remove(TARGET_TAG);
			tag.remove(TARGET_NAME_TAG);
		});
	}
}
